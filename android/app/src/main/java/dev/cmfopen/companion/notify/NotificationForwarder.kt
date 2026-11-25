package dev.cmfopen.companion.notify

import android.app.KeyguardManager
import android.app.Notification
import android.os.Bundle
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.cmfopen.companion.CompanionApp
import dev.cmfopen.companion.DebugLog
import dev.cmfopen.protocol.Payloads

/**
 * Forwards phone notifications (texts, chats, calls, email, anything) to the watch, filtered per
 * app. Being a notification listener also lets [dev.cmfopen.companion.media.MediaBridge] see the
 * phone's media sessions.
 */
class NotificationForwarder : NotificationListenerService() {
    private val recent = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 200
    }

    override fun onListenerConnected() {
        DebugLog.info("notification access active")
        CompanionApp.instance.watch.media.start()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = CompanionApp.instance
        val prefs = app.prefs
        if (!prefs.forwardNotifications || !app.watch.connected) return
        if (sbn.packageName == packageName || !prefs.appForwarded(sbn.packageName)) return
        val n = sbn.notification
        val isCall = n.category == Notification.CATEGORY_CALL
        if (!isCall) {
            if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            if (n.category in SKIP_CATEGORIES) return
            if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return
        }
        val content = extract(n.extras) ?: return
        val dedupeKey = "${sbn.key}|${content.first}|${content.second}"
        val now = System.currentTimeMillis()
        synchronized(recent) {
            val last = recent[dedupeKey]
            if (last != null && now - last < DEDUPE_MS) return
            recent[dedupeKey] = now
        }
        if (prefs.skipWhenPhoneInUse && phoneInUse()) return

        val label = appLabel(sbn.packageName)
        val icon = ICONS[sbn.packageName] ?: Payloads.Icons.GENERIC
        val (title, text) = content
        val watchTitle = when {
            isCall -> "Call: ${title.ifBlank { label }}"
            title.isBlank() -> label
            else -> title
        }
        // The generic icon doesn't say which app this is, so lead the body with its name.
        val body = if (icon == Payloads.Icons.GENERIC && !isCall && label != watchTitle) "$label: $text" else text
        app.watch.enqueueNotification(watchTitle, body, icon)
    }

    /** (title, text) from a notification's extras, preferring the newest chat message. */
    private fun extract(extras: Bundle): Pair<String, String>? {
        val title = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty()
        var text = ""
        @Suppress("DEPRECATION")
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        val last = messages?.lastOrNull() as? Bundle
        if (last != null) {
            val msg = last.getCharSequence("text")?.toString().orEmpty()
            val sender = last.getCharSequence("sender")?.toString()
            text = if (sender != null && sender != title && extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null) "$sender: $msg" else msg
        }
        if (text.isBlank()) {
            text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        }
        if (title.isBlank() && text.isBlank()) return null
        return title.trim() to text.trim().replace(Regex("\\s+"), " ")
    }

    private fun phoneInUse(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(KeyguardManager::class.java)
        return pm.isInteractive && !km.isKeyguardLocked
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        private const val DEDUPE_MS = 5 * 60_000L
        private val SKIP_CATEGORIES = setOf(
            Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE, Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_SYSTEM, Notification.CATEGORY_STATUS,
        )
        val ICONS = mapOf(
            "com.whatsapp" to Payloads.Icons.WHATSAPP,
            "com.whatsapp.w4b" to Payloads.Icons.WHATSAPP,
            "org.telegram.messenger" to Payloads.Icons.TELEGRAM,
            "com.instagram.android" to Payloads.Icons.INSTAGRAM,
            "com.google.android.gm" to Payloads.Icons.GMAIL,
        )
    }
}
