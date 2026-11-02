#include "notify.h"
#include "chime.h"
#include "singleinstance.h"

#include <QAction>
#include <QApplication>
#include <QIcon>
#include <QMenu>
#include <QSystemTrayIcon>

namespace Platform
{
namespace
{
// What a click on the newest balloon does (Windows says only that the last one was clicked).
std::function<void()> s_onClick;

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
        const std::function<void()> f = std::move(s_onClick);
        s_onClick = nullptr;
        if (f)
            f();
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
    s_onClick = std::move(onClick);
    tray->showMessage(title, body, tray->icon(), timeoutMs);
}

void setUpTray(std::function<bool()> muted, std::function<bool()> canLock, std::function<void(const QString &)> onAction)
{
    QSystemTrayIcon *tray = trayIcon();
    if (!tray || tray->contextMenu())
        return;
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
