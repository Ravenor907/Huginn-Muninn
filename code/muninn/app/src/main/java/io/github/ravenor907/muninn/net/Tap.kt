package io.github.ravenor907.muninn.net

import android.nfc.cardemulation.HostApduService
import android.nfc.tech.IsoDep
import android.os.Bundle

/**
 * Tap phones together to add each other (NFC). While the Tap to add screen is open, this phone answers another
 * phone's reader with its contact code (a one-time invite, so whoever reads it is added on both phones), and in turns it
 * reads the other phone's code. Nothing answers when the screen isn't open.
 *
 * The exchange: SELECT Muninn's application id, which answers the code's length (2 bytes); then READ BINARY from
 * an offset (P1 P2), which answers up to [CHUNK] bytes of the code. Every answer ends with 90 00.
 */
object Tap {
    val AID = byteArrayOf(0xF0.toByte(), 0x4D, 0x55, 0x4E, 0x49, 0x4E, 0x4E, 0x01)
    const val CHUNK = 200
    private val OK = byteArrayOf(0x90.toByte(), 0x00)
    private val NO = byteArrayOf(0x6A.toByte(), 0x82.toByte())

    /** The code this phone hands over, until when (ms). Set by the Tap to add screen only. */
    @Volatile private var code: ByteArray? = null
    @Volatile private var until = 0L

    fun offer(c: String?, forMs: Long = 120_000L) { code = c?.toByteArray(); until = System.currentTimeMillis() + forMs }

    private fun selectApdu() = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, AID.size.toByte()) + AID + byteArrayOf(0x00)
    private fun readApdu(at: Int) = byteArrayOf(0x00, 0xB0.toByte(), (at shr 8).toByte(), (at and 0xFF).toByte(), CHUNK.toByte())

    /** The card side: what this phone answers. */
    fun answer(apdu: ByteArray): ByteArray {
        val c = code?.takeIf { System.currentTimeMillis() < until } ?: return NO
        if (apdu.size >= 5 && apdu[1] == 0xA4.toByte()) {
            val aid = apdu.copyOfRange(5, minOf(apdu.size, 5 + (apdu[4].toInt() and 0xFF)))
            return if (aid.contentEquals(AID)) byteArrayOf((c.size shr 8).toByte(), (c.size and 0xFF).toByte()) + OK else NO
        }
        if (apdu.size >= 4 && apdu[1] == 0xB0.toByte()) {
            val at = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)
            if (at >= c.size) return NO
            return c.copyOfRange(at, minOf(c.size, at + CHUNK)) + OK
        }
        return NO
    }

    /** The reader side: the other phone's code, or null. Call off the main thread. */
    fun read(iso: IsoDep): String? = try {
        iso.connect()
        iso.timeout = 2000
        val sel = iso.transceive(selectApdu())
        if (sel.size != 4 || sel[2] != 0x90.toByte()) null else {
            val total = ((sel[0].toInt() and 0xFF) shl 8) or (sel[1].toInt() and 0xFF)
            if (total !in 10..4000) null else {
                val out = java.io.ByteArrayOutputStream()
                while (out.size() < total) {
                    val r = iso.transceive(readApdu(out.size()))
                    if (r.size < 3 || r[r.size - 2] != 0x90.toByte()) break
                    out.write(r, 0, r.size - 2)
                }
                if (out.size() == total) String(out.toByteArray()) else null
            }
        }
    } catch (e: Exception) { null } finally { try { iso.close() } catch (_: Exception) {} }
}

/** Android hands this service the other phone's reader commands while the screen is on and unlocked. */
class TapCard : HostApduService() {
    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray = Tap.answer(apdu)
    override fun onDeactivated(reason: Int) {}
}
