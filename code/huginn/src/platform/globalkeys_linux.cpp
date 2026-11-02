// Linux: the desktop's GlobalShortcuts portal (org.freedesktop.portal.GlobalShortcuts), where there is one (see
// globalkeys.h). Huginn asks for three shortcuts the first time it is in a voice room or a call; the desktop owns the
// keys and says when one goes down (Activated) and up (Deactivated). Without the portal nothing here works, and
// Settings says the keys work only while Huginn is in front.
#include "../globalkeys.h"

#include <QDBusConnection>
#include <QDBusConnectionInterface>
#include <QDBusMessage>
#include <QDBusMetaType>
#include <QDBusObjectPath>
#include <QDBusPendingCallWatcher>
#include <QDBusPendingReply>
#include <QDBusVariant>
#include <QKeySequence>
#include <QList>
#include <QPair>
#include <QRandomGenerator>
#include <QVariantMap>

namespace
{
const QString kService = QStringLiteral("org.freedesktop.portal.Desktop");
const QString kPath = QStringLiteral("/org/freedesktop/portal/desktop");
const QString kIface = QStringLiteral("org.freedesktop.portal.GlobalShortcuts");
const QString kRequest = QStringLiteral("org.freedesktop.portal.Request");

using Shortcut = QPair<QString, QVariantMap>;   // (sa{sv})
using Shortcuts = QList<Shortcut>;               // a(sa{sv})

// The talk key as the shortcut spec writes keys ("grave", "F9", "space"); empty to let the desktop choose.
QString triggerName(int k)
{
    if (k == Qt::Key_QuoteLeft)
        return QStringLiteral("grave");
    if (k == Qt::Key_Space)
        return QStringLiteral("space");
    if ((k >= Qt::Key_A && k <= Qt::Key_Z) || (k >= Qt::Key_0 && k <= Qt::Key_9))
        return QString(QChar(char16_t(k))).toLower();
    if (k >= Qt::Key_F1 && k <= Qt::Key_F24)
        return QStringLiteral("F%1").arg(k - Qt::Key_F1 + 1);
    return QString();
}
}

// A QObject of its own for the portal's signals.
class PortalKeys : public QObject, public GlobalKeys::Backend
{
    Q_OBJECT
public:
    explicit PortalKeys(GlobalKeys *owner) : m_owner(owner)
    {
        qDBusRegisterMetaType<Shortcut>();
        qDBusRegisterMetaType<Shortcuts>();
        QDBusConnection bus = QDBusConnection::sessionBus();
        if (!bus.isConnected())
            return;
        // Is the portal there, with GlobalShortcuts? Its version property says (an error when it isn't).
        QDBusMessage m = QDBusMessage::createMethodCall(kService, kPath, QStringLiteral("org.freedesktop.DBus.Properties"), QStringLiteral("Get"));
        m.setArguments({kIface, QStringLiteral("version")});
        auto *w = new QDBusPendingCallWatcher(bus.asyncCall(m, 5000), this);
        connect(w, &QDBusPendingCallWatcher::finished, this, [this](QDBusPendingCallWatcher *done) {
            done->deleteLater();
            QDBusPendingReply<QDBusVariant> r = *done;
            if (r.isError() || r.value().variant().toUInt() < 1)
                return;
            m_available = true;
            QDBusConnection::sessionBus().connect(kService, kPath, kIface, QStringLiteral("Activated"), this,
                                                  SLOT(activated(QDBusObjectPath,QString,qulonglong,QVariantMap)));
            QDBusConnection::sessionBus().connect(kService, kPath, kIface, QStringLiteral("Deactivated"), this,
                                                  SLOT(deactivated(QDBusObjectPath,QString,qulonglong,QVariantMap)));
            m_owner->backendChanged();
            if (m_voice)
                start();
        });
    }

    void want(bool voice, bool talk, int qtKey) override
    {
        Q_UNUSED(talk);   // the owner ignores the talk key while push to talk is off
        m_voice = voice;
        m_key = qtKey;
        if (voice && m_available)
            start();
    }
    bool everywhere() const override { return m_available; }

private Q_SLOTS:
    void activated(const QDBusObjectPath &session, const QString &id, qulonglong, const QVariantMap &)
    {
        if (session.path() == m_session)
            m_owner->key(id == QLatin1String("mute") ? GlobalKeys::Mute : id == QLatin1String("deafen") ? GlobalKeys::Deafen : GlobalKeys::Talk, true);
    }
    void deactivated(const QDBusObjectPath &session, const QString &id, qulonglong, const QVariantMap &)
    {
        if (session.path() == m_session && id == QLatin1String("talk"))
            m_owner->key(GlobalKeys::Talk, false);
    }
    void sessionCreated(uint response, const QVariantMap &results)
    {
        if (response != 0) {
            fail();
            return;
        }
        const QVariant h = results.value(QStringLiteral("session_handle"));
        m_session = h.userType() == qMetaTypeId<QDBusObjectPath>() ? h.value<QDBusObjectPath>().path() : h.toString();
        if (m_session.isEmpty()) {
            fail();
            return;
        }
        auto opts = [](const QString &what, const QString &key) {
            QVariantMap o{{QStringLiteral("description"), what}};
            if (!key.isEmpty())
                o.insert(QStringLiteral("preferred_trigger"), key);
            return o;
        };
        const Shortcuts list{
            {QStringLiteral("talk"), opts(QStringLiteral("Push to talk (hold)"), triggerName(m_key))},
            {QStringLiteral("mute"), opts(QStringLiteral("Mute or unmute"), QStringLiteral("CTRL+SHIFT+M"))},
            {QStringLiteral("deafen"), opts(QStringLiteral("Deafen or undeafen"), QStringLiteral("CTRL+SHIFT+D"))},
        };
        const QString token = newToken();
        listen(token, SLOT(bound(uint,QVariantMap)));
        QDBusMessage m = QDBusMessage::createMethodCall(kService, kPath, kIface, QStringLiteral("BindShortcuts"));
        m.setArguments({QVariant::fromValue(QDBusObjectPath(m_session)), QVariant::fromValue(list), QString(),
                        QVariantMap{{QStringLiteral("handle_token"), token}}});
        ask(m);
    }
    void bound(uint response, const QVariantMap &)
    {
        // 1: the person said no; 2: something else went wrong. Either way the keys work only in front.
        if (response != 0)
            fail();
    }

private:
    void start()
    {
        if (m_started)
            return;
        m_started = true;
        const QString token = newToken();
        listen(token, SLOT(sessionCreated(uint,QVariantMap)));
        QDBusMessage m = QDBusMessage::createMethodCall(kService, kPath, kIface, QStringLiteral("CreateSession"));
        m.setArguments({QVariantMap{{QStringLiteral("handle_token"), token}, {QStringLiteral("session_handle_token"), newToken()}}});
        ask(m);
    }
    // A call the portal turns away at once (no Response follows): the keys work only in front.
    void ask(const QDBusMessage &m)
    {
        auto *w = new QDBusPendingCallWatcher(QDBusConnection::sessionBus().asyncCall(m, 10000), this);
        connect(w, &QDBusPendingCallWatcher::finished, this, [this](QDBusPendingCallWatcher *done) {
            done->deleteLater();
            if (done->isError())
                fail();
        });
    }
    void fail()
    {
        m_available = false;
        m_owner->backendChanged();
    }
    static QString newToken() { return QStringLiteral("huginn%1").arg(QRandomGenerator::global()->generate()); }
    // The portal answers on a Request object named from our bus name and the token: listen before asking.
    void listen(const QString &token, const char *slot)
    {
        QString sender = QDBusConnection::sessionBus().baseService().mid(1);
        sender.replace(QLatin1Char('.'), QLatin1Char('_'));
        const QString path = QStringLiteral("/org/freedesktop/portal/desktop/request/%1/%2").arg(sender, token);
        QDBusConnection::sessionBus().connect(kService, path, kRequest, QStringLiteral("Response"), this, slot);
    }

    GlobalKeys *m_owner;
    QString m_session;
    bool m_available = false, m_started = false, m_voice = false;
    int m_key = 0;
};

GlobalKeys::Backend *makeGlobalKeysBackend(GlobalKeys *owner)
{
    return new PortalKeys(owner);
}

#include "globalkeys_linux.moc"
