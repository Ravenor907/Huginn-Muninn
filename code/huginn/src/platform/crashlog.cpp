#include "crashlog.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QMutex>
#include <QMutexLocker>
#include <QRegularExpression>
#include <QStandardPaths>

#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#ifdef Q_OS_WIN
#include <io.h>
#else
#include <fcntl.h>
#include <unistd.h>
#if defined(__GLIBC__)
#include <execinfo.h>
#endif
#endif

namespace
{
const qint64 kMaxBytes = 200 * 1024;
const qint64 kKeepBytes = 150 * 1024;   // what stays after a trim

QMutex s_lock;
QString s_path;      // crash.log
QString s_running;   // beside it while a run is under way (gone on a clean exit)
FILE *s_file = nullptr;
#ifndef Q_OS_WIN
char s_pathC[4096] = {0};   // for the crash handler, which may not allocate
int s_fd = -1;
#endif

// Opens crash.log for adding (s_lock held).
void openFile()
{
    if (s_file || s_path.isEmpty())
        return;
#ifdef Q_OS_WIN
    s_file = _wfopen(reinterpret_cast<const wchar_t *>(QDir::toNativeSeparators(s_path).utf16()), L"ab");
#else
    s_file = std::fopen(QFile::encodeName(s_path).constData(), "ab");
    s_fd = s_file ? fileno(s_file) : -1;
#endif
}

void writeRaw(const char *text)
{
    if (!s_file)
        return;
    std::fputs(text, s_file);
    std::fputc('\n', s_file);
    std::fflush(s_file);   // a crash right after must not lose it
}

#ifndef Q_OS_WIN
void put(int fd, const char *s)
{
    if (fd >= 0)
        (void)!::write(fd, s, std::strlen(s));
}

// Linux: a crash or abort. Only what's safe inside a signal handler: the signal and the calls that led there.
void crashed(int sig)
{
    int fd = s_fd;
    if (fd < 0 && s_pathC[0])
        fd = ::open(s_pathC, O_WRONLY | O_APPEND | O_CREAT, 0600);
    const char *what = sig == SIGSEGV ? "crash: segmentation fault" : sig == SIGABRT ? "crash: abort" : sig == SIGBUS ? "crash: bus error"
                     : sig == SIGFPE ? "crash: arithmetic error" : sig == SIGILL ? "crash: illegal instruction" : "crash: signal";
    put(fd, what);
    put(fd, "\n");
#if defined(__GLIBC__)
    void *frames[48];
    const int n = backtrace(frames, 48);
    if (fd >= 0)
        backtrace_symbols_fd(frames, n, fd);
#endif
    // The default action now (the handler was reset on entry): the process ends as it would have.
    std::raise(sig);
}
#else
void aborted(int)
{
    CrashLog::line("crash: abort");
    std::signal(SIGABRT, SIG_DFL);
    std::abort();
}
#endif

QString stamp() { return QDateTime::currentDateTime().toString(QStringLiteral("yyyy-MM-dd HH:mm:ss ")); }
}

namespace CrashLog
{
void start()
{
    const QString dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    if (dir.isEmpty())
        return;
    QDir().mkpath(dir);
    {
        QMutexLocker l(&s_lock);
        s_path = dir + QStringLiteral("/crash.log");
        s_running = dir + QStringLiteral("/running.mark");
#ifndef Q_OS_WIN
        const QByteArray p = QFile::encodeName(s_path);
        if (p.size() < int(sizeof s_pathC))
            std::memcpy(s_pathC, p.constData(), size_t(p.size()) + 1);
#endif
    }
#ifndef Q_OS_WIN
    struct sigaction sa;
    std::memset(&sa, 0, sizeof sa);
    sa.sa_handler = crashed;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_RESETHAND | SA_NODEFER;
    for (int sig : {SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL})
        sigaction(sig, &sa, nullptr);
#else
    std::signal(SIGABRT, aborted);
#endif
}

void claimed()
{
    QMutexLocker l(&s_lock);
    if (s_path.isEmpty())
        return;
    // Only the last ~200 KB: older lines go, cut at a whole line.
    if (QFileInfo(s_path).size() > kMaxBytes) {
        if (s_file) {
            std::fclose(s_file);
            s_file = nullptr;
#ifndef Q_OS_WIN
            s_fd = -1;
#endif
        }
        QFile f(s_path);
        if (f.open(QIODevice::ReadOnly)) {
            f.seek(f.size() - kKeepBytes);
            QByteArray keep = f.readAll();
            f.close();
            const qsizetype nl = keep.indexOf('\n');
            keep = nl >= 0 ? keep.mid(nl + 1) : QByteArray();
            if (f.open(QIODevice::WriteOnly | QIODevice::Truncate))
                f.write(keep);
        }
    }
    openFile();
    // The last run never closed cleanly: it crashed, hung or was ended.
    QFile mark(s_running);
    if (mark.exists()) {
        QByteArray when;
        if (mark.open(QIODevice::ReadOnly)) {
            when = mark.readAll().trimmed().left(40);
            mark.close();
        }
        writeRaw((stamp() + QStringLiteral("the last run (started ") + QString::fromLatin1(when)
                  + QStringLiteral(") ended without closing")).toUtf8().constData());
    }
    if (mark.open(QIODevice::WriteOnly | QIODevice::Truncate))
        mark.write(QDateTime::currentDateTime().toString(Qt::ISODate).toLatin1() + ' ' + QCoreApplication::applicationVersion().toUtf8().left(40));
}

void cleanExit()
{
    QMutexLocker l(&s_lock);
    if (!s_running.isEmpty())
        QFile::remove(s_running);
    if (s_file) {
        std::fclose(s_file);
        s_file = nullptr;
#ifndef Q_OS_WIN
        s_fd = -1;
#endif
    }
}

QString scrub(const QString &text)
{
    QString t = text;
    // The home folder names the person's account.
    const QString home = QDir::homePath();
    if (home.size() > 1) {
        t.replace(home, QStringLiteral("~"), Qt::CaseInsensitive);
        t.replace(QDir::toNativeSeparators(home), QStringLiteral("~"), Qt::CaseInsensitive);
    }
    // Anything that looks like a network address (IPv4, or IPv6 with at least two colons).
    static const QRegularExpression v4(QStringLiteral("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b"));
    static const QRegularExpression v6(QStringLiteral("(?<![\\w:])(?:[0-9A-Fa-f]{1,4}:){2,7}[0-9A-Fa-f]{1,4}(?![\\w:])"));
    static const QRegularExpression v6short(
        QStringLiteral("(?<![\\w:])(?:[0-9A-Fa-f]{1,4}:){1,7}:(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4}){0,6})?(?![\\w:])"));
    t.replace(v4, QStringLiteral("[address]"));
    t.replace(v6, QStringLiteral("[address]"));
    t.replace(v6short, QStringLiteral("[address]"));
    t.replace(QLatin1Char('\n'), QLatin1Char(' '));
    return t.left(400);
}

void note(const QString &what)
{
    QMutexLocker l(&s_lock);
    if (s_path.isEmpty())
        return;
    openFile();
    writeRaw((stamp() + scrub(what)).toUtf8().constData());
}

void line(const char *text)
{
    // A crash handler: no waiting on a lock another thread may hold forever.
    if (!s_lock.tryLock(500))
        return;
    openFile();
    writeRaw(text);
    s_lock.unlock();
}

QString tail(int maxChars)
{
    QMutexLocker l(&s_lock);
    if (s_path.isEmpty() || maxChars <= 0)
        return {};
    QFile f(s_path);
    if (!f.open(QIODevice::ReadOnly))
        return {};
    const qint64 take = qMin<qint64>(f.size(), qint64(maxChars) * 2);
    f.seek(f.size() - take);
    QString t = QString::fromUtf8(f.readAll()).trimmed();
    if (t.size() > maxChars) {
        t = t.right(maxChars);
        const qsizetype nl = t.indexOf(QLatin1Char('\n'));
        t = nl >= 0 ? t.mid(nl + 1) : QString();
    }
    return t;
}

bool hasLines()
{
    QMutexLocker l(&s_lock);
    return !s_path.isEmpty() && QFileInfo(s_path).size() > 0;
}
}
