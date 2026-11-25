package dev.cmfopen.companion

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory protocol log shown on the Log screen and shareable for debugging.
 * Also mirrored to logcat under the "CmfCompanion" tag, so `adb logcat -s CmfCompanion` works.
 */
object DebugLog {
    private const val MAX = 600
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun add(dir: String, text: String) {
        val line = "${fmt.format(Date())} $dir $text"
        Log.i("CmfCompanion", "$dir $text")
        synchronized(this) { _lines.value = (_lines.value + line).takeLast(MAX) }
    }

    fun info(text: String) = add("..", text)
    fun warn(text: String) = add("!!", text)

    fun clear() = synchronized(this) { _lines.value = emptyList() }
    fun dump(): String = _lines.value.joinToString("\n")
}
