package io.github.ravenor907.muninn.net

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The backup file, the same in Huginn and Muninn (shared/docs/backup.md). Plain Kotlin (no Android) so it can be
 * tested off the phone.
 *
 *   "MUNB1"
 *   uint32 BE header length, then the header JSON
 *   12-byte nonce + AES-256-GCM ciphertext + 16-byte tag
 *
 * The header says how the password makes the 32-byte key:
 *   {"v":3,"salt":base64,"kdf":"scrypt","N":65536,"r":8,"p":2,"created":ISO8601,"app":"muninn"}   what both apps write;
 *     the GCM tag also covers every byte before the nonce, so a changed header is refused too
 *   {"v":2,...,"kdf":"scrypt","N","r","p"} or {"v":2,...,"kdf":"argon2id","m","t","p"}   older, still restored (Argon2id
 *     ones only in Huginn)
 *   {"v":1,"salt":base64,"rounds":310000,"created":ISO8601,"app":"muninn"}   PBKDF2-SHA256, still restored
 * New backups need a password of at least [MIN_PASSWORD] characters. Inside the encryption, the files one after
 * another: [uint32 BE name length][name, UTF-8][uint32 BE data length][data]. Nothing but the header is readable
 * without the password: not the names, not how many files there are. A v3 backup also holds [ABOUT] (which app and
 * device made it, and whether files and pictures are in it) and [SHARED] (the form both apps read), so one made on a
 * phone restores on a computer and back.
 */
object BackupFormat {
    val MAGIC = "MUNB1".toByteArray()
    const val VERSION = 3
    const val ROUNDS = 310_000
    /** scrypt for new backups: 64 MiB, two lanes (as hard to guess as N = 2^17 with one, at half the memory on a phone). */
    const val N = 65_536
    const val R = 8
    const val P = 2
    /** Which app and device made it, and whether files and pictures are in it: {"app","device","created","files"}. */
    const val ABOUT = "@about.json"
    /** Identity, contacts, chats, groups, folders and profile in the form both apps read ([MoveCommon], protocol.md 18.4). */
    const val SHARED = "@move.json"
    /** The usual file ending. Any name opens. */
    const val EXT = "hmbak"
    /** New backups: at least this many characters. */
    const val MIN_PASSWORD = 12
    private const val MAX_HEADER = 4096
    private const val MAX_NAME = 4096
    private val random = SecureRandom()

    /** [v] 1: PBKDF2 with [rounds]. [v] 2: [kdf] "scrypt" with [n], [r], [p], or "argon2id" with [m], [t], [p]. */
    class Header(val v: Int, val salt: ByteArray, val rounds: Int, val created: String, val app: String,
                 val kdf: String = "", val n: Int = 0, val r: Int = 0, val p: Int = 0, val m: Int = 0, val t: Int = 0)
    /** A file to put in the backup. [load] gives its plain bytes, or null to leave it out. */
    class Entry(val name: String, val load: () -> ByteArray?)
    class Opened(val header: Header, val files: List<Pair<String, ByteArray>>)

    class NotABackup(message: String) : Exception(message)
    class WrongPassword : Exception("Wrong password")

    /**
     * Writes a whole backup to [out] (and closes it). [progress] goes from 0 to 1 by files done. A v3 backup (scrypt
     * [N], [R], [P]); [rounds] > 0 writes a PBKDF2 (v1) one and [legacy] true a v2 one instead, only so tests can check
     * those still open.
     */
    fun write(out: OutputStream, password: String, entries: List<Entry>, app: String = "muninn",
              created: String = java.time.Instant.now().toString(), rounds: Int = 0,
              salt: ByteArray = ByteArray(16).also { random.nextBytes(it) }, legacy: Boolean = false, progress: (Float) -> Unit = {}): Int {
        val h = when {
            rounds > 0 -> Header(1, salt, rounds, created, app)
            legacy -> Header(2, salt, 0, created, app, kdf = "scrypt", n = Vault.SCRYPT_N, r = Vault.SCRYPT_R, p = Vault.SCRYPT_P)
            else -> Header(VERSION, salt, 0, created, app, kdf = "scrypt", n = N, r = R, p = P)
        }
        val key = key(h, password)
        val header = (if (h.v == 1) "{\"v\":1,\"salt\":\"${b64(salt)}\",\"rounds\":$rounds,"
            else "{\"v\":${h.v},\"salt\":\"${b64(salt)}\",\"kdf\":\"scrypt\",\"N\":${h.n},\"r\":${h.r},\"p\":${h.p},") +
            "\"created\":\"${esc(created)}\",\"app\":\"${esc(app)}\"}"
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        key.fill(0)
        val hb = header.toByteArray()
        val head = MAGIC + ByteBuffer.allocate(4).putInt(hb.size).array() + hb
        if (h.v >= 3) c.updateAAD(head)
        out.write(head)
        out.write(iv)
        var count = 0
        DataOutputStream(BufferedOutputStream(CipherOutputStream(out, c), 64 * 1024)).use { d ->
            entries.forEachIndexed { i, e ->
                val data = e.load()
                if (data != null) {
                    val name = e.name.toByteArray(Charsets.UTF_8)
                    d.writeInt(name.size); d.write(name)
                    d.writeInt(data.size); d.write(data)
                    count++
                }
                progress((i + 1f) / entries.size)
            }
        }
        if (entries.isEmpty()) progress(1f)
        return count
    }

    /** Reads the header only (no password needed). Returns it and where the encrypted part starts. */
    fun header(bytes: ByteArray): Pair<Header, Int> {
        if (bytes.size < MAGIC.size + 4 || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw NotABackup("This isn't a Huginn or Muninn backup.")
        val len = ByteBuffer.wrap(bytes, MAGIC.size, 4).int
        if (len <= 0 || len > MAX_HEADER || MAGIC.size + 4 + len > bytes.size) throw NotABackup("This backup is damaged.")
        val json = String(bytes, MAGIC.size + 4, len, Charsets.UTF_8)
        val fields = Regex("\"([A-Za-z]+)\"\\s*:\\s*(?:\"([^\"\\\\]*)\"|(-?\\d+))").findAll(json)
            .associate { m -> m.groupValues[1] to (m.groups[2]?.value ?: m.groupValues[3]) }
        val v = fields["v"]?.toIntOrNull() ?: throw NotABackup("This backup is damaged.")
        if (v !in 1..3) throw NotABackup("This backup is from a newer version. Update Muninn first.")
        val salt = try { b64d(fields["salt"] ?: "") } catch (e: Exception) { ByteArray(0) }
        fun num(k: String) = fields[k]?.toLongOrNull()?.takeIf { it in 0..Int.MAX_VALUE }?.toInt() ?: 0
        val h = Header(v, salt, num("rounds"), fields["created"] ?: "", fields["app"] ?: "", fields["kdf"] ?: "",
            num("N"), num("r"), num("p"), num("m"), num("t"))
        // The key's settings come from the unprotected header: a weakened one is refused rather than trusted, and so is
        // one so big it would run the phone out of memory.
        if (salt.size < 16 || !sane(h)) throw NotABackup("This backup is damaged.")
        return h to (MAGIC.size + 4 + len)
    }

    /** Round 17: the most memory and lanes a backup's scrypt may ask of a phone. */
    const val SCRYPT_MAX_BYTES = 1L shl 28
    const val SCRYPT_MAX_P = 4

    private fun sane(h: Header): Boolean = when {
        h.v == 1 -> h.rounds in 100_000..10_000_000
        h.v == 3 && h.kdf != "scrypt" -> false   // both apps must be able to open it
        // Round 17: at most 256 MiB and 4 lanes on a phone (both apps write at most 128 MiB, p 2): a crafted file can't
        // run it out of memory or hang it before the password is even checked.
        h.kdf == "scrypt" -> h.n in 65_536..1_048_576 && (h.n and (h.n - 1)) == 0 && h.r in 8..32 && h.p in 1..SCRYPT_MAX_P &&
            h.n.toLong() * h.r * 128 <= SCRYPT_MAX_BYTES
        h.kdf == "argon2id" -> h.m in 65_536..1_048_576 && h.t in 3..16 && h.p in 1..16
        else -> false
    }

    /** The key [h] says to make from [password]. Muninn makes PBKDF2 and scrypt keys; Argon2id is Huginn's. */
    fun key(h: Header, password: String): ByteArray = when {
        h.v == 1 -> derive(password, h.salt, h.rounds)
        h.kdf == "scrypt" -> Vault.Crypto.scrypt(password.toByteArray(Charsets.UTF_8), h.salt, h.n, h.r, h.p)
        else -> throw NotABackup("This backup is damaged.")
    }

    /**
     * Checks the password and unpacks every file. Throws [WrongPassword] or [NotABackup]. A v3 backup from either app
     * opens; an older one only in the app that made it ([app]).
     */
    fun open(bytes: ByteArray, password: String, app: String = "muninn"): Opened {
        val (h, at) = header(bytes)
        if (h.v < 3 && h.app != app) throw NotABackup(when (h.app) {
            "huginn" -> "This older backup was made in Huginn. Restore it in Huginn."
            "muninn" -> "This older backup was made in Muninn. Restore it in Muninn."
            else -> "This isn't a Huginn or Muninn backup."
        })
        if (h.v == 2 && h.kdf == "argon2id") throw NotABackup("This older backup only opens in Huginn.")
        if (bytes.size - at < 12 + 16) throw NotABackup("This backup is damaged.")
        val key = key(h, password)
        val plain = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes, at, 12))
            if (h.v >= 3) c.updateAAD(bytes, 0, at)
            c.doFinal(bytes, at + 12, bytes.size - at - 12)
        } catch (e: AEADBadTagException) {
            throw WrongPassword()   // a wrong password and a changed file look the same, by design
        } finally { key.fill(0) }
        return Opened(h, unpack(plain))
    }

    /** What a backup holds, for the screen before Restore. Counts come from [SHARED]; null when it has none (older backups). */
    class Summary(val app: String, val device: String, val created: String, val files: Boolean?, val contacts: Int?,
                  val chats: Int?, val groups: Int?, val folders: Int?, val items: Int)

    fun summary(o: Opened): Summary {
        val about = o.files.firstOrNull { it.first == ABOUT }?.let { try { org.json.JSONObject(String(it.second, Charsets.UTF_8)) } catch (e: Exception) { null } }
        val shared = o.files.firstOrNull { it.first == SHARED }?.let { try { org.json.JSONObject(String(it.second, Charsets.UTF_8)) } catch (e: Exception) { null } }
        val app = about?.optString("app")?.takeIf { it.isNotEmpty() } ?: o.header.app
        return Summary(app, about?.optString("device") ?: "", about?.optString("created")?.takeIf { it.isNotEmpty() } ?: o.header.created,
            if (about != null && about.has("files")) about.optBoolean("files") else null,
            shared?.optJSONArray("contacts")?.length(), shared?.optJSONObject("chats")?.length(),
            shared?.optJSONArray("groups")?.length(), shared?.optJSONObject("folders")?.optJSONArray("folders")?.length(),
            o.files.count { !it.first.startsWith("@") })
    }

    /** The [ABOUT] entry. */
    fun about(app: String, device: String, created: String, files: Boolean): ByteArray =
        org.json.JSONObject().put("v", 1).put("app", app).put("device", device).put("created", created).put("files", files).toString().toByteArray()

    /**
     * How strong a new backup password looks: 0 too short (it can't be used), 1 weak, 2 fair, 3 good, 4 strong. The
     * same rules as Huginn's (BackupPanel.qml): length, different characters, and kinds (a-z, A-Z, 0-9, others).
     */
    fun strength(pw: String): Int {
        if (pw.length < MIN_PASSWORD) return 0
        if (pw.toSet().size < 5) return 1
        val kinds = listOf(pw.any { it in 'a'..'z' }, pw.any { it in 'A'..'Z' }, pw.any { it in '0'..'9' },
            pw.any { !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9') }).count { it }
        var s = 2
        if (pw.length >= 16) s++
        if (pw.length >= 20 || kinds >= 3) s++
        return minOf(s, 4)
    }

    /** Photos, voice and video messages and other big files: in the backup only when files and pictures are. */
    fun isMedia(name: String): Boolean = name.startsWith("photos/") || name.startsWith("mirror-media/")

    fun unpack(plain: ByteArray): List<Pair<String, ByteArray>> {
        val b = ByteBuffer.wrap(plain)
        val out = ArrayList<Pair<String, ByteArray>>()
        while (b.hasRemaining()) {
            if (b.remaining() < 4) throw NotABackup("This backup is damaged.")
            val n = b.int
            if (n <= 0 || n > MAX_NAME || n > b.remaining()) throw NotABackup("This backup is damaged.")
            val name = ByteArray(n).also { b.get(it) }.toString(Charsets.UTF_8)
            if (b.remaining() < 4) throw NotABackup("This backup is damaged.")
            val len = b.int
            if (len < 0 || len > b.remaining()) throw NotABackup("This backup is damaged.")
            out += name to ByteArray(len).also { b.get(it) }
        }
        return out
    }

    /** A name that stays inside the data folder: relative, no "..", no backslashes. */
    fun safeName(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith("/") && !name.contains('\\') && !name.contains('\u0000') &&
            name.split('/').none { it.isEmpty() || it == "." || it == ".." }

    fun derive(password: String, salt: ByteArray, rounds: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, rounds, 256)).encoded

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun b64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
    private fun b64d(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
}
