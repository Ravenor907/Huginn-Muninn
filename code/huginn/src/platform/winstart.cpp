#include "winstart.h"

#ifndef Q_OS_WIN

namespace Platform
{
void beginStart() {}
void startClaimed(bool) {}
void chooseGraphics() {}
void startStep(const QString &) {}
void watchMainWindow(QQuickWindow *) {}
void blockCapture(QQuickWindow *, bool) {}
void startFailed(const QString &) {}
void noStartLog() {}
QProcessEnvironment childEnvironment() { return QProcessEnvironment::systemEnvironment(); }
}

#else

#include "crashlog.h"
#include "portable.h"
#include "singleinstance.h"
#include "winupdate.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QGuiApplication>
#include <QMutex>
#include <QOffscreenSurface>
#include <QOperatingSystemVersion>
#include <QProcess>
#include <QProcessEnvironment>
#include <QQuickWindow>
#include <QSGRendererInterface>
#include <QTimer>
#include <rhi/qrhi.h>

#include <windows.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#ifndef HUGINN_VERSION
#define HUGINN_VERSION "0.1"
#endif

namespace
{
QString s_logPath;
FILE *s_log = nullptr;
QMutex s_logLock;
// Until startClaimed() the lines wait in memory: a second copy started while this one runs (or
// updates) must not empty this one's log. Closed: nothing more is kept.
enum class LogState { Waiting, Open, Closed };
LogState s_state = LogState::Waiting;
QList<QByteArray> s_waiting;
QtMessageHandler s_next = nullptr;
bool s_keep = false;
bool s_shown = false;
// This copy was started again after a graphics failure: with the software renderer (before the first frame), or
// after the graphics reset once already. Read at start and taken out of the environment, so later copies (an
// update, a restore) start normally.
bool s_softwareRetry = false;
bool s_resetRestarted = false;

QString exeDir()
{
    std::wstring self(32768, L'\0');
    const DWORD n = GetModuleFileNameW(nullptr, self.data(), DWORD(self.size()));
    if (n == 0 || n >= self.size())
        return {};
    self.resize(n);
    return QFileInfo(QDir::fromNativeSeparators(QString::fromStdWString(self))).absolutePath();
}

// Opens Huginn-start.log in the data folder beside Huginn.exe (next to Huginn.exe on a development build; the temp
// folder if that can't be written) and writes what waited. mode: L"w" for a fresh log, L"a" to add to one (a crash
// before startClaimed).
void openLog(const wchar_t *mode)
{
    for (const QString &where : {Portable::on() ? Portable::dataDir() : exeDir(), QDir::tempPath()}) {
        if (where.isEmpty())
            continue;
        s_logPath = where + QStringLiteral("/Huginn-start.log");
        s_log = _wfopen(reinterpret_cast<const wchar_t *>(QDir::toNativeSeparators(s_logPath).utf16()), mode);
        if (s_log)
            break;
    }
    if (!s_log) {
        s_logPath.clear();
        s_state = LogState::Closed;
        s_waiting.clear();
        return;
    }
    s_state = LogState::Open;
    for (const QByteArray &l : std::as_const(s_waiting)) {
        std::fputs(l.constData(), s_log);
        std::fputc('\n', s_log);
    }
    s_waiting.clear();
    std::fflush(s_log);
}

void writeLine(const QByteArray &line)
{
    QMutexLocker lock(&s_logLock);
    if (s_state == LogState::Waiting) {
        if (s_waiting.size() < 500)
            s_waiting << line;
        return;
    }
    if (!s_log)
        return;
    std::fputs(line.constData(), s_log);
    std::fputc('\n', s_log);
    std::fflush(s_log);   // a hang or a crash must not lose the last lines
}

void logLine(const QString &what)
{
    writeLine(QDateTime::currentDateTime().toString(QStringLiteral("HH:mm:ss.zzz ")).toUtf8() + what.toUtf8());
}

void messages(QtMsgType type, const QMessageLogContext &ctx, const QString &msg)
{
    if (type != QtDebugMsg) {
        static const char *names[] = {"debug", "warning", "critical", "fatal", "info"};
        logLine(QString::fromLatin1(names[qBound(0, int(type), 4)]) + QStringLiteral(": ") + msg);
    }
    if (s_next)
        s_next(type, ctx, msg);
}

// A crash: what and where, so the log says more than "it stopped".
LONG WINAPI crashed(EXCEPTION_POINTERS *e)
{
    char line[512];
    const void *at = e && e->ExceptionRecord ? e->ExceptionRecord->ExceptionAddress : nullptr;
    HMODULE mod = nullptr;
    char name[MAX_PATH] = "?";
    if (at && GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT, static_cast<LPCSTR>(at), &mod))
        GetModuleFileNameA(mod, name, MAX_PATH);
    {
        // Crashed before startClaimed: what waited goes to the end of the log, which is kept.
        QMutexLocker lock(&s_logLock);
        if (s_state == LogState::Waiting)
            openLog(L"a");
        s_keep = true;
    }
    std::snprintf(line, sizeof line, "crash: exception 0x%08lx at %s+0x%llx",
                  e && e->ExceptionRecord ? e->ExceptionRecord->ExceptionCode : 0ul, name,
                  static_cast<unsigned long long>(reinterpret_cast<quintptr>(at) - reinterpret_cast<quintptr>(mod)));
    writeLine(line);
    CrashLog::line(line);
    // The calls that led there (module+offset each), unwound from the crash's own registers.
    if (e && e->ContextRecord) {
        CONTEXT c = *e->ContextRecord;
        for (int i = 0; i < 32 && c.Rip; ++i) {
            HMODULE m = nullptr;
            char mname[MAX_PATH] = "?";
            if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT, reinterpret_cast<LPCSTR>(c.Rip), &m))
                GetModuleFileNameA(m, mname, MAX_PATH);
            const char *base = std::strrchr(mname, '\\');
            std::snprintf(line, sizeof line, "  at %s+0x%llx", base ? base + 1 : mname,
                          static_cast<unsigned long long>(c.Rip - reinterpret_cast<quintptr>(m)));
            writeLine(line);
            CrashLog::line(line);
            DWORD64 imageBase = 0;
            PRUNTIME_FUNCTION fn = RtlLookupFunctionEntry(c.Rip, &imageBase, nullptr);
            if (!fn) {
                // A leaf function: the return address is on top of the stack.
                c.Rip = *reinterpret_cast<DWORD64 *>(c.Rsp);
                c.Rsp += 8;
                continue;
            }
            void *handlerData = nullptr;
            DWORD64 frame = 0;
            RtlVirtualUnwind(UNW_FLAG_NHANDLER, imageBase, c.Rip, fn, &c, &handlerData, &frame, nullptr);
        }
    }
    return EXCEPTION_CONTINUE_SEARCH;
}

void closeLog(bool remove)
{
    QMutexLocker lock(&s_logLock);
    if (s_log) {
        std::fclose(s_log);
        s_log = nullptr;
    }
    s_state = LogState::Closed;
    s_waiting.clear();
    if (remove && !s_logPath.isEmpty())
        QFile::remove(s_logPath);
}

bool envSet(const char *name)
{
    return !qEnvironmentVariableIsEmpty(name);
}

QSGRendererInterface::GraphicsApi apiFor(QRhi::Implementation i)
{
    return i == QRhi::OpenGLES2 ? QSGRendererInterface::OpenGL : QSGRendererInterface::Direct3D11;
}

bool probe(QRhi::Implementation impl)
{
    if (impl == QRhi::OpenGLES2) {
        QRhiGles2InitParams params;
        params.fallbackSurface = QRhiGles2InitParams::newFallbackSurface();
        const bool ok = QRhi::probe(impl, &params);
        delete params.fallbackSurface;
        return ok;
    }
    QRhiD3D11InitParams params;
    return QRhi::probe(impl, &params);
}
}

namespace Platform
{
void beginStart()
{
    s_keep = envSet("HUGINN_KEEP_START_LOG");
    s_softwareRetry = envSet("HUGINN_GRAPHICS_RETRY");
    s_resetRestarted = envSet("HUGINN_GRAPHICS_RESET");
    qunsetenv("HUGINN_GRAPHICS_RETRY");
    qunsetenv("HUGINN_GRAPHICS_RESET");
    const QString dir = exeDir();
    s_next = qInstallMessageHandler(messages);
    SetUnhandledExceptionFilter(crashed);
    logLine(QStringLiteral("Huginn %1 starting in %2").arg(QStringLiteral(HUGINN_VERSION), QDir::toNativeSeparators(dir)));

    // Qt's own reading of the Windows version (the real one, whatever the manifest says).
    const QOperatingSystemVersion os = QOperatingSystemVersion::current();
    logLine(QStringLiteral("Windows %1.%2 build %3").arg(os.majorVersion()).arg(os.minorVersion()).arg(os.microVersion()));

    // The DLLs are in lib\ (a private assembly, see packaging/windows/huginn.manifest.in). Qt looks for
    // OpenSSL's libssl by full path in Huginn's folder, the system folder and then PATH, so lib\ goes on PATH.
    // Wide strings all the way: a folder name outside the system's code page (a check mark, a Polish name on an English
    // Windows) would otherwise turn into question marks and Qt's secure connections couldn't load.
    const QString lib = dir + QStringLiteral("/lib");
    if (!dir.isEmpty() && QFileInfo::exists(lib + QStringLiteral("/lib.manifest"))) {
        const wchar_t *was = _wgetenv(L"PATH");
        const std::wstring path = QDir::toNativeSeparators(lib).toStdWString() + L";" + (was ? std::wstring(was) : std::wstring());
        _wputenv_s(L"PATH", path.c_str());
        // And OpenSSL's TLS library loaded now by its full path, so Qt finds it already there whatever PATH holds.
        const QString ssl = lib + QStringLiteral("/libssl-3-x64.dll");
        if (QFileInfo::exists(ssl) && !LoadLibraryW(QDir::toNativeSeparators(ssl).toStdWString().c_str()))
            logLine(QStringLiteral("couldn't load lib\\libssl-3-x64.dll"));
    }
}

void startClaimed(bool tidy)
{
    {
        QMutexLocker lock(&s_logLock);
        if (s_state == LogState::Waiting)
            openLog(L"w");
    }
    if (!tidy)
        return;
    // What an update moved aside and files from other folder layouts. Only now: a second copy that
    // quits on the single-instance check must not touch an update that's under way.
    WinUpdate::cleanUp();
    // The copy that just updated us may still have been closing, holding its old Huginn.exe: once more in a while.
    QTimer::singleShot(15000, qApp, [] { WinUpdate::cleanUp(); });
}

void chooseGraphics()
{
    const QString platform = QGuiApplication::platformName();
    logLine(QStringLiteral("Qt %1, platform %2").arg(QString::fromLatin1(qVersion()), platform));
    if (envSet("QSG_RHI_BACKEND") || envSet("QT_QUICK_BACKEND")) {
        logLine(QStringLiteral("graphics: as set (QSG_RHI_BACKEND=%1, QT_QUICK_BACKEND=%2)")
                    .arg(qEnvironmentVariable("QSG_RHI_BACKEND"), qEnvironmentVariable("QT_QUICK_BACKEND")));
        return;
    }
    if (platform != QLatin1String("windows")) {
        // offscreen or minimal (CI): no screen to draw on, so the software renderer.
        QQuickWindow::setGraphicsApi(QSGRendererInterface::Software);
        logLine(QStringLiteral("graphics: software (no screen)"));
        return;
    }
    const QList<QRhi::Implementation> order{QRhi::D3D11, QRhi::OpenGLES2};
    for (const QRhi::Implementation impl : order) {
        if (probe(impl)) {
            QQuickWindow::setGraphicsApi(apiFor(impl));
            logLine(QStringLiteral("graphics: %1").arg(impl == QRhi::OpenGLES2 ? QStringLiteral("OpenGL") : QStringLiteral("Direct3D 11")));
            return;
        }
        logLine(QStringLiteral("graphics: %1 didn't start").arg(impl == QRhi::OpenGLES2 ? QStringLiteral("OpenGL") : QStringLiteral("Direct3D 11")));
    }
    QQuickWindow::setGraphicsApi(QSGRendererInterface::Software);
    logLine(QStringLiteral("graphics: software"));
}

void startStep(const QString &what)
{
    logLine(what);
}

void blockCapture(QQuickWindow *window, bool on)
{
    if (!window)
        return;
#ifndef WDA_EXCLUDEFROMCAPTURE
#define WDA_EXCLUDEFROMCAPTURE 0x00000011
#endif
    // Only ever called once Block screenshots was switched on (main.cpp): a window that never had it is left alone.
    const HWND hwnd = reinterpret_cast<HWND>(window->winId());
    if (!on) {
        SetWindowDisplayAffinity(hwnd, WDA_NONE);
        return;
    }
    // Windows 10 2004 and newer leave the window out; older ones only know WDA_MONITOR (shown black).
    if (!SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE))
        SetWindowDisplayAffinity(hwnd, WDA_MONITOR);
}

void watchMainWindow(QQuickWindow *window)
{
    if (!window) {
        startFailed(QStringLiteral("no main window"));
        return;
    }
    // No window could be made (no screen Windows can draw on): say so and stop, rather than
    // run on with nothing to see.
    QTimer::singleShot(0, window, [window] {
        if (!window->handle()) {
            startFailed(QStringLiteral("Windows couldn't make Huginn's window"));
            QCoreApplication::exit(5);
        }
    });
    // Drawn once: the start worked.
    auto *once = new QObject(window);
    QObject::connect(window, &QQuickWindow::frameSwapped, once, [once] {
        once->deleteLater();
        if (s_shown)
            return;   // more frames were already on their way
        logLine(QStringLiteral("window shown"));
        s_shown = true;
        QObject::connect(qApp, &QCoreApplication::aboutToQuit, qApp, [] { closeLog(!s_keep); });
        QTimer::singleShot(5000, qApp, [] {
            logLine(QStringLiteral("started"));
            closeLog(!s_keep);
        });
    }, Qt::QueuedConnection);
    // The graphics failed. Never drawn: start again once with the software renderer. Drawn before (the graphics
    // card reset, a driver update): start again once, the same way; a second time in one run it stops and says so.
    QObject::connect(window, &QQuickWindow::sceneGraphError, qApp, [](QQuickWindow::SceneGraphError, const QString &msg) {
        static bool handled = false;
        if (handled)
            return;
        handled = true;
        logLine(QStringLiteral("graphics failed: ") + msg);
        CrashLog::note(QStringLiteral("graphics failed") + (s_shown ? QStringLiteral(" after the window was shown: ") : QStringLiteral(" at start: ")) + msg);
        QStringList env;
        if (!s_shown) {
            if (s_softwareRetry) {
                startFailed(QStringLiteral("graphics failed with the software renderer too"));
                QCoreApplication::exit(4);
                return;
            }
            env << QStringLiteral("QT_QUICK_BACKEND=software") << QStringLiteral("HUGINN_GRAPHICS_RETRY=1");
        } else {
            if (s_resetRestarted) {
                startFailed(QStringLiteral("graphics failed again after starting again"));
                QCoreApplication::exit(4);
                return;
            }
            env << QStringLiteral("HUGINN_GRAPHICS_RESET=1");
            if (s_softwareRetry)
                env << QStringLiteral("QT_QUICK_BACKEND=software") << QStringLiteral("HUGINN_GRAPHICS_RETRY=1");
        }
        // The new copy waits until this one has saved and closed.
        const bool started = SingleInstance::startAgain(QString(), QString(), env, QCoreApplication::arguments().mid(1));
        logLine(started ? (s_shown ? QStringLiteral("starting again") : QStringLiteral("starting again with the software renderer")) : QStringLiteral("couldn't start again"));
        if (!started)
            CrashLog::note(QStringLiteral("couldn't start again after the graphics failed"));
        QCoreApplication::exit(4);
    });
}

void startFailed(const QString &why)
{
    logLine(QStringLiteral("start failed: ") + why);
    s_keep = true;
}

void noStartLog()
{
    closeLog(true);
}

QProcessEnvironment childEnvironment()
{
    QProcessEnvironment env = QProcessEnvironment::systemEnvironment();
    env.remove(QStringLiteral("HUGINN_GRAPHICS_RETRY"));
    env.remove(QStringLiteral("HUGINN_GRAPHICS_RESET"));
    // The software renderer was only for this copy (a graphics retry), not every copy after it.
    if (s_softwareRetry)
        env.remove(QStringLiteral("QT_QUICK_BACKEND"));
    return env;
}
}

#endif
