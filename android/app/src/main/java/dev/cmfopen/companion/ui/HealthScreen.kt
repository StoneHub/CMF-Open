package dev.cmfopen.companion.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cmfopen.companion.CompanionApp
import dev.cmfopen.companion.health.HealthConnectWriter

@Composable
fun HealthScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val app = CompanionApp.instance
    val health = app.watch.health
    val status by health.status.collectAsStateWithLifecycle()
    @Suppress("UNUSED_VARIABLE") val v by health.settingsVersion.collectAsStateWithLifecycle()
    val watchState by app.watch.state.collectAsStateWithLifecycle()

    var sdkStatus by remember { mutableIntStateOf(health.writer.sdkStatus()) }
    var granted by remember { mutableStateOf<Set<String>>(emptySet()) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        sdkStatus = health.writer.sdkStatus()
        granted = runCatching { health.writer.granted() }.getOrDefault(emptySet())
    }

    val wanted = HealthConnectWriter.permissions(health.distanceAndCalories)
    val required = wanted - HealthConnectWriter.PERM_ROUTE
    val hasRequired = granted.containsAll(required)
    val askPermissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { got ->
        granted = got
        refresh++
        if (got.containsAll(required)) health.autoSync = true
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("Google Health") {
            when (sdkStatus) {
                HealthConnectClient.SDK_AVAILABLE -> {
                    Hint(
                        "Watch data goes to Health Connect on this phone. Google Health reads it from there: in Google Health, " +
                            "open Connections and turn on Health Connect.",
                    )
                    if (hasRequired) {
                        Text("Health Connect: allowed")
                        if (HealthConnectWriter.PERM_ROUTE !in granted) Hint("Workout routes are off. Allow \"exercise routes\" in Health Connect to include GPS tracks.")
                    } else {
                        Text("Health Connect: needs permission")
                        Button(onClick = { askPermissions.launch(wanted) }) { Text("Allow Health Connect") }
                    }
                    OutlinedButton(onClick = {
                        runCatching { ctx.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
                    }) { Text("Open Health Connect") }
                }
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                    Text("Health Connect needs to be installed or updated.")
                    Button(onClick = {
                        val uri = Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending"))
                        } catch (_: ActivityNotFoundException) {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")))
                        }
                    }) { Text("Get Health Connect") }
                }
                else -> Text("Health Connect isn't available on this phone.")
            }
        }

        Section("Sync") {
            ToggleRow(
                "Sync automatically",
                health.autoSync,
                help = "Pulls new data from the watch while it's connected, at most this often. Uses the existing connection; no extra wakeups.",
            ) { on ->
                if (on && !hasRequired && sdkStatus == HealthConnectClient.SDK_AVAILABLE) askPermissions.launch(wanted)
                else health.autoSync = on
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30 to "30 min", 60 to "1 hour", 180 to "3 hours", 360 to "6 hours").forEach { (min, label) ->
                    FilterChip(selected = health.intervalMin == min, onClick = { health.intervalMin = min }, label = { Text(label) })
                }
            }
            Button(
                onClick = { health.syncNow() },
                enabled = !status.running && app.watch.connected,
            ) { Text(if (status.running) "Syncing…" else "Sync now") }
            if (!app.watch.connected) Hint("Connect the watch to sync. (${watchState.phase.name.lowercase().replace('_', ' ')})")
            val last = if (status.lastFetchMs == 0L) "never" else DateUtils.getRelativeTimeSpanString(status.lastFetchMs).toString()
            Text("Last sync: $last")
            status.lastResult?.let { Hint(it) }
            if (status.pending > 0) Hint("${status.pending} record batch(es) waiting on the phone for Health Connect.")
        }

        Section("What gets sent") {
            Hint("Steps, heart rate, resting heart rate, blood oxygen (SpO2), sleep with stages, and workouts with GPS routes.")
            ToggleRow(
                "Distance and calories",
                health.distanceAndCalories,
                help = "Off by default: the watch's units for these aren't confirmed yet. Compare the numbers below with the watch's own screen first.",
            ) { on ->
                health.distanceAndCalories = on
                if (on && sdkStatus == HealthConnectClient.SDK_AVAILABLE) askPermissions.launch(HealthConnectWriter.permissions(true))
            }
            status.today?.let { t ->
                val at = DateUtils.formatDateTime(ctx, t.tsS * 1000, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE)
                Text("Watch totals at $at")
                Hint("Steps ${t.stepsTotal} · distance ${t.distanceRaw} (raw, sent as metres) · calories ${t.caloriesRaw} (raw, sent as kcal)")
            } ?: Hint("Watch totals appear here after the first sync.")
        }
    }
}
