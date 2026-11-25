package dev.cmfopen.companion.media

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import dev.cmfopen.companion.DebugLog
import dev.cmfopen.companion.Prefs
import dev.cmfopen.companion.WatchManager
import dev.cmfopen.companion.notify.NotificationForwarder
import dev.cmfopen.protocol.Payloads
import dev.cmfopen.protocol.WatchButton

/**
 * Mirrors the phone's now-playing (YouTube, podcasts, Spotify, …) onto the watch's music card and
 * turns the card's buttons back into media controls. Needs notification access.
 */
class MediaBridge(private val context: Context, private val prefs: Prefs, private val watch: WatchManager) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, NotificationForwarder::class.java)
    private var controller: MediaController? = null
    private var started = false
    private var lastSent: String? = null
    private val pushRunnable = Runnable { pushNow(false) }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = schedulePush()
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // Another app may have started playing; prefer whichever is playing now.
            if (state?.state != PlaybackState.STATE_PLAYING) runCatching { pick(msm.getActiveSessions(component)) }
            schedulePush()
        }
        override fun onSessionDestroyed() {
            main.post { runCatching { pick(msm.getActiveSessions(component)) } }
        }
    }

    /** Begin tracking media sessions. No-op without notification access. */
    fun start() = main.post {
        if (started) return@post
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            pick(msm.getActiveSessions(component))
            started = true
        } catch (e: SecurityException) {
            DebugLog.warn("media: needs notification access")
        }
    }

    private fun pick(list: List<MediaController>) {
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = best
        best?.registerCallback(callback, main)
        DebugLog.info("media: following ${best?.packageName ?: "nothing"}")
        schedulePush()
    }

    private fun schedulePush() {
        main.removeCallbacks(pushRunnable)
        main.postDelayed(pushRunnable, 300)
    }

    /** Send the current track to the watch (force: even if unchanged, e.g. after reconnect). */
    fun push(force: Boolean = false) = main.post { pushNow(force) }

    private fun pushNow(force: Boolean) {
        if (!prefs.mirrorMedia || watch.apps.active || !watch.connected) return
        val c = controller
        val md = c?.metadata
        val track = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "Nothing playing"
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: appName(c?.packageName)
        val state = when (c?.playbackState?.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> Payloads.MUSIC_PLAYING
            null, PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED -> Payloads.MUSIC_STOPPED
            else -> Payloads.MUSIC_PAUSED
        }
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 16 / max
        val key = "$track|$artist|$state|$vol"
        if (!force && key == lastSent) return
        lastSent = key
        watch.launchWithSession("music") { it.setMusic(track, artist, state, vol, 16) }
    }

    private fun appName(pkg: String?) = pkg?.let {
        runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(it, 0)).toString() }.getOrNull()
    } ?: ""

    /** A music-card button pressed on the watch. */
    fun handle(b: WatchButton) = main.post {
        val c = controller
        val t = c?.transportControls
        when (b) {
            // The real code map is unconfirmed, so play and pause both toggle: correct either way.
            WatchButton.Named.PLAY, WatchButton.Named.PAUSE ->
                if (t == null) mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                else if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            WatchButton.Named.NEXT -> t?.skipToNext() ?: mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            WatchButton.Named.PREV -> t?.skipToPrevious() ?: mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            WatchButton.Named.VOL_UP -> volume(AudioManager.ADJUST_RAISE)
            WatchButton.Named.VOL_DOWN -> volume(AudioManager.ADJUST_LOWER)
            else -> DebugLog.info("media: no action for ${b.id}")
        }
    }

    private fun volume(direction: Int) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        schedulePush()
    }

    private fun mediaKey(code: Int) {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }
}
