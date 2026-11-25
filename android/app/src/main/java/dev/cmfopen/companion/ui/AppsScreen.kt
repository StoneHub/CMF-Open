package dev.cmfopen.companion.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cmfopen.companion.CompanionApp
import dev.cmfopen.companion.apps.AppRuntime
import org.json.JSONObject

/** The web store's watch apps, running on the phone. One at a time drives the watch. */
@Composable
fun AppsScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val runtime = CompanionApp.instance.watch.apps
    val running by runtime.running.collectAsStateWithLifecycle()
    val logs by runtime.logs.collectAsStateWithLifecycle()
    val watchState by CompanionApp.instance.watch.state.collectAsStateWithLifecycle()
    var catalogVersion by remember { mutableStateOf(0) }
    val catalog = remember(catalogVersion) { runtime.catalog() }
    var editing by remember { mutableStateOf(false) }
    var importNote by remember { mutableStateOf<String?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        importNote = runCatching {
            val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "app.js"
            val src = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            runtime.import(name, src)
            catalogVersion++
            "Imported $name"
        }.getOrElse { it.message }
    }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val r = running
                Section(if (r == null) "Watch apps" else "Running: ${r.name}") {
                    if (r == null) {
                        Hint("Apps draw on the watch's music card, notifications, weather tile and contacts list, and react to the music buttons. While one runs, it takes over the music card.")
                    } else {
                        if (r.buttons.isNotEmpty()) Hint(r.buttons.entries.joinToString("\n") { "${it.key}: ${it.value}" })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { runtime.stop() }) { Text("Stop") }
                            if (r.settings.isNotEmpty()) OutlinedButton(onClick = { editing = true }) { Text("Settings") }
                        }
                        if (logs.isNotEmpty()) Text(logs.takeLast(6).joinToString("\n"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (watchState.phase != dev.cmfopen.companion.WatchManager.Phase.CONNECTED) Hint("Connect the watch to run apps.")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Library", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text("Import .js") }
                }
                importNote?.let { Hint(it) }
            }
        }
        items(catalog, key = { it.id }) { e -> AppRow(e, running?.id == e.id, onRun = { runtime.run(e) }, onDelete = { runtime.delete(e); catalogVersion++ }) }
        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp)) }
    }

    val r = running
    if (editing && r != null) SettingsDialog(r, runtime.settingsFor(r.id), onDismiss = { editing = false }) {
        editing = false
        runtime.saveSettings(r.id, it)
    }
}

@Composable
private fun AppRow(e: AppRuntime.Entry, isRunning: Boolean, onRun: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(enabled = !isRunning, onClick = onRun)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(e.icon.ifBlank { "🧩" }, fontSize = 26.sp, modifier = Modifier.width(44.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name + if (isRunning) " · running" else "", fontWeight = FontWeight.SemiBold)
                Hint(e.description)
            }
            if (!e.builtIn) TextButton(onClick = onDelete) { Text("Remove") }
        }
    }
}

@Composable
private fun SettingsDialog(r: AppRuntime.Running, current: JSONObject, onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    val values = remember { mutableStateMapOf<String, Any?>().apply { r.settings.forEach { put(it.key, current.opt(it.key) ?: it.default) } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${r.name} settings") },
        confirmButton = {
            TextButton(onClick = {
                val out = JSONObject()
                r.settings.forEach { s ->
                    val v = values[s.key]
                    out.put(s.key, if (s.type == "number") (v?.toString()?.toDoubleOrNull() ?: 0.0) else v)
                }
                onSave(out)
            }) { Text("Save and restart") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(r.settings, key = { it.key }) { s ->
                    if (s.type == "boolean") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.label, Modifier.weight(1f))
                            Switch(checked = values[s.key] == true, onCheckedChange = { values[s.key] = it })
                        }
                    } else {
                        OutlinedTextField(
                            value = values[s.key]?.let { v -> if (v is Double && v % 1.0 == 0.0) v.toLong().toString() else v.toString() } ?: "",
                            onValueChange = { values[s.key] = it },
                            label = { Text(s.label) },
                            singleLine = s.type != "textarea",
                            supportingText = s.help?.let { h -> { Text(h) } },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
    )
}
