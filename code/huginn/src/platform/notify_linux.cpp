#include "notify.h"
#include "singleinstance.h"

#include <QCoreApplication>
#include <QDBusConnection>
#include <QDBusMessage>
#include <QDBusPendingCallWatcher>
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
const QString kService = QStringLiteral("org.freedesktop.Notifications");
const QString kPath = QStringLiteral("/org/freedesktop/Notifications");

QDBusMessage message(const QString &method, const QVariantList &args)
{
    QDBusMessage m = QDBusMessage::createMethodCall(kService, kPath, kService, method);
    m.setArguments(args);
    return m;
}

// A desktop that reads markup in the body (and may fetch an <img> from the web) gets names and texts escaped,
// so they always show as plain text. Asked once (a short wait, the first time only).
QString plainBody(const QString &body)
{
    static int markup = -1;
    if (markup < 0) {
        const QDBusMessage r = QDBusConnection::sessionBus().call(message(QStringLiteral("GetCapabilities"), {}), QDBus::Block, 2000);
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
        bus.connect(kService, kPath, kService, QStringLiteral("ActionInvoked"), c, SLOT(invoked(uint,QString)));
        bus.connect(kService, kPath, kService, QStringLiteral("NotificationClosed"), c, SLOT(closed(uint,uint)));
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
    const QStringList actions = onClick ? QStringList{QStringLiteral("default"), QStringLiteral("Open")} : QStringList();
    // Never waits for the desktop: its answer (the notification's id, for a click) comes back later.
    const QDBusPendingCall call = QDBusConnection::sessionBus().asyncCall(
        message(QStringLiteral("Notify"), {QStringLiteral("Huginn"), 0u, QStringLiteral("io.github.ravenor907.Huginn"), title, plainBody(body), actions,
                                           QVariantMap(), timeoutMs}),
        5000);
    if (!c)
        return;
    auto *w = new QDBusPendingCallWatcher(call, c);
    QObject::connect(w, &QDBusPendingCallWatcher::finished, c, [c, onClick = std::move(onClick)](QDBusPendingCallWatcher *w) mutable {
        w->deleteLater();
        const QDBusMessage r = w->reply();
        if (r.type() != QDBusMessage::ReplyMessage || r.arguments().isEmpty())
            return;
        if (c->pending.size() > 50)
            c->pending.clear();
        c->pending.insert(r.arguments().constFirst().toUInt(), std::move(onClick));
    });
}

uint notifyCall(uint replaces, const QString &title, const QString &body, std::function<void(const QString &)> onAction)
{
    NotifyClicks *c = clicks();
    const QVariantMap hints{{QStringLiteral("urgency"), QVariant::fromValue(uchar(2))}, {QStringLiteral("category"), QStringLiteral("call.incoming")},
                            {QStringLiteral("resident"), true}};
    // The caller needs the id now (to replace or close it): a short wait, for a ringing call only.
    const QDBusMessage r = QDBusConnection::sessionBus().call(
        message(QStringLiteral("Notify"), {QStringLiteral("Huginn"), replaces, QStringLiteral("io.github.ravenor907.Huginn"), title, plainBody(body),
                                           QStringList{QStringLiteral("answer"), QStringLiteral("Answer"), QStringLiteral("decline"), QStringLiteral("Decline")},
                                           hints, 45000}),
        QDBus::Block, 2000);
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
    QDBusConnection::sessionBus().asyncCall(message(QStringLiteral("CloseNotification"), {id}));
}
}

#include "notify_linux.moc"
