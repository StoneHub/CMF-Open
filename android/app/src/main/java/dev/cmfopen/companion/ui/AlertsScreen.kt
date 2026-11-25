package dev.cmfopen.companion.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cmfopen.companion.CompanionApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class InstalledApp(val pkg: String, val label: String)

@Composable
fun AlertsScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val app = CompanionApp.instance
    val prefs = app.prefs
    @Suppress("UNUSED_VARIABLE") val v by prefs.version.collectAsStateWithLifecycle()
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName }
                .plus(prefs.notifyApps)
                .distinct()
                .filter { it != ctx.packageName }
                .map { pkg -> InstalledApp(pkg, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)) }
                .sortedBy { it.label.lowercase() }
        }
    }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Section("Notifications") {
                    ToggleRow("Send phone notifications to the watch", prefs.forwardNotifications, "Texts, chats, calls, email: any app you allow below.") {
                        prefs.forwardNotifications = it
                    }
                    ToggleRow("Not while I'm using the phone", prefs.skipWhenPhoneInUse, "Skip notifications while the phone is unlocked and on.") {
                        prefs.skipWhenPhoneInUse = it
                    }
                    Hint("The watch shows a 20-byte title and 128-byte message, so long texts are cut.")
                    OutlinedButton(onClick = {
                        app.watch.enqueueNotification("CMF Companion", "Test notification. If you can read this, forwarding works.")
                    }) { Text("Send a test") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !prefs.notifyAllowlist, onClick = { prefs.notifyAllowlist = false }, label = { Text("All apps except muted") })
                    FilterChip(selected = prefs.notifyAllowlist, onClick = { prefs.notifyAllowlist = true }, label = { Text("Only apps I pick") })
                }
                OutlinedTextField(query, { query = it }, label = { Text("Search apps") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (apps.isEmpty()) Hint("Loading apps…")
            }
        }
        items(apps.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }, key = { it.pkg }) { a ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(a.label)
                    Hint(a.pkg)
                }
                Switch(checked = prefs.appForwarded(a.pkg), onCheckedChange = { prefs.setAppForwarded(a.pkg, it) })
            }
        }
    }
}
