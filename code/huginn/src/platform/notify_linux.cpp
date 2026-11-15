#include "notify.h"
#include "singleinstance.h"

#include <QCoreApplication>
#include <QDBusConnection>
#include <QDBusMessage>
#include <QDBusPendingCallWatcher>
#include <QHash>
#include <QList>
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
    // The desktop's activation token for a click (sent just before ActionInvoked): with it, Wayland lets the window
    // come forward instead of only flashing on the taskbar.
    QHash<uint, QByteArray> tokens;
    // Round 17: every notification still on show (clearShown takes them all away on a lock).
    QList<uint> shown;
    // notifyCall answers at once with a handle of its own; the desktop's id for it comes later (never waited for).
    struct CallNote {
        uint real = 0;       // the desktop's id, once it answered
        int asked = 0;       // which Notify the newest is (an older answer that comes late is taken away)
        bool close = false;  // closed before the desktop answered: taken away as soon as it does
    };
    QHash<uint, CallNote> callNotes;
    uint nextHandle = 0x7f000000u;

public Q_SLOTS:
    void token(uint id, const QString &t)
    {
        if (tokens.size() > 50)
            tokens.clear();
        tokens.insert(id, t.toUtf8());
    }
    void invoked(uint id, const QString &action)
    {
        SingleInstance::useActivationToken(tokens.take(id));
        if (calls.contains(id)) {
            // A click on the ringing notification itself only brings Huginn up; its buttons stay live.
            if (action != QLatin1String("answer") && action != QLatin1String("decline")) {
                SingleInstance::bringToFront();
                return;
            }
            const std::function<void(const QString &)> a = calls.take(id);
            for (auto n = callNotes.begin(); n != callNotes.end();)
                n = n->real == id ? callNotes.erase(n) : std::next(n);
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
        shown.removeAll(id);
        pending.remove(id);
        calls.remove(id);
        tokens.remove(id);
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
// so they always show as plain text. Asked once without waiting; until it answers, texts are escaped (the safe way).
QString plainBody(const QString &body)
{
    static int markup = -1;
    static bool asking = false;
    if (markup < 0 && !asking && QCoreApplication::instance()) {
        asking = true;
        auto *w = new QDBusPendingCallWatcher(QDBusConnection::sessionBus().asyncCall(message(QStringLiteral("GetCapabilities"), {}), 2000),
                                              QCoreApplication::instance());
        QObject::connect(w, &QDBusPendingCallWatcher::finished, w, [](QDBusPendingCallWatcher *w) {
            w->deleteLater();
            const QDBusMessage r = w->reply();
            markup = r.type() == QDBusMessage::ReplyMessage && !r.arguments().isEmpty()
                     && r.arguments().constFirst().toStringList().contains(QStringLiteral("body-markup")) ? 1 : 0;
        });
    }
    return markup != 0 ? body.toHtmlEscaped() : body;
}

NotifyClicks *clicks()
{
    static NotifyClicks *c = nullptr;
    if (!c) {
        c = new NotifyClicks;
        c->setParent(QCoreApplication::instance());
        QDBusConnection bus = QDBusConnection::sessionBus();
        bus.connect(kService, kPath, kService, QStringLiteral("ActivationToken"), c, SLOT(token(uint,QString)));
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

void notify(const QString &title, const QString &body, int timeoutMs, std::function<void()> onClick, std::function<void()> onFailed, const QString &)
{
    // No session bus, or nobody on it shows notifications (some full-screen sessions run without a notification
    // service; the Flatpak may talk to org.freedesktop.Notifications): said in the window instead.
    if (!QDBusConnection::sessionBus().isConnected()) {
        if (onFailed)
            onFailed();
        return;
    }
    NotifyClicks *c = clicks();   // round 17: every id is kept, so a lock can take them all away
    const QStringList actions = onClick ? QStringList{QStringLiteral("default"), QStringLiteral("Open")} : QStringList();
    // Huginn plays its own sound as Settings › Notifications › Sounds says (or none): the desktop adds none of its own.
    const QVariantMap hints{{QStringLiteral("suppress-sound"), true}};
    // Never waits for the desktop: its answer (the notification's id, for a click) comes back later.
    const QDBusPendingCall call = QDBusConnection::sessionBus().asyncCall(
        message(QStringLiteral("Notify"), {QStringLiteral("Huginn"), 0u, QStringLiteral("io.github.ravenor907.Huginn"), title, plainBody(body), actions,
                                           hints, timeoutMs}),
        5000);
    auto *w = new QDBusPendingCallWatcher(call, c);
    QObject::connect(w, &QDBusPendingCallWatcher::finished, c, [c, onClick = std::move(onClick), onFailed = std::move(onFailed)](QDBusPendingCallWatcher *w) mutable {
        w->deleteLater();
        const QDBusMessage r = w->reply();
        if (r.type() != QDBusMessage::ReplyMessage || r.arguments().isEmpty()) {
            if (onFailed)
                onFailed();
            return;
        }
        if (c->shown.size() > 100)
            c->shown.removeFirst();
        c->shown.append(r.arguments().constFirst().toUInt());
        if (!onClick)
            return;
        if (c->pending.size() > 50)
            c->pending.clear();
        c->pending.insert(r.arguments().constFirst().toUInt(), std::move(onClick));
    });
}

uint notifyCall(uint replaces, const QString &title, const QString &body, std::function<void(const QString &)> onAction)
{
    NotifyClicks *c = clicks();
    if (!QDBusConnection::sessionBus().isConnected())
        return 0;
    const QVariantMap hints{{QStringLiteral("urgency"), QVariant::fromValue(uchar(2))}, {QStringLiteral("category"), QStringLiteral("call.incoming")},
                            {QStringLiteral("resident"), true}, {QStringLiteral("suppress-sound"), true}};
    // Never waits for the desktop (a slow or missing notification service would freeze a ringing call): the caller
    // gets a handle now, and the desktop's id is kept with it once it answers.
    const uint handle = replaces && c->callNotes.contains(replaces) ? replaces : ++c->nextHandle;
    NotifyClicks::CallNote &note = c->callNotes[handle];
    const int asked = ++note.asked;
    note.close = false;
    const QDBusPendingCall call = QDBusConnection::sessionBus().asyncCall(
        message(QStringLiteral("Notify"), {QStringLiteral("Huginn"), note.real, QStringLiteral("io.github.ravenor907.Huginn"), title, plainBody(body),
                                           QStringList{QStringLiteral("answer"), QStringLiteral("Answer"), QStringLiteral("decline"), QStringLiteral("Decline")},
                                           hints, 45000}),
        5000);
    auto *w = new QDBusPendingCallWatcher(call, c);
    QObject::connect(w, &QDBusPendingCallWatcher::finished, c, [c, handle, asked, onAction = std::move(onAction)](QDBusPendingCallWatcher *w) mutable {
        w->deleteLater();
        const QDBusMessage r = w->reply();
        if (r.type() != QDBusMessage::ReplyMessage || r.arguments().isEmpty())
            return;
        const uint id = r.arguments().constFirst().toUInt();
        auto it = c->callNotes.find(handle);
        // Closed meanwhile, or a newer one asked for since: this one goes.
        if (it == c->callNotes.end() || it->close || it->asked != asked) {
            if (it != c->callNotes.end() && it->close && it->asked == asked)
                c->callNotes.erase(it);
            if (it == c->callNotes.end() || it->real != id)
                QDBusConnection::sessionBus().asyncCall(message(QStringLiteral("CloseNotification"), {id}));
            return;
        }
        if (it->real && it->real != id)
            c->calls.remove(it->real);
        it->real = id;
        c->calls.insert(id, std::move(onAction));
    });
    return handle;
}

// Linux has no tray icon of Huginn's own: the desktop's notifications and the window do the job.
void setUpTray(std::function<bool()>, std::function<bool()>, std::function<void(const QString &)>) {}
void setTrayState(int, bool) {}
// The desktop says which notification was clicked, so there's nothing to guess.
void forgetChat(const QString &) {}

void clearShown()
{
    if (!QDBusConnection::sessionBus().isConnected())
        return;
    NotifyClicks *c = clicks();
    const QList<uint> ids = c->shown;
    c->shown.clear();
    for (const uint id : ids) {
        c->pending.remove(id);
        QDBusConnection::sessionBus().asyncCall(message(QStringLiteral("CloseNotification"), {id}));
    }
}

void closeNotification(uint handle)
{
    if (!handle)
        return;
    NotifyClicks *c = clicks();
    auto it = c->callNotes.find(handle);
    if (it == c->callNotes.end())
        return;
    if (!it->real) {
        it->close = true;   // the desktop hasn't answered yet: taken away when it does
        return;
    }
    const uint id = it->real;
    c->callNotes.erase(it);
    c->calls.remove(id);
    QDBusConnection::sessionBus().asyncCall(message(QStringLiteral("CloseNotification"), {id}));
}
}

#include "notify_linux.moc"
