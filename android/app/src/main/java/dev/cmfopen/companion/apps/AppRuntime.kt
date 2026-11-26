package dev.cmfopen.companion.apps

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import dev.cmfopen.companion.DebugLog
import dev.cmfopen.companion.Prefs
import dev.cmfopen.companion.WatchManager
import dev.cmfopen.protocol.Payloads
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Runs the web store's watch apps on the phone, unchanged, in a hidden WebView. One app at a
 * time owns the watch's music card, notification, weather tile and contacts list, and gets the
 * music buttons, exactly like the browser version.
 */
class AppRuntime(private val context: Context, private val prefs: Prefs, private val watch: WatchManager) {
    data class Entry(val id: String, val name: String, val icon: String, val description: String, val builtIn: Boolean, val source: () -> String)
    data class Setting(val key: String, val label: String, val type: String, val default: Any?, val help: String?)
    data class Running(val id: String, val name: String, val buttons: Map<String, String>, val settings: List<Setting>, val tickS: Double?)

    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var ready = false
    private val onReady = ArrayList<() -> Unit>()
    private var tickJob: Job? = null
    private val lastDrawn = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val _running = MutableStateFlow<Running?>(null)
    val running: StateFlow<Running?> = _running.asStateFlow()
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    val active get() = _running.value != null
    /** True while the running app has drawn on the weather tile, so weather sync stands aside. */
    @Volatile var usesWeatherTile = false
        private set

    private val userDir get() = File(context.filesDir, "apps").apply { mkdirs() }

    fun catalog(): List<Entry> {
        val out = ArrayList<Entry>()
        runCatching {
            val index = JSONObject(context.assets.open("apps/index.json").bufferedReader().readText()).getJSONArray("apps")
            for (i in 0 until index.length()) {
                val a = index.getJSONObject(i)
                val entry = a.getString("entry")
                out += Entry(a.getString("id"), a.getString("name"), a.optString("icon"), a.optString("description"), true) {
                    context.assets.open("apps/$entry").bufferedReader().readText()
                }
            }
        }.onFailure { DebugLog.warn("apps: no bundled catalog (${it.message})") }
        userDir.listFiles { f -> f.extension == "js" }?.sortedBy { it.name }?.forEach { f ->
            val src = f.readText()
            val id = Regex("""id:\s*['"]([a-z0-9-]+)['"]""").find(src)?.groupValues?.get(1) ?: f.nameWithoutExtension
            val name = Regex("""name:\s*['"]([^'"]+)['"]""").find(src)?.groupValues?.get(1) ?: f.nameWithoutExtension
            val icon = Regex("""icon:\s*['"]([^'"]+)['"]""").find(src)?.groupValues?.get(1) ?: "🧩"
            val desc = Regex("""description:\s*['"]([^'"]+)['"]""").find(src)?.groupValues?.get(1) ?: "Imported app"
            out.removeAll { it.id == id }
            out += Entry(id, name, icon, desc, false) { f.readText() }
        }
        return out
    }

    /** Save an app module the user picked (a .js file written for the web store). */
    fun import(fileName: String, source: String): String {
        require(Regex("""export\s+const\s+manifest""").containsMatchIn(source)) {
            "That file doesn't look like a watch app (no `export const manifest`)."
        }
        val safe = fileName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.endsWith(".js")) it else "$it.js" }
        File(userDir, safe).writeText(source)
        return safe
    }

    fun delete(entry: Entry) {
        if (entry.builtIn) return
        userDir.listFiles()?.filter { Regex("""id:\s*['"]${Regex.escape(entry.id)}['"]""").containsMatchIn(it.readText()) }?.forEach { it.delete() }
    }

    // -- run / stop ------------------------------------------------------------

    fun run(entry: Entry) {
        stop {
            prefs.activeApp = entry.id
            lastDrawn.clear()
            usesWeatherTile = false
            _logs.value = emptyList()
            val source = runCatching { entry.source() }.getOrElse {
                appLog("error", "could not read app: ${it.message}")
                return@stop
            }
            _running.value = Running(entry.id, entry.name, emptyMap(), emptyList(), null)
            withWeb { w ->
                val settings = prefs.appSettings(entry.id)
                val storage = prefs.appStorage(entry.id)
                w.evaluateJavascript("cmf.load(${JSONObject.quote(source)}, $settings, $storage)", null)
            }
        }
    }

    /** Stop the running app (its stop() gets a moment to run), then hand the music card back. */
    fun stop(then: (() -> Unit)? = null) = main.post {
        tickJob?.cancel()
        val wasRunning = _running.value != null
        _running.value = null
        usesWeatherTile = false
        if (!wasRunning) {
            then?.invoke()
            return@post
        }
        prefs.activeApp = null
        web?.evaluateJavascript("cmf.stop()", null)
        main.postDelayed({
            destroyWeb()
            if (then != null) then() else {
                watch.media.push(force = true)
                watch.weather.refreshSoon()
                watch.contacts.syncSoon()
            }
        }, 800)
    }

    /** Resume the app that was running before the process died. */
    fun restore() {
        val id = prefs.activeApp ?: return
        catalog().firstOrNull { it.id == id }?.let(::run)
    }

    fun press(button: String) = main.post { web?.evaluateJavascript("cmf.button(${JSONObject.quote(button)})", null) }

    /** Redraw after a reconnect: forget what was drawn so the next calls go out again. */
    fun redraw() = main.post {
        lastDrawn.clear()
        web?.evaluateJavascript("cmf.tick()", null)
    }

    fun saveSettings(id: String, values: JSONObject) {
        prefs.setAppSettings(id, values)
        catalog().firstOrNull { it.id == id }?.let(::run)
    }

    fun settingsFor(id: String): JSONObject {
        val r = _running.value
        val saved = prefs.appSettings(id)
        if (r?.id == id) r.settings.forEach { s -> if (!saved.has(s.key) && s.default != null) saved.put(s.key, s.default) }
        return saved
    }

    // -- WebView plumbing --------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun withWeb(block: (WebView) -> Unit) = main.post {
        val existing = web
        if (existing != null && ready) return@post block(existing)
        onReady += { block(web!!) }
        if (existing != null) return@post
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        web = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            addJavascriptInterface(Bridge(), "CmfHost")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    appLog("console", m.message())
                    return true
                }
            }
            loadUrl("https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/runtime/host.html")
        }
    }

    private fun destroyWeb() {
        ready = false
        onReady.clear()
        web?.destroy()
        web = null
    }

    private fun appLog(level: String, text: String) {
        DebugLog.add("app", "[$level] $text")
        synchronized(this) { _logs.value = (_logs.value + "[$level] $text").takeLast(100) }
    }

    private fun startTicks(seconds: Double?) {
        tickJob?.cancel()
        val every = seconds ?: return
        tickJob = watch.scope.launch {
            while (isActive) {
                delay((maxOf(every, 0.25) * 1000).toLong())
                main.post { web?.evaluateJavascript("cmf.tick()", null) }
            }
        }
    }

    private fun parseManifest(json: String): Running? {
        if (json == "null") return null
        val m = JSONObject(json)
        val buttons = m.optJSONObject("buttons")?.let { b -> b.keys().asSequence().associateWith { b.optString(it) } } ?: emptyMap()
        val settings = m.optJSONArray("settings")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                Setting(it.getString("key"), it.optString("label", it.getString("key")), it.optString("type", "text"), it.opt("default"), it.optString("help").ifBlank { null })
            }
        } ?: emptyList()
        // tickInterval: missing -> 1 s (web default), null -> no ticks.
        val tick = when {
            !m.has("tickInterval") -> 1.0
            m.isNull("tickInterval") -> null
            else -> m.getDouble("tickInterval").takeIf { it > 0 }
        }
        return Running(m.optString("id"), m.optString("name"), buttons, settings, tick)
    }

    private suspend fun apply(call: String, args: JSONArray) {
        fun changed(v: String): Boolean = (lastDrawn.put(call, v) != v)
        when (call) {
            "status" -> if (changed(args.toString())) {
                watch.withSession("app status") {
                    it.setMusic(args.getString(0), args.getString(1), if (args.optBoolean(2, true)) Payloads.MUSIC_PLAYING else Payloads.MUSIC_PAUSED)
                }
            }
            "card" -> watch.withSession("app card") { it.notify(args.getString(0), args.getString(1)) }
            "ticker" -> if (changed(args.toString())) {
                usesWeatherTile = true
                watch.withSession("app ticker") { it.setWeather(Payloads.weather(args.getString(0), args.getInt(1))) }
            }
            "menu" -> if (changed(args.toString())) {
                val rows = args.getJSONArray(0)
                val contacts = (0 until minOf(rows.length(), Payloads.CONTACTS_MAX)).map {
                    val r = rows.getJSONArray(it)
                    Payloads.Contact(r.getString(0), r.optString(1))
                }
                watch.withSession("app menu") { it.setContacts(contacts) }
            }
            "buzz" -> watch.withSession("app buzz") { it.buzz() }
        }
    }

    /** Called by host.html. Methods run on a WebView binder thread. */
    private inner class Bridge {
        @JavascriptInterface fun ready() = main.post {
            ready = true
            val q = onReady.toList()
            onReady.clear()
            q.forEach { it() }
        }.let { }

        @JavascriptInterface fun loaded(manifestJson: String) {
            val r = runCatching { parseManifest(manifestJson) }.getOrNull()
            if (r == null) {
                appLog("error", "the module doesn't export a manifest")
                stop()
                return
            }
            _running.value = r
            appLog("info", "started ${r.name}")
            main.post {
                web?.evaluateJavascript("cmf.start()", null)
                startTicks(r.tickS)
            }
        }

        @JavascriptInterface fun screen(id: Int, json: String) {
            watch.scope.launch {
                val err = runCatching {
                    val o = JSONObject(json)
                    if (!watch.connected) throw IllegalStateException("watch not connected")
                    apply(o.getString("call"), o.getJSONArray("args"))
                }.exceptionOrNull()?.message
                val errJs = if (err == null) "undefined" else JSONObject.quote(err)
                main.post { web?.evaluateJavascript("cmf.ack($id, $errJs)", null) }
            }
        }

        @JavascriptInterface fun storage(key: String, valueJson: String) {
            val id = _running.value?.id ?: return
            val s = prefs.appStorage(id)
            s.put(key, JSONObject("{\"v\":$valueJson}").get("v"))
            prefs.setAppStorage(id, s)
        }

        @JavascriptInterface fun log(level: String, text: String) = appLog(level, text)

        @JavascriptInterface fun error(message: String, fatal: Boolean) {
            appLog("error", message)
            if (fatal) stop()
        }

        @JavascriptInterface fun exit() {
            appLog("info", "app exited")
            stop()
        }

        @JavascriptInterface fun stopped() = Unit
    }
}
