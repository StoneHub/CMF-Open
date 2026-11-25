package dev.cmfopen.companion

import android.content.Context
import dev.cmfopen.protocol.Bytes
import dev.cmfopen.protocol.WatchButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** App settings and per-watch auth keys, in app-private SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("companion", Context.MODE_PRIVATE)

    /** Bumped on every write so Compose screens can recompose. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        sp.edit().apply(block).apply()
        _version.value++
    }

    var deviceAddress: String?
        get() = sp.getString("device_address", null)
        set(v) = edit { putString("device_address", v) }

    var deviceName: String?
        get() = sp.getString("device_name", null)
        set(v) = edit { putString("device_name", v) }

    fun authkey(address: String): ByteArray? = sp.getString("authkey_$address", null)?.let(Bytes::fromHex)
    fun setAuthkey(address: String, key: ByteArray?) = edit {
        if (key == null) remove("authkey_$address") else putString("authkey_$address", Bytes.hex(key))
    }

    /** Keep the connection alive in the background (foreground service). */
    var autoConnect: Boolean
        get() = sp.getBoolean("auto_connect", true)
        set(v) = edit { putBoolean("auto_connect", v) }

    // -- notifications ------------------------------------------------------

    var forwardNotifications: Boolean
        get() = sp.getBoolean("forward_notifications", true)
        set(v) = edit { putBoolean("forward_notifications", v) }

    /** true: only apps in [notifyApps] are forwarded. false: everything except [notifyApps]. */
    var notifyAllowlist: Boolean
        get() = sp.getBoolean("notify_allowlist", false)
        set(v) = edit { putBoolean("notify_allowlist", v) }

    var notifyApps: Set<String>
        get() = sp.getStringSet("notify_apps", DEFAULT_MUTED) ?: DEFAULT_MUTED
        set(v) = edit { putStringSet("notify_apps", v) }

    fun appForwarded(pkg: String) = if (notifyAllowlist) pkg in notifyApps else pkg !in notifyApps

    fun setAppForwarded(pkg: String, forwarded: Boolean) {
        val listed = if (notifyAllowlist) forwarded else !forwarded
        notifyApps = if (listed) notifyApps + pkg else notifyApps - pkg
    }

    /** Skip notifications while the phone screen is on and unlocked. */
    var skipWhenPhoneInUse: Boolean
        get() = sp.getBoolean("skip_in_use", false)
        set(v) = edit { putBoolean("skip_in_use", v) }

    // -- music -----------------------------------------------------------------

    var mirrorMedia: Boolean
        get() = sp.getBoolean("mirror_media", true)
        set(v) = edit { putBoolean("mirror_media", v) }

    /** Raw MUSIC_BUTTON code -> button, learned on the real watch. Defaults match the simulator. */
    var buttonCodes: Map<Int, WatchButton.Named>
        get() {
            val raw = sp.getString("button_codes", null) ?: return WatchButton.DEFAULT_CODES
            val o = JSONObject(raw)
            return o.keys().asSequence().mapNotNull { k ->
                WatchButton.Named.entries.firstOrNull { it.id == o.getString(k) }?.let { k.toInt() to it }
            }.toMap()
        }
        set(v) = edit { putString("button_codes", JSONObject(v.mapKeys { it.key.toString() }.mapValues { it.value.id }).toString()) }

    // -- weather ---------------------------------------------------------------

    var weatherEnabled: Boolean
        get() = sp.getBoolean("weather", true)
        set(v) = edit { putBoolean("weather", v) }

    /** City to use instead of the phone's location. Blank = use location. */
    var weatherCity: String
        get() = sp.getString("weather_city", "") ?: ""
        set(v) = edit { putString("weather_city", v.trim()) }

    // -- watch apps ------------------------------------------------------------

    var activeApp: String?
        get() = sp.getString("active_app", null)
        set(v) = edit { putString("active_app", v) }

    fun appSettings(id: String): JSONObject = JSONObject(sp.getString("app_settings_$id", "{}") ?: "{}")
    fun setAppSettings(id: String, v: JSONObject) = edit { putString("app_settings_$id", v.toString()) }

    fun appStorage(id: String): JSONObject = JSONObject(sp.getString("app_storage_$id", "{}") ?: "{}")
    fun setAppStorage(id: String, v: JSONObject) = edit { putString("app_storage_$id", v.toString()) }

    companion object {
        /** Muted by default in block-list mode: system chatter that would buzz the wrist all day. */
        val DEFAULT_MUTED = setOf(
            "android", "com.android.systemui", "com.android.providers.downloads", "com.google.android.gms",
            "com.android.vending", "com.google.android.apps.photos", "com.nothing.smartcenter",
        )
    }
}
