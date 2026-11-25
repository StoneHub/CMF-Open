package dev.cmfopen.companion.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import dev.cmfopen.companion.DebugLog
import dev.cmfopen.protocol.Channel
import dev.cmfopen.protocol.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * GATT transport for one CMF watch. All GATT operations are serialised (Android allows one
 * outstanding operation per connection), and each write waits for its callback before the next.
 */
@SuppressLint("MissingPermission") // Callers check BLUETOOTH_CONNECT before connecting.
class BleTransport(private val context: Context, val device: BluetoothDevice) : Transport {
    override var mtu: Int = 23
        private set

    /** Called once when the link drops after [connect] succeeded (or while connecting). */
    var onDisconnected: (() -> Unit)? = null

    private var gatt: BluetoothGatt? = null
    private var handler: ((Channel, ByteArray) -> Unit)? = null
    private val chars = HashMap<UUID, BluetoothGattCharacteristic>()
    private val opMutex = Mutex()
    private var pending: CompletableDeferred<Int>? = null
    private var connected = CompletableDeferred<Unit>()
    private var servicesFound = CompletableDeferred<Int>()
    private var mtuDone = CompletableDeferred<Int>()
    @Volatile private var closed = false

    override fun setNotifyHandler(handler: (Channel, ByteArray) -> Unit) {
        this.handler = handler
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            DebugLog.info("gatt state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val err = IllegalStateException("disconnected (status $status)")
                connected.completeExceptionally(err)
                servicesFound.completeExceptionally(err)
                mtuDone.completeExceptionally(err)
                pending?.completeExceptionally(err)
                if (!closed) {
                    closed = true
                    g.close()
                    onDisconnected?.invoke()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesFound.complete(status)
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            mtuDone.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status)
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(c.uuid, value)
        }

        // API 29-32
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) @Suppress("DEPRECATION") deliver(c.uuid, c.value ?: return)
        }
    }

    private fun deliver(uuid: UUID, value: ByteArray) {
        val channel = NOTIFY.entries.firstOrNull { it.value == uuid }?.key ?: return
        handler?.invoke(channel, value)
    }

    /** Connect, discover services, raise the MTU and subscribe to all notify characteristics. */
    suspend fun connect(timeoutMs: Long = 20_000) {
        closed = false
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IllegalStateException("connectGatt returned null")
        gatt = g
        try {
            withTimeout(timeoutMs) {
                connected.await()
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (!g.discoverServices()) throw IllegalStateException("discoverServices refused")
                if (servicesFound.await() != BluetoothGatt.GATT_SUCCESS) throw IllegalStateException("service discovery failed")
                for (s in g.services) for (c in s.characteristics) chars[c.uuid] = c
                if (WRITE.getValue(Channel.COMMAND) !in chars) throw IllegalStateException("not a CMF watch (no command service)")
                if (g.requestMtu(REQUESTED_MTU)) runCatching { withTimeout(5_000) { mtuDone.await() } }
                DebugLog.info("mtu=$mtu, services=${g.services.size}")
                for ((channel, uuid) in NOTIFY) {
                    val c = chars[uuid] ?: continue
                    enableNotify(g, c)
                    DebugLog.info("subscribed $channel")
                }
            }
            // After the first round trip the stack is settled; small pause helps some firmware.
            delay(150)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private suspend fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD) ?: return
        gattOp {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
        }
    }

    /** Run one GATT operation and wait for its callback. Retries while the stack is busy. */
    private suspend fun gattOp(start: () -> Boolean) = opMutex.withLock {
        var attempt = 0
        while (true) {
            val d = CompletableDeferred<Int>()
            pending = d
            if (start()) {
                val status = withTimeout(5_000) { d.await() }
                if (status != BluetoothGatt.GATT_SUCCESS) throw IllegalStateException("GATT op failed ($status)")
                return@withLock
            }
            if (closed) throw IllegalStateException("not connected")
            if (++attempt > 40) throw IllegalStateException("GATT busy")
            delay(25)
        }
    }

    override suspend fun write(channel: Channel, data: ByteArray) {
        val g = gatt ?: throw IllegalStateException("not connected")
        if (closed) throw IllegalStateException("not connected")
        val c = chars[WRITE.getValue(channel)] ?: throw IllegalStateException("watch has no $channel characteristic")
        val type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        gattOp {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = data
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }
    }

    /** Drop the link. Fires [onDisconnected] if the link was still up, so waiters always wake. */
    fun close() {
        val g = gatt
        gatt = null
        if (!closed) {
            closed = true
            g?.let {
                runCatching { it.disconnect() }
                runCatching { it.close() }
            }
            pending?.completeExceptionally(IllegalStateException("closed"))
            onDisconnected?.invoke()
        }
    }

    companion object {
        const val NAME_PREFIX = "CMF Watch"
        const val REQUESTED_MTU = 247
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val WRITE = mapOf(
            Channel.COMMAND to UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb"),
            Channel.SHELL to UUID.fromString("77d4ff01-2fe2-2334-0d35-9ccd078f529c"),
            Channel.DATA to UUID.fromString("02f00000-0000-0000-0000-00000000ffe1"),
        )
        val NOTIFY = mapOf(
            Channel.COMMAND to UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
            Channel.SHELL to UUID.fromString("77d4ff02-2fe2-2334-0d35-9ccd078f529c"),
            Channel.DATA to UUID.fromString("02f00000-0000-0000-0000-00000000ffe2"),
        )
    }
}
