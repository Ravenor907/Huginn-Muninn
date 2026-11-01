#pragma once

#include <QString>
#include <functional>

// A desktop notification ("New message"). Linux: org.freedesktop.Notifications over D-Bus.
// Windows: a tray icon balloon, which Windows 10 and 11 show as a normal toast.
namespace Platform
{
void notify(const QString &title, const QString &body, int timeoutMs = 6000);
// The same; a click on it brings Huginn to the front and runs onClick (a missed call opens its chat).
void notify(const QString &title, const QString &body, int timeoutMs, std::function<void()> onClick);
// A ringing call: urgent and resident, with Answer and Decline buttons ("answer" or "decline" goes to onAction).
// replaces: the id of the one it updates (0 for a new one). Returns its id; 0 where buttons aren't offered
// (Windows shows a plain toast, and answering happens in the app).
uint notifyCall(uint replaces, const QString &title, const QString &body, std::function<void(const QString &)> onAction);
// Takes a notification made by notifyCall away (does nothing for 0).
void closeNotification(uint id);
}
