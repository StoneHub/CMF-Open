package dev.cmfopen.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reconnect after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (CompanionApp.instance.prefs.autoConnect) WatchService.start(context)
    }
}
