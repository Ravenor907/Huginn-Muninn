package io.github.ravenor907.muninn.net.call

/**
 * How calls talk to Chat. Chat implements [send]; when an authentic envelope in room "call" opens, Chat
 * calls [onSignal] with the sender's chat key and the plaintext, which hands it to [CallManager].
 */
interface CallSignal {
    /**
     * Seal [json] for [contactKey] in room "call" exactly like a private message (the ratchet when the
     * contact has it, else v1) and send it on every route at once right now. Never put it in the day-long
     * Outbox: calls resend on their own schedule (shared/docs/protocol.md 5.2).
     */
    fun send(contactKey: String, json: String)

    /** Chat calls this for every opened "call" plaintext from a contact or group peer. */
    fun onSignal(contactKey: String, json: String) {
        CallManager.onSignal(contactKey, json)
        RoomManager.onSignal(contactKey, json)   // voice rooms and group calls (section 6)
    }
}

/**
 * An optional Bluetooth LE (or Nearby Connections) media link to a peer close by (5.6, characteristic
 * MEDIA 7b1e0005-...), set as [CallManager.bluetooth]. Without one, calls don't use Bluetooth as a path.
 */
interface CallBluetooth {
    /** True if one media packet (up to about 200 bytes) can go to [contactKey] over Bluetooth right now. */
    fun canCarry(contactKey: String): Boolean
    /** Writes one packet without waiting; false if it couldn't go within 40 ms. */
    fun send(contactKey: String, packet: ByteArray): Boolean
    /** Where packets that arrive on the link (first byte 0x81) go; null to stop. */
    fun onMedia(handler: ((contactKey: String, packet: ByteArray) -> Unit)?)
}
