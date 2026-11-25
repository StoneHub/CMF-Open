package dev.cmfopen.companion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class CompanionApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var watch: WatchManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, "Watch connection", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Find my phone", NotificationManager.IMPORTANCE_HIGH))
        watch = WatchManager(this, prefs)
    }

    companion object {
        const val CHANNEL_SERVICE = "service"
        const val CHANNEL_ALERTS = "alerts"
        lateinit var instance: CompanionApp
            private set
    }
}
