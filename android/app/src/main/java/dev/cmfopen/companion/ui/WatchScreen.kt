package dev.cmfopen.companion.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cmfopen.companion.CompanionApp
import dev.cmfopen.companion.WatchManager.Phase
import dev.cmfopen.companion.ble.FoundWatch
import dev.cmfopen.companion.ble.WatchScanner
import dev.cmfopen.companion.media.SpeakerExperiment
import dev.cmfopen.protocol.Payloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun WatchScreen(modifier: Modifier) {
    val app = CompanionApp.instance
    val prefs = app.prefs
    val watch = app.watch
    val state by watch.state.collectAsStateWithLifecycle()
    @Suppress("UNUSED_VARIABLE") val v by prefs.version.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section(prefs.deviceName ?: "No watch yet") {
            val status = when (state.phase) {
                Phase.IDLE -> if (prefs.deviceAddress == null) "Choose your watch to get started." else "Not connected"
                Phase.CONNECTING -> "Connecting…"
                Phase.PAIRING -> "Pairing…"
                Phase.AWAITING_ACCEPT -> "Accept the request on your watch screen"
                Phase.CONNECTED -> "Connected"
                Phase.WAITING_RETRY -> "Out of reach, retrying" + (state.retryInS?.let { " in ${it}s" } ?: "")
                Phase.NEEDS_PAIRING -> "Needs pairing again"
            }
            Text(status, fontWeight = FontWeight.SemiBold)
            if (state.phase == Phase.CONNECTED) {
                Text(listOfNotNull(state.battery?.let { "Battery $it%" + if (state.charging) " (charging)" else "" }, state.firmware?.let { "firmware $it" }).joinToString(" · "))
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (state.phase == Phase.CONNECTING || state.phase == Phase.WAITING_RETRY) {
                Hint("Only one app can talk to the watch at a time. If Nothing X is installed, force-stop it or turn off its Bluetooth access.")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picking = true }) { Text(if (prefs.deviceAddress == null) "Choose watch" else "Change") }
                if (state.phase == Phase.CONNECTED) OutlinedButton(onClick = { watch.findWatch() }) { Text("Find watch") }
                if (prefs.deviceAddress != null && state.phase != Phase.CONNECTED) OutlinedButton(onClick = { watch.start() }) { Text("Retry now") }
            }
            if (prefs.deviceAddress != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { watch.pairAgain() }) { Text("Pair again") }
                    TextButton(onClick = { watch.forget() }) { Text("Forget watch") }
                }
            }
        }

        SetupSection()
        MusicSection(state.lastButton)
        WeatherSection()
        FacesSection(state.phase == Phase.CONNECTED)
        SpeakerSection()
    }

    if (picking) PickWatchDialog(onDismiss = { picking = false }) { device, name ->
        picking = false
        watch.select(device, name)
    }
}

// -- setup -------------------------------------------------------------------

private fun Context.granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

@Composable
private fun SetupSection() {
    val ctx = LocalContext.current
    val app = CompanionApp.instance
    var refresh by remember { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++
        app.watch.start()
    }
    val btPerms = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    if (refresh < 0) return // read `refresh` so the checks below re-run when it changes
    val btOk = btPerms.all { ctx.granted(it) }
    val postOk = Build.VERSION.SDK_INT < 33 || ctx.granted(Manifest.permission.POST_NOTIFICATIONS)
    val listenerOk = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    val locOk = ctx.granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    val batteryOk = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    if (btOk && postOk && listenerOk && locOk && batteryOk) return

    LaunchedEffect(Unit) {
        // Notification access and battery settings return without a result; poll while visible.
        while (true) {
            delay(1500)
            refresh++
        }
    }
    Section("Finish setup") {
        if (!btOk) SetupRow("Nearby devices (Bluetooth)", "Required to talk to the watch.") { launcher.launch(btPerms) }
        if (!listenerOk) SetupRow("Notification access", "Lets the app forward texts and calls, and see what's playing.") {
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        if (!postOk) SetupRow("Show notifications", "For the connection status and find-my-phone.") {
            launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        if (!batteryOk) SetupRow("Run in background", "Stops Android from cutting the watch connection.") {
            @SuppressLint("BatteryLife")
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            ctx.startActivity(i)
        }
        if (!locOk) SetupRow("Approximate location (optional)", "Local weather on the watch. You can type a city instead.") {
            launcher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
}

@Composable
private fun SetupRow(title: String, help: String, onGrant: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Hint(help)
        }
        TextButton(onClick = onGrant) { Text("Allow") }
    }
}

// -- pick a watch --------------------------------------------------------------

@SuppressLint("MissingPermission")
@Composable
private fun PickWatchDialog(onDismiss: () -> Unit, onPick: (BluetoothDevice, String) -> Unit) {
    val ctx = LocalContext.current
    val found = remember { mutableStateListOf<FoundWatch>() }
    var scanning by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        // Bonded watches first: a watch busy with another phone app may not advertise.
        runCatching {
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
                ?.filter { (it.name ?: "").startsWith("CMF Watch") }
                ?.forEach { found += FoundWatch(it, it.name + " (paired)", 0) }
        }
        withTimeoutOrNull(20_000) {
            WatchScanner.scan(ctx).catch { error = it.message }.collect { w ->
                if (found.none { it.device.address == w.device.address }) found += w
            }
        }
        scanning = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Choose your watch") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (scanning) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                    Text("Looking for CMF watches…")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                found.forEach { w ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(w.device, w.name.removeSuffix(" (paired)")) }.padding(vertical = 6.dp)) {
                        Text(w.name, fontWeight = FontWeight.SemiBold)
                        Hint(w.device.address + if (w.rssi != 0) " · ${w.rssi} dBm" else "")
                    }
                }
                if (!scanning && found.isEmpty()) Hint("No watch found. Make sure it's nearby and not connected to Nothing X.")
            }
        },
    )
}

// -- music ---------------------------------------------------------------------

@Composable
private fun MusicSection(lastButton: String?) {
    val prefs = CompanionApp.instance.prefs
    Section("Music") {
        ToggleRow("Show what's playing on the watch", prefs.mirrorMedia, "YouTube, podcasts, Spotify… The watch's music controls play, skip and change volume.") {
            prefs.mirrorMedia = it
            if (it) CompanionApp.instance.watch.media.push(force = true)
        }
        Hint("Last button from the watch: ${lastButton ?: "none yet"}")
        Hint("If a watch button does the wrong thing, press it and check the code here or in the Log tab.")
    }
}

// -- weather -------------------------------------------------------------------

@Composable
private fun WeatherSection() {
    val app = CompanionApp.instance
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    var city by remember { mutableStateOf(prefs.weatherCity) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf(app.watch.weather.last?.let { "${it.place}: ${it.tempC}°C, ${it.description}" }) }
    Section("Weather") {
        ToggleRow("Send weather to the watch", prefs.weatherEnabled, "From Open-Meteo, every 30 minutes.") { prefs.weatherEnabled = it }
        OutlinedTextField(
            value = city,
            onValueChange = { city = it },
            label = { Text("City (blank = use location)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = !busy, onClick = {
                prefs.weatherCity = city
                busy = true
                scope.launch {
                    note = runCatching {
                        app.watch.weather.syncOnce()
                        app.watch.weather.last?.let { "${it.place}: ${it.tempC}°C, ${it.description}" } ?: "No location yet"
                    }.getOrElse { "Failed: ${it.message}" }
                    busy = false
                }
            }) { Text("Save and send now") }
            if (busy) CircularProgressIndicator(strokeWidth = 2.dp)
        }
        note?.let { Hint(it) }
    }
}

// -- faces ---------------------------------------------------------------------

@Composable
private fun FacesSection(connected: Boolean) {
    val ctx = LocalContext.current
    val watch = CompanionApp.instance.watch
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var dials by remember { mutableStateOf<List<Payloads.Dial>>(emptyList()) }
    var pendingStructured by remember { mutableStateOf<ByteArray?>(null) }

    suspend fun read(uri: Uri): ByteArray? = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val blob = read(uri) ?: return@launch
            progress = 0f
            note = "Uploading ${blob.size / 1024} KB…"
            val ok = watch.withSession("photo face") { s -> s.uploadPhotoDial(blob) { done, total -> progress = done.toFloat() / total } }
            progress = null
            note = when (ok) { true -> "Face installed."; false -> "The watch rejected that face."; null -> "Upload failed (see Log)." }
        }
    }
    val structuredPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            pendingStructured = read(uri)
            dials = watch.withSession("list faces") { it.listDials() } ?: emptyList()
            if (dials.isEmpty()) note = "Couldn't read the installed faces."
        }
    }

    Section("Watch faces") {
        Hint("Install faces you made in the CMF Open face editor (.bin files).")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = connected && progress == null, onClick = { photoPicker.launch(arrayOf("*/*")) }) { Text("Photo face") }
            OutlinedButton(enabled = connected && progress == null, onClick = { structuredPicker.launch(arrayOf("*/*")) }) { Text("Structured face") }
        }
        progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        note?.let { Hint(it) }
    }

    val blob = pendingStructured
    if (blob != null && dials.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingStructured = null },
            confirmButton = { TextButton(onClick = { pendingStructured = null }) { Text("Cancel") } },
            title = { Text("Replace which face?") },
            text = {
                Column {
                    Hint("A structured face replaces one that's installed.")
                    dials.forEach { d ->
                        Text(
                            "Face ${d.id} (type ${d.type})" + if (d.active) " · current" else "",
                            Modifier.fillMaxWidth().clickable {
                                pendingStructured = null
                                scope.launch {
                                    progress = 0f
                                    val ok = watch.withSession("structured face") { s -> s.uploadStructuredDial(blob, d.id) { done, total -> progress = done.toFloat() / total } }
                                    progress = null
                                    note = when (ok) { true -> "Face installed."; false -> "The watch rejected that face."; null -> "Upload failed (see Log)." }
                                }
                            }.padding(vertical = 10.dp),
                        )
                    }
                }
            },
        )
    }
}

// -- speaker -------------------------------------------------------------------

@Composable
private fun SpeakerSection() {
    val ctx = LocalContext.current
    val exp = remember { SpeakerExperiment(ctx.applicationContext) }
    var on by remember { mutableStateOf(exp.on) }
    var callOn by remember { mutableStateOf(dev.cmfopen.companion.media.SpeakerCallService.current != null) }
    var note by remember { mutableStateOf<String?>(null) }
    Section("Watch as speaker (experiment)") {
        Hint("Plays phone audio through the watch's call link, so it sounds like a phone call. The watch must be connected for calls in Android's Bluetooth settings.")
        ToggleRow("Mode 1: open the call audio link", on, "Plain SCO link. Didn't work on Nov 24; kept for comparison.") {
            note = if (it) exp.start() else { exp.stop(); "Stopped." }
            on = exp.on
        }
        ToggleRow("Mode 2: pretend phone call", callOn, "Starts a silent call so the watch switches into call audio.") {
            if (it) {
                note = dev.cmfopen.companion.media.SpeakerCallService.start(ctx)
                callOn = true
            } else {
                dev.cmfopen.companion.media.SpeakerCallService.stop()
                callOn = false
                note = "Call ended."
            }
        }
        note?.let { Hint(it) }
    }
}
