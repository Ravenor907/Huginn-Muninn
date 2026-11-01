#include "notify.h"
#include "chime.h"
#include "singleinstance.h"

#include <QApplication>
#include <QIcon>
#include <QSystemTrayIcon>

namespace Platform
{
namespace
{
// What a click on the newest balloon does (Windows says only that the last one was clicked).
std::function<void()> s_onClick;
}

void notify(const QString &title, const QString &body, int timeoutMs)
{
    notify(title, body, timeoutMs, nullptr);
}

void notify(const QString &title, const QString &body, int timeoutMs, std::function<void()> onClick)
{
    // One tray icon for the whole run, made the first time something needs to be said.
    static QSystemTrayIcon *tray = nullptr;
    if (!tray) {
        if (!QSystemTrayIcon::isSystemTrayAvailable())
            return;
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
    }
    s_onClick = std::move(onClick);
    tray->showMessage(title, body, tray->icon(), timeoutMs);
}

uint notifyCall(uint, const QString &title, const QString &body, std::function<void(const QString &)>)
{
    notify(title, body, 45000);
    return 0;
}

void closeNotification(uint) {}
}
