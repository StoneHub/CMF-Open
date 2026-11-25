package dev.cmfopen.companion.media

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import dev.cmfopen.companion.DebugLog

/**
 * Speaker experiment, mode 2: a self-managed "call" through Android's Telecom framework.
 *
 * Plain SCO (mode 1) wasn't enough on Nov 24: the watch never entered its call-audio state.
 * A Telecom call makes the phone's hands-free profile report an active call to the watch, the
 * same signal a real call sends, and then asks Telecom to route the call audio to Bluetooth.
 * Whether media playback follows the call route depends on the phone; this logs what happens.
 */
class SpeakerCallService : ConnectionService() {
    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest?): Connection {
        val c = SpeakerConnection()
        c.connectionProperties = Connection.PROPERTY_SELF_MANAGED
        c.audioModeIsVoip = true
        c.setAddress(Uri.fromParts("tel", "watch-speaker", null), TelecomManager.PRESENTATION_ALLOWED)
        c.setCallerDisplayName("Watch speaker", TelecomManager.PRESENTATION_ALLOWED)
        c.setDialing()
        c.setActive()
        current = c
        DebugLog.info("speaker call: active")
        return c
    }

    override fun onCreateOutgoingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) {
        DebugLog.warn("speaker call: Telecom refused the call")
    }

    class SpeakerConnection : Connection() {
        /**
         * How many times we've asked for the Bluetooth route. On a Watch 3 Pro (Nov 25) the route
         * fell back to the earpiece within ~0.4 s of every request, so re-requesting on each change
         * looped several times a second. Ask a few times, then leave it and say so.
         */
        private var btRequests = 0
        private var lastRoute = -1

        override fun onCallAudioStateChanged(state: CallAudioState) {
            if (state.route == lastRoute) return
            lastRoute = state.route
            DebugLog.info("speaker call: audio route=${CallAudioState.audioRouteToString(state.route)} supported=${state.supportedRouteMask}")
            if (state.route == CallAudioState.ROUTE_BLUETOOTH || state.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH == 0) return
            if (btRequests >= MAX_BT_REQUESTS) {
                if (btRequests++ == MAX_BT_REQUESTS) {
                    DebugLog.warn("speaker call: the watch dropped call audio $MAX_BT_REQUESTS times; leaving it on ${CallAudioState.audioRouteToString(state.route)}")
                }
                return
            }
            btRequests++
            @Suppress("DEPRECATION")
            setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
        }

        override fun onDisconnect() = end()
        override fun onAbort() = end()

        fun end() {
            setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            destroy()
            if (current === this) current = null
            DebugLog.info("speaker call: ended")
        }
    }

    companion object {
        @Volatile var current: SpeakerConnection? = null
        private const val MAX_BT_REQUESTS = 3

        private fun handle(ctx: Context) = PhoneAccountHandle(ComponentName(ctx, SpeakerCallService::class.java), "watch-speaker")

        /** Start the pretend call. Returns a line for the UI. */
        fun start(ctx: Context): String {
            val tm = ctx.getSystemService(TelecomManager::class.java)
            val h = handle(ctx)
            tm.registerPhoneAccount(
                PhoneAccount.builder(h, "Watch speaker").setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED).build(),
            )
            val extras = Bundle().apply { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, h) }
            return try {
                tm.placeCall(Uri.fromParts("tel", "watch-speaker", null), extras)
                "Call started. Play something and listen on the watch (details in Log)."
            } catch (e: SecurityException) {
                "Telecom refused: ${e.message}"
            }.also { DebugLog.info("speaker call: $it (sdk ${Build.VERSION.SDK_INT})") }
        }

        fun stop() {
            current?.end()
        }
    }
}
