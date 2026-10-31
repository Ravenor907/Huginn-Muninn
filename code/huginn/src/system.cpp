#include "system.h"
#include "settings.h"

#include "platform/keepawake.h"

#ifndef Q_OS_WIN
#include <QDBusConnection>
#include <QDBusMessage>
#include <QDBusObjectPath>
#include <QDBusPendingCall>
#include <QDBusPendingCallWatcher>
#endif
#include <QCoreApplication>
#include <QDateTime>
#include <QDesktopServices>
#include <QDir>
#include <QFile>
#include <QSet>
#include <QFileDialog>
#include <QUrl>
#include <QFileInfo>
#include <QGuiApplication>
#include <QImage>
#include <QLocale>
#include <QPair>
#include <QProcess>
#include <QQuickWindow>
#include <QScreen>
#include <QStandardPaths>
#include <QStorageInfo>
#include <QTimer>
#include <QVariantMap>
#include <algorithm>

int System::s_qmlWarnings = 0;

System::System(Settings *settings, const QString &tourDir, bool windowed, QObject *parent)
    : QObject(parent)
    , m_settings(settings)
    , m_tourDir(tourDir)
    , m_windowed(windowed)   // --windowed (and the tour): always a window, whatever Settings › Look says
{
    m_gameMode = qEnvironmentVariable("XDG_CURRENT_DESKTOP").contains(QLatin1String("gamescope"), Qt::CaseInsensitive)
        || !qEnvironmentVariableIsEmpty("GAMESCOPE_WAYLAND_DISPLAY")
        || qEnvironmentVariable("SteamGamepadUI") == QLatin1String("1");
    connect(settings, &Settings::changed, this, &System::layoutChanged);
}

QString System::version() const
{
    return QCoreApplication::applicationVersion();
}

double System::uiScale() const
{
    // Real size at every resolution: Qt and the system already scale for high-DPI screens (a 4K
    // monitor at 150% gets 1.5x without us). The person's Size setting (50% to 200%) goes on top.
    return qBound(50, m_settings->uiSize().toInt(), 200) / 100.0;
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
    // SD cards and USB drives mount under /run/media/<user>/<label>.
    static const QString media = QStringLiteral("/run/media/");
    if (p.startsWith(media)) {
        const QStringList parts = p.mid(media.size()).split(QLatin1Char('/'), Qt::SkipEmptyParts);
        if (parts.size() >= 2) {
            QStringList rest = parts.mid(2);
            const QString label = parts.at(1);
            const bool card = QString::fromUtf8(QStorageInfo(p).device()).contains(QLatin1String("mmcblk"));
            const QString name = card ? QStringLiteral("SD Card") : label;
            rest.prepend(name);
            return rest.join(QStringLiteral(" / "));
        }
    }
    if (p == home)
        return QStringLiteral("Home");
    if (p.startsWith(home + QLatin1Char('/'))) {
        QString rest = p.mid(home.size() + 1);
        if (rest.startsWith(QLatin1String(".local/share/Steam/")))
            rest = QStringLiteral("Steam / ") + rest.mid(19);
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
