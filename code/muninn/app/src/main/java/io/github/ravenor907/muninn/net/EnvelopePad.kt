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
