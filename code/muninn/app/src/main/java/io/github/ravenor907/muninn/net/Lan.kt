package io.github.ravenor907.muninn.net

import java.net.Inet4Address
import java.net.InetAddress

/** Home-network address checks, the same as Huginn's Lan (src/lan.cpp), kept apart so they can be tested off the phone. */
object Lan {
    /** Private (home network) addresses: 10/8, 172.16/12, 192.168/16, fe80::/10 and fd00::/8; IPv4 seen through IPv6 too. */
    fun isPrivate(a: InetAddress): Boolean {
        val b = a.address
        if (a is Inet4Address || b.size == 4) {
            val x = b[0].toInt() and 0xff; val y = b[1].toInt() and 0xff
            return x == 10 || (x == 172 && y in 16..31) || (x == 192 && y == 168)
        }
        if (b.size != 16) return false
        if (mapped(b)) return isPrivate(InetAddress.getByAddress(b.copyOfRange(12, 16)))
        val x = b[0].toInt() and 0xff; val y = b[1].toInt() and 0xff
        return (x == 0xfe && (y and 0xc0) == 0x80) || x == 0xfd
    }

    /** ::ffff:a.b.c.d */
    fun mapped(b: ByteArray) = b.size == 16 && (0 until 10).all { b[it].toInt() == 0 } && (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff

    /** Whether [a] is inside [net]/[prefix] (both the same length: 4 or 16 bytes). */
    fun inSubnet(a: ByteArray, net: ByteArray, prefix: Int): Boolean {
        if (a.size != net.size || prefix <= 0) return false
        var bits = prefix.coerceAtMost(a.size * 8)
        var i = 0
        while (bits >= 8) { if (a[i] != net[i]) return false; i++; bits -= 8 }
        if (bits == 0) return true
        val mask = (0xff shl (8 - bits)) and 0xff
        return (a[i].toInt() and mask) == (net[i].toInt() and mask)
    }
}
