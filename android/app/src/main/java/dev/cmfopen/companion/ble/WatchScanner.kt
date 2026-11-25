package dev.cmfopen.companion.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class FoundWatch(val device: BluetoothDevice, val name: String, val rssi: Int)

/** Scans for advertising CMF watches. The flow emits each watch the first time it is seen. */
@SuppressLint("MissingPermission")
object WatchScanner {
    fun scan(context: Context): Flow<FoundWatch> = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || !adapter.isEnabled) {
            close(IllegalStateException("Bluetooth is off"))
            return@callbackFlow
        }
        val seen = HashSet<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                if (!name.startsWith(BleTransport.NAME_PREFIX)) return
                if (seen.add(result.device.address)) trySend(FoundWatch(result.device, name, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("scan failed ($errorCode)"))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, cb)
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }
}
