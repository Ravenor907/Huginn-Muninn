#pragma once

// The relay Worker that hands out short-lived Cloudflare TURN logins (shared/docs/relay.md). The only place
// these two values live in Huginn. APP_SECRET must be the same 64 hex characters as the Worker's
// secret; it is not a real secret (anyone can read it out of the app), the Worker's daily caps are
// the protection. While it is the placeholder, Huginn doesn't ask the Worker: internet calls then
// fail with "Can't connect" and only Wi-Fi and Bluetooth calls work.
namespace RelayConfig
{
constexpr const char kWorkerUrl[] = "https://huginn-relay.ravenor907.workers.dev";
constexpr const char kAppSecret[] = "32c1837380d34ce91c2b39f3011951b4bc4c3ca622035f0b46a5cf49dc5d9158";

inline bool configured()
{
    // 64 hex characters, as shared/docs/relay.md says.
    int n = 0;
    for (const char *p = kAppSecret; *p; ++p, ++n) {
        const char c = *p;
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')))
            return false;
    }
    return n == 64;
}
}
