#pragma once

#include <QString>
#include <QtGlobal>

// Keeps the screen and the machine awake while files move. Windows: SetThreadExecutionState.
// Linux keeps its own D-Bus path in System::setKeepAwake (portal Inhibit plus ScreenSaver).
namespace Platform
{
#ifdef Q_OS_WIN
void keepAwake(bool on);
// Running on battery (GetSystemPowerStatus); known is false when Windows can't tell.
bool onBattery(bool *known);
#endif
}
