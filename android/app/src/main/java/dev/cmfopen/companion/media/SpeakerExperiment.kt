package dev.cmfopen.companion.media

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import dev.cmfopen.companion.DebugLog

/**
 * EXPERIMENT: use the watch as a speaker by "pretending to be a phone call".
 *
 * The watch only exposes the hands-free (call) audio link, not media audio (A2DP). This opens
 * that call link (SCO) with no call in progress and switches the phone to communication mode,
 * which on many phones routes all playback to the call device. It needs the watch to be
 * connected for calls in the phone's Bluetooth settings. Expect phone-call audio quality.
 * A Nov 24 attempt could not get the watch to accept the link; this logs every step so we
 * can see where it stops on the real watch.
 */
class SpeakerExperiment(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    var on = false
        private set

    fun start(): String {
        val msg = if (Build.VERSION.SDK_INT >= 31) {
            val devices = audio.availableCommunicationDevices
            DebugLog.info("speaker: comm devices = ${devices.joinToString { "${it.productName}(type ${it.type})" }}")
            val sco = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                ?: return "No Bluetooth call device. Connect the watch for calls in Bluetooth settings first.".also { DebugLog.warn("speaker: $it") }
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            val ok = audio.setCommunicationDevice(sco)
            "setCommunicationDevice(${sco.productName}) = $ok"
        } else {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audio.startBluetoothSco()
            @Suppress("DEPRECATION")
            audio.isBluetoothScoOn = true
            "startBluetoothSco() requested"
        }
        on = true
        DebugLog.info("speaker: $msg; now play something and listen on the watch")
        return msg
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice() else {
            @Suppress("DEPRECATION")
            audio.stopBluetoothSco()
            @Suppress("DEPRECATION")
            audio.isBluetoothScoOn = false
        }
        audio.mode = AudioManager.MODE_NORMAL
        on = false
        DebugLog.info("speaker: stopped")
    }
}
