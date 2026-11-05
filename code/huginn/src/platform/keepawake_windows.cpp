#include "keepawake.h"

#include <windows.h>

namespace Platform
{
void keepAwake(bool on)
{
    // Called from the GUI thread, which lives as long as Huginn does, so the request holds until cleared.
    SetThreadExecutionState(on ? (ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED) : ES_CONTINUOUS);
}

bool onBattery(bool *known)
{
    SYSTEM_POWER_STATUS st;
    *known = GetSystemPowerStatus(&st) && st.ACLineStatus != 255;
    return *known && st.ACLineStatus == 0;
}
}
