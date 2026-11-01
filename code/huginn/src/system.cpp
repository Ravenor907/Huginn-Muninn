#include "system.h"
#include "settings.h"

#include "platform/crashlog.h"
#include "platform/keepawake.h"
#include "platform/winstart.h"

#ifndef Q_OS_WIN
#include <QDBusConnection>
#include <QDBusMessage>
#include <QDBusObjectPath>
#endif
#include <QCoreApplication>
#include <QDesktopServices>
#include <QDir>
#include <QFile>
#include <QFileDialog>
#include <QGuiApplication>
#include <QScreen>
#include <QSGRendererInterface>
#include <QThread>
#include <QUrl>
#include <QImage>
#include <QLocale>
#include <QQuickWindow>
#include <QStandardPaths>
#include <QTimer>
#include <QVariantMap>

#ifdef Q_OS_WIN
#include <rhi/qrhi.h>
#else
#include <QOpenGLContext>
#include <QOpenGLFunctions>
#endif

#include <cmath>
#include <cstdio>

int System::s_qmlWarnings = 0;

System::System(Settings *settings, const QString &tourDir, bool windowed, QObject *parent)
    : QObject(parent)
    , m_settings(settings)
    , m_tourDir(tourDir)
    , m_windowed(windowed)   // --windowed (and the tour): always a window, whatever Settings › Look says
{
    m_gameMode = detectGameMode();
    // The Size setting shares Settings' changed signal with everything else: the whole layout is told only when
    // the size actually changes (every Theme.px() binding follows it).
    connect(settings, &Settings::changed, this, &System::refit);
    watchScreen(QGuiApplication::primaryScreen());
    refit();
}

bool System::detectGameMode()
{
    return qEnvironmentVariable("XDG_CURRENT_DESKTOP").contains(QLatin1String("gamescope"), Qt::CaseInsensitive)
        || qEnvironmentVariable("XDG_SESSION_DESKTOP").contains(QLatin1String("gamescope"), Qt::CaseInsensitive)
        || !qEnvironmentVariableIsEmpty("GAMESCOPE_WAYLAND_DISPLAY")
        || qEnvironmentVariable("SteamGamepadUI") == QLatin1String("1");
}

double System::fitFor(const QSize &px, const QSizeF &mm, bool gameMode)
{
    if (px.width() <= 0 || px.height() <= 0)
        return 1.0;
    const double pxDiag = std::hypot(double(px.width()), double(px.height()));
    // The screen's diagonal in inches, from its physical size when that can be believed: the same shape as the
    // picture (either way round: a handheld's panel is often mounted turned) and a size a screen can be.
    double inches = 0;
    if (mm.width() > 0 && mm.height() > 0) {
        const double a = double(px.width()) / px.height(), m = mm.width() / mm.height();
        const bool sameShape = std::abs(m / a - 1.0) < 0.15 || std::abs(m * a - 1.0) < 0.15;
        const double d = std::hypot(mm.width(), mm.height()) / 25.4;
        // Some compositors make a size up from 96 dots per inch when the screen doesn't say: no help in Game Mode.
        const bool madeUp = std::abs(pxDiag / d - 96.0) < 1.5;
        if (sameShape && d >= 3.0 && d <= 120.0 && !(gameMode && madeUp))
            inches = d;
    }
    // Game Mode on a screen that doesn't say its size: a handheld's 7 inches.
    if (inches <= 0 && gameMode)
        inches = 7.0;
    // Monitors, TVs and laptops at their usual scaling stay exactly as designed.
    if (inches <= 0 || inches > 17.5)
        return 1.0;
    // Designed for about 150 pixels per inch on a small screen (a 1280x800 handheld draws at 1.45, a 7 inch 1080p
    // one at 2.1). The pixels are the system's own, after its scaling, so that is never counted twice.
    const double fit = qBound(1.0, pxDiag / inches / 150.0, 2.5);
    return std::round(fit * 20.0) / 20.0;
}

void System::watchScreen(QScreen *screen)
{
    if (m_screen == screen)
        return;
    if (m_screen)
        disconnect(m_screen, nullptr, this, nullptr);
    m_screen = screen;
    if (screen) {
        connect(screen, &QScreen::geometryChanged, this, &System::refit);
        connect(screen, &QScreen::physicalSizeChanged, this, &System::refit);
    }
}

void System::refit()
{
    // The screenshot tour keeps the design size, so its pictures never depend on the machine.
    const double fit = m_tourDir.isEmpty() && m_screen ? fitFor(m_screen->size(), m_screen->physicalSize(), m_gameMode) : 1.0;
    const double ui = fit * qBound(50, m_settings->uiSize().toInt(), 200) / 100.0;
    if (qFuzzyCompare(fit, m_fit) && qFuzzyCompare(ui, m_uiScale))
        return;
    m_fit = fit;
    m_uiScale = ui;
    Q_EMIT layoutChanged();
}

void System::watchWindow(QQuickWindow *window)
{
    if (!window)
        return;
    watchScreen(window->screen());
    refit();
    connect(window, &QQuickWindow::screenChanged, this, [this](QScreen *s) {
        watchScreen(s);
        refit();
    });
    // What draws the window, noted once it's ready (on the render thread, where the GPU can be asked its name).
    connect(window, &QQuickWindow::sceneGraphInitialized, this, [this, window] {
        const QSGRendererInterface *ri = window->rendererInterface();
        const QSGRendererInterface::GraphicsApi api = ri ? ri->graphicsApi() : QSGRendererInterface::Unknown;
        QString name;
        bool software = false;
        switch (api) {
        case QSGRendererInterface::Software: name = QStringLiteral("Software"); software = true; break;
        case QSGRendererInterface::OpenGL: name = QStringLiteral("OpenGL"); break;
        case QSGRendererInterface::Direct3D11: name = QStringLiteral("Direct3D 11"); break;
        case QSGRendererInterface::Direct3D12: name = QStringLiteral("Direct3D 12"); break;
        case QSGRendererInterface::Vulkan: name = QStringLiteral("Vulkan"); break;
        case QSGRendererInterface::Metal: name = QStringLiteral("Metal"); break;
        default: name = QStringLiteral("Unknown"); break;
        }
        QString gpu;
#ifdef Q_OS_WIN
        if (QRhi *rhi = window->rhi()) {
            const QRhiDriverInfo d = rhi->driverInfo();
            gpu = QString::fromUtf8(d.deviceName).trimmed();
            if (d.deviceType == QRhiDriverInfo::CpuDevice)
                software = true;
        }
#else
        if (api == QSGRendererInterface::OpenGL) {
            if (QOpenGLContext *ctx = QOpenGLContext::currentContext()) {
                if (const GLubyte *r = ctx->functions()->glGetString(GL_RENDERER))
                    gpu = QString::fromLatin1(reinterpret_cast<const char *>(r)).trimmed();
            }
        }
#endif
        // Mesa's and Windows' drawing on the processor.
        const QString low = gpu.toLower();
        for (const char *cpu : {"llvmpipe", "softpipe", "swrast", "lavapipe", "swiftshader", "basic render"})
            if (low.contains(QLatin1String(cpu)))
                software = true;
        const QString what = gpu.isEmpty() ? name : name + QStringLiteral(", ") + gpu;
        const QString line = QStringLiteral("graphics: ") + what
            + (QThread::currentThread() != qApp->thread() ? QStringLiteral(" (threaded") : QStringLiteral(" (basic"))
            + QStringLiteral(" render loop, ") + QGuiApplication::platformName() + QStringLiteral(" platform)")
            + (software ? QStringLiteral(": drawn by the processor, slow") : QString());
        QMetaObject::invokeMethod(this, [this, what, software, line] {
            noteGraphics(what, software);
            CrashLog::note(line);
            Platform::startStep(line);
            std::fprintf(stderr, "%s\n", line.toLocal8Bit().constData());
        }, Qt::QueuedConnection);
    }, Qt::DirectConnection);
}

void System::noteGraphics(const QString &what, bool software)
{
    if (what == m_graphics && software == m_software)
        return;
    m_graphics = what;
    m_software = software;
    Q_EMIT graphicsChanged();
}

QString System::version() const
{
    return QCoreApplication::applicationVersion();
}

QString System::formatSize(qint64 bytes)
{
    if (bytes < 0)
        return {};
    return QLocale(QLocale::English).formattedDataSize(bytes, 1, QLocale::DataSizeTraditionalFormat);
}

QString System::displayPath(const QUrl &url)
{
    if (!url.isLocalFile()) {
        if (url.scheme() == QLatin1String("trash"))
            return QStringLiteral("Trash");
        QString host = url.host();
        return (host.isEmpty() ? url.scheme() : host) + (url.path().isEmpty() || url.path() == QLatin1String("/") ? QString() : url.path().replace(QLatin1Char('/'), QStringLiteral(" / ")));
    }
    QString p = url.toLocalFile();
    const QString home = QDir::homePath();
    if (p == home)
        return QStringLiteral("Home");
    if (p.startsWith(home + QLatin1Char('/'))) {
        const QString rest = p.mid(home.size() + 1);
        return QStringLiteral("Home / ") + rest.split(QLatin1Char('/'), Qt::SkipEmptyParts).join(QStringLiteral(" / "));
    }
    return p == QLatin1String("/") ? QStringLiteral("System") : p.split(QLatin1Char('/'), Qt::SkipEmptyParts).join(QStringLiteral(" / "));
}

bool System::inFlatpak()
{
    return QFile::exists(QStringLiteral("/.flatpak-info"));
}

void System::openSteamUrl(const QString &url)
{
    // Qt opens it the sandbox-safe way: inside the Flatpak through the desktop's OpenURI portal,
    // outside it with the desktop's own handler. Nothing is started outside the sandbox.
    QDesktopServices::openUrl(QUrl(url));
}


void System::showSteamKeyboard()
{
    // Steam's own keyboard types into whatever has focus. Desktop users have a real keyboard.
    if (m_gameMode)
        openSteamUrl(QStringLiteral("steam://open/keyboard"));
}

void System::hideSteamKeyboard()
{
    if (m_gameMode)
        openSteamUrl(QStringLiteral("steam://close/keyboard"));
}

QStringList System::pickFiles() const
{
    return QFileDialog::getOpenFileNames(nullptr, QStringLiteral("Pick files to send"),
                                         QStandardPaths::writableLocation(QStandardPaths::DownloadLocation));
}

QString System::pickPicture() const
{
    const QString f = QFileDialog::getOpenFileName(nullptr, QStringLiteral("Choose a picture"),
                                                   QStandardPaths::writableLocation(QStandardPaths::PicturesLocation),
                                                   QStringLiteral("Pictures (*.png *.jpg *.jpeg *.webp *.bmp *.gif)"));
    return f.isEmpty() ? QString() : QUrl::fromLocalFile(f).toString();
}

void System::grab(QQuickWindow *window, const QString &name)
{
    if (m_tourDir.isEmpty() || !window)
        return;
    QDir().mkpath(m_tourDir);
    const QImage img = window->grabWindow();
    img.save(m_tourDir + QLatin1Char('/') + name + QStringLiteral(".png"));
}

void System::tourFinished()
{
    QTimer::singleShot(0, qApp, [] { QCoreApplication::exit(s_qmlWarnings > 0 ? 3 : 0); });
}

void System::setKeepAwake(bool on, const QString &reason)
{
    if (on == m_awake)
        return;
    m_awake = on;
#ifdef Q_OS_WIN
    Q_UNUSED(reason);
    Platform::keepAwake(on);
#else
    QDBusConnection bus = QDBusConnection::sessionBus();
    auto call = [&bus](const QString &service, const QString &path, const QString &iface, const QString &method, const QVariantList &args) {
        QDBusMessage m = QDBusMessage::createMethodCall(service, path, iface, method);
        m.setArguments(args);
        return bus.call(m, QDBus::Block, 2000);
    };
    if (on) {
        // The desktop portal is the sandbox-friendly way; the screensaver interface covers sessions without it.
        const QDBusMessage r = call(QStringLiteral("org.freedesktop.portal.Desktop"), QStringLiteral("/org/freedesktop/portal/desktop"),
                                    QStringLiteral("org.freedesktop.portal.Inhibit"), QStringLiteral("Inhibit"),
                                    {QString(), uint(4 | 8), QVariantMap{{QStringLiteral("reason"), reason}}});
        if (r.type() == QDBusMessage::ReplyMessage && !r.arguments().isEmpty())
            m_inhibitHandle = r.arguments().first().value<QDBusObjectPath>().path();
        const QDBusMessage c = call(QStringLiteral("org.freedesktop.ScreenSaver"), QStringLiteral("/org/freedesktop/ScreenSaver"),
                                    QStringLiteral("org.freedesktop.ScreenSaver"), QStringLiteral("Inhibit"), {QStringLiteral("Huginn"), reason});
        if (c.type() == QDBusMessage::ReplyMessage && !c.arguments().isEmpty())
            m_screensaverCookie = c.arguments().first().toUInt();
    } else {
        if (!m_inhibitHandle.isEmpty())
            call(QStringLiteral("org.freedesktop.portal.Desktop"), m_inhibitHandle, QStringLiteral("org.freedesktop.portal.Request"), QStringLiteral("Close"), {});
        m_inhibitHandle.clear();
        if (m_screensaverCookie)
            call(QStringLiteral("org.freedesktop.ScreenSaver"), QStringLiteral("/org/freedesktop/ScreenSaver"),
                 QStringLiteral("org.freedesktop.ScreenSaver"), QStringLiteral("UnInhibit"), {m_screensaverCookie});
        m_screensaverCookie = 0;
    }
#endif
}
