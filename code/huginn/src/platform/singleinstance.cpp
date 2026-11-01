#include "singleinstance.h"
#include "winstart.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QElapsedTimer>
#include <QFile>
#include <QFileInfo>
#include <QGuiApplication>
#include <QLocalServer>
#include <QLocalSocket>
#include <QLockFile>
#include <QProcess>
#include <QProcessEnvironment>
#include <QStandardPaths>
#include <QTemporaryDir>
#include <QThread>
#include <QTimer>
#include <QPointer>
#include <QWindow>
#include <cstdio>

#ifdef Q_OS_WIN
#include <windows.h>
#else
#include <csignal>
#include <sys/types.h>
#endif

namespace
{
SingleInstance *s_current = nullptr;
const char kAppId[] = "io.github.ravenor907.Huginn";
const QString kRestarted = QStringLiteral("--restarted");

bool inFlatpak() { return QFile::exists(QStringLiteral("/.flatpak-info")); }

#ifndef Q_OS_WIN
// The other Flatpak copies of Huginn (each runs in its own sandbox, so their process ids aren't visible here).
void endOtherFlatpakCopies()
{
    QString mine;
    QFile info(QStringLiteral("/.flatpak-info"));
    if (info.open(QIODevice::ReadOnly | QIODevice::Text)) {
        const QList<QByteArray> lines = info.readAll().split('\n');
        for (const QByteArray &l : lines)
            if (l.startsWith("instance-id="))
                mine = QString::fromUtf8(l.mid(12)).trimmed();
    }
    if (mine.isEmpty())
        return;   // without knowing which copy is this one, none is ended
    QProcess ps;
    ps.start(QStringLiteral("flatpak-spawn"), {QStringLiteral("--host"), QStringLiteral("flatpak"), QStringLiteral("ps"), QStringLiteral("--columns=instance,application")});
    if (!ps.waitForFinished(3000)) {
        ps.kill();
        ps.waitForFinished(1000);
        return;
    }
    const QStringList rows = QString::fromUtf8(ps.readAllStandardOutput()).split(QLatin1Char('\n'), Qt::SkipEmptyParts);
    for (const QString &row : rows) {
        const QStringList cols = row.simplified().split(QLatin1Char(' '));
        if (cols.size() < 2 || cols.at(1) != QLatin1String(kAppId) || cols.at(0) == mine)
            continue;
        qWarning("single instance: ending a Huginn that doesn't answer");
        QProcess kill;
        kill.start(QStringLiteral("flatpak-spawn"), {QStringLiteral("--host"), QStringLiteral("flatpak"), QStringLiteral("kill"), cols.at(0)});
        if (!kill.waitForFinished(3000)) {
            kill.kill();
            kill.waitForFinished(1000);
        }
    }
}
#endif
}

SingleInstance::SingleInstance(QObject *parent, const QString &where)
    : QObject(parent)
{
    // Per user: the app data folder differs per account, so its hash names the socket.
    const QString dir = where.isEmpty() ? QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) : where;
    QDir().mkpath(dir);
    const QByteArray h = QCryptographicHash::hash(dir.toUtf8(), QCryptographicHash::Sha256).toHex().left(16);
    m_name = QStringLiteral("io.github.ravenor907.Huginn-") + QString::fromLatin1(h);
    m_lock = new QLockFile(dir + QStringLiteral("/instance.lock"));
    // Beside the lock while its copy is closing (written on aboutToQuit, gone when the process lets the lock go).
    // A copy that is closing and doesn't answer is stuck; one that doesn't answer otherwise may only be busy.
    m_closing = dir + QStringLiteral("/instance.closing");
    m_lock->setStaleLockTime(0);   // only a live process holds it; a crashed one's lock is taken over
}

SingleInstance::~SingleInstance()
{
    release();
    delete m_lock;
    if (s_current == this)
        s_current = nullptr;
}

bool SingleInstance::claim(bool restarted)
{
    // Started by a copy that is closing: wait for it to save and end, never alongside it.
    if (restarted) {
        QElapsedTimer waited;
        waited.start();
        if (m_lock->tryLock(kRestartWaitMs)) {
            if (waited.elapsed() > 300)
                std::fprintf(stderr, "single instance: waited %lld ms for the closing copy\n", static_cast<long long>(waited.elapsed()));
            listen();
            return true;
        }
        qWarning("single instance: the closing copy is still running after %d s", kRestartWaitMs / 1000);
    }
    if (m_lock->tryLock(200) || takeOverStaleLock()) {
        listen();
        return true;
    }
    return false;
}

void SingleInstance::listen()
{
    s_current = this;
    QFile::remove(m_closing);   // left by a copy that ended while closing
    QLocalServer::removeServer(m_name);   // left behind by a crash (Unix sockets only)
    m_server = new QLocalServer(this);
    m_server->setSocketOptions(QLocalServer::UserAccessOption);
    connect(m_server, &QLocalServer::newConnection, this, [this] {
        while (QLocalSocket *s = m_server->nextPendingConnection()) {
            connect(s, &QLocalSocket::disconnected, s, &QObject::deleteLater);
            // The answer says this copy is alive (the knocking copy then quits).
            s->write("ok\n");
            s->flush();
            s->disconnectFromServer();
        }
        bringToFront();
        Q_EMIT activated();
    });
    if (!m_server->listen(m_name))
        qWarning("single instance: couldn't listen on %s", qPrintable(m_name));
}

void SingleInstance::markClosing()
{
    if (!m_lock->isLocked())
        return;
    QFile f(m_closing);
    if (f.open(QIODevice::WriteOnly))
        f.write(QByteArray::number(QCoreApplication::applicationPid()));
}

bool SingleInstance::knock()
{
#ifdef Q_OS_WIN
    // We were just started by the user, so we may hand the foreground to the running copy.
    AllowSetForegroundWindow(ASFW_ANY);
#endif
    // A copy that is still starting may not listen yet, so knock a few times.
    for (int i = 0; i < 3; ++i) {
        QLocalSocket s;
        s.connectToServer(m_name);
        if (s.waitForConnected(700)) {
            s.write("show\n");
            s.waitForBytesWritten(500);
            // Alive: it answers, or (a copy that never answers) it hangs up at once.
            const bool answered = s.waitForReadyRead(kAnswerMs) || s.state() != QLocalSocket::ConnectedState;
            s.abort();
            return answered;
        }
        QThread::msleep(300);
    }
    return false;
}

bool SingleInstance::takeOverStaleLock()
{
    // Someone holds the lock: knock so they come forward. An answer means it runs: this copy quits.
    if (knock())
        return false;
    // Nobody answers. A copy that was killed left its lock: take it.
    if (m_lock->removeStaleLockFile() && m_lock->tryLock(200)) {
        qWarning("single instance: took over the lock of a copy that ended");
        return true;
    }
    // Still held by a live process. Only a copy that is closing and stuck (hidden, holding the lock forever) is
    // ended; one that is busy in front of the person is left alone.
    if (!QFile::exists(m_closing)) {
        qWarning("single instance: the running copy is busy; leaving it");
        return false;
    }
    // Closing normally takes a moment (the engine, the window): a few seconds from its mark before it counts as stuck.
    QElapsedTimer wait;
    wait.start();
    while (wait.elapsed() < kStuckMs + 1000) {
        const QFileInfo mark(m_closing);
        if (!mark.exists() || mark.lastModified().msecsTo(QDateTime::currentDateTime()) >= kStuckMs)
            break;
        if (m_lock->tryLock(250))
            return true;
    }
    if (m_lock->tryLock(300))
        return true;   // it finished after all
    if (!QFile::exists(m_closing))
        return m_lock->tryLock(1000);   // its mark went as it let go (or it isn't closing any more: left alone)
    qWarning("single instance: the running copy is stuck closing; ending it");
    endHolder();
    QElapsedTimer t;
    t.start();
    while (t.elapsed() < 4000) {
        m_lock->removeStaleLockFile();
        if (m_lock->tryLock(200))
            return true;
        QThread::msleep(200);
    }
    return false;
}

void SingleInstance::endHolder()
{
    qint64 pid = 0;
    QString host, app;
    const bool known = m_lock->getLockInfo(&pid, &host, &app) && pid > 0 && pid != QCoreApplication::applicationPid();
#ifdef Q_OS_WIN
    if (!known)
        return;
    HANDLE h = OpenProcess(PROCESS_TERMINATE | PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE, FALSE, DWORD(pid));
    if (!h)
        return;
    wchar_t buf[4096];
    DWORD n = DWORD(sizeof buf / sizeof buf[0]);
    if (QueryFullProcessImageNameW(h, 0, buf, &n)) {
        const QString theirs = QFileInfo(QString::fromWCharArray(buf, int(n))).fileName();
        const QString ours = QFileInfo(QCoreApplication::applicationFilePath()).fileName();
        // Only Huginn itself, never another program that happens to have the same process id now.
        if (theirs.compare(ours, Qt::CaseInsensitive) == 0) {
            TerminateProcess(h, 3);
            WaitForSingleObject(h, 3000);
        }
    }
    CloseHandle(h);
#else
    if (known) {
        QString exe = QFile::symLinkTarget(QStringLiteral("/proc/%1/exe").arg(pid));
        if (exe.endsWith(QLatin1String(" (deleted)")))
            exe.chop(10);
        if (!exe.isEmpty() && exe == QCoreApplication::applicationFilePath()) {
            ::kill(pid_t(pid), SIGKILL);
            QThread::msleep(300);
            return;
        }
    }
    // A copy in another Flatpak sandbox: its process id means nothing here.
    if (inFlatpak())
        endOtherFlatpakCopies();
#endif
}

void SingleInstance::release()
{
    if (m_server) {
        m_server->close();
        delete m_server;
        m_server = nullptr;
    }
    if (m_lock && m_lock->isLocked()) {
        QFile::remove(m_closing);
        m_lock->unlock();
    }
}

bool SingleInstance::startAgain(const QString &program, const QString &workDir, const QStringList &env, const QStringList &args)
{
    QProcess p;
    if (inFlatpak() && program.isEmpty()) {
        p.setProgram(QStringLiteral("flatpak-spawn"));
        QStringList a{QStringLiteral("--host"), QStringLiteral("flatpak"), QStringLiteral("run")};
        for (const QString &e : env)
            a << QStringLiteral("--env=") + e;
        a << QString::fromLatin1(kAppId) << kRestarted;
        for (const QString &x : args)
            if (x != kRestarted)
                a << x;
        p.setArguments(a);
    } else {
        p.setProgram(program.isEmpty() ? QCoreApplication::applicationFilePath() : program);
        QStringList a{kRestarted};
        for (const QString &x : args)
            if (x != kRestarted)
                a << x;
        p.setArguments(a);
        // What this copy was started with for itself (a graphics retry) never carries over to the next one.
        QProcessEnvironment e = Platform::childEnvironment();
        for (const QString &kv : env)
            e.insert(kv.section(QLatin1Char('='), 0, 0), kv.section(QLatin1Char('='), 1));
        p.setProcessEnvironment(e);
        if (!workDir.isEmpty())
            p.setWorkingDirectory(workDir);
    }
    const bool ok = p.startDetached();
    if (!ok)
        qWarning("single instance: couldn't start Huginn again");
    return ok;
}

namespace
{
QPointer<QWindow> s_tucked;
QWindow::Visibility s_tuckedAs = QWindow::Windowed;
}

void SingleInstance::tuck(QWindow *window)
{
    if (!window)
        return;
    s_tucked = window;
    s_tuckedAs = window->visibility() == QWindow::Hidden ? QWindow::FullScreen : window->visibility();
    window->hide();
}

void SingleInstance::bringToFront()
{
    if (s_tucked) {
        if (!s_tucked->isVisible())
            s_tucked->setVisibility(s_tuckedAs);
        s_tucked = nullptr;
    }
    for (QWindow *w : QGuiApplication::topLevelWindows()) {
        if (!w->isVisible() || w->type() == Qt::ToolTip || w->type() == Qt::Popup)
            continue;
        if (w->windowState() & Qt::WindowMinimized)
            w->setWindowStates(w->windowStates() & ~Qt::WindowMinimized);
        w->raise();
        w->requestActivate();
    }
}

// ------------------------------------------------------------------ self-test (--instance-selftest)

int SingleInstance::selfTestHolder(const QString &dir, int ms, bool hang)
{
    SingleInstance one(nullptr, dir);
    if (!one.claim(false))
        return 2;
    std::printf("holding\n");
    std::fflush(stdout);
    if (hang) {
        // Stuck while closing: says so, then never answers a knock (no event loop) until it is ended.
        QFile f(one.m_closing);
        if (f.open(QIODevice::WriteOnly))
            f.write("1");
        f.close();
        QThread::msleep(ms);
        return 0;
    }
    QTimer::singleShot(ms, QCoreApplication::instance(), &QCoreApplication::quit);
    return QCoreApplication::exec();
}

bool SingleInstance::selfTest()
{
    int fail = 0;
    auto check = [&](bool good, const char *what) {
        if (!good) {
            ++fail;
            std::printf("instance self-test: FAILED %s\n", what);
        }
    };
    QTemporaryDir tmp;
    if (!tmp.isValid()) {
        std::printf("instance self-test: no temp folder\n");
        return false;
    }
    const QString dir = tmp.path();
    auto holder = [&](int ms, bool hang) {
        auto *p = new QProcess;
        p->start(QCoreApplication::applicationFilePath(), {QStringLiteral("--instance-selftest-holder"), dir, QString::number(ms), hang ? QStringLiteral("hang") : QStringLiteral("ok")});
        // Wait until it holds the lock.
        QElapsedTimer t;
        t.start();
        while (t.elapsed() < 10000 && !p->readAllStandardOutput().contains("holding"))
            p->waitForReadyRead(200);
        return p;
    };

    // 1. A copy that runs and answers: a second one doesn't take over.
    {
        QProcess *p = holder(6000, false);
        SingleInstance second(nullptr, dir);
        check(!second.claim(false), "a second copy runs beside a live one");
        p->kill();
        p->waitForFinished(5000);
        delete p;
    }
    // 2. A restarted copy waits for the closing one to end, then holds the lock.
    {
        QProcess *p = holder(1500, false);
        QElapsedTimer t;
        t.start();
        SingleInstance next(nullptr, dir);
        const bool got = next.claim(true);
        check(got, "a restarted copy gets the lock once the old one ends");
        check(t.elapsed() >= 800, "a restarted copy started while the old one still held the lock");
        check(p->state() == QProcess::NotRunning || p->waitForFinished(3000), "the old copy ended");
        delete p;
    }
    // 3. A copy stuck closing (no answer) is ended and its lock taken.
    {
        QProcess *p = holder(60000, true);
        QElapsedTimer t;
        t.start();
        SingleInstance next(nullptr, dir);
        check(next.claim(false), "a copy stuck closing is taken over");
        check(t.elapsed() < 12000, "taking over a stuck copy is quick");
        check(p->waitForFinished(5000), "the stuck copy was ended");
        p->kill();
        p->waitForFinished(2000);
        delete p;
    }
    std::printf("instance self-test: %s\n", fail ? "FAILED" : "passed");
    return fail == 0;
}
