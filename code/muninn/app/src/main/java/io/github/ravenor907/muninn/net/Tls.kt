package io.github.ravenor907.muninn.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * This phone's identity for encrypted nearby sharing (LocalSend's HTTPS mode): a self-signed
 * certificate made once and kept. Its SHA-256 is the "fingerprint" other devices know us by.
 *
 * The key is an ordinary software key saved in the app's private files, not an Android Keystore
 * key. Keystore keys can back TLS only with exactly matching purposes, paddings and digests, and
 * behave differently across Android 8 to 16 and phone makers; a software key works the same with
 * Conscrypt on every version. Android has no public certificate builder, so [selfSigned] writes
 * the few DER fields by hand and signs them with the key.
 */
class Tls private constructor(private val key: PrivateKey, val cert: X509Certificate) {
    /** Uppercase hex SHA-256 of the certificate, the form the LocalSend apps use. */
    val fingerprint: String = fingerprintOf(cert)

    /** Hands our certificate to the TLS stack, both as a server and as a client. */
    private val keyManager = object : X509ExtendedKeyManager() {
        // One key, so every question gets the same answer.
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getCertificateChain(alias: String?) = arrayOf(cert)
        override fun getPrivateKey(alias: String?) = key
    }

    /** For our server: any client certificate is fine (some apps send none); handle() checks claims against it. */
    val serverContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply { init(arrayOf<KeyManager>(keyManager), arrayOf<TrustManager>(Pinned(null)), SecureRandom()) }
    }

    private val clients = ConcurrentHashMap<String, SSLSocketFactory>()

    /**
     * A socket factory that shows our certificate and only accepts a server whose certificate has
     * the [expected] fingerprint (any certificate when null, for a scan that can't know it yet).
     * One factory per fingerprint: connections are pooled per factory, so one opened for a device
     * is never reused for another.
     */
    fun client(expected: String?): SSLSocketFactory = clients.getOrPut(expected?.uppercase() ?: "") {
        SSLContext.getInstance("TLS").apply {
            init(arrayOf<KeyManager>(keyManager), arrayOf<TrustManager>(Pinned(expected)), SecureRandom())
        }.socketFactory
    }

    /**
     * Certificates are self-signed, so there is no authority to check: the fingerprint is the check.
     * Refusing in the handshake means nothing (not even the request line) reaches a wrong device.
     */
    private class Pinned(expected: String?) : X509ExtendedTrustManager() {
        // 1.42 (cap h1): several fingerprints, comma-separated, when a contact named more than one (any of them will do).
        private val expected = expected?.uppercase()?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        private fun check(chain: Array<out X509Certificate>?) {
            if (expected == null) return
            val got = chain?.firstOrNull()?.let { fingerprintOf(it) }
            if (got == null || got !in expected) throw CertificateException("Certificate doesn't match the fingerprint the device announced")
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /**
     * 1.42 (cap h1): hands the TLS stack whichever certificate [pick] says at each handshake (Nearby off: the hidden one),
     * by its fingerprint as the alias, so a handshake that straddles a change still gets a matching key and certificate.
     */
    private class Picking(private val pick: () -> Tls, private val all: () -> List<Tls>) : X509ExtendedKeyManager() {
        private fun of(alias: String?): Tls? = all().firstOrNull { it.fingerprint == alias }
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = all().map { it.fingerprint }.toTypedArray()
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = pick().fingerprint
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = all().map { it.fingerprint }.toTypedArray()
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = pick().fingerprint
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = pick().fingerprint
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = pick().fingerprint
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? = of(alias)?.let { arrayOf(it.cert) }
        override fun getPrivateKey(alias: String?): PrivateKey? = of(alias)?.key
    }

    companion object {
        private const val ALIAS = "muninn"

        /**
         * A server context whose certificate is [pick]'s at each handshake; [all] lists every one it may pick (the
         * lasting one and the hidden ones). Any client certificate is fine, as for [serverContext].
         */
        fun picking(pick: () -> Tls, all: () -> List<Tls>): SSLContext =
            SSLContext.getInstance("TLS").apply { init(arrayOf<KeyManager>(Picking(pick, all)), arrayOf<TrustManager>(Pinned(null)), SecureRandom()) }

        fun fingerprintOf(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }

        fun same(a: String?, b: String?) = !a.isNullOrEmpty() && a.equals(b, ignoreCase = true)

        /** Only if no certificate of our own could be made: still checks the device's certificate, just shows none. */
        fun anonymousClient(expected: String?): SSLSocketFactory =
            SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(Pinned(expected)), SecureRandom()) }.socketFactory

        /** The saved identity in [dir], or a new one (saved there) if it's missing or damaged. Null if none can be made. */
        fun load(dir: File): Tls? {
            val keyFile = File(dir, "key.der")
            val certFile = File(dir, "cert.der")
            try {
                if (keyFile.isFile && certFile.isFile) {
                    val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                    val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certFile.readBytes())) as X509Certificate
                    if (matches(key, cert)) return Tls(key, cert)
                }
            } catch (_: Exception) {
                // Damaged: make a new one below. Other devices will see this phone as new once.
            }
            return try {
                val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), SecureRandom()) }
                val pair = kpg.generateKeyPair()
                val certDer = selfSigned(pair.public.encoded, pair.private)
                val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
                cert.verify(pair.public)
                // Even if saving fails, use the new identity for this run so sharing still works.
                try {
                    dir.mkdirs()
                    writePrivate(keyFile, pair.private.encoded)
                    writePrivate(certFile, certDer)
                } catch (_: Exception) {}
                Tls(pair.private, cert)
            } catch (_: Exception) {
                null
            }
        }

        /** True when the key signs what the certificate's public key verifies. */
        private fun matches(key: PrivateKey, cert: X509Certificate): Boolean = try {
            val data = "muninn".toByteArray()
            val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(data) }.sign()
            Signature.getInstance("SHA256withECDSA").apply { initVerify(cert.publicKey); update(data) }.verify(sig)
        } catch (_: Exception) {
            false
        }

        /** Written to a temporary file, then moved into place, readable by this app only. */
        private fun writePrivate(f: File, data: ByteArray) {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.delete()
            tmp.outputStream().use { it.write(data) }
            tmp.setReadable(false, false); tmp.setReadable(true, true)
            tmp.setWritable(false, false); tmp.setWritable(true, true)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }

        // ---- a minimal X.509 v3 certificate, DER encoded

        private val OID_ECDSA_SHA256 = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x04, 0x03, 0x02)   // 1.2.840.10045.4.3.2
        private val OID_COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)   // 2.5.4.3

        private fun der(tag: Int, vararg parts: ByteArray): ByteArray {
            val body = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
            val out = ByteArrayOutputStream()
            out.write(tag)
            val n = body.size
            when {
                n < 0x80 -> out.write(n)
                n < 0x100 -> { out.write(0x81); out.write(n) }
                n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xff) }
                else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xff); out.write(n and 0xff) }
            }
            out.write(body)
            return out.toByteArray()
        }

        private fun seq(vararg parts: ByteArray) = der(0x30, *parts)

        /**
         * A self-signed certificate for [publicKey] (its X.509 SubjectPublicKeyInfo encoding), as DER.
         * Version 3 because rustls (in some LocalSend apps) refuses lower versions; fixed wide dates so
         * a phone or peer with a wrong clock still accepts it; CN=Muninn; no extensions (none are checked).
         */
        internal fun selfSigned(publicKey: ByteArray, key: PrivateKey): ByteArray {
            val serial = ByteArray(16).also { SecureRandom().nextBytes(it) }
            serial[0] = ((serial[0].toInt() and 0x7f) or 0x40).toByte()   // positive, and no leading zero byte needed
            val algorithm = seq(der(0x06, OID_ECDSA_SHA256))
            val name = seq(der(0x31, seq(der(0x06, OID_COMMON_NAME), der(0x0c, "Muninn".toByteArray()))))
            val validity = seq(der(0x17, "000101000000Z".toByteArray()), der(0x17, "491231235959Z".toByteArray()))
            val tbs = seq(
                der(0xa0, der(0x02, byteArrayOf(2))),   // [0] version: 2 means v3
                der(0x02, serial),
                algorithm,
                name,   // issuer: itself
                validity,
                name,   // subject
                publicKey,
            )
            val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(tbs) }.sign()
            return seq(tbs, algorithm, der(0x03, byteArrayOf(0) + signature))   // BIT STRING: no unused bits
        }
    }
}

/**
 * 1.42 (cap h1, protocol.md 21.3): the certificates Muninn shows with Nearby off, kept in [dir] (outside the vault, like
 * the lasting one). "now" is shown; "next" is already made and named to contacts ("hf" in receipts) before it is ever
 * shown, so a contact with Nearby off still has it pinned when it takes over. Turning Nearby off, and every day while
 * it's off, "next" becomes "now" and a new "next" is made ([rotate]). Nobody close by can follow the phone from day to
 * day by its certificate, and contacts never lose the route.
 */
class HiddenTls(private val dir: File) {
    @Volatile private var now: Tls? = null
    @Volatile private var next: Tls? = null
    private val atFile = File(dir, "at")

    private fun load() {
        if (now != null && next != null) return
        synchronized(this) {
            if (now == null) now = Tls.load(File(dir, "now"))
            if (next == null) next = Tls.load(File(dir, "next"))
            if (!atFile.isFile) try { atFile.writeText(System.currentTimeMillis().toString()) } catch (_: Exception) {}
        }
    }

    /** Shown now with Nearby off; null only if none could be made (then the lasting one is shown). */
    fun current(): Tls? { load(); return now }
    /** Named to contacts already; shown after the next [rotate]. */
    fun upcoming(): Tls? { load(); return next }
    /** When "now" took over (ms). */
    fun since(): Long = try { atFile.readText().trim().toLong() } catch (_: Exception) { 0L }

    /** "next" becomes "now", and a new "next" is made. True when it changed. */
    fun rotate(nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(this) {
        load()
        val nowDir = File(dir, "now")
        val nextDir = File(dir, "next")
        try {
            nowDir.deleteRecursively()
            if (!nextDir.renameTo(nowDir)) return@synchronized false
        } catch (_: Exception) { return@synchronized false }
        now = next
        next = Tls.load(nextDir)
        try { atFile.writeText(nowMs.toString()) } catch (_: Exception) {}
        true
    }
}
