package io.github.ravenor907.muninn.net

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Huginn on a Linux handheld, close by, with no Wi-Fi or internet. Linux has no Google Nearby, so
 * here the phone is a plain Bluetooth LE peripheral: it advertises one service and Huginn connects
 * to it. Huginn reads HELLO to learn who this is, writes framed messages to INBOX, and gets
 * framed messages back as OUTBOX notifications (see [BleFrames]). Huginn's first message is its
 * own hello; every later one is a chat envelope, the same body Muninn posts over Wi-Fi.
 *
 * All Bluetooth state lives on one background thread, so the callbacks (which Android calls on
 * its own threads) never race each other.
 */
@SuppressLint("MissingPermission")   // every call checks permissions first and catches SecurityException
class Ble(context: Context, private val hello: () -> JSONObject) {
    /** [proven]: cap s1, it signed the number this phone put in its HELLO. Without s1, only an extra route. */
    data class Peer(val address: String, val name: String, val chatKey: String?, val mailbox: String?, val proven: Boolean = false)

    private val context = context.applicationContext
    private val manager: BluetoothManager? = this.context.getSystemService(BluetoothManager::class.java)
    private val handler = Handler(HandlerThread("muninn-ble").apply { start() }.looper)
    /** Messages go to the app one at a time, in order, without holding up Bluetooth. */
    private val deliver = Executors.newSingleThreadExecutor()

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    /** Connected Huginns that have said hello. */
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Every chat envelope from a connected Huginn: (its chat key, json). */
    @Volatile var onMessage: ((chatKey: String, json: String) -> Unit)? = null
    /** Cap s1: signs Huginn's number with my chat key ([Proof.close]); the contact a hidden Huginn's hint stands for. */
    @Volatile var prove: ((String) -> String?)? = null
    @Volatile var hintKey: ((String) -> String?)? = null
    /**
     * Hidden from people nearby (21.3): while it says true, Bluetooth is silent. No advertising, HELLO answers nothing,
     * and Huginns already connected are let go.
     */
    @Volatile var hidden: () -> Boolean = { false }

    /** Hidden from people nearby was turned on or off: goes silent at once, or advertises again. */
    fun hiddenChanged() {
        handler.post {
            if (hidden()) {
                try { advertiser?.stopAdvertising(advertising) } catch (_: Exception) {}
                server?.let { s -> for (c in conns.values) try { s.cancelConnection(c.device) } catch (_: Exception) {} }
            } else restartAdvertising()
        }
    }

    private class Frame(val number: Int, val bytes: ByteArray)

    /** One connected Huginn. Only touched on the Bluetooth thread, except the @Volatile fields. */
    private inner class Conn(val device: BluetoothDevice) {
        val since = System.nanoTime()
        @Volatile var mtu = 23
        @Volatile var chatKey: String? = null
        @Volatile var proven = false
        /** Cap s1: the number in my HELLO for this Huginn, which its hello signs. */
        val nonce = Proof.newNonce()
        @Volatile var queued = 0
        var name = "Huginn"
        var mailbox: String? = null
        var heard = false
        var subscribed = false
        var nextNumber = 0
        val frames = java.util.ArrayDeque<Frame>()
        var busy = false
        var sending = 0
        var tries = 0
        var helloBytes: ByteArray? = null
        var prepared: ByteArrayOutputStream? = null
        val assembler = BleFrames.Assembler()
        // ATT's limit on one value is 512 bytes, even when MTU 517 leaves room for 514.
        val frameSize get() = (mtu - 3).coerceIn(BleFrames.HEADER + 1, MAX_VALUE)
        /** Some phones never say a notification went; don't wait forever. */
        val watchdog = Runnable { busy = false; pump(this) }
        val again = Runnable { pump(this) }
    }

    private val conns = ConcurrentHashMap<String, Conn>()

    // Bluetooth thread only.
    private var wanted = false
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var outbox: BluetoothGattCharacteristic? = null
    /** Bumped on every start and stop, so callbacks from an old server are ignored. */
    private var gen = 0
    private var switchListening = false
    private var retryDelay = RETRY_FIRST
    private val retry = Runnable { bringUp() }
    private val readvertise = Runnable { if (server != null) advertise() }
    /** Saving battery (see PowerLogic): FULL as usual, LOW quieter and in turns, OFF not advertising (Huginns already connected stay). */
    private var level = PowerLogic.Radio.FULL
    /** LOW only: in the advertising half of the duty cycle. */
    private var dutyOn = true
    private val duty = Runnable { dutyOn = !dutyOn; restartAdvertising(); scheduleDuty() }

    fun power(r: PowerLogic.Radio) {
        handler.post {
            if (r == level) return@post
            level = r
            handler.removeCallbacks(duty)
            dutyOn = true
            restartAdvertising()
            scheduleDuty()
        }
    }

    private fun scheduleDuty() {
        handler.removeCallbacks(duty)
        if (level == PowerLogic.Radio.LOW && wanted) handler.postDelayed(duty, PowerLogic.dutyNext(dutyOn))
    }

    /** Starts advertising and serving. Safe to call again, for example once permission is given. */
    fun start() {
        handler.post {
            wanted = true
            listenForSwitch()
            retryDelay = RETRY_FIRST
            bringUp()
            scheduleDuty()
        }
    }

    fun stop() {
        handler.post {
            wanted = false
            if (switchListening) {
                try { context.unregisterReceiver(onOff) } catch (_: Exception) {}
                switchListening = false
            }
            tearDown()
            _error.value = null
        }
    }

    /** The connection for that chat key: one that proved it first, else (not [strict]) the newest that couldn't. */
    private fun connFor(chatKey: String, strict: Boolean): Conn? {
        val all = conns.values.filter { it.chatKey == chatKey }
        return all.filter { it.proven }.maxByOrNull { it.since } ?: if (strict) null else all.maxByOrNull { it.since }
    }

    fun has(chatKey: String, strict: Boolean = false) = connFor(chatKey, strict) != null
    fun proven(chatKey: String) = conns.values.any { it.proven && it.chatKey == chatKey }

    /** Queues a message for that Huginn. False if it isn't connected (or the message is too big). */
    fun send(chatKey: String, json: String, strict: Boolean = false): Boolean {
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (bytes.size > BleFrames.MAX_MESSAGE) return false
        val c = connFor(chatKey, strict) ?: return false
        if (c.queued + bytes.size > MAX_QUEUE) return false
        // Numbering happens on the Bluetooth thread, so two senders can't put numbers out of order.
        handler.post { enqueue(c, bytes) }
        return true
    }

    // ------------------------------------------------------------------ starting and stopping

    private fun allowed() = Build.VERSION.SDK_INT < 31 || PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Bluetooth turned off and on again: everything has to be set up from scratch. */
    private val onOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> if (wanted) { retryDelay = RETRY_FIRST; bringUp() }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    tearDown()
                    if (wanted) _error.value = OFF_TEXT
                }
            }
        }
    }

    private fun listenForSwitch() {
        if (switchListening) return
        try {
            // Delivered on the Bluetooth thread, like everything else here.
            ContextCompat.registerReceiver(context, onOff, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), null, handler,
                ContextCompat.RECEIVER_NOT_EXPORTED)
            switchListening = true
        } catch (_: Exception) {}
    }

    private fun bringUp() {
        handler.removeCallbacks(retry)
        if (!wanted || server != null) return
        val m = manager
        val adapter = m?.adapter
        if (m == null || adapter == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            _error.value = NO_BLE_TEXT; return
        }
        if (!allowed()) { _error.value = PERMISSION_TEXT; return }
        val on = try { adapter.isEnabled } catch (_: Exception) { false }
        if (!on) { _error.value = OFF_TEXT; return }
        val adv = try { adapter.bluetoothLeAdvertiser } catch (_: Exception) { null }
        if (adv == null) { _error.value = NO_PERIPHERAL_TEXT; return }
        val g = ++gen
        val s = try {
            m.openGattServer(context, ServerCallback(g))
        } catch (_: SecurityException) {
            _error.value = PERMISSION_TEXT; return
        } catch (_: Exception) { null }
        // Right after Bluetooth turns on this can come back empty: try again shortly.
        if (s == null) { _error.value = FAILED_TEXT; scheduleRetry(); return }
        val (service, out) = buildService()
        server = s; advertiser = adv; outbox = out
        val added = try { s.addService(service) } catch (_: Exception) { false }
        if (!added) { tearDown(); _error.value = FAILED_TEXT; scheduleRetry() }
        // Advertising starts once Android confirms the service (onServiceAdded), so nobody
        // connects to a phone that has nothing to offer yet.
    }

    private fun scheduleRetry() {
        if (!wanted) return
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, retryDelay)
        retryDelay = (retryDelay * 2).coerceAtMost(RETRY_MAX)
    }

    private fun buildService(): Pair<BluetoothGattService, BluetoothGattCharacteristic> {
        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(HELLO,
            BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        service.addCharacteristic(BluetoothGattCharacteristic(INBOX,
            BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        val out = BluetoothGattCharacteristic(OUTBOX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        out.addDescriptor(BluetoothGattDescriptor(CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(out)
        return service to out
    }

    private fun tearDown() {
        gen++
        handler.removeCallbacks(retry)
        handler.removeCallbacks(readvertise)
        try { advertiser?.stopAdvertising(advertising) } catch (_: Exception) {}
        server?.let { s ->
            for (c in conns.values) try { s.cancelConnection(c.device) } catch (_: Exception) {}
            try { s.clearServices() } catch (_: Exception) {}
            try { s.close() } catch (_: Exception) {}
        }
        server = null; advertiser = null; outbox = null
        for (c in conns.values) { handler.removeCallbacks(c.watchdog); handler.removeCallbacks(c.again) }
        conns.clear()
        publish()
    }

    // ------------------------------------------------------------------ advertising

    private fun advertise() {
        val adv = advertiser ?: return
        handler.removeCallbacks(readvertise)
        if (hidden() || level == PowerLogic.Radio.OFF || (level == PowerLogic.Radio.LOW && !dutyOn)) return
        val low = level == PowerLogic.Radio.LOW
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(if (low) AdvertiseSettings.ADVERTISE_MODE_LOW_POWER else AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(if (low) AdvertiseSettings.ADVERTISE_TX_POWER_LOW else AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // Only the service id: no phone name or maker data for strangers to see.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE))
            .build()
        try {
            adv.startAdvertising(settings, data, advertising)
        } catch (_: SecurityException) {
            _error.value = PERMISSION_TEXT
        } catch (_: Exception) {
            _error.value = FAILED_TEXT
            handler.postDelayed(readvertise, READVERTISE)
        }
    }

    /** Some phones stop advertising once someone connects; start again so Huginn can always find it. */
    private fun restartAdvertising() {
        if (server == null) return
        try { advertiser?.stopAdvertising(advertising) } catch (_: Exception) {}
        advertise()
    }

    private val advertising = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            handler.post { retryDelay = RETRY_FIRST; if (wanted && server != null) _error.value = null }
        }

        override fun onStartFailure(errorCode: Int) {
            handler.post {
                if (!wanted || server == null) return@post
                when (errorCode) {
                    AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> _error.value = null
                    AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> _error.value = NO_PERIPHERAL_TEXT
                    // Phones allow only a few at once, and Nearby Connections may hold some: wait and retry.
                    AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> {
                        _error.value = BUSY_TEXT
                        handler.postDelayed(readvertise, READVERTISE)
                    }
                    else -> {
                        _error.value = FAILED_TEXT
                        handler.postDelayed(readvertise, READVERTISE)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ serving

    private fun conn(device: BluetoothDevice): Conn = conns.getOrPut(device.address) { Conn(device) }

    private fun respond(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
        val s = server ?: return
        try { s.sendResponse(device, requestId, status, offset, value) }
        catch (_: SecurityException) { _error.value = PERMISSION_TEXT }
        catch (_: Exception) {}
    }

    /**
     * Just the fields Huginn needs, whatever else the app's hello carries. "n": this connection's number, which
     * Huginn's hello signs. Hidden from people nearby: nothing (Bluetooth is silent then, [hidden]).
     */
    private fun helloBytes(c: Conn): ByteArray? = try {
        val h = hello()
        if (hidden() || !h.has("chatKey")) null
        else JSONObject().put("name", h.optString("name")).put("chatKey", h.optString("chatKey")).put("mailbox", h.optString("mailbox"))
            .put("app", "muninn").put("n", c.nonce).toString().toByteArray(Charsets.UTF_8)
    } catch (_: Exception) { null }

    private inner class ServerCallback(private val g: Int) : BluetoothGattServerCallback() {
        /** Runs [f] on the Bluetooth thread, unless this server has since been closed. */
        private fun later(f: () -> Unit) { handler.post { if (g == gen && server != null) f() } }

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            later {
                if (status == BluetoothGatt.GATT_SUCCESS) advertise()
                else { tearDown(); _error.value = FAILED_TEXT; scheduleRetry() }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            device ?: return
            later {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        // Hidden from people nearby: nobody is let in.
                        if (hidden()) { try { server?.cancelConnection(device) } catch (_: Exception) {}; return@later }
                        conn(device); restartAdvertising()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        conns.remove(device.address)?.let { handler.removeCallbacks(it.watchdog); handler.removeCallbacks(it.again) }
                        publish()
                        restartAdvertising()
                    }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            device ?: return
            later { conn(device).mtu = mtu }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic?) {
            device ?: return
            later {
                if (characteristic?.uuid != HELLO) { respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null); return@later }
                val c = conn(device)
                // A long read comes in pieces; keep the same bytes for all of them so they match up.
                val bytes = (if (offset == 0) null else c.helloBytes) ?: helloBytes(c).also { c.helloBytes = it }
                when {
                    bytes == null -> respond(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                    offset < 0 || offset > bytes.size -> respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                    else -> respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, bytes.copyOfRange(offset, bytes.size))
                }
            }
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice?, requestId: Int, characteristic: BluetoothGattCharacteristic?,
                                                  preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            device ?: return
            later {
                if (characteristic?.uuid != INBOX) {
                    if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null)
                    return@later
                }
                val c = conn(device)
                val v = value ?: ByteArray(0)
                if (preparedWrite) {
                    // A long write: pieces arrive first, then onExecuteWrite says keep or throw away.
                    val buf = c.prepared ?: ByteArrayOutputStream().also { c.prepared = it }
                    if (offset != buf.size() || buf.size() + v.size > BleFrames.MAX_MESSAGE + BleFrames.HEADER) {
                        c.prepared = null
                        if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                        return@later
                    }
                    buf.write(v, 0, v.size)
                    // The answer to a prepared write echoes the piece back so the sender can check it.
                    if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, v)
                    return@later
                }
                if (offset != 0) {
                    if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                    return@later
                }
                // Answer first so Huginn can send its next frame while this one is handled.
                if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                received(c, v)
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice?, requestId: Int, execute: Boolean) {
            device ?: return
            later {
                val c = conn(device)
                val data = c.prepared?.toByteArray()
                c.prepared = null
                respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                if (execute && data != null) received(c, data)
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor?) {
            device ?: return
            later {
                if (descriptor?.uuid != CCCD) { respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null); return@later }
                val v = if (conn(device).subscribed) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                if (offset < 0 || offset > v.size) respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                else respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, v.copyOfRange(offset, v.size))
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice?, requestId: Int, descriptor: BluetoothGattDescriptor?,
                                              preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            device ?: return
            later {
                if (descriptor?.uuid != CCCD || preparedWrite || offset != 0) {
                    if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null)
                    return@later
                }
                val c = conn(device)
                // Bit 0 turns notifications on (bit 1 would be indications; either counts as listening).
                c.subscribed = value != null && value.isNotEmpty() && (value[0].toInt() and 3) != 0
                if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                if (c.subscribed) pump(c)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            device ?: return
            later {
                val c = conns[device.address] ?: return@later
                handler.removeCallbacks(c.watchdog)
                c.busy = false
                // A lost frame spoils the rest of its message: skip to the next one.
                if (status != BluetoothGatt.GATT_SUCCESS) dropMessage(c, c.sending)
                pump(c)
            }
        }
    }

    // ------------------------------------------------------------------ receiving

    private fun received(c: Conn, frame: ByteArray) {
        val message = c.assembler.add(frame) ?: return
        val text = String(message, Charsets.UTF_8)
        val o = try { JSONObject(text) } catch (_: Exception) { return }
        if (o.optString("t") == "hello") {
            c.name = o.optString("name").ifBlank { c.name }.take(40)
            val key = o.optString("chatKey").ifEmpty { null } ?: o.optString("hint").ifEmpty { null }?.let { hintKey?.invoke(it) }
            // Cap s1: its hello signs the number in my HELLO. A wrong signature: not who it says, so no chat key at all.
            val sig = o.optString("proof")
            c.proven = key != null && sig.isNotEmpty() && Proof.closeOk(key, c.nonce, sig)
            c.chatKey = if (c.proven || sig.isEmpty()) key else null
            c.mailbox = o.optString("mailbox").ifEmpty { null }
            c.heard = true
            // And I sign its number, so it knows this is really me.
            val n = o.optString("n")
            if (Proof.isNonce(n)) prove?.invoke(n)?.let { enqueue(c, JSONObject().put("t", "proof").put("sig", it).toString().toByteArray(Charsets.UTF_8)) }
            publish()
            pump(c)
            return
        }
        // Huginn says hello first; anything before it can't be told apart from a stranger's.
        val key = c.chatKey ?: return
        val f = onMessage ?: return
        deliver.execute { try { f(key, text) } catch (_: Exception) {} }
    }

    // ------------------------------------------------------------------ sending

    private fun enqueue(c: Conn, bytes: ByteArray) {
        if (conns[c.device.address] !== c) return   // gone while this waited
        val n = c.nextNumber++
        for (f in BleFrames.split(bytes, n, c.frameSize)) { c.frames.addLast(Frame(n, f)); c.queued += f.size }
        pump(c)
    }

    private fun dropMessage(c: Conn, number: Int) {
        c.frames.removeIf { it.number == number }
        c.queued = c.frames.sumOf { it.bytes.size }
    }

    /** One notification at a time: the next goes when Android says the last one went. */
    private fun pump(c: Conn) {
        handler.removeCallbacks(c.again)
        if (c.busy || !c.subscribed || conns[c.device.address] !== c) return
        val s = server ?: return
        val out = outbox ?: return
        val f = c.frames.peekFirst() ?: return
        when (notify(s, c.device, out, f.bytes)) {
            SENT -> {
                c.frames.pollFirst()
                c.queued -= f.bytes.size
                c.busy = true
                c.sending = f.number
                c.tries = 0
                handler.postDelayed(c.watchdog, NOTIFY_TIMEOUT)
            }
            BUSY -> {
                if (++c.tries > MAX_TRIES) { c.tries = 0; dropMessage(c, f.number); handler.post(c.again) }
                else handler.postDelayed(c.again, BUSY_WAIT)
            }
            else -> { c.frames.clear(); c.queued = 0 }
        }
    }

    private fun notify(s: BluetoothGattServer, device: BluetoothDevice, out: BluetoothGattCharacteristic, frame: ByteArray): Int = try {
        if (Build.VERSION.SDK_INT >= 33) {
            when (s.notifyCharacteristicChanged(device, out, false, frame)) {
                BluetoothStatusCodes.SUCCESS -> SENT
                BluetoothStatusCodes.ERROR_GATT_WRITE_NOT_ALLOWED, BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND -> FAILED
                BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION -> { _error.value = PERMISSION_TEXT; FAILED }
                else -> BUSY   // usually ERROR_GATT_WRITE_REQUEST_BUSY; worth another try
            }
        } else {
            // Before Android 13 the value rides on the shared characteristic object; this thread is
            // the only one that sets it, so set-then-notify can't mix two frames.
            @Suppress("DEPRECATION") out.setValue(frame)
            @Suppress("DEPRECATION") val ok = s.notifyCharacteristicChanged(device, out, false)
            if (ok) SENT else BUSY
        }
    } catch (_: SecurityException) {
        _error.value = PERMISSION_TEXT; FAILED
    } catch (_: Exception) { FAILED }

    private fun publish() {
        _peers.value = conns.values.filter { it.heard }.sortedBy { it.name.lowercase() }
            .map { Peer(it.device.address, it.name, it.chatKey, it.mailbox, it.proven) }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("7b1e0001-6d75-6e69-6e6e-48756767696e")
        val HELLO: UUID = UUID.fromString("7b1e0002-6d75-6e69-6e6e-48756767696e")
        val INBOX: UUID = UUID.fromString("7b1e0003-6d75-6e69-6e6e-48756767696e")
        val OUTBOX: UUID = UUID.fromString("7b1e0004-6d75-6e69-6e6e-48756767696e")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** What to ask for on Android 12 and newer (older versions grant Bluetooth at install). */
        val PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)

        private const val MAX_VALUE = 512
        private const val MAX_QUEUE = 1024 * 1024
        private const val NOTIFY_TIMEOUT = 2_000L
        private const val BUSY_WAIT = 20L
        private const val MAX_TRIES = 100
        private const val RETRY_FIRST = 2_000L
        private const val RETRY_MAX = 60_000L
        private const val READVERTISE = 15_000L
        private const val SENT = 0
        private const val BUSY = 1
        private const val FAILED = 2

        const val PERMISSION_TEXT = "Muninn needs the Nearby devices permission to talk to Huginn over Bluetooth. Tap here to allow it."
        const val OFF_TEXT = "Turn Bluetooth on to reach Huginn close by."
        const val NO_BLE_TEXT = "This phone has no Bluetooth LE, so Huginn can't connect to it directly."
        const val NO_PERIPHERAL_TEXT = "This phone can't be found over Bluetooth LE, so Huginn can't connect to it directly."
        const val BUSY_TEXT = "Bluetooth is busy with other things right now. Muninn will keep trying."
        const val FAILED_TEXT = "Bluetooth didn't start for Huginn. Muninn will keep trying."
    }
}
