#!/usr/bin/env bash
# Runs inside the emulator: install the debug APK, open each tab, screenshot it, and fail if
# Muninn crashed anywhere. Then the same on a tablet-size display for the rail layout.
set -u
PKG=io.github.ravenor907.muninn
mkdir -p screens
adb install -r code/muninn/app/build/outputs/apk/debug/app-debug.apk || { echo "::error title=Muninn install::the debug APK did not install"; exit 1; }
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell pm grant $PKG android.permission.CAMERA 2>/dev/null || true
for p in BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT BLUETOOTH_SCAN NEARBY_WIFI_DEVICES ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do adb shell pm grant $PKG android.permission.$p 2>/dev/null || true; done
adb logcat -c
n=0
crashed() {
  if [ -z "$(adb shell pidof $PKG | tr -d '\r')" ]; then
    msg=$(adb logcat -d | grep -A20 "FATAL EXCEPTION" | head -30 | sed 's/%/%25/g' | sed ':a;N;$!ba;s/\n/%0A/g')
    echo "::error title=Muninn crashed on '$1'::$msg"
    exit 1
  fi
}
# shot NAME ARGS...: open Muninn with those extras and screenshot it.
shot() {
  local name=$1; shift
  n=$((n+1))
  adb shell am force-stop $PKG
  adb shell am start -W -n $PKG/.MainActivity --ez ci true "$@" >/dev/null
  sleep 3
  adb exec-out screencap -p > "screens/$(printf %02d $n)-$name.png"
  crashed "$name"
}
# The three tabs and Settings (the valknut).
for screen in chats files contacts settings; do
  s=$screen; [ "$s" = chats ] && s=chat
  shot "$screen" --es screen "$s"
done
# Settings: the short list's pages (each page opens on its own).
shot settings-notify --es screen settings --es folder notify
shot settings-lock --es screen settings --es folder lock
shot settings-look --es screen settings --es folder look
shot settings-battery --es screen settings --es folder battery
shot settings-devices --es screen settings --es folder devices
shot settings-about --es screen settings --es folder about
# A conversation (top bar, bubbles, composer), Add someone (camera first) and the lock screen.
shot conversation --es chat demo
shot add-someone --es chat add
# Groups: a group conversation (channel chips, sender names) and its settings (members, channels, folders).
shot group --ez group true
shot group-settings --ez group true --ez gsettings true
# The group's one Call button, its popup open (Join voice for the CI group's voice room).
shot group-call --ez group true --ez callpick true
# Voice: the join sheet (mic and sound choice), and a room with a shared screen (made up: no sound, nothing sent).
shot room-join --ez roomjoin true
shot room --ez room true
# Press and hold: the person and chat sheet (here on the group).
shot hold-sheet --ez group true --ez hold true
shot lock --es screen lock
# The Kids' TV voice processing library (echo canceller, RNNoise) loads and runs on Android.
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.MainActivity --ez ci true --ez tvvoice true >/dev/null
sleep 6
tvv=$(adb logcat -d -s Muninn:I | grep "TvVoice self-check" | tail -1)
case "$tvv" in
  *"self-check: ok"*) echo "$tvv" ;;
  *) echo "::error title=Kids' TV voice library::${tvv:-no self-check line in logcat}"; exit 1 ;;
esac
crashed tvvoice
# A phone turned on its side gets the landscape layout (the rail) too.
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 2
shot phone-landscape --es screen chat
adb shell settings put system user_rotation 0
sleep 2
# Tablet size (1280x800 at density 160, so 1280dp wide): the rail, chat list and conversation.
adb shell wm size 1280x800
adb shell wm density 160
sleep 2
shot tablet-chats --es screen chat
shot tablet-conversation --es chat demo
# The rail's group button, the channel column (TEXT, VOICE) and the group in the main column.
shot tablet-group --ez group true
shot tablet-room --ez room true
shot tablet-files --es screen files
shot tablet-settings --es screen settings
adb shell wm size reset
adb shell wm density reset
sleep 2
# A fresh install opens on "Protect Muninn" (a lock is required).
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.MainActivity >/dev/null
sleep 5
adb exec-out screencap -p > "screens/$(printf %02d $((n+1)))-first-lock.png"
crashed first-lock
if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  msg=$(adb logcat -d | grep -A20 "FATAL EXCEPTION" | head -30 | sed 's/%/%25/g' | sed ':a;N;$!ba;s/\n/%0A/g')
  echo "::error title=Crash in logcat::$msg"
  exit 1
fi
echo "All Muninn screens rendered without crashing"
