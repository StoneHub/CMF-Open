package dev.cmfopen.companion.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cmfopen.companion.DebugLog

/** Protocol log: every frame in and out, unknown messages, and button codes. */
@Composable
fun LogScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val lines by DebugLog.lines.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.size - 1) }
    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, DebugLog.dump())
                ctx.startActivity(Intent.createChooser(send, "Share log"))
            }) { Text("Share") }
            OutlinedButton(onClick = { DebugLog.clear() }) { Text("Clear") }
        }
        Hint("Also in logcat: adb logcat -s CmfCompanion")
        LazyColumn(state = list, modifier = Modifier.weight(1f)) {
            items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 13.sp) }
        }
    }
}
