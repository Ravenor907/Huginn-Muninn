package io.github.ravenor907.muninn.net

/**
 * Relay envelopes are padded to a few fixed sizes (shared/docs/protocol.md section 20), so a relay can't tell a
 * short "ok" from a long message, or a reaction from a photo piece, by its size. The padding is a field
 * "z" of zeros in the outer wrapping, outside the seal; apps that don't pad ignore fields they don't know.
 */
object EnvelopePad {
    /** 1 KB, 4 KB, 16 KB, 32 KB, then 16 KB steps. Relays commonly refuse events over 64 KB, so photo pieces (about 42 KB) stay at 48 KB. */
    fun bucket(n: Int): Int {
        for (b in intArrayOf(1024, 4096, 16384, 32768)) if (n <= b) return b
        return (n + 16383) / 16384 * 16384
    }

    /**
     * 1.54: the biggest bucket that stays under the 64 KiB many relays allow for a whole event (the 65536 one, with the
     * event around it, is about 66 KB and gets refused).
     */
    const val RELAY_MAX = 49152

    /**
     * What a message's own text, link preview and the like may add up to (UTF-8 bytes) so that, sealed (the ratchet,
     * base64) and wrapped for the relays (base64 again, with the clear fields), it still pads to [RELAY_MAX] at most.
     */
    const val PAYLOAD_MAX = 18_000

    /** [content] (the relay event's content, already padded) is small enough for relays that cap events at 64 KiB. */
    fun fits(content: String) = content.length <= RELAY_MAX

    /** Base64 length of [n] bytes. */
    private fun b64(n: Int) = (n + 2) / 3 * 4

    /**
     * [json] is the wrapping as written ({"e":..,"n":..,"d":..}). Returns it with ,"z":"000..." added so that its
     * base64 is exactly a bucket's length.
     */
    fun pad(json: String): String {
        val body = json.trimEnd()
        require(body.endsWith("}"))
        val base = body.toByteArray().size + 7   // ,"z":"" adds 7 characters
        val target = bucket(b64(base)) / 4 * 3
        return body.dropLast(1) + ",\"z\":\"" + "0".repeat(target - base) + "\"}"
    }
}
