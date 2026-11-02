package io.github.ravenor907.muninn.net.call

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a call or voice room sounds: the phone's earpiece, its speaker, wired headphones, or a Bluetooth device,
 * picked by name on the call screen ("Audio"). One call or room at a time, so one route for the whole app.
 * [automatic] means the usual order: Bluetooth, then wired or USB, then the earpiece; in a video call or a voice
 * room the speaker takes the earpiece's place. A device that goes away mid-call (headphones unplugged, a headset
 * switched off) drops the choice back to automatic, so a room goes to the speaker, and a newly connected one is
 * listed and, while nothing was chosen, used at once.
 */
object AudioRoute {
    /** One place the sound can go. [id]: what [choose] takes ("earpiece", "speaker", or a device's own id). */
    data class Output(val id: String, val kind: Kind, val name: String)
    enum class Kind { EARPIECE, SPEAKER, WIRED, BLUETOOTH }

    const val AUTOMATIC = ""

    private val _outputs = MutableStateFlow<List<Output>>(emptyList())
    /** What is connected now, earpiece first. */
    val outputs: StateFlow<List<Output>> = _outputs.asStateFlow()
    private val _current = MutableStateFlow<Output?>(null)
    /** Where the sound goes now. */
    val current: StateFlow<Output?> = _current.asStateFlow()

    @Volatile private var am: AudioManager? = null
    @Volatile private var chosen = AUTOMATIC
    @Volatile private var preferSpeaker = false
    @Volatile private var video = false
    private val main = Handler(Looper.getMainLooper())
    private val watch = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refresh()
    }

    /**
     * A call or room took the audio: route it and follow devices. [speaker]: a voice room, so the speaker unless a
     * headset is connected. [video]: a video call, likewise.
     */
    fun start(audio: AudioManager, speaker: Boolean = false, video: Boolean = false) {
        am = audio
        chosen = AUTOMATIC
        preferSpeaker = speaker
        this.video = video
        try { audio.registerAudioDeviceCallback(watch, main) } catch (_: Exception) {}
        apply()
    }

    /** The call or room let the audio go: back to how Android had it. */
    fun stop() {
        val a = am ?: return
        am = null
        try { a.unregisterAudioDeviceCallback(watch) } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= 31) a.clearCommunicationDevice()
            else @Suppress("DEPRECATION") { a.stopBluetoothSco(); a.isBluetoothScoOn = false; a.isSpeakerphoneOn = false }
        } catch (_: Exception) {}
        _outputs.value = emptyList()
        _current.value = null
    }

    /** The camera came on mid-call: it's a video call now, so the speaker unless a headset is connected or the person chose. */
    fun videoCall() {
        if (video) return
        video = true
        if (chosen == AUTOMATIC) apply()
    }

    /** The person picked [id] on the call screen ([AUTOMATIC]: the usual order). */
    fun choose(id: String) {
        chosen = id
        apply()
    }

    /** Routes again (a phone call ended and Android reset the call audio). */
    fun reapply() { if (am != null) apply() }

    /** What is connected now, without taking the audio (the join sheet, before a room starts). */
    fun preview(audio: AudioManager): List<Output> = list(audio)

    /** True while the sound comes out of the phone's own speaker (no proximity sensor then). */
    val onSpeaker: Boolean get() = _current.value?.kind == Kind.SPEAKER

    private fun refresh() {
        if (am == null) return
        // The chosen device went away: the usual order picks the next best one.
        if (chosen != AUTOMATIC && list(am ?: return).none { it.id == chosen }) chosen = AUTOMATIC
        apply()
    }

    private fun apply() {
        val a = am ?: return
        val all = list(a)
        _outputs.value = all
        val pick = pickOutput(all, chosen, preferSpeaker, video)
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val dev = pick?.let { p -> a.availableCommunicationDevices.firstOrNull { idOf(it) == p.id } }
                if (dev != null) a.setCommunicationDevice(dev) else a.clearCommunicationDevice()
            } else @Suppress("DEPRECATION") {
                // Before Android 12 there is no device list for calls: the speaker switch and the Bluetooth link do it.
                when (pick?.kind) {
                    Kind.SPEAKER -> { a.stopBluetoothSco(); a.isBluetoothScoOn = false; a.isSpeakerphoneOn = true }
                    Kind.BLUETOOTH -> { a.isSpeakerphoneOn = false; a.startBluetoothSco(); a.isBluetoothScoOn = true }
                    else -> { a.stopBluetoothSco(); a.isBluetoothScoOn = false; a.isSpeakerphoneOn = false }
                }
            }
        } catch (_: Exception) {}
        _current.value = pick
    }

    private fun list(a: AudioManager): List<Output> {
        val out = mutableListOf<Output>()
        if (Build.VERSION.SDK_INT >= 31) {
            for (d in a.availableCommunicationDevices) {
                val kind = kindOf(d.type) ?: continue
                out += Output(idOf(d), kind, nameOf(kind, d.productName?.toString()))
            }
        } else {
            val devs = a.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            if (devs.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }) out += Output("earpiece", Kind.EARPIECE, nameOf(Kind.EARPIECE, null))
            if (devs.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }) out += Output("speaker", Kind.SPEAKER, nameOf(Kind.SPEAKER, null))
            devs.firstOrNull { kindOf(it.type) == Kind.WIRED }?.let { out += Output("wired", Kind.WIRED, nameOf(Kind.WIRED, null)) }
            devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }?.let { out += Output("bluetooth", Kind.BLUETOOTH, nameOf(Kind.BLUETOOTH, it.productName?.toString())) }
        }
        // One line per place: Android can list the same headset twice (its call and its music side).
        return out.distinctBy { it.kind to it.name }.sortedBy { it.kind.ordinal }
    }

    private fun idOf(d: AudioDeviceInfo): String = when (kindOf(d.type)) {
        Kind.EARPIECE -> "earpiece"
        Kind.SPEAKER -> "speaker"
        else -> "dev:${d.id}"
    }

    private fun kindOf(type: Int): Kind? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> Kind.EARPIECE
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Kind.SPEAKER
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> Kind.WIRED
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID -> Kind.BLUETOOTH
        else -> null
    }

    private fun nameOf(kind: Kind, product: String?): String = when (kind) {
        Kind.EARPIECE -> "Phone"
        Kind.SPEAKER -> "Speaker"
        Kind.WIRED -> "Headphones"
        Kind.BLUETOOTH -> product?.trim()?.takeIf { it.isNotEmpty() } ?: "Bluetooth"
    }
}

/**
 * Where the sound goes: the person's choice while it's connected; else a Bluetooth headset, then wired or USB
 * headphones, then for a voice room ([room]) or a video call the speaker, then the earpiece.
 */
fun pickOutput(all: List<AudioRoute.Output>, chosen: String, room: Boolean, video: Boolean): AudioRoute.Output? {
    fun kind(k: AudioRoute.Kind) = all.firstOrNull { it.kind == k }
    return all.firstOrNull { it.id == chosen }
        ?: kind(AudioRoute.Kind.BLUETOOTH) ?: kind(AudioRoute.Kind.WIRED)
        ?: (if (room || video) kind(AudioRoute.Kind.SPEAKER) else null)
        ?: kind(AudioRoute.Kind.EARPIECE) ?: all.firstOrNull()
}
