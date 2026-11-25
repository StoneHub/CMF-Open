package dev.cmfopen.companion

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper

/** "Find my phone" from the watch: ring loudly (even on silent) until stopped or 45 s pass. */
class PhoneRinger(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private val audio = context.getSystemService(AudioManager::class.java)
    private val autoStop = Runnable { stop() }

    val ringing get() = player != null

    fun toggle() = main.post { if (player != null) stop() else ring() }

    fun ring() = main.post {
        if (player != null) return@post
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { DebugLog.warn("ringer: ${it.message}") }.getOrNull()
        showNotification()
        main.postDelayed(autoStop, 45_000)
        DebugLog.info("find phone: ringing")
    }

    fun stop(): Boolean = main.post {
        main.removeCallbacks(autoStop)
        player?.runCatching { stop(); release() }
        player = null
        if (savedVolume >= 0) audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
        savedVolume = -1
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun showNotification() {
        val stopIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, StopRingingReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, CompanionApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentTitle("Your watch is looking for this phone")
            .setContentText("Tap to stop ringing")
            .setContentIntent(stopIntent)
            .setDeleteIntent(stopIntent)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .setCategory(Notification.CATEGORY_ALARM)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n) }
    }

    companion object {
        private const val NOTIFICATION_ID = 2
    }
}

class StopRingingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CompanionApp.instance.watch.ringer.stop()
    }
}
