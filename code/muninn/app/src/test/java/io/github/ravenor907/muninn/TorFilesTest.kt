package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.FileLink
import io.github.ravenor907.muninn.net.TorFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Cap f4 (protocol.md section 30): a file over a Tor stream, here on loopback without Tor. The same sealed packets as
 * the UDP path, each in a frame; the receiver's first ack says what it has, so a file that started on Nearby or broke
 * part way goes on from the last piece.
 */
class TorFilesTest {
    private val id = "0123456789abcdef0123456789abcdef"
    private val key = Random(7).nextBytes(32)
    private val loop = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    private class Ctl : TorFiles.Ctl {
        @Volatile var done = 0L
        override fun stopped() = false
        override fun progress(done: Long) { this.done = done }
    }

    /** A stream that ends after [limit] bytes (the stream broke on the way). */
    private class Cut(private val ins: InputStream, private var limit: Int) : InputStream() {
        override fun read(): Int = if (limit-- <= 0) -1 else ins.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (limit <= 0) return -1
            val n = ins.read(b, off, minOf(len, limit))
            if (n > 0) limit -= n
            return n
        }
    }

    private fun run(src: File, dst: File, s: FileLink.Sender, r: FileLink.Receiver, sSeq: AtomicLong, rSeq: AtomicLong, cut: Int = 0): Pair<TorFiles.End, TorFiles.End> {
        val server = ServerSocket(0, 1, loop)
        var served = TorFiles.End.BROKEN
        val t = Thread {
            server.accept().use { c ->
                RandomAccessFile(src, "r").use { raf -> served = TorFiles.serve(c.getInputStream(), c.getOutputStream(), key, id, src.length(), s, raf, sSeq, Ctl(), idleMs = 3000) }
            }
        }.apply { start() }
        val got = Socket(loop, server.localPort).use { c ->
            val ins = if (cut > 0) Cut(c.getInputStream(), cut) else c.getInputStream()
            RandomAccessFile(dst, "rw").use { raf ->
                raf.setLength(src.length())
                TorFiles.receive(ins, c.getOutputStream(), key, id, src.length(), r, raf, rSeq, Ctl(), idleMs = 3000)
            }
        }
        t.join(10_000)
        server.close()
        return served to got
    }

    @Test fun aWholeFileGoesOverOneStream() {
        val src = File.createTempFile("tor-src", ".bin").apply { writeBytes(Random(1).nextBytes(300_001)); deleteOnExit() }
        val dst = File.createTempFile("tor-dst", ".bin").apply { deleteOnExit() }
        val n = FileLink.pieces(src.length())
        val (served, got) = run(src, dst, FileLink.Sender(n), FileLink.Receiver(n), AtomicLong(TorFiles.SEQ_BASE), AtomicLong(TorFiles.SEQ_BASE))
        assertEquals(TorFiles.End.DONE, got)
        assertEquals(TorFiles.End.DONE, served)
        assertArrayEquals(src.readBytes(), dst.readBytes())
    }

    @Test fun aBrokenStreamGoesOnFromTheLastPiece() {
        val bytes = Random(2).nextBytes(500_000)
        val src = File.createTempFile("tor-src", ".bin").apply { writeBytes(bytes); deleteOnExit() }
        val dst = File.createTempFile("tor-dst", ".bin").apply { deleteOnExit() }
        val n = FileLink.pieces(src.length())
        val s = FileLink.Sender(n)
        val r = FileLink.Receiver(n)
        // As after Nearby: the first 100 pieces came another way.
        r.preload(100)
        RandomAccessFile(dst, "rw").use { it.setLength(src.length()); it.write(bytes, 0, 100 * FileLink.CHUNK) }
        val sSeq = AtomicLong(TorFiles.SEQ_BASE)
        val rSeq = AtomicLong(TorFiles.SEQ_BASE)
        val (_, first) = run(src, dst, s, r, sSeq, rSeq, cut = 200_000)
        assertEquals(TorFiles.End.BROKEN, first)
        assertFalse(r.done)
        val had = r.ack().first
        assertTrue(had > 100)
        // A new stream (the receiver connects again): the same keys, the packet numbers go on.
        val (served, got) = run(src, dst, s, r, sSeq, rSeq)
        assertEquals(TorFiles.End.DONE, got)
        assertEquals(TorFiles.End.DONE, served)
        assertArrayEquals(bytes, dst.readBytes())
    }

    @Test fun aStrangerIsTurnedAway() {
        val src = File.createTempFile("tor-src", ".bin").apply { writeBytes(ByteArray(5000)); deleteOnExit() }
        val server = ServerSocket(0, 1, loop)
        var end = TorFiles.End.DONE
        val t = Thread { server.accept().use { c -> RandomAccessFile(src, "r").use { raf ->
            end = TorFiles.serve(c.getInputStream(), c.getOutputStream(), key, id, src.length(), FileLink.Sender(5), raf, AtomicLong(TorFiles.SEQ_BASE), Ctl()) } } }
            .apply { start() }
        Socket(loop, server.localPort).use { c ->
            // Sealed with another key: not theirs.
            c.getOutputStream().write(TorFiles.frame(FileLink.seal(ByteArray(32), id, FileLink.ACK, 1, TorFiles.SEQ_BASE, ByteArray(12))))
            c.getOutputStream().flush()
            t.join(5000)
        }
        server.close()
        assertEquals(TorFiles.End.STRANGER, end)
    }

    @Test fun addressesAndWords() {
        val onion = "a".repeat(55) + "d"
        assertTrue(TorFiles.onionOk(onion))
        assertFalse(TorFiles.onionOk("a".repeat(56)))
        assertFalse(TorFiles.onionOk(onion.uppercase()))
        assertFalse(TorFiles.onionOk("$onion.onion"))
        // SOCKS5 by name: Tor looks the onion up, nothing is looked up here.
        val req = TorFiles.socksRequest(onion, 4242)
        assertEquals(listOf<Byte>(5, 1, 0, 3, 62), req.take(5))
        assertEquals("$onion.onion", String(req, 5, 62))
        assertEquals(4242, ((req[67].toInt() and 255) shl 8) or (req[68].toInt() and 255))
        val m = TorFiles.torMessage(id, onion, 4242)
        assertTrue(TorFiles.torOk(m) && FileLink.ok(m))
        assertFalse(TorFiles.torOk(TorFiles.torMessage(id, onion, 0)))
        assertEquals(null, TorFiles.warning(TorFiles.WARN_BYTES, true))
        assertEquals(null, TorFiles.warning(2 * TorFiles.WARN_BYTES, false))
        assertEquals("It goes over Tor, which is slower. 2.0 GB may take about 36 min.", TorFiles.warning(2 * TorFiles.WARN_BYTES, true))
    }
}
