package io.github.ravenor907.muninn.net.call

/**
 * The relay Worker (shared/relay-worker/worker.js, shared/docs/relay.md): it hands out short-lived Cloudflare TURN logins.
 * These two values live here and nowhere else.
 * APP_SECRET is not a real secret (anyone can pull it out of the APK); the Worker's daily caps are the protection.
 */
object RelayConfig {
    const val WORKER_URL = "https://huginn-relay.ravenor907.workers.dev"
    const val APP_SECRET = "32c1837380d34ce91c2b39f3011951b4bc4c3ca622035f0b46a5cf49dc5d9158"

    /** False while the placeholders above are still in: internet calls then say the relay isn't set up. */
    val configured: Boolean get() = WORKER_URL.startsWith("https://") && APP_SECRET.any { it != '0' }
}
