#pragma once

#include <QString>
#include <functional>

// A desktop notification ("New message"). Linux: org.freedesktop.Notifications over D-Bus.
// Windows: a tray icon balloon, which Windows 10 and 11 show as a normal toast.
namespace Platform
{
void notify(const QString &title, const QString &body, int timeoutMs = 6000);
// The same; a click on it brings Huginn to the front and runs onClick (a message or a missed call opens its chat).
// onFailed runs when the system can't show it (no notification service or tray), so Huginn can say it in the window.
// chat: the chat it's about ("" for none), so Windows can tell which one a later click from the notification list means.
void notify(const QString &title, const QString &body, int timeoutMs, std::function<void()> onClick, std::function<void()> onFailed = nullptr,
            const QString &chat = QString());
// A chat was opened (or read) with Huginn in front: Windows forgets its notifications, so a click from the
// notification list opens the one chat still unread. Elsewhere it does nothing.
void forgetChat(const QString &chat);
// Windows: the tray icon's tooltip says how many are unread and whether notifications are muted, and a muted tray icon
// is dimmed. Elsewhere it does nothing.
void setTrayState(int unread, bool muted);
// Windows: the tray icon from the start, with its menu: Open Huginn, Mute notifications (muted says whether it's on),
// Lock and close (only while canLock) and Quit. onAction gets "mute", "lock" or "quit"; Open is done here.
// Elsewhere it does nothing.
void setUpTray(std::function<bool()> muted, std::function<bool()> canLock, std::function<void(const QString &)> onAction);
// A ringing call: urgent and resident, with Answer and Decline buttons ("answer" or "decline" goes to onAction).
// replaces: the id of the one it updates (0 for a new one). Returns its id; 0 where buttons aren't offered
// (Windows shows a plain toast, and answering happens in the app).
uint notifyCall(uint replaces, const QString &title, const QString &body, std::function<void(const QString &)> onAction);
// Takes a notification made by notifyCall away (does nothing for 0).
void closeNotification(uint id);
}
