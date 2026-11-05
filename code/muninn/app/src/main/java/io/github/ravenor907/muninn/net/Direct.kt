package io.github.ravenor907.muninn.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Direct connect for when there's no shared Wi-Fi: Muninn makes a private hotspot (no internet,
 * nothing shared from mobile data) and shows a QR code. Another phone's camera joins it, then
 * sending and chat work as on any Wi-Fi. Huginn does the same on the handheld.
 */
private const val BACKSLASH = '\u005C'

class Direct(private val context: Context) {
    data class State(val on: Boolean = false, val ssid: String = "", val password: String = "", val error: String? = null, val starting: Boolean = false) {
        /** The standard Wi-Fi QR text every Android and iPhone camera understands. */
        val qr get() = "WIFI:T:WPA;S:${esc(ssid)};P:${esc(password)};;"
        private fun esc(s: String) = buildString { for (c in s) { if (c == BACKSLASH || c == ';' || c == ',' || c == ':' || c == '"') append(BACKSLASH); append(c) } }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (_state.value.on || _state.value.starting) return
        _state.value = State(starting = true)
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = r
                    if (Build.VERSION.SDK_INT >= 30) {
                        val c = r.softApConfiguration
                        val ssid = if (Build.VERSION.SDK_INT >= 33) c.wifiSsid?.toString()?.trim('"') else @Suppress("DEPRECATION") c.ssid
                        _state.value = State(true, ssid ?: "", c.passphrase ?: "")
                    } else {
                        @Suppress("DEPRECATION") val c = r.wifiConfiguration
                        _state.value = State(true, c?.SSID?.trim('"') ?: "", c?.preSharedKey?.trim('"') ?: "")
                    }
                }

                override fun onStopped() { _state.value = State() }

                override fun onFailed(reason: Int) {
                    _state.value = State(error = when (reason) {
                        ERROR_TETHERING_DISALLOWED -> "This phone doesn't allow hotspots (a work profile or carrier setting)."
                        ERROR_INCOMPATIBLE_MODE -> "Turn off the phone's regular hotspot first, then try again."
                        ERROR_NO_CHANNEL -> "No free Wi-Fi channel right now. Try again in a moment."
                        else -> "The phone couldn't start Direct connect. Turn Wi-Fi on and try again."
                    })
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            _state.value = State(error = "Muninn needs the Nearby devices (or Location) permission for Direct connect.")
        } catch (e: Exception) {
            _state.value = State(error = "Direct connect isn't available on this phone.")
        }
    }

    fun stop() {
        reservation?.close()
        reservation = null
        _state.value = State()
    }
}
