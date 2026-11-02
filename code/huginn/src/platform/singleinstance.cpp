#include "singleinstance.h"
#include "portable.h"
#include "../busy.h"
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
#include <QSysInfo>
#include <QTemporaryDir>
#include <QThread>
#include <QTimer>
#include <QPointer>
#include <QWindow>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <thread>

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
    const QString dir = where.isEmpty() ? Portable::dataDir() : where;
    QDir().mkpath(dir);
    m_dir = dir;
    const QByteArray h = QCryptographicHash::hash(dir.toUtf8(), QCryptographicHash::Sha256).toHex().left(16);
    m_name = QStringLiteral("io.github.ravenor907.Huginn-") + QString::fromLatin1(h);
    m_lock = new QLockFile(dir + QStringLiteral("/instance.lock"));
    // Beside the lock while its copy is closing (written on aboutToQuit, gone when the process lets the lock go).
    // A copy that is closing and doesn't answer is stuck; one that doesn't answer otherwise may only be busy.
    m_closing = dir + QStringLiteral("/instance.closing");
    // Written by a newer copy that finds this one stuck closing: this copy then ends itself (markClosing()).
    m_endAsk = dir + QStringLiteral("/instance.end");
    m_lock->setStaleLockTime(0);   // only a live process holds it; a crashed one's lock is taken over
}

SingleInstance::~SingleInstance()
{
    release();
    delete m_lock;
    if (s_current == this)
        s_current = nullptr;
}

namespace
{
// The lock file couldn't be made or written at all (a full disk, a read-only folder): not another copy.
bool cantWrite(const QLockFile *lock)
{
    const QLockFile::LockError e = lock->error();
    return e == QLockFile::PermissionError || e == QLockFile::UnknownError;
}

#ifndef Q_OS_WIN
// Only a short, plain token is passed on (it goes into this process's environment).
bool tokenOk(const QByteArray &t)
{
    if (t.isEmpty() || t.size() > 512)
        return false;
    for (const char c : t)
        if (c <= ' ' || c > '~')
            return false;
    return true;
}
#endif
}

bool SingleInstance::claim(bool restarted)
{
    m_outcome = Claimed;
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
        if (cantWrite(m_lock)) {
            m_outcome = CantWrite;
            return false;
        }
        qWarning("single instance: the closing copy is still running after %d s", kRestartWaitMs / 1000);
    }
    if (m_lock->tryLock(200)) {
        listen();
        return true;
    }
    // A full disk or a read-only folder fails here too: said as that, never as "another copy is open".
    if (cantWrite(m_lock)) {
        qWarning("single instance: the data folder can't be written");
        m_outcome = CantWrite;
        return false;
    }
    if (takeOverStaleLock()) {
        m_outcome = Claimed;
        listen();
        return true;
    }
    if (m_outcome != Shown)
        m_outcome = cantWrite(m_lock) ? CantWrite : NotAnswering;
    return false;
}

QString SingleInstance::problemText() const
{
    if (m_outcome == CantWrite)
        return QStringLiteral("Huginn can't save in its data folder (%1). Free up space on the disk or check the folder's permissions, "
                              "then open Huginn again.")
            .arg(QDir::toNativeSeparators(m_dir));
    if (m_outcome != NotAnswering)
        return {};
    qint64 pid = 0;
    QString host, app;
    m_lock->getLockInfo(&pid, &host, &app);
    const QString here = QSysInfo::machineHostName();
    // Another computer opened the same folder (a network drive, a synced or shared folder).
    if (!host.isEmpty() && !here.isEmpty() && host.compare(here, Qt::CaseInsensitive) != 0)
        return QStringLiteral("Huginn is already open on another computer (%1) that uses this same folder. Close it there first: "
                              "two copies can't share one data folder.")
            .arg(host);
#ifdef Q_OS_WIN
    // Another Windows account on this computer: its process can't be looked at from this one.
    if (pid > 0) {
        DWORD theirs = 0, mine = 0;
        const bool known = ProcessIdToSessionId(DWORD(pid), &theirs) != 0;
        const DWORD err = known ? 0 : GetLastError();
        if ((!known && err == ERROR_ACCESS_DENIED) || (known && ProcessIdToSessionId(GetCurrentProcessId(), &mine) && theirs != mine))
            return QStringLiteral("Huginn is already open under another Windows account on this computer, using this same folder. "
                                  "Close it there first, or give each account its own copy of Huginn.");
    }
    return QStringLiteral("Huginn is already open%1 but isn't answering. Wait a moment, or end Huginn in Task Manager, then open it again.")
        .arg(pid > 0 ? QStringLiteral(" (process %1)").arg(pid) : QString());
#else
    // In the Flatpak each copy has its own process numbers, so none is named.
    return QStringLiteral("Huginn is already open%1 but isn't answering. Wait a moment, or end Huginn in your system monitor, then open it again.")
        .arg(pid > 0 && !inFlatpak() ? QStringLiteral(" (process %1)").arg(pid) : QString());
#endif
}

void SingleInstance::useActivationToken(const QByteArray &token)
{
#ifdef Q_OS_WIN
    Q_UNUSED(token);
#else
    // Qt's Wayland window reads it in requestActivate() and then clears it.
    if (tokenOk(token))
        qputenv("XDG_ACTIVATION_TOKEN", token);
#endif
}

void SingleInstance::listen()
{
    s_current = this;
    QFile::remove(m_closing);   // left by a copy that ended while closing
    QFile::remove(m_endAsk);
    QLocalServer::removeServer(m_name);   // left behind by a crash (Unix sockets only)
    m_server = new QLocalServer(this);
    Busy::nameSocket(m_server, "single instance");
    m_server->setSocketOptions(QLocalServer::UserAccessOption);
    connect(m_server, &QLocalServer::newConnection, this, [this] {
        Busy::Scope busy("another copy started");
        while (QLocalSocket *s = m_server->nextPendingConnection()) {
            Busy::nameSocket(s, "single instance");
            // "show <token>": the knocking copy's activation token (Wayland), so the desktop lets this window forward.
            // Shown once per knock, as soon as its line is in (or it hung up, or a moment passed without one).
            auto show = [this, s](bool now) {
                if (s->property("huginnShown").toBool())
                    return;
                const QByteArray line = s->readAll();
                if (line.isEmpty() && !now && s->state() == QLocalSocket::ConnectedState)
                    return;
                s->setProperty("huginnShown", true);
                const QByteArray first = line.split('\n').value(0).trimmed();
                if (first.startsWith("show "))
                    useActivationToken(first.mid(5).trimmed());
                bringToFront();
                Q_EMIT activated();
            };
            connect(s, &QLocalSocket::readyRead, s, [show] { show(false); });
            connect(s, &QLocalSocket::disconnected, s, [s, show] {
                show(true);
                s->deleteLater();
            });
            QTimer::singleShot(1000, s, [s, show] {
                show(true);
                s->disconnectFromServer();
            });
            // The answer says this copy is alive (the knocking copy then quits).
            s->write("ok\n");
            s->flush();
            if (s->bytesAvailable() > 0)
                show(false);
        }
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
    f.close();
    // Everything is saved by now. If this copy is still closing kStuckMs from here and a newer copy asks it to make
    // way (m_endAsk), it ends itself: no copy ever ends another.
    static bool watching = false;
    if (watching)
        return;
    watching = true;
    // Plain file checks on this thread (no Qt): it may still be looking while Qt itself is being taken down.
    std::thread([ask = std::filesystem::path(m_endAsk.toStdU16String())] {
        const auto from = std::chrono::steady_clock::now();
        for (;;) {
            std::this_thread::sleep_for(std::chrono::milliseconds(250));
            std::error_code err;
            if (std::chrono::steady_clock::now() - from >= std::chrono::milliseconds(kStuckMs) && std::filesystem::exists(ask, err)) {
                std::fprintf(stderr, "single instance: a newer copy is waiting; this one ends now\n");
                std::_Exit(3);
            }
        }
    }).detach();
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
            QByteArray line = "show";
#ifndef Q_OS_WIN
            // The launcher's activation token goes along, so the running copy may come forward on Wayland.
            const QByteArray token = qgetenv("XDG_ACTIVATION_TOKEN");
            if (tokenOk(token)) {
                line += ' ';
                line += token;
            }
#endif
            s.write(line + '\n');
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
    if (knock()) {
        m_outcome = Shown;
        return false;
    }
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
    // Stuck closing: it is asked to end itself (markClosing() watches for this), and the lock is waited for.
    qWarning("single instance: the running copy is stuck closing; asking it to end");
    {
        QFile ask(m_endAsk);
        if (ask.open(QIODevice::WriteOnly))
            ask.write("end");
    }
    QElapsedTimer t;
    t.start();
    while (t.elapsed() < 4000) {
        m_lock->removeStaleLockFile();
        if (m_lock->tryLock(200)) {
            QFile::remove(m_endAsk);
            return true;
        }
        QThread::msleep(200);
    }
#ifndef Q_OS_WIN
    // Linux: a copy from before this way of asking doesn't watch for it.
    endHolder();
    t.restart();
    while (t.elapsed() < 4000) {
        m_lock->removeStaleLockFile();
        if (m_lock->tryLock(200)) {
            QFile::remove(m_endAsk);
            return true;
        }
        QThread::msleep(200);
    }
#endif
    QFile::remove(m_endAsk);
    qWarning("single instance: the running copy didn't end; leaving it");
    return false;
}

#ifndef Q_OS_WIN
void SingleInstance::endHolder()
{
    qint64 pid = 0;
    QString host, app;
    const bool known = m_lock->getLockInfo(&pid, &host, &app) && pid > 0 && pid != QCoreApplication::applicationPid();
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
}
#endif

void SingleInstance::release()
{
    if (m_server) {
        m_server->close();
        delete m_server;
        m_server = nullptr;
    }
    if (m_lock && m_lock->isLocked()) {
        QFile::remove(m_closing);
        QFile::remove(m_endAsk);
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
        // Stuck while closing: says so, then never answers a knock (no event loop) until it is asked to end.
        one.markClosing();
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
        check(second.outcome() == Shown && second.problemText().isEmpty(), "a second copy that was answered says nothing");
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
    // 3. A copy stuck closing (no answer) is asked to end, ends itself, and its lock is taken.
    {
        QProcess *p = holder(60000, true);
        QElapsedTimer t;
        t.start();
        SingleInstance next(nullptr, dir);
        check(next.claim(false), "a copy stuck closing is taken over");
        check(t.elapsed() < 12000, "taking over a stuck copy is quick");
        check(p->waitForFinished(5000), "the stuck copy ended itself");
        p->kill();
        p->waitForFinished(2000);
        delete p;
    }
    // 4. A data folder that can't be written (here a "folder" that is really a file): said as that, never taken as
    // another copy that is open.
    {
        const QString notDir = dir + QStringLiteral("/not-a-folder");
        QFile f(notDir);
        if (f.open(QIODevice::WriteOnly))
            f.write("x");
        f.close();
        SingleInstance stuck(nullptr, notDir);
        check(!stuck.claim(false) && stuck.outcome() == CantWrite && stuck.problemText().contains(QLatin1String("can't save")),
              "a data folder that can't be written is said as that");
    }
    std::printf("instance self-test: %s\n", fail ? "FAILED" : "passed");
    return fail == 0;
}
