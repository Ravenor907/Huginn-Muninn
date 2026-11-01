#include "notify.h"
#include "singleinstance.h"

#include <QCoreApplication>
#include <QDBusConnection>
#include <QDBusInterface>
#include <QDBusMessage>
#include <QHash>
#include <QObject>
#include <QStringList>
#include <QVariantMap>

namespace Platform
{
// Clicked notifications: the desktop says which one with ActionInvoked ("default" is a click on it).
class NotifyClicks : public QObject
{
    Q_OBJECT

public:
    QHash<uint, std::function<void()>> pending;
    QHash<uint, std::function<void(const QString &)>> calls;   // notifyCall's buttons

public Q_SLOTS:
    void invoked(uint id, const QString &action)
    {
        if (calls.contains(id)) {
            // A click on the ringing notification itself only brings Huginn up; its buttons stay live.
            if (action != QLatin1String("answer") && action != QLatin1String("decline")) {
                SingleInstance::bringToFront();
                return;
            }
            const std::function<void(const QString &)> a = calls.take(id);
            if (a)
                a(action);
            return;
        }
        const std::function<void()> f = pending.take(id);
        if (!f || action != QLatin1String("default"))
            return;
        SingleInstance::bringToFront();
        f();
    }
    void closed(uint id, uint reason)
    {
        Q_UNUSED(reason);
        pending.remove(id);
        calls.remove(id);
    }
};

namespace
{
// A desktop that reads markup in the body (and may fetch an <img> from the web) gets names and texts escaped,
// so they always show as plain text.
QString plainBody(const QString &body)
{
    static int markup = -1;
    if (markup < 0) {
        QDBusInterface n(QStringLiteral("org.freedesktop.Notifications"), QStringLiteral("/org/freedesktop/Notifications"),
                         QStringLiteral("org.freedesktop.Notifications"));
        n.setTimeout(2000);
        const QDBusMessage r = n.call(QStringLiteral("GetCapabilities"));
        markup = r.type() == QDBusMessage::ReplyMessage && !r.arguments().isEmpty()
                 && r.arguments().constFirst().toStringList().contains(QStringLiteral("body-markup")) ? 1 : 0;
    }
    return markup ? body.toHtmlEscaped() : body;
}

NotifyClicks *clicks()
{
    static NotifyClicks *c = nullptr;
    if (!c) {
        c = new NotifyClicks;
        c->setParent(QCoreApplication::instance());
        QDBusConnection bus = QDBusConnection::sessionBus();
        const QString service = QStringLiteral("org.freedesktop.Notifications"), path = QStringLiteral("/org/freedesktop/Notifications");
        bus.connect(service, path, service, QStringLiteral("ActionInvoked"), c, SLOT(invoked(uint,QString)));
        bus.connect(service, path, service, QStringLiteral("NotificationClosed"), c, SLOT(closed(uint,uint)));
    }
    return c;
}
}

void notify(const QString &title, const QString &body, int timeoutMs)
{
    notify(title, body, timeoutMs, nullptr);
}

void notify(const QString &title, const QString &body, int timeoutMs, std::function<void()> onClick)
{
    NotifyClicks *c = onClick ? clicks() : nullptr;
    QDBusInterface n(QStringLiteral("org.freedesktop.Notifications"), QStringLiteral("/org/freedesktop/Notifications"),
                     QStringLiteral("org.freedesktop.Notifications"));
    const QStringList actions = onClick ? QStringList{QStringLiteral("default"), QStringLiteral("Open")} : QStringList();
    const QDBusMessage r = n.call(QStringLiteral("Notify"), QStringLiteral("Huginn"), 0u, QStringLiteral("io.github.ravenor907.Huginn"), title, plainBody(body),
                                  actions, QVariantMap(), timeoutMs);
    if (c && r.type() == QDBusMessage::ReplyMessage && !r.arguments().isEmpty()) {
        if (c->pending.size() > 50)
            c->pending.clear();
        c->pending.insert(r.arguments().constFirst().toUInt(), std::move(onClick));
    }
}

uint notifyCall(uint replaces, const QString &title, const QString &body, std::function<void(const QString &)> onAction)
{
    NotifyClicks *c = clicks();
    QDBusInterface n(QStringLiteral("org.freedesktop.Notifications"), QStringLiteral("/org/freedesktop/Notifications"),
                     QStringLiteral("org.freedesktop.Notifications"));
    const QVariantMap hints{{QStringLiteral("urgency"), QVariant::fromValue(uchar(2))}, {QStringLiteral("category"), QStringLiteral("call.incoming")},
                            {QStringLiteral("resident"), true}};
    const QDBusMessage r = n.call(QStringLiteral("Notify"), QStringLiteral("Huginn"), replaces, QStringLiteral("io.github.ravenor907.Huginn"), title, plainBody(body),
                                  QStringList{QStringLiteral("answer"), QStringLiteral("Answer"), QStringLiteral("decline"), QStringLiteral("Decline")},
                                  hints, 45000);
    if (r.type() != QDBusMessage::ReplyMessage || r.arguments().isEmpty())
        return 0;
    const uint id = r.arguments().constFirst().toUInt();
    if (replaces && replaces != id)
        c->calls.remove(replaces);
    c->calls.insert(id, std::move(onAction));
    return id;
}

void closeNotification(uint id)
{
    if (!id)
        return;
    clicks()->calls.remove(id);
    QDBusInterface n(QStringLiteral("org.freedesktop.Notifications"), QStringLiteral("/org/freedesktop/Notifications"),
                     QStringLiteral("org.freedesktop.Notifications"));
    n.call(QStringLiteral("CloseNotification"), id);
}
}

#include "notify_linux.moc"
