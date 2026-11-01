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
#include <QSettings>
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

namespace
{
// Known handhelds for Settings › Look › Handheld mode (Automatic). Checked in order and the first match wins, so
// the more specific names come first. part: a case-insensitive piece of the product name, product family or board
// name (Linux: /sys/class/dmi/id; Windows: the BIOS key in the registry). maker: a piece of the system vendor that
// must match too (empty: any maker). An entry without a part matches on the maker alone.
struct KnownHandheld
{
    const char *name;
    const char *part;
    const char *maker;
};
const KnownHandheld kHandhelds[] = {
    {"Steam Deck", "Jupiter", "Valve"},
    {"Steam Deck", "Galileo", "Valve"},
    {"ROG Xbox Ally X", "RC73XA", ""},
    {"ROG Xbox Ally", "RC73YA", ""},
    {"ROG Xbox Ally X", "Xbox Ally X", ""},
    {"ROG Xbox Ally", "Xbox Ally", ""},
    {"ROG Ally X", "RC72LA", ""},
    {"ROG Ally X", "Ally X", "ASUS"},
    {"ROG Ally", "RC71L", ""},
    {"ROG Ally", "Ally", "ASUS"},
    {"Legion Go S", "Legion Go S", ""},
    {"Legion Go S", "83L3", "LENOVO"},
    {"Legion Go S", "83N6", "LENOVO"},
    {"Legion Go", "Legion Go", ""},
    {"Legion Go", "83E1", "LENOVO"},
    {"MSI Claw", "Claw", "Micro-Star"},
    {"Zotac Zone", "Zone", "ZOTAC"},
    {"AYANEO", "", "AYANEO"},
    {"AYANEO", "", "AYADEVICE"},
    {"GPD", "", "GPD"},
    {"OneXPlayer", "", "ONE-NETBOOK"},
};

// The firmware's product name, family, board name and maker. Read from /sys even in the Flatpak (these four are
// readable by anyone; the serial numbers next to them are not read). Empty when they can't be read.
QStringList firmwareNames()
{
#ifdef Q_OS_WIN
    QSettings bios(QStringLiteral("HKEY_LOCAL_MACHINE\\HARDWARE\\DESCRIPTION\\System\\BIOS"), QSettings::NativeFormat);
    return {bios.value(QStringLiteral("SystemProductName")).toString().trimmed(), bios.value(QStringLiteral("SystemFamily")).toString().trimmed(),
            bios.value(QStringLiteral("BaseBoardProduct")).toString().trimmed(), bios.value(QStringLiteral("SystemManufacturer")).toString().trimmed()};
#else
    QStringList out;
    for (const char *f : {"product_name", "product_family", "board_name", "sys_vendor"}) {
        QFile file(QStringLiteral("/sys/class/dmi/id/") + QLatin1String(f));
        out << (file.open(QIODevice::ReadOnly) ? QString::fromUtf8(file.read(256)).trimmed() : QString());
    }
    return out;
#endif
}

// Steam is there to show its keyboard: game mode, or Huginn was started from Steam.
bool steamAround(bool gameMode)
{
    return gameMode || qEnvironmentVariableIsSet("SteamAppId") || qEnvironmentVariableIsSet("SteamGameId")
        || qEnvironmentVariableIsSet("SteamClientLaunch");
}
}

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
    detectHandheld();
    refit();
}

void System::detectHandheld()
{
    // The screenshot tour never looks: its pictures stay the same on every machine.
    if (!m_tourDir.isEmpty())
        return;
    const QStringList fw = firmwareNames();
    m_model = handheldModelFor(fw.value(0), fw.value(1), fw.value(2), fw.value(3));
    // The built-in screen is the one Huginn starts on.
    const QScreen *s = QGuiApplication::primaryScreen();
    const double inches = s ? trustedInches(s->size(), s->physicalSize(), m_gameMode) : 0;
    m_smallScreen = inches >= 6.0 && inches <= 9.0;
    updateHandheld();
}

void System::noteControllerAtStart(bool connected)
{
    m_controllerAtStart = connected && m_tourDir.isEmpty();
    refit();
}

QString System::handheldModelFor(const QString &product, const QString &family, const QString &board, const QString &vendor)
{
    for (const KnownHandheld &k : kHandhelds) {
        if (*k.maker && !vendor.contains(QLatin1String(k.maker), Qt::CaseInsensitive))
            continue;
        if (!*k.part && !vendor.isEmpty())
            return QLatin1String(k.name);
        const QLatin1String part(k.part);
        if (*k.part && (product.contains(part, Qt::CaseInsensitive) || family.contains(part, Qt::CaseInsensitive)
                        || board.contains(part, Qt::CaseInsensitive)))
            return QLatin1String(k.name);
    }
    return {};
}

bool System::handheldRule(bool knownModel, bool smallScreen, bool controller, bool gameMode)
{
    return knownModel || int(smallScreen) + int(controller) + int(gameMode) >= 2;
}

void System::updateHandheld()
{
    const QString mode = m_settings->handheldMode();
    const bool detected = m_tourDir.isEmpty() && handheldRule(!m_model.isEmpty(), m_smallScreen, m_controllerAtStart, m_gameMode);
    // The tour always draws the desktop layout.
    const bool on = m_tourDir.isEmpty() && (mode == QLatin1String("on") || (mode == QLatin1String("auto") && detected));
    QStringList why;
    if (!m_model.isEmpty())
        why << m_model;
    if (m_smallScreen)
        why << QStringLiteral("6 to 9 inch screen");
    if (m_controllerAtStart)
        why << QStringLiteral("controller");
    if (m_gameMode)
        why << QStringLiteral("game mode");
    const QString whyText = why.join(QStringLiteral(", "));
    if (on == m_handheld && detected == m_handheldAuto && whyText == m_handheldWhy)
        return;
    m_handheld = on;
    m_handheldAuto = detected;
    m_handheldWhy = whyText;
    Q_EMIT handheldChanged();
}

bool System::gameLike() const
{
    return m_handheld || (m_gameMode && m_settings->handheldMode() != QLatin1String("off"));
}

QString System::handheldSummary() const
{
    const QString mode = m_settings->handheldMode();
    const QString setting = mode == QLatin1String("on") ? QStringLiteral("On") : mode == QLatin1String("off") ? QStringLiteral("Off") : QStringLiteral("Automatic");
    return (m_handheld ? QStringLiteral("on (") : QStringLiteral("off (")) + setting + QStringLiteral("; detected: ")
        + (m_handheldAuto ? QStringLiteral("handheld") : QStringLiteral("not a handheld"))
        + (m_handheldWhy.isEmpty() ? QString() : QString(QStringLiteral(", ") + m_handheldWhy)) + QLatin1Char(')');
}

bool System::handheldSelfTest()
{
    bool ok = true;
    struct Case { const char *product, *family, *board, *vendor, *want; };
    const Case cases[] = {
        {"Jupiter", "Aerith", "Jupiter", "Valve", "Steam Deck"},
        {"Galileo", "Sephiroth", "Galileo", "Valve", "Steam Deck"},
        {"ROG Ally RC71L_RC71L", "ROG Ally", "RC71L", "ASUSTeK COMPUTER INC.", "ROG Ally"},
        {"ROG Ally X RC72LA_RC72LA", "ROG Ally X", "RC72LA", "ASUSTeK COMPUTER INC.", "ROG Ally X"},
        {"ROG Xbox Ally X RC73XA_RC73XA", "ROG Xbox Ally X", "RC73XA", "ASUSTeK COMPUTER INC.", "ROG Xbox Ally X"},
        {"ROG Xbox Ally RC73YA_RC73YA", "ROG Xbox Ally", "RC73YA", "ASUSTeK COMPUTER INC.", "ROG Xbox Ally"},
        {"83E1", "Legion Go 8APU1", "LNVNB161216", "LENOVO", "Legion Go"},
        {"83L3", "Legion Go S 8ARP1", "LNVNB161216", "LENOVO", "Legion Go S"},
        {"83N6", "", "", "LENOVO", "Legion Go S"},
        {"Claw A1M", "Claw", "MS-1T41", "Micro-Star International Co., Ltd.", "MSI Claw"},
        {"AIR Plus", "AIR", "AB05-Mendocino", "AYANEO", "AYANEO"},
        {"G1618-04", "G1618", "G1618-04", "GPD", "GPD"},
        {"ONEXPLAYER X1 i", "", "", "ONE-NETBOOK", "OneXPlayer"},
        {"ZOTAC GAMING ZONE", "", "G1A1W", "ZOTAC", "Zotac Zone"},
        // Not handhelds: laptops and desktops, including makers that also make handhelds.
        {"ROG Zephyrus G14 GA402RJ", "ROG Zephyrus G14", "GA402RJ", "ASUSTeK COMPUTER INC.", ""},
        {"21CB", "ThinkPad X1 Carbon Gen 10", "21CB", "LENOVO", ""},
        {"Katana GF66 11UC", "Katana GF66", "MS-1582", "Micro-Star International Co., Ltd.", ""},
        {"ZBOX-EN173080C", "", "ZBOX", "ZOTAC", ""},
        {"", "", "", "", ""},
    };
    for (const Case &c : cases) {
        const QString got = handheldModelFor(QLatin1String(c.product), QLatin1String(c.family), QLatin1String(c.board), QLatin1String(c.vendor));
        if (got != QLatin1String(c.want)) {
            std::printf("handheld model for \"%s\" by \"%s\": got \"%s\", want \"%s\"\n", c.product, c.vendor, got.toUtf8().constData(), c.want);
            ok = false;
        }
    }
    // A known model alone is enough; otherwise two of screen, controller and game mode.
    struct Rule { bool model, screen, pad, game, want; };
    const Rule rules[] = {
        {true, false, false, false, true},
        {false, true, true, false, true},
        {false, true, false, true, true},
        {false, false, true, true, true},
        {false, true, false, false, false},
        {false, false, true, false, false},
        {false, false, false, true, false},
        {false, false, false, false, false},
    };
    for (const Rule &r : rules) {
        if (handheldRule(r.model, r.screen, r.pad, r.game) != r.want) {
            std::printf("handheld rule (model %d, screen %d, controller %d, game mode %d) wrong\n", r.model, r.screen, r.pad, r.game);
            ok = false;
        }
    }
    // A laptop with a plugged-in pad and a 14 inch screen: not a handheld.
    {
        const double inches = trustedInches(QSize(1920, 1200), QSizeF(302, 189), false);
        const bool small = inches >= 6.0 && inches <= 9.0;
        const QString model = handheldModelFor(QStringLiteral("82XV"), QStringLiteral("IdeaPad Slim 5 14IRL8"), QStringLiteral("LNVNB161216"), QStringLiteral("LENOVO"));
        if (small || !model.isEmpty() || handheldRule(!model.isEmpty(), small, true, false)) {
            std::printf("a 14 inch laptop with a controller counted as a handheld\n");
            ok = false;
        }
        // A 7 inch handheld screen does count as small.
        const double hand = trustedInches(QSize(1920, 1080), QSizeF(155, 87), false);
        if (!(hand >= 6.0 && hand <= 9.0)) {
            std::printf("a 7 inch screen read as %.1f inches\n", hand);
            ok = false;
        }
    }
    std::printf("handheld detection: known models, two of three, laptop with a pad: %s\n", ok ? "ok" : "FAILED");
    return ok;
}

bool System::detectGameMode()
{
    return qEnvironmentVariable("XDG_CURRENT_DESKTOP").contains(QLatin1String("gamescope"), Qt::CaseInsensitive)
        || qEnvironmentVariable("XDG_SESSION_DESKTOP").contains(QLatin1String("gamescope"), Qt::CaseInsensitive)
        || !qEnvironmentVariableIsEmpty("GAMESCOPE_WAYLAND_DISPLAY")
        || qEnvironmentVariable("SteamGamepadUI") == QLatin1String("1");
}

double System::trustedInches(const QSize &px, const QSizeF &mm, bool gameMode)
{
    if (px.width() <= 0 || px.height() <= 0 || mm.width() <= 0 || mm.height() <= 0)
        return 0;
    // From its physical size when that can be believed: the same shape as the picture (either way round: a
    // handheld's panel is often mounted turned) and a size a screen can be.
    const double pxDiag = std::hypot(double(px.width()), double(px.height()));
    const double a = double(px.width()) / px.height(), m = mm.width() / mm.height();
    const bool sameShape = std::abs(m / a - 1.0) < 0.15 || std::abs(m * a - 1.0) < 0.15;
    const double d = std::hypot(mm.width(), mm.height()) / 25.4;
    // Some compositors make a size up from 96 dots per inch when the screen doesn't say: no help in Game Mode.
    const bool madeUp = std::abs(pxDiag / d - 96.0) < 1.5;
    return sameShape && d >= 3.0 && d <= 120.0 && !(gameMode && madeUp) ? d : 0;
}

double System::fitFor(const QSize &px, const QSizeF &mm, bool gameMode)
{
    if (px.width() <= 0 || px.height() <= 0)
        return 1.0;
    const double pxDiag = std::hypot(double(px.width()), double(px.height()));
    // The screen's diagonal in inches, when its physical size can be believed.
    double inches = trustedInches(px, mm, gameMode);
    // Game Mode (or Handheld mode) on a screen that doesn't say its size: a handheld's 7 inches.
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
    // Settings › Look › Handheld mode may have changed (it shares Settings' changed signal).
    updateHandheld();
    // The screenshot tour keeps the design size, so its pictures never depend on the machine. Handheld mode
    // guesses 7 inches on a screen that doesn't say its size, as game mode does; Off doesn't.
    const double fit = m_tourDir.isEmpty() && m_screen ? fitFor(m_screen->size(), m_screen->physicalSize(), gameLike()) : 1.0;
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
        const QString loop = QThread::currentThread() != qApp->thread() ? QStringLiteral("threaded") : QStringLiteral("basic");
        const QString line = QStringLiteral("graphics: ") + what + QStringLiteral(" (") + loop
            + QStringLiteral(" render loop, ") + QGuiApplication::platformName() + QStringLiteral(" platform)")
            + (software ? QStringLiteral(": drawn by the processor, slow") : QString());
        QMetaObject::invokeMethod(this, [this, what, software, loop, line] {
            noteGraphics(what, software, loop);
            CrashLog::note(line + QStringLiteral("; handheld mode ") + handheldSummary());
            Platform::startStep(line);
            std::fprintf(stderr, "%s\n", line.toLocal8Bit().constData());
        }, Qt::QueuedConnection);
    }, Qt::DirectConnection);
}

void System::noteGraphics(const QString &what, bool software, const QString &loop)
{
    if (what == m_graphics && software == m_software && loop == m_renderLoop)
        return;
    m_graphics = what;
    m_software = software;
    m_renderLoop = loop;
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
    // Steam's own keyboard types into whatever has focus, in game mode and Handheld mode (when Steam is there to
    // show it). Desktop users have a real keyboard; Windows shows its own touch keyboard for a tapped text field.
    if (gameLike() && steamAround(m_gameMode))
        openSteamUrl(QStringLiteral("steam://open/keyboard"));
}

void System::hideSteamKeyboard()
{
    if (gameLike() && steamAround(m_gameMode))
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
