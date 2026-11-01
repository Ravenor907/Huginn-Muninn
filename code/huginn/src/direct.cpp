#include "direct.h"

#include <QDBusArgument>
#include <QDBusConnection>
#include <QDBusMessage>
#include <QDBusMetaType>
#include <QDBusObjectPath>
#include <QDBusPendingCallWatcher>
#include <QDBusPendingReply>
#include <QDBusReply>
#include <QRandomGenerator>

namespace
{
const QString kNm = QStringLiteral("org.freedesktop.NetworkManager");
const QString kNmPath = QStringLiteral("/org/freedesktop/NetworkManager");
const QString kProfileName = QStringLiteral("Huginn Direct connect");
using Settings = QMap<QString, QVariantMap>;

QString randomText(const char *alphabet, int n)
{
    const QByteArray a(alphabet);
    QString out;
    for (int i = 0; i < n; ++i)
        out += QLatin1Char(a.at(QRandomGenerator::system()->bounded(a.size())));
    return out;
}
}
Q_DECLARE_METATYPE(Settings)

Direct::Direct(QObject *parent)
    : QObject(parent)
{
    qDBusRegisterMetaType<Settings>();
}

Direct::~Direct()
{
    // Don't leave the handheld stuck on its own hotspot after Huginn closes.
    stop();
}

QString Direct::qr() const
{
    auto esc = [](QString s) {
        QString out;
        for (const QChar c : s) {
            if (c == QLatin1Char('\\') || c == QLatin1Char(';') || c == QLatin1Char(',') || c == QLatin1Char(':') || c == QLatin1Char('"'))
                out += QLatin1Char('\\');
            out += c;
        }
        return out;
    };
    return QStringLiteral("WIFI:T:WPA;S:%1;P:%2;;").arg(esc(m_ssid), esc(m_password));
}

QString Direct::wifiDevice(QString *error) const
{
    QDBusMessage m = QDBusMessage::createMethodCall(kNm, kNmPath, kNm, QStringLiteral("GetDevices"));
    const QDBusReply<QList<QDBusObjectPath>> devices = QDBusConnection::systemBus().call(m, QDBus::Block, 5000);
    if (!devices.isValid()) {
        *error = QStringLiteral("Couldn't reach the network service: %1").arg(devices.error().message());
        return {};
    }
    for (const QDBusObjectPath &d : devices.value()) {
        QDBusMessage g = QDBusMessage::createMethodCall(kNm, d.path(), QStringLiteral("org.freedesktop.DBus.Properties"), QStringLiteral("Get"));
        g.setArguments({kNm + QStringLiteral(".Device"), QStringLiteral("DeviceType")});
        const QDBusReply<QVariant> t = QDBusConnection::systemBus().call(g, QDBus::Block, 3000);
        if (t.isValid() && t.value().toUInt() == 2)   // NM_DEVICE_TYPE_WIFI
            return d.path();
    }
    *error = QStringLiteral("This device has no Wi-Fi adapter NetworkManager can use.");
    return {};
}

void Direct::start()
{
    if (on() || m_busy)
        return;
    m_error.clear();
    const QString dev = wifiDevice(&m_error);
    if (dev.isEmpty()) {
        Q_EMIT changed();
        return;
    }
    m_ssid = QStringLiteral("Huginn-") + randomText("ABCDEFGHJKLMNPQRSTUVWXYZ23456789", 4);
    m_password = randomText("abcdefghjkmnpqrstuvwxyz23456789", 10);
    const QString user = qEnvironmentVariable("USER");
    Settings s;
    s[QStringLiteral("connection")] = {{QStringLiteral("id"), kProfileName}, {QStringLiteral("type"), QStringLiteral("802-11-wireless")},
                                       {QStringLiteral("autoconnect"), false}};
    // Owned by this user only, which NetworkManager allows without a password.
    if (!user.isEmpty())
        s[QStringLiteral("connection")].insert(QStringLiteral("permissions"), QStringList{QStringLiteral("user:") + user});
    s[QStringLiteral("802-11-wireless")] = {{QStringLiteral("ssid"), m_ssid.toUtf8()}, {QStringLiteral("mode"), QStringLiteral("ap")},
                                            {QStringLiteral("band"), QStringLiteral("bg")}};
    s[QStringLiteral("802-11-wireless-security")] = {{QStringLiteral("key-mgmt"), QStringLiteral("wpa-psk")}, {QStringLiteral("psk"), m_password}};
    s[QStringLiteral("ipv4")] = {{QStringLiteral("method"), QStringLiteral("shared")}};
    s[QStringLiteral("ipv6")] = {{QStringLiteral("method"), QStringLiteral("ignore")}};

    QDBusMessage m = QDBusMessage::createMethodCall(kNm, kNmPath, kNm, QStringLiteral("AddAndActivateConnection"));
    m.setArguments({QVariant::fromValue(s), QVariant::fromValue(QDBusObjectPath(dev)), QVariant::fromValue(QDBusObjectPath(QStringLiteral("/")))});
    m.setInteractiveAuthorizationAllowed(true);
    m_busy = true;
    Q_EMIT changed();
    auto *w = new QDBusPendingCallWatcher(QDBusConnection::systemBus().asyncCall(m, 60000), this);
    connect(w, &QDBusPendingCallWatcher::finished, this, [this, w] {
        w->deleteLater();
        m_busy = false;
        const QDBusMessage r = w->reply();
        if (r.type() == QDBusMessage::ErrorMessage || r.arguments().size() < 2) {
            m_error = r.errorName().contains(QLatin1String("PermissionDenied")) || r.errorName().contains(QLatin1String("NotAuthorized"))
                ? QStringLiteral("This computer didn't allow Huginn to make its own Wi-Fi. Try once from the desktop, not Game Mode.")
                : QStringLiteral("This computer couldn't start Direct connect: %1").arg(r.errorMessage());
            m_ssid.clear();
            m_password.clear();
        } else {
            m_connection = qvariant_cast<QDBusObjectPath>(r.arguments().at(0)).path();
            m_active = qvariant_cast<QDBusObjectPath>(r.arguments().at(1)).path();
        }
        Q_EMIT changed();
    });
}

void Direct::stop()
{
    if (!m_active.isEmpty()) {
        QDBusMessage m = QDBusMessage::createMethodCall(kNm, kNmPath, kNm, QStringLiteral("DeactivateConnection"));
        m.setArguments({QVariant::fromValue(QDBusObjectPath(m_active))});
        QDBusConnection::systemBus().call(m, QDBus::Block, 5000);
    }
    if (!m_connection.isEmpty()) {
        QDBusMessage d = QDBusMessage::createMethodCall(kNm, m_connection, kNm + QStringLiteral(".Settings.Connection"), QStringLiteral("Delete"));
        QDBusConnection::systemBus().call(d, QDBus::Block, 5000);
    }
    const bool was = on();
    m_active.clear();
    m_connection.clear();
    m_ssid.clear();
    m_password.clear();
    if (was)
        Q_EMIT changed();
}

void Direct::clearLeftovers()
{
    const QString settingsPath = kNmPath + QStringLiteral("/Settings");
    QDBusMessage m = QDBusMessage::createMethodCall(kNm, settingsPath, kNm + QStringLiteral(".Settings"), QStringLiteral("ListConnections"));
    auto *w = new QDBusPendingCallWatcher(QDBusConnection::systemBus().asyncCall(m, 5000), this);
    connect(w, &QDBusPendingCallWatcher::finished, this, [this, w] {
        w->deleteLater();
        const QDBusPendingReply<QList<QDBusObjectPath>> list = *w;
        if (list.isError())
            return;
        for (const QDBusObjectPath &c : list.value()) {
            const QString path = c.path();
            QDBusMessage g = QDBusMessage::createMethodCall(kNm, path, kNm + QStringLiteral(".Settings.Connection"), QStringLiteral("GetSettings"));
            auto *each = new QDBusPendingCallWatcher(QDBusConnection::systemBus().asyncCall(g, 5000), this);
            connect(each, &QDBusPendingCallWatcher::finished, this, [this, each, path] {
                each->deleteLater();
                const QDBusPendingReply<Settings> s = *each;
                // Only Huginn's own profile, and never the one this run has just made.
                if (s.isError() || path == m_connection || s.value().value(QStringLiteral("connection")).value(QStringLiteral("id")).toString() != kProfileName)
                    return;
                QDBusMessage d = QDBusMessage::createMethodCall(kNm, path, kNm + QStringLiteral(".Settings.Connection"), QStringLiteral("Delete"));
                QDBusConnection::systemBus().asyncCall(d, 5000);
            });
        }
    });
}
