#include "notify.h"
#include "chime.h"
#include "singleinstance.h"

#include <QAction>
#include <QApplication>
#include <QDateTime>
#include <QIcon>
#include <QMenu>
#include <QSystemTrayIcon>

#include <vector>

namespace Platform
{
namespace
{
// Every balloon shown since the last click, oldest first, with what a click on it does. Windows says only that
// "a" balloon was clicked, not which, so: a click while the newest is still popped up is the newest; one later (from
// the notification list) is the one shown if they all came from the same chat; otherwise it can't tell, and the
// chat list opens (onAction "chats") rather than the wrong chat.
struct Shown
{
    QString key;   // who it's from, as far as this side can tell (the title, or the title and text)
    std::function<void()> onClick;
};
std::vector<Shown> s_shown;
qint64 s_newestAt = 0;
// How long a balloon stays popped up before it goes into the notification list (Windows' default is about 5 s).
constexpr qint64 kPoppedMs = 5000;
std::function<void(const QString &)> s_onAction;

void clicked()
{
    // Kept after a click: an older one can still be clicked from the notification list.
    const std::vector<Shown> &list = s_shown;
    if (list.empty())
        return;
    const bool popped = QDateTime::currentMSecsSinceEpoch() - s_newestAt < kPoppedMs;
    bool oneChat = true;
    for (const Shown &e : list)
        if (e.key != list.back().key)
            oneChat = false;
    // Without a name ("Huginn" when previews are off) the same words can come from different chats.
    if (list.size() > 1 && list.back().key.startsWith(QLatin1String("Huginn\n")))
        oneChat = false;
    if (popped || oneChat) {
        const std::function<void()> f = list.back().onClick;
        if (f)
            f();
    } else if (s_onAction) {
        s_onAction(QStringLiteral("chats"));
    }
}

// One tray icon for the whole run: made at start by setUpTray, or the first time something needs to be said.
// Null when this desktop has no tray.
QSystemTrayIcon *trayIcon()
{
    static QSystemTrayIcon *tray = nullptr;
    if (tray)
        return tray;
    if (!QSystemTrayIcon::isSystemTrayAvailable())
        return nullptr;
    tray = new QSystemTrayIcon(QIcon(Platform::resource(QStringLiteral("raven-gold.png"))), qApp);
    tray->setToolTip(QStringLiteral("Huginn"));
    QObject::connect(tray, &QSystemTrayIcon::messageClicked, qApp, [] {
        SingleInstance::bringToFront();
        clicked();
    });
    QObject::connect(tray, &QSystemTrayIcon::activated, qApp, [](QSystemTrayIcon::ActivationReason r) {
        if (r == QSystemTrayIcon::Trigger || r == QSystemTrayIcon::DoubleClick)
            SingleInstance::bringToFront();
    });
    tray->show();
    return tray;
}
}

void notify(const QString &title, const QString &body, int timeoutMs)
{
    notify(title, body, timeoutMs, nullptr);
}

void notify(const QString &title, const QString &body, int timeoutMs, std::function<void()> onClick, std::function<void()> onFailed)
{
    QSystemTrayIcon *tray = trayIcon();
    if (!tray || !QSystemTrayIcon::supportsMessages()) {
        if (onFailed)
            onFailed();
        return;
    }
    // A message's title is who it's from; the others ("Missed call", "File waiting") name them in the text.
    const bool named = title != QLatin1String("Huginn") && title != QLatin1String("Missed call") && title != QLatin1String("File waiting");
    s_shown.push_back({named ? title : title + QStringLiteral("\n") + body, std::move(onClick)});
    if (s_shown.size() > 30)
        s_shown.erase(s_shown.begin());
    s_newestAt = QDateTime::currentMSecsSinceEpoch();
    tray->showMessage(title, body, tray->icon(), timeoutMs);
}

void setUpTray(std::function<bool()> muted, std::function<bool()> canLock, std::function<void(const QString &)> onAction)
{
    QSystemTrayIcon *tray = trayIcon();
    if (!tray || tray->contextMenu())
        return;
    s_onAction = onAction;
    // Lives as long as Huginn runs (the tray icon doesn't own it).
    auto *menu = new QMenu;
    QAction *open = menu->addAction(QStringLiteral("Open Huginn"));
    QObject::connect(open, &QAction::triggered, qApp, [] { SingleInstance::bringToFront(); });
    QAction *mute = menu->addAction(QStringLiteral("Mute notifications"));
    mute->setCheckable(true);
    QObject::connect(mute, &QAction::triggered, qApp, [onAction] { if (onAction) onAction(QStringLiteral("mute")); });
    menu->addSeparator();
    QAction *lock = menu->addAction(QStringLiteral("Lock and close"));
    QObject::connect(lock, &QAction::triggered, qApp, [onAction] { if (onAction) onAction(QStringLiteral("lock")); });
    QAction *quit = menu->addAction(QStringLiteral("Quit"));
    QObject::connect(quit, &QAction::triggered, qApp, [onAction] { if (onAction) onAction(QStringLiteral("quit")); });
    // What the menu says is read each time it opens.
    QObject::connect(menu, &QMenu::aboutToShow, qApp, [mute, lock, muted, canLock] {
        mute->setChecked(muted && muted());
        lock->setVisible(canLock && canLock());
    });
    tray->setContextMenu(menu);
}

uint notifyCall(uint, const QString &title, const QString &body, std::function<void(const QString &)>)
{
    notify(title, body, 45000);
    return 0;
}

void closeNotification(uint) {}
}
