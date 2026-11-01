#pragma once

#include <QString>

class QQuickWindow;

// Starting up on Windows. Everything here does nothing on Linux builds.
namespace Platform
{
// First thing in main(), before QApplication: starts the start log (its lines wait in memory until
// startClaimed), logs every Qt warning and a crash into it. Nothing to undo.
void beginStart();
// Once this copy holds the single instance (or runs on its own, like the screenshot tour): writes
// Huginn-start.log next to Huginn.exe (the temp folder if that can't be written), fresh, and with
// tidy, cleans up after an update.
void startClaimed(bool tidy);
// Right after QApplication: picks a Qt Quick graphics backend that actually starts here (Direct3D 11,
// then OpenGL, the software renderer if neither does), unless one was set already.
void chooseGraphics();
// A line in the start log ("engine loaded", "window shown"...).
void startStep(const QString &what);
// The main window: once it has drawn, the start went well and the log is deleted a few seconds later
// (kept when HUGINN_KEEP_START_LOG is set). If its graphics fail, Huginn restarts once with the
// software renderer instead of stopping with an error.
void watchMainWindow(QQuickWindow *window);
// Something stopped the start (the QML didn't load): keep the log and say where it is.
void startFailed(const QString &why);
// A command-line run (self-tests): no start log is left behind.
void noStartLog();
}
