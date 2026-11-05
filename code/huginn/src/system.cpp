#include "system.h"
#include "settings.h"

#include "platform/crashlog.h"
#include "platform/keepawake.h"
#include "platform/notify.h"
#include "platform/portable.h"
#include "platform/singleinstance.h"
#include "platform/winstart.h"

#ifndef Q_OS_WIN
#include <QDBusConnection>
#include <QDBusMessage>
#include <QDBusObjectPath>
#include <QDBusPendingCallWatcher>
#include <QDBusVariant>
#endif
#include <QCoreApplication>
#include <QDesktopServices>
#include <QDateTime>
#include <QDir>
#include <QFileInfo>
#include <QEvent>
#include <QMouseEvent>
#include <QFile>
#include <QFileDialog>
#include <QGuiApplication>
#include <QScreen>
#include <QSGRendererInterface>
#include <QThread>
#include <QUrl>
#include <QImage>
#include <QLocale>
#include <QProcess>
#include <QQuickWindow>
#include <QStandardPaths>
#include <QTimer>
#include <QVariantMap>

#ifdef Q_OS_WIN
#include <rhi/qrhi.h>
#include <windows.h>
#include <objbase.h>
#include <shobjidl.h>
#include <QColor>
#include <QFont>
#include <QPainter>
#include <string>
#else
#include <QOpenGLContext>
#include <QOpenGLFunctions>
#endif

#include <algorithm>
#include <cmath>
#include <cstdio>

int System::s_qmlWarnings = 0;

namespace
{
// Known handhelds for Settings › Look › Handheld mode (Automatic). Checked in order and the first match wins, so
// the more specific names come first. part: a case-insensitive piece of the product name, product family or board
// name (Linux: /sys/class/dmi/id; Windows: the firmware's SMBIOS table, read only on a 6 to 9 inch screen). maker: a
// piece of the system vendor that must match too (empty: any maker). An entry without a part matches on the maker alone.
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

#ifdef Q_OS_WIN
// String number n (1-based) of the SMBIOS structure at s, whose formatted part is len bytes; empty when absent.
QString smbiosString(const BYTE *s, BYTE len, BYTE n, const BYTE *end)
{
    if (n == 0)
        return {};
    const char *p = reinterpret_cast<const char *>(s + len);
    const char *stop = reinterpret_cast<const char *>(end);
    for (BYTE i = 1; p < stop && *p; ++i) {
        const char *e = p;
        while (e < stop && *e)
            ++e;
        if (i == n)
            return QString::fromLatin1(p, int(qMin<qsizetype>(e - p, 256))).trimmed();
        p = e + 1;
    }
    return {};
}
#endif

// The firmware's product name, family, board name and maker. Linux: read from /sys even in the Flatpak (these four
// are readable by anyone; the serial numbers next to them are not read). Windows: the same four from the SMBIOS
// table Windows documents for this (GetSystemFirmwareTable), nothing else from it. Empty when they can't be read.
QStringList firmwareNames()
{
#ifdef Q_OS_WIN
    QStringList out{QString(), QString(), QString(), QString()};
    const DWORD sig = 'R' << 24 | 'S' << 16 | 'M' << 8 | 'B';
    const UINT size = GetSystemFirmwareTable(sig, 0, nullptr, 0);
    if (size < 8 || size > 1024 * 1024)
        return out;
    QByteArray buf(int(size), Qt::Uninitialized);
    if (GetSystemFirmwareTable(sig, 0, buf.data(), size) != size)
        return out;
    // RawSMBIOSData: 4 bytes of version, a 32-bit length, then the structures.
    const BYTE *raw = reinterpret_cast<const BYTE *>(buf.constData());
    const DWORD length = *reinterpret_cast<const DWORD *>(raw + 4);
    const BYTE *p = raw + 8;
    const BYTE *end = raw + qMin<qsizetype>(qsizetype(size), 8 + qsizetype(length));
    while (p + 4 <= end) {
        const BYTE type = p[0], len = p[1];
        if (len < 4 || p + len > end)
            break;
        if (type == 1 && len >= 8) {   // System: maker, product name, and (SMBIOS 2.4 and newer) family
            out[3] = smbiosString(p, len, p[4], end);
            out[0] = smbiosString(p, len, p[5], end);
            if (len > 0x1A)
                out[1] = smbiosString(p, len, p[0x1A], end);
        } else if (type == 2 && len >= 6 && out[2].isEmpty()) {   // Baseboard: product
            out[2] = smbiosString(p, len, p[5], end);
        } else if (type == 127) {
            break;
        }
        // The strings after the formatted part end with two zero bytes.
        const BYTE *q = p + len;
        while (q + 1 < end && (q[0] || q[1]))
            ++q;
        p = q + 2;
    }
    return out;
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
    checkSteam();   // Steam beside Huginn (Linux Handheld mode): known before the first keyboard is wanted
    m_steamPoll.setInterval(10000);
    connect(&m_steamPoll, &QTimer::timeout, this, &System::checkSteam);
    connect(this, &System::handheldChanged, this, &System::keyboardChanged);
    watchPower();
    // Input: every key, mouse, touch and controller press notes the time; 30 seconds without any is idle.
    m_inputClock.start();
    m_idleCheck.setInterval(5000);
    connect(&m_idleCheck, &QTimer::timeout, this, [this] {
        if (m_inputIdle || m_inputClock.elapsed() - m_lastInput < 30000)
            return;
        m_inputIdle = true;
        m_idleCheck.stop();
        Q_EMIT inputIdleChanged();
    });
    m_idleCheck.start();
    if (QCoreApplication *app = QCoreApplication::instance())
        app->installEventFilter(this);
}

// ------------------------------------------------------------------ battery and input

namespace
{
#ifdef Q_OS_LINUX
// Without UPower (not reachable from here): the kernel's power supplies. A mains supply that is offline, or else a
// system battery that is discharging. known: there was something to go by.
bool batteryFromSys(bool *known)
{
    *known = false;
    const QDir dir(QStringLiteral("/sys/class/power_supply"));
    bool mains = false, mainsOnline = false, discharging = false;
    for (const QString &name : dir.entryList(QDir::Dirs | QDir::NoDotAndDotDot)) {
        auto read = [&dir, &name](const char *field) {
            QFile f(dir.filePath(name + QLatin1Char('/') + QLatin1String(field)));
            return f.open(QIODevice::ReadOnly) ? QString::fromLatin1(f.readAll()).trimmed() : QString();
        };
        const QString type = read("type");
        if (type == QLatin1String("Mains")) {
            mains = true;
            mainsOnline = mainsOnline || read("online") == QLatin1String("1");
        } else if (type == QLatin1String("Battery") && read("scope") != QLatin1String("Device")) {
            *known = true;
            discharging = discharging || read("status") == QLatin1String("Discharging");
        }
    }
    if (mains) {
        *known = true;
        return !mainsOnline;
    }
    return discharging;
}
#endif
}

void System::watchPower()
{
    connect(m_settings, &Settings::changed, this, &System::updateSaving);
    // The screenshot tour draws the same on every machine: never on battery there.
    if (!m_tourDir.isEmpty())
        return;
    m_powerPoll.setInterval(60000);
    connect(&m_powerPoll, &QTimer::timeout, this, &System::askBattery);
#ifdef Q_OS_WIN
    m_powerPoll.start();
#elif defined(Q_OS_LINUX)
    // UPower says when it changes; asked once now (without waiting on it).
    QDBusConnection::systemBus().connect(QStringLiteral("org.freedesktop.UPower"), QStringLiteral("/org/freedesktop/UPower"),
                                         QStringLiteral("org.freedesktop.DBus.Properties"), QStringLiteral("PropertiesChanged"), this,
                                         SLOT(upowerChanged(QString,QVariantMap,QStringList)));
#endif
    askBattery();
    updateSaving();
}

void System::askBattery()
{
#ifdef Q_OS_WIN
    bool known = false;
    const bool on = Platform::onBattery(&known);
    if (known)
        setOnBattery(on);
#elif defined(Q_OS_LINUX)
    QDBusMessage m = QDBusMessage::createMethodCall(QStringLiteral("org.freedesktop.UPower"), QStringLiteral("/org/freedesktop/UPower"),
                                                    QStringLiteral("org.freedesktop.DBus.Properties"), QStringLiteral("Get"));
    m.setArguments({QStringLiteral("org.freedesktop.UPower"), QStringLiteral("OnBattery")});
    auto *watch = new QDBusPendingCallWatcher(QDBusConnection::systemBus().asyncCall(m, 3000), this);
    connect(watch, &QDBusPendingCallWatcher::finished, this, [this](QDBusPendingCallWatcher *w) {
        w->deleteLater();
        const QDBusMessage r = w->reply();
        if (r.type() == QDBusMessage::ReplyMessage && !r.arguments().isEmpty()) {
            m_powerPoll.stop();   // UPower's signal follows it from here
            setOnBattery(qvariant_cast<QDBusVariant>(r.arguments().constFirst()).variant().toBool());
            return;
        }
        // No UPower: the kernel's view, looked at again every minute.
        bool known = false;
        const bool on = batteryFromSys(&known);
        if (known)
            setOnBattery(on);
        if (!m_powerPoll.isActive())
            m_powerPoll.start();
    });
#endif
}

void System::upowerChanged(const QString &iface, const QVariantMap &changed, const QStringList &invalidated)
{
    if (iface != QLatin1String("org.freedesktop.UPower"))
        return;
    if (changed.contains(QStringLiteral("OnBattery")))
        setOnBattery(changed.value(QStringLiteral("OnBattery")).toBool());
    else if (invalidated.contains(QStringLiteral("OnBattery")))
        askBattery();
}

void System::setOnBattery(bool on)
{
    if (on == m_onBattery)
        return;
    m_onBattery = on;
    CrashLog::note(on ? QStringLiteral("power: on battery") : QStringLiteral("power: plugged in"));
    Q_EMIT powerChanged();
    updateSaving();
}

void System::updateSaving()
{
    const QString mode = m_settings->batterySaver();
    const bool on = m_tourDir.isEmpty() && (mode == QLatin1String("on") || (mode == QLatin1String("auto") && m_onBattery));
    if (on == m_saving)
        return;
    m_saving = on;
    Q_EMIT powerChanged();
}

QString System::powerSummary() const
{
    const QString mode = m_settings->batterySaver();
    const QString setting = mode == QLatin1String("on") ? QStringLiteral("On") : mode == QLatin1String("off") ? QStringLiteral("Off") : QStringLiteral("Automatic");
    return (m_onBattery ? QStringLiteral("on battery") : QStringLiteral("plugged in or not known")) + QStringLiteral(", Battery saver ") + setting
        + (m_saving ? QStringLiteral(" (on)") : QStringLiteral(" (off)"));
}

void System::noteInput()
{
    m_lastInput = m_inputClock.elapsed();
    if (!m_inputIdle)
        return;
    m_inputIdle = false;
    m_idleCheck.start();
    Q_EMIT inputIdleChanged();
}

bool System::eventFilter(QObject *watched, QEvent *event)
{
    switch (event->type()) {
    case QEvent::MouseButtonPress:
        // A real mouse (or a touchpad) click: touch is no longer what's in use. A click Qt or the system made from a
        // touch, and the controller's pointer (it keeps padInUse on), don't count.
        if (static_cast<QMouseEvent *>(event)->source() == Qt::MouseEventNotSynthesized && !m_controllerInUse)
            setTouchInUse(false);
        noteInput();
        break;
    case QEvent::TouchBegin:
        setTouchInUse(true);
        noteInput();
        break;
    case QEvent::KeyPress:
    case QEvent::MouseMove:
    case QEvent::Wheel:
    case QEvent::TouchUpdate:
    case QEvent::TabletPress:
        noteInput();
        break;
    default:
        break;
    }
    return QObject::eventFilter(watched, event);
}

void System::detectHandheld()
{
    // The screenshot tour never looks: its pictures stay the same on every machine.
    if (!m_tourDir.isEmpty())
        return;
    // The built-in screen is the one Huginn starts on.
    const QScreen *s = QGuiApplication::primaryScreen();
    const double inches = s ? trustedInches(s->size(), s->physicalSize(), m_gameMode) : 0;
    m_smallScreen = inches >= 6.0 && inches <= 9.0;
#ifdef Q_OS_WIN
    // Windows: the firmware is only asked on a handheld-sized screen (a PC on a monitor never needs the model).
    if (!m_smallScreen) {
        updateHandheld();
        return;
    }
#endif
    const QStringList fw = firmwareNames();
    m_model = handheldModelFor(fw.value(0), fw.value(1), fw.value(2), fw.value(3));
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
    // The tour draws the desktop layout, except at its handheld stops.
    const bool on = m_tourDir.isEmpty() ? (mode == QLatin1String("on") || (mode == QLatin1String("auto") && detected))
                                        : m_tourHandheld;
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

void System::setTourHandheld(bool on)
{
    if (m_tourDir.isEmpty() || on == m_tourHandheld)
        return;
    m_tourHandheld = on;
    updateHandheld();
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
    // Round 15 (R2): the on-screen keyboard outside game mode only for the controller or touch, never a mouse or keys.
    {
        const bool docked = !keyboardRule(true, false, false, false, false, true) && !keyboardRule(true, false, false, false, true, false);
        const bool pad = keyboardRule(true, false, false, true, false, true) && keyboardRule(true, false, false, true, true, false)
            && !keyboardRule(true, false, false, true, false, false);
        const bool game = keyboardRule(true, false, true, false, false, false) && !keyboardRule(false, false, true, true, true, true);
        if (!docked || !pad || !game) {
            std::printf("on-screen keyboard rule wrong (mouse %d, controller %d, game mode %d)\n", docked, pad, game);
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
        const bool smallScreen = inches >= 6.0 && inches <= 9.0;
        const QString model = handheldModelFor(QStringLiteral("82XV"), QStringLiteral("IdeaPad Slim 5 14IRL8"), QStringLiteral("LNVNB161216"), QStringLiteral("LENOVO"));
        if (smallScreen || !model.isEmpty() || handheldRule(!model.isEmpty(), smallScreen, true, false)) {
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

QString System::dataDir() { return Portable::dataDir(); }
QString System::dataFolder() const { return QDir::toNativeSeparators(Portable::dataDir()); }
bool System::portable() const { return Portable::on(); }
bool System::openDataFolder() const { return QDesktopServices::openUrl(QUrl::fromLocalFile(Portable::dataDir())); }
QString System::cacheDir() { return Portable::cacheDir(); }

bool System::startsFullScreen() const
{
    return !m_windowed && (m_settings->fullScreen() || m_gameMode || m_handheld);
}

void System::setUnreadState(QQuickWindow *window, int unread, bool muted)
{
    Platform::setTrayState(unread, muted);
#ifdef Q_OS_WIN
    // The taskbar button's badge: Windows 11 shows no window titles there, so "(3) Huginn" alone isn't seen.
    if (!window)
        return;
    static ITaskbarList3 *bar = nullptr;
    static bool tried = false;
    if (!bar && !tried) {
        tried = true;
        ITaskbarList3 *made = nullptr;
        if (SUCCEEDED(CoCreateInstance(CLSID_TaskbarList, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&made)))) {
            if (SUCCEEDED(made->HrInit()))
                bar = made;
            else
                made->Release();
        }
    }
    if (!bar)
        return;
    const HWND hwnd = reinterpret_cast<HWND>(window->winId());
    if (unread <= 0) {
        bar->SetOverlayIcon(hwnd, nullptr, L"");
        return;
    }
    // A red dot with the count in white (9+ past nine), drawn at 32 px; Windows shrinks it to the corner.
    QImage dot(32, 32, QImage::Format_ARGB32_Premultiplied);
    dot.fill(Qt::transparent);
    {
        QPainter p(&dot);
        p.setRenderHint(QPainter::Antialiasing);
        p.setPen(Qt::NoPen);
        p.setBrush(QColor(0xD8, 0x3A, 0x3A));
        p.drawEllipse(QRectF(1, 1, 30, 30));
        QFont f = p.font();
        f.setPixelSize(unread > 9 ? 15 : 20);
        f.setBold(true);
        p.setFont(f);
        p.setPen(Qt::white);
        p.drawText(QRectF(0, 0, 32, 32), Qt::AlignCenter, unread > 9 ? QStringLiteral("9+") : QString::number(unread));
    }
    const HICON icon = dot.toHICON();
    if (!icon)
        return;
    const std::wstring words = QStringLiteral("%1 unread").arg(unread).toStdWString();
    bar->SetOverlayIcon(hwnd, icon, words.c_str());
    DestroyIcon(icon);
#else
    Q_UNUSED(window)
#endif
}

void System::notifyNow(const QString &title, const QString &body)
{
    Platform::notify(title, body, 8000);
}

void System::minimize(QQuickWindow *window)
{
    if (!window)
        return;
    if (!m_gameMode) {
        window->showMinimized();
        return;
    }
    // Game mode can't minimize: Steam's library comes to the front and Huginn's window stays as it is behind it, so
    // Resume in Steam always shows Huginn again. Hidden only when Steam isn't there to switch to.
    if (steamAround(m_gameMode))
        openSteamUrl(QStringLiteral("steam://open/games"));
    else
        SingleInstance::tuck(window);
}

#ifdef Q_OS_WIN
namespace
{
// Windows' touch keyboard, for a controller with no keyboard attached. Declared here (not from the SDK headers) so
// every compiler sees the same thing: the touch keyboard's toggle and the input pane's place on screen.
const GUID kUiHostNoLaunch = {0x4ce576fa, 0x83dc, 0x4f88, {0x95, 0x1c, 0x9d, 0x07, 0x82, 0xb4, 0xe3, 0x76}};
const GUID kTipInvocationId = {0x37c994e7, 0x432b, 0x4834, {0xa2, 0xf7, 0xdc, 0xe1, 0xf1, 0x3b, 0x83, 0x4b}};
const GUID kFrameworkInputPane = {0xd5120aa3, 0x46ba, 0x44c5, {0x82, 0x2d, 0xca, 0x80, 0x92, 0xc1, 0xfc, 0x72}};
const GUID kFrameworkInputPaneId = {0x5752238b, 0x24f0, 0x495a, {0x82, 0xf1, 0x2f, 0xd5, 0x93, 0x05, 0x67, 0x96}};
struct TipToggle : public IUnknown {
    virtual HRESULT STDMETHODCALLTYPE Toggle(HWND wnd) = 0;
};
struct InputPaneArea : public IUnknown {
    virtual HRESULT STDMETHODCALLTYPE Advise(IUnknown *window, IUnknown *handler, DWORD *cookie) = 0;
    virtual HRESULT STDMETHODCALLTYPE AdviseWithHWND(HWND wnd, IUnknown *handler, DWORD *cookie) = 0;
    virtual HRESULT STDMETHODCALLTYPE Unadvise(DWORD cookie) = 0;
    virtual HRESULT STDMETHODCALLTYPE Location(RECT *rect) = 0;
};

// The touch keyboard is up: the input pane has a place on screen.
bool touchKeyboardUp()
{
    InputPaneArea *pane = nullptr;
    if (FAILED(CoCreateInstance(kFrameworkInputPane, nullptr, CLSCTX_INPROC_SERVER, kFrameworkInputPaneId, reinterpret_cast<void **>(&pane))) || !pane)
        return false;
    RECT r = {0, 0, 0, 0};
    const bool up = SUCCEEDED(pane->Location(&r)) && r.right > r.left && r.bottom > r.top;
    pane->Release();
    return up;
}

// Toggle it; false when its host isn't running yet.
bool toggleTouchKeyboard()
{
    TipToggle *tip = nullptr;
    if (FAILED(CoCreateInstance(kUiHostNoLaunch, nullptr, CLSCTX_INPROC_SERVER | CLSCTX_LOCAL_SERVER, kTipInvocationId, reinterpret_cast<void **>(&tip))) || !tip)
        return false;
    tip->Toggle(GetDesktopWindow());
    tip->Release();
    return true;
}

void showTouchKeyboard(bool show)
{
    if (touchKeyboardUp() == show)
        return;
    if (toggleTouchKeyboard() || !show)
        return;
    // Its host starts with Windows' touch keyboard program; once it's there, the toggle works. TabTip.exe asks for UI
    // access, so only the shell can start it (a plain process start fails with "elevation required"). Qt's
    // QDesktopServices opens it through the shell from Qt's own library, so Huginn.exe starts no program itself.
    QString dir = qEnvironmentVariable("CommonProgramW6432");
    if (dir.isEmpty())
        dir = qEnvironmentVariable("CommonProgramFiles");
    if (dir.isEmpty()) {
        CrashLog::note(QStringLiteral("touch keyboard: no common program folder"));
        return;
    }
    const QString exe = QDir::toNativeSeparators(dir + QStringLiteral("/microsoft shared/ink/TabTip.exe"));
    if (!QFileInfo::exists(exe)) {
        CrashLog::note(QStringLiteral("touch keyboard: TabTip.exe isn't there"));
        return;
    }
    if (!QDesktopServices::openUrl(QUrl::fromLocalFile(exe))) {
        CrashLog::note(QStringLiteral("touch keyboard: the shell didn't start TabTip.exe"));
        return;
    }
    // Started: the toggle shows it once its host answers (asked again a little later on a slow start).
    QTimer::singleShot(600, qApp, [] {
        if (!touchKeyboardUp() && !toggleTouchKeyboard())
            QTimer::singleShot(900, qApp, [] {
                if (!touchKeyboardUp() && !toggleTouchKeyboard())
                    CrashLog::note(QStringLiteral("touch keyboard: started, but its host didn't answer"));
            });
    });
}
}
#endif

bool System::steamKeyboard() const
{
    if (steamAround(m_gameMode))
        return true;
#ifdef Q_OS_LINUX
    return m_steamRunning;
#else
    return false;
#endif
}

void System::setSteamRunning(bool on)
{
    if (on == m_steamRunning)
        return;
    m_steamRunning = on;
    Q_EMIT keyboardChanged();
}

// Steam's client running beside Huginn (Handheld mode on the desktop): its keyboard can come up there too. Looked for
// at start and every 10 seconds while the controller or touch is in use, without waiting: in the Flatpak on the host
// (the sandbox sees only its own processes), elsewhere in /proc. keyboardChanged says when the answer changes.
void System::checkSteam()
{
#ifdef Q_OS_LINUX
    if (!gameLike() || m_steamChecking || !m_tourDir.isEmpty())
        return;
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    if (m_steamCheckedAt != 0 && now - m_steamCheckedAt < 9000)
        return;
    m_steamCheckedAt = now;
    if (!inFlatpak()) {
        bool found = false;
        const QStringList pids = QDir(QStringLiteral("/proc")).entryList(QDir::Dirs | QDir::NoDotAndDotDot);
        for (const QString &pid : pids) {
            if (pid.isEmpty() || !pid.at(0).isDigit())
                continue;
            QFile comm(QStringLiteral("/proc/") + pid + QStringLiteral("/comm"));
            if (comm.open(QIODevice::ReadOnly) && comm.readLine().trimmed() == "steam") {
                found = true;
                break;
            }
        }
        setSteamRunning(found);
        return;
    }
    m_steamChecking = true;
    auto *ps = new QProcess(this);
    connect(ps, &QProcess::finished, this, [this, ps](int code, QProcess::ExitStatus status) {
        m_steamChecking = false;
        setSteamRunning(status == QProcess::NormalExit && code == 0);
        ps->deleteLater();
    });
    connect(ps, &QProcess::errorOccurred, this, [this, ps](QProcess::ProcessError e) {
        if (e != QProcess::FailedToStart)
            return;
        m_steamChecking = false;
        setSteamRunning(false);
        ps->deleteLater();
    });
    ps->start(QStringLiteral("flatpak-spawn"), {QStringLiteral("--host"), QStringLiteral("pgrep"), QStringLiteral("-x"), QStringLiteral("steam")});
#endif
}

void System::setControllerInUse(bool on)
{
    if (on == m_controllerInUse)
        return;
    const bool was = padOrTouch();
    m_controllerInUse = on;
    if (on)
        m_touchInUse = false;   // the controller is the latest; a tap turns touch back on
    if (padOrTouch() == was)
        return;
#ifdef Q_OS_LINUX
    if (padOrTouch()) {
        checkSteam();
        m_steamPoll.start();
    } else {
        m_steamPoll.stop();
    }
#endif
    Q_EMIT keyboardChanged();
}

void System::setTouchInUse(bool on)
{
    if (on == m_touchInUse || !m_tourDir.isEmpty())
        return;
    const bool was = padOrTouch();
    m_touchInUse = on;
    if (padOrTouch() == was)
        return;
#ifdef Q_OS_LINUX
    if (padOrTouch()) {
        checkSteam();
        m_steamPoll.start();
    } else {
        m_steamPoll.stop();
    }
#endif
    Q_EMIT keyboardChanged();
}

void System::noteKeyboardUsed()
{
    setTouchInUse(false);
}

void System::showSteamKeyboard()
{
    // Steam's own keyboard types into whatever has focus, in game mode and Handheld mode (when Steam is there to
    // show it). On a Windows handheld with no Steam, Windows' touch keyboard. A mouse or a real keyboard in use
    // (a docked handheld on the desktop) gets nothing. The screenshot tour opens nothing outside its window.
    if (!keyboardAvailable() || !m_tourDir.isEmpty())
        return;
    if (steamKeyboard()) {
        openSteamUrl(QStringLiteral("steam://open/keyboard"));
        return;
    }
#ifdef Q_OS_WIN
    showTouchKeyboard(true);
#endif
}

bool System::keyboardCovers() const
{
    return m_tourDir.isEmpty() && keyboardAvailable();
}

bool System::keyboardRule(bool gameLike, bool tour, bool gameMode, bool padOrTouch, bool windows, bool steam)
{
    if (!gameLike)
        return false;
    if (tour)
        return true;   // the tour's handheld stops show the legend as on the device
    // Game mode: Steam's keyboard is how anything is typed there (a touch there may come as a mouse click).
    if (gameMode)
        return true;
    // The desktop (a docked handheld too, Steam running or started from it): only for the controller or touch, never
    // over a mouse or a real keyboard (Main.qml's padInUse, and a tap or a click seen in eventFilter).
    if (!padOrTouch)
        return false;
    // Windows: Steam's keyboard when started from Steam, else Windows' touch keyboard. Elsewhere only Steam's.
    return windows || steam;
}

bool System::keyboardAvailable() const
{
#ifdef Q_OS_WIN
    const bool windows = true;
#else
    const bool windows = false;
#endif
    return keyboardRule(gameLike(), !m_tourDir.isEmpty(), m_gameMode, padOrTouch(), windows, steamKeyboard());
}

void System::hideSteamKeyboard()
{
    if (!gameLike() || !m_tourDir.isEmpty())
        return;
    if (steamKeyboard()) {
        openSteamUrl(QStringLiteral("steam://close/keyboard"));
        return;
    }
#ifdef Q_OS_WIN
    showTouchKeyboard(false);
#endif
}

QStringList System::pickFiles() const
{
    return QFileDialog::getOpenFileNames(nullptr, QStringLiteral("Pick files to send"),
                                         QStandardPaths::writableLocation(QStandardPaths::DownloadLocation));
}

// Steam's screenshots (F12, or the Steam button and R1 in game mode): <Steam>/userdata/<account>/760/remote/<game>/screenshots,
// for every account and game, newest first. The thumbnails folder under each is left out. Steam is looked for where it
// installs itself and where its Flatpak keeps it; the ~/.steam/steam link is the first one again, so a file is listed once.
static QFileInfoList steamShotFiles()
{
    const QString home = QDir::homePath();
    const QStringList roots = {home + QStringLiteral("/.local/share/Steam/userdata"), home + QStringLiteral("/.steam/steam/userdata"),
                               home + QStringLiteral("/.var/app/com.valvesoftware.Steam/.local/share/Steam/userdata")};
    QFileInfoList out;
    QStringList seen;
    for (const QString &root : roots) {
        const QDir users(root);
        if (!users.exists())
            continue;
        const QFileInfoList accounts = users.entryInfoList(QDir::Dirs | QDir::NoDotAndDotDot | QDir::Readable);
        for (const QFileInfo &account : accounts) {
            const QDir remote(account.absoluteFilePath() + QStringLiteral("/760/remote"));
            if (!remote.exists())
                continue;
            const QFileInfoList games = remote.entryInfoList(QDir::Dirs | QDir::NoDotAndDotDot | QDir::Readable);
            for (const QFileInfo &game : games) {
                const QDir shots(game.absoluteFilePath() + QStringLiteral("/screenshots"));
                const QFileInfoList files = shots.entryInfoList({QStringLiteral("*.jpg"), QStringLiteral("*.jpeg"), QStringLiteral("*.png")},
                                                                QDir::Files | QDir::Readable);
                for (const QFileInfo &fi : files) {
                    const QString real = fi.canonicalFilePath();
                    if (real.isEmpty() || seen.contains(real))
                        continue;
                    seen << real;
                    out << fi;
                }
            }
        }
    }
    std::sort(out.begin(), out.end(), [](const QFileInfo &a, const QFileInfo &b) { return a.lastModified() > b.lastModified(); });
    return out;
}

QVariantList System::filePlaces() const
{
    QVariantList out;
    QStringList seen;
    const std::pair<QStandardPaths::StandardLocation, const char *> places[] = {
        {QStandardPaths::DownloadLocation, "Downloads"}, {QStandardPaths::MoviesLocation, "Videos"},
        {QStandardPaths::PicturesLocation, "Pictures"}, {QStandardPaths::DocumentsLocation, "Documents"},
        {QStandardPaths::MusicLocation, "Music"}};
    for (const auto &place : places) {
        const QString path = QStandardPaths::writableLocation(place.first);
        if (path.isEmpty() || seen.contains(path) || !QFileInfo(path).isDir())
            continue;
        seen << path;
        out << QVariantMap{{QStringLiteral("name"), QString::fromLatin1(place.second)}, {QStringLiteral("path"), path}};
    }
    // Steam's screenshots, all games in one list (listFolder knows this path).
    if (!steamShotFiles().isEmpty())
        out << QVariantMap{{QStringLiteral("name"), QStringLiteral("Steam screenshots")}, {QStringLiteral("path"), QStringLiteral("steam:screenshots")}};
    return out;
}

QVariantList System::listFolder(const QString &path) const
{
    QVariantList out;
    if (path == QLatin1String("steam:screenshots")) {
        const QFileInfoList shots = steamShotFiles();
        for (int i = 0; i < shots.size() && i < 300; ++i) {
            const QFileInfo &fi = shots.at(i);
            out << QVariantMap{{QStringLiteral("name"), fi.fileName()}, {QStringLiteral("path"), fi.absoluteFilePath()}, {QStringLiteral("dir"), false},
                               {QStringLiteral("size"), double(fi.size())}, {QStringLiteral("time"), double(fi.lastModified().toMSecsSinceEpoch())}};
        }
        return out;
    }
    const QDir dir(path);
    if (path.isEmpty() || !dir.exists())
        return out;
    const QFileInfoList dirs = dir.entryInfoList(QDir::Dirs | QDir::NoDotAndDotDot | QDir::Readable, QDir::Name | QDir::IgnoreCase);
    for (const QFileInfo &fi : dirs)
        out << QVariantMap{{QStringLiteral("name"), fi.fileName()}, {QStringLiteral("path"), fi.absoluteFilePath()}, {QStringLiteral("dir"), true}};
    const QFileInfoList files = dir.entryInfoList(QDir::Files | QDir::Readable, QDir::Time);
    int shown = 0;
    for (const QFileInfo &fi : files) {
        if (++shown > 300)
            break;
        out << QVariantMap{{QStringLiteral("name"), fi.fileName()}, {QStringLiteral("path"), fi.absoluteFilePath()}, {QStringLiteral("dir"), false},
                           {QStringLiteral("size"), double(fi.size())}, {QStringLiteral("time"), double(fi.lastModified().toMSecsSinceEpoch())}};
    }
    return out;
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
    // Never waits for an answer (the window's thread keeps going); an answer for an older change lets go of what it got.
    const int ask = ++m_awakeAsk;
    QDBusConnection bus = QDBusConnection::sessionBus();
    auto send = [bus](const QString &service, const QString &path, const QString &iface, const QString &method, const QVariantList &args) mutable {
        QDBusMessage m = QDBusMessage::createMethodCall(service, path, iface, method);
        m.setArguments(args);
        return bus.asyncCall(m, 2000);
    };
    auto closeRequest = [send](const QString &handle) mutable {
        send(QStringLiteral("org.freedesktop.portal.Desktop"), handle, QStringLiteral("org.freedesktop.portal.Request"), QStringLiteral("Close"), {});
    };
    auto unInhibit = [send](uint cookie) mutable {
        send(QStringLiteral("org.freedesktop.ScreenSaver"), QStringLiteral("/org/freedesktop/ScreenSaver"), QStringLiteral("org.freedesktop.ScreenSaver"),
             QStringLiteral("UnInhibit"), {cookie});
    };
    if (on) {
        // The desktop portal is the sandbox-friendly way; the screensaver interface covers sessions without it.
        auto *portal = new QDBusPendingCallWatcher(send(QStringLiteral("org.freedesktop.portal.Desktop"), QStringLiteral("/org/freedesktop/portal/desktop"),
                                                        QStringLiteral("org.freedesktop.portal.Inhibit"), QStringLiteral("Inhibit"),
                                                        {QString(), uint(4 | 8), QVariantMap{{QStringLiteral("reason"), reason}}}),
                                                   this);
        connect(portal, &QDBusPendingCallWatcher::finished, this, [this, ask, closeRequest](QDBusPendingCallWatcher *w) mutable {
            w->deleteLater();
            const QDBusMessage r = w->reply();
            if (r.type() != QDBusMessage::ReplyMessage || r.arguments().isEmpty())
                return;
            const QString handle = r.arguments().first().value<QDBusObjectPath>().path();
            if (ask == m_awakeAsk && m_awake)
                m_inhibitHandle = handle;
            else if (!handle.isEmpty())
                closeRequest(handle);
        });
        auto *saver = new QDBusPendingCallWatcher(send(QStringLiteral("org.freedesktop.ScreenSaver"), QStringLiteral("/org/freedesktop/ScreenSaver"),
                                                       QStringLiteral("org.freedesktop.ScreenSaver"), QStringLiteral("Inhibit"), {QStringLiteral("Huginn"), reason}),
                                                  this);
        connect(saver, &QDBusPendingCallWatcher::finished, this, [this, ask, unInhibit](QDBusPendingCallWatcher *w) mutable {
            w->deleteLater();
            const QDBusMessage r = w->reply();
            if (r.type() != QDBusMessage::ReplyMessage || r.arguments().isEmpty())
                return;
            const uint cookie = r.arguments().first().toUInt();
            if (ask == m_awakeAsk && m_awake)
                m_screensaverCookie = cookie;
            else if (cookie)
                unInhibit(cookie);
        });
    } else {
        if (!m_inhibitHandle.isEmpty())
            closeRequest(m_inhibitHandle);
        m_inhibitHandle.clear();
        if (m_screensaverCookie)
            unInhibit(m_screensaverCookie);
        m_screensaverCookie = 0;
    }
#endif
}
