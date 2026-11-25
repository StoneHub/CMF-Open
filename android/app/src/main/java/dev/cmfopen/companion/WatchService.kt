package dev.cmfopen.companion

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.cmfopen.companion.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process (and so the BLE link) alive while the phone is in a
 * pocket. The connection logic itself lives in [WatchManager].
 */
class WatchService : Service() {
    private val watch get() = CompanionApp.instance.watch
    private var statusJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED ->
                    watch.launchWithSession("time sync") { it.syncTime() }
                BluetoothAdapter.ACTION_STATE_CHANGED ->
                    if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) watch.start()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        registerReceiver(receiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = buildNotification("Starting…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
        watch.start()
        if (statusJob == null) {
            statusJob = watch.scope.launch {
                combine(watch.state, CompanionApp.instance.prefs.version) { s, _ -> statusText(s) }
                    .distinctUntilChanged()
                    .collect { text ->
                        getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
                    }
            }
        }
        return START_STICKY
    }

    private fun statusText(s: WatchManager.State): String {
        val name = CompanionApp.instance.prefs.deviceName ?: "Watch"
        return when (s.phase) {
            WatchManager.Phase.CONNECTED -> "$name connected" + (s.battery?.let { " · $it%" } ?: "")
            WatchManager.Phase.CONNECTING -> "Connecting to $name…"
            WatchManager.Phase.PAIRING -> "Pairing with $name…"
            WatchManager.Phase.AWAITING_ACCEPT -> "Accept the pairing request on your watch"
            WatchManager.Phase.WAITING_RETRY -> "$name out of reach, retrying"
            WatchManager.Phase.NEEDS_PAIRING -> "Open the app to pair $name again"
            WatchManager.Phase.IDLE -> "Idle"
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CompanionApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentTitle(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        statusJob?.cancel()
        statusJob = null
        runCatching { unregisterReceiver(receiver) }
        watch.stop()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val app = CompanionApp.instance
            if (app.prefs.deviceAddress == null || !app.watch.hasBluetoothPermission()) return
            runCatching { context.startForegroundService(Intent(context, WatchService::class.java)) }
                .onFailure { DebugLog.warn("could not start service: ${it.message}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
        }
    }
}
