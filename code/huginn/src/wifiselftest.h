#pragma once

class Chat;
class Chime;
class LocalSend;

// huginn --wifi-selftest (for CI): the whole app runs on a throwaway profile, and a pretend Muninn
// in the same process does what the phone does on the same Wi-Fi: it registers over HTTPS, sends a
// real encrypted message to the chat endpoint, and waits for Huginn's "delivered" receipt. The
// arrival goes through everything a real one does (Chat, the screen, the raven call and the
// notification). It also checks that nothing listens outside the home network (not on 127.0.0.1).
namespace WifiSelfTest
{
// Before anything reads settings or app data: test folders and (Windows) a separate registry key. The
// screenshot tour uses it too.
void isolate();
// Once the window is up. Ends the app with 0 when everything worked, 1 otherwise.
void start(Chat *chat, LocalSend *net, Chime *chime);
}
