package io.github.ravenor907.muninn.net

import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM (12-byte nonce, 16-byte tag) that streams both ways, byte for byte the same as a one-shot GCM cipher. On
 * Android the system's GCM holds the whole message in memory until the end, which a backup with files and pictures can't
 * afford; this is AES-CTR for the bytes and GHASH for the tag. Plain Kotlin, so the tests check it against the JDK's GCM.
 */
object GcmStream {
    /** The tag at the end didn't match: a wrong key (password), or a changed file. */
    class BadTag : IOException("The data didn't check out")

    private fun aes(key: ByteArray, block: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")); doFinal(block) }

    /** J0 = nonce || 00000001; the data starts at J0 + 1. */
    private fun j0(nonce: ByteArray): ByteArray {
        require(nonce.size == 12) { "GCM nonce must be 12 bytes" }
        return nonce + byteArrayOf(0, 0, 0, 1)
    }

    private fun ctr(key: ByteArray, nonce: ByteArray, mode: Int): Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(nonce + byteArrayOf(0, 0, 0, 2)))
    }

    /** GHASH (NIST SP 800-38D), with one 256-entry table per byte position. */
    internal class Ghash(h: ByteArray) {
        private val th = Array(16) { LongArray(256) }
        private val tl = Array(16) { LongArray(256) }
        private var yh = 0L
        private var yl = 0L
        private val part = ByteArray(16)
        private var partLen = 0

        init {
            // H times x^k for each bit k (bit 0 is the top bit of the first byte).
            val bh = LongArray(128); val bl = LongArray(128)
            var vh = be64(h, 0); var vl = be64(h, 8)
            for (k in 0 until 128) {
                bh[k] = vh; bl[k] = vl
                val lsb = vl and 1L
                vl = (vl ushr 1) or (vh shl 63); vh = vh ushr 1
                if (lsb != 0L) vh = vh xor (0xE1L shl 56)
            }
            for (i in 0 until 16) for (b in 1 until 256) {
                val low = Integer.numberOfTrailingZeros(b)
                val k = i * 8 + (7 - low)
                val rest = b and (b - 1)
                th[i][b] = th[i][rest] xor bh[k]
                tl[i][b] = tl[i][rest] xor bl[k]
            }
        }

        private fun block(x: ByteArray, off: Int) {
            val xh = yh xor be64(x, off); val xl = yl xor be64(x, off + 8)
            var zh = 0L; var zl = 0L
            for (i in 0 until 8) {
                val b = ((xh ushr (56 - 8 * i)) and 0xFF).toInt()
                zh = zh xor th[i][b]; zl = zl xor tl[i][b]
            }
            for (i in 0 until 8) {
                val b = ((xl ushr (56 - 8 * i)) and 0xFF).toInt()
                zh = zh xor th[8 + i][b]; zl = zl xor tl[8 + i][b]
            }
            yh = zh; yl = zl
        }

        fun update(b: ByteArray, off: Int, len: Int) {
            var o = off; var n = len
            if (partLen > 0) {
                val t = minOf(16 - partLen, n)
                System.arraycopy(b, o, part, partLen, t); partLen += t; o += t; n -= t
                if (partLen == 16) { block(part, 0); partLen = 0 }
            }
            while (n >= 16) { block(b, o); o += 16; n -= 16 }
            if (n > 0) { System.arraycopy(b, o, part, 0, n); partLen = n }
        }

        /** Ends a section (the additional data, or the ciphertext): a part block is padded with zeros. */
        fun pad() {
            if (partLen == 0) return
            part.fill(0, partLen, 16); block(part, 0); partLen = 0
        }

        fun finish(aadBytes: Long, dataBytes: Long): ByteArray {
            pad()
            val l = ByteArray(16)
            put64(l, 0, aadBytes * 8); put64(l, 8, dataBytes * 8)
            block(l, 0)
            return ByteArray(16).also { put64(it, 0, yh); put64(it, 8, yl) }
        }

        private fun be64(b: ByteArray, o: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
            return v
        }
        private fun put64(b: ByteArray, o: Int, v: Long) { for (i in 0 until 8) b[o + i] = (v ushr (56 - 8 * i)).toByte() }
    }

    /**
     * Encrypts everything written to it into [out]; [close] writes the tag and closes [out]. [aad]: the additional data
     * (empty for none).
     */
    class Writer(out: OutputStream, key: ByteArray, nonce: ByteArray, aad: ByteArray = ByteArray(0)) : FilterOutputStream(out) {
        private val c = ctr(key, nonce, Cipher.ENCRYPT_MODE)
        private val g = Ghash(aes(key, ByteArray(16)))
        private val s = aes(key, j0(nonce))
        private val aadLen = aad.size.toLong()
        private var n = 0L
        private var closed = false

        init { g.update(aad, 0, aad.size); g.pad() }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            val ct = c.update(b, off, len) ?: return
            g.update(ct, 0, ct.size); n += ct.size
            out.write(ct)
        }
        override fun close() {
            if (closed) return
            closed = true
            try {
                c.doFinal()?.let { if (it.isNotEmpty()) { g.update(it, 0, it.size); n += it.size; out.write(it) } }
                val t = g.finish(aadLen, n)
                for (i in 0 until 16) t[i] = (t[i].toInt() xor s[i].toInt()).toByte()
                out.write(t)
                out.flush()
            } finally { out.close() }
        }
    }

    /**
     * Decrypts [input] (ciphertext then the 16-byte tag, to its end). Bytes come out as they're read; the tag is checked
     * at the end of [input], and a mismatch throws [BadTag] there. So nothing read may be trusted, or kept, until the
     * end was reached without [BadTag].
     */
    class Reader(private val input: InputStream, key: ByteArray, nonce: ByteArray, aad: ByteArray = ByteArray(0)) : InputStream() {
        private val c = ctr(key, nonce, Cipher.DECRYPT_MODE)
        private val g = Ghash(aes(key, ByteArray(16)))
        private val s = aes(key, j0(nonce))
        private val aadLen = aad.size.toLong()
        private var n = 0L
        /** The last 16 bytes read so far: they may be the tag. */
        private val held = ByteArray(16)
        private var heldLen = 0
        private val raw = ByteArray(64 * 1024)
        private var plain = ByteArray(0)
        private var at = 0
        private var ended = false
        /** True once the whole input was read and its tag matched. */
        var checked = false
            private set

        init { g.update(aad, 0, aad.size); g.pad() }

        /** Reads more: false at the end (the tag checked). */
        private fun fill(): Boolean {
            while (!ended) {
                val r = input.read(raw, 0, raw.size)
                if (r < 0) { ended = true; end(); return false }
                if (r == 0) continue
                // held + raw[0..r): all but the last 16 bytes are ciphertext.
                val total = heldLen + r
                val ctLen = total - 16
                if (ctLen <= 0) {
                    System.arraycopy(raw, 0, held, heldLen, r); heldLen += r
                    continue
                }
                val ct = ByteArray(ctLen)
                val fromHeld = minOf(heldLen, ctLen)
                System.arraycopy(held, 0, ct, 0, fromHeld)
                System.arraycopy(raw, 0, ct, fromHeld, ctLen - fromHeld)
                // What stays held: the rest of held, then the tail of raw.
                val keep = ByteArray(16)
                var k = 0
                for (i in fromHeld until heldLen) keep[k++] = held[i]
                System.arraycopy(raw, ctLen - fromHeld, keep, k, r - (ctLen - fromHeld))
                System.arraycopy(keep, 0, held, 0, 16); heldLen = 16
                g.update(ct, 0, ct.size); n += ct.size
                plain = c.update(ct) ?: ByteArray(0); at = 0
                if (plain.isNotEmpty()) return true
            }
            return false
        }

        private fun end() {
            if (heldLen < 16) throw BadTag()
            val t = g.finish(aadLen, n)
            for (i in 0 until 16) t[i] = (t[i].toInt() xor s[i].toInt()).toByte()
            if (!MessageDigest.isEqual(t, held)) throw BadTag()
            checked = true
        }

        override fun read(): Int {
            if (at >= plain.size && !fill()) return -1
            return plain[at++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (at >= plain.size && !fill()) return -1
            val t = minOf(len, plain.size - at)
            System.arraycopy(plain, at, b, off, t); at += t
            return t
        }

        /** Reads to the end (the tag checked), dropping what's left. */
        fun drain() { val b = ByteArray(64 * 1024); while (read(b, 0, b.size) >= 0) { } }

        override fun close() = input.close()
    }
}
