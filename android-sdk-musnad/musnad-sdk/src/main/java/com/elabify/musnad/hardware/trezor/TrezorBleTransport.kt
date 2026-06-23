// Trezor BLE byte pipe. Ported from iOS Maknoon/HardwareWallet/
// TrezorBLE.swift, the connection + raw-transport halves.
//
// Unlike Ledger (which frames APDUs host-side), the entire Trezor wire
// protocol (THP v2 packet framing, channel allocation, the Noise XX
// handshake, the AES-256-GCM session, ACK/ABP) lives in the trezor-core
// Rust crate. This class owns ONLY the raw BLE byte pipe: it writes one
// report to the write characteristic and hands each notify report back,
// via the TrezorTransport foreign-callback the Rust client drives.
//
// Wire reference (github.com/trezor/trezor-firmware, docs/common/thp):
//
//   Service UUID:          8c000001-a59b-4d58-a9ad-073df69fa1b1
//   Write characteristic:  8c000002-a59b-4d58-a9ad-073df69fa1b1
//   Notify characteristic: 8c000003-a59b-4d58-a9ad-073df69fa1b1
//
// One notify == one report in the inbox; the Rust THP layer reassembles.

package com.elabify.musnad.hardware.trezor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.elabify.musnad.hardware.HardwareWalletException
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import uniffi.trezor_core.TrezorTransport
import uniffi.trezor_core.TrezorTransportException

/**
 * GATT transport to the Trezor THP service. Construct with the Android
 * [Context]; set [targetAddress] to hard-filter reconnects to one
 * specific Trezor MAC the user paired.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT/SCAN declared in the app manifest.
class TrezorBleTransport(
    private val context: Context,
) : TrezorTransport {

    /**
     * When set, [ensureConnected] hard-filters scan + connected-device
     * results to this specific MAC so we never connect to a different
     * Trezor the user also owns. The iOS analog is targetPeripheralUUID.
     */
    var targetAddress: String? = null

    /** MAC of the device most recently connected, or null. */
    @Volatile
    var currentAddress: String? = null
        private set

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val connectionLock = Mutex()

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    private var connectedSignal: CompletableDeferred<Unit>? = null
    private var servicesReadySignal: CompletableDeferred<Unit>? = null
    private var mtuReadySignal: CompletableDeferred<Unit>? = null
    private var writeSignal: CompletableDeferred<Unit>? = null

    // Inbound notify reports buffered for readChunk(). One notify == one
    // report; the Rust THP layer reassembles, so we never inspect them.
    private val inboxLock = Any()
    private val inbox = ArrayDeque<ByteArray>()
    private var readSignal: CompletableDeferred<ByteArray>? = null

    private var scanCallback: ScanCallback? = null

    // MARK: -- TrezorTransport conformance (driven by the Rust client)

    override suspend fun writeChunk(data: ByteArray) {
        try {
            ensureConnected()
            val g = gatt ?: throw HardwareWalletException.Transport("Not connected to Trezor over BLE")
            val ch = writeChar ?: throw HardwareWalletException.Transport("Not connected to Trezor over BLE")
            android.util.Log.d("TrezorBLE", "writeChunk ${data.size}B")
            // Serialize: Android permits one outstanding GATT write at a time,
            // so wait for onCharacteristicWrite before returning. Without this,
            // back-to-back THP frames get silently dropped and the handshake
            // stalls. iOS CoreBluetooth queues writes for us; Android does not.
            val signal = CompletableDeferred<Unit>()
            writeSignal = signal
            writeValue(g, ch, data)
            withTimeout(WRITE_TIMEOUT_MS) { signal.await() }
        } catch (e: HardwareWalletException) {
            throw TrezorTransportException.Io(e.message ?: "write failed")
        } catch (e: TrezorTransportException) {
            throw e
        } catch (e: Throwable) {
            throw TrezorTransportException.Io(e.message ?: "write failed")
        }
    }

    override suspend fun readChunk(): ByteArray {
        try {
            synchronized(inboxLock) { if (inbox.isNotEmpty()) return inbox.removeFirst() }
            return withTimeout(READ_TIMEOUT_MS) {
                val signal = CompletableDeferred<ByteArray>()
                synchronized(inboxLock) {
                    if (inbox.isNotEmpty()) return@withTimeout inbox.removeFirst()
                    readSignal = signal
                }
                signal.await()
            }
        } catch (e: TimeoutCancellationException) {
            throw TrezorTransportException.Timeout(timeoutMessage("Trezor BLE read", READ_TIMEOUT_MS))
        } catch (e: HardwareWalletException) {
            throw TrezorTransportException.Io(e.message ?: "read failed")
        } catch (e: TrezorTransportException) {
            throw e
        } catch (e: Throwable) {
            throw TrezorTransportException.Io(e.message ?: "read failed")
        }
    }

    // MARK: -- connection

    /**
     * Scan, connect, discover the THP service, and subscribe to the
     * notify characteristic. Mirrors LedgerBLE's machinery; the
     * connected-state check forces a clean reconnect when Android has
     * quietly dropped a stale link.
     */
    private suspend fun ensureConnected() = connectionLock.withLock {
        // Reuse the cached link ONLY if it is actually still connected. The old
        // code reused a non-null gatt without checking liveness, so a stale link
        // (Trezor quietly dropped it after a prior attempt) was reused, the first
        // write failed with "disconnected", and the orphaned THP channel left the
        // device reporting TransportBusy on the retry. Verify, else tear down.
        val cached = gatt
        if (cached != null && writeChar != null && notifyChar != null) {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val live = mgr?.getConnectionState(cached.device, BluetoothProfile.GATT) ==
                BluetoothProfile.STATE_CONNECTED
            if (live) return@withLock
            android.util.Log.d("TrezorBLE", "ensureConnected: cached link is stale, reconnecting")
        }

        forceTeardownLocked()

        val adapter = adapter ?: throw HardwareWalletException.Transport("BLE unsupported")
        if (!adapter.isEnabled) throw HardwareWalletException.Transport("Bluetooth is off")

        // Reconnect directly to an OS-known bonded/connected peripheral if
        // possible, hard-filtered to targetAddress when set.
        val known = knownDevice(adapter)
        var connected = false
        if (known != null) {
            connectGatt(known)
            try {
                withTimeout(DIRECT_CONNECT_TIMEOUT_MS) { awaitConnected() }
                connected = true
            } catch (e: TimeoutCancellationException) {
                tripTimeout("Trezor BLE direct-connect", DIRECT_CONNECT_TIMEOUT_MS)
                throw HardwareWalletException.Transport(
                    timeoutMessage("Trezor BLE direct-connect", DIRECT_CONNECT_TIMEOUT_MS)
                )
            }
        }

        if (!connected) {
            val device = try {
                withTimeout(SCAN_TIMEOUT_MS) { scanForDevice(adapter) }
            } catch (e: TimeoutCancellationException) {
                stopScan()
                throw HardwareWalletException.Transport(
                    timeoutMessage("Trezor BLE scan", SCAN_TIMEOUT_MS)
                )
            }
            connectGatt(device)
            try {
                withTimeout(DIRECT_CONNECT_TIMEOUT_MS) { awaitConnected() }
            } catch (e: TimeoutCancellationException) {
                tripTimeout("Trezor BLE connect", DIRECT_CONNECT_TIMEOUT_MS)
                throw HardwareWalletException.Transport(
                    timeoutMessage("Trezor BLE connect", DIRECT_CONNECT_TIMEOUT_MS)
                )
            }
        }

        // Negotiate a larger ATT MTU before the THP handshake. Android defaults
        // to 23 (20 usable bytes), which truncates Trezor's 64-byte BLE notify
        // frames and stalls the post-pairing exchange until the read timeout.
        // iOS CoreBluetooth auto-negotiates a large MTU, so this never bit there.
        // Best-effort: proceed on failure / timeout (some stacks ignore it).
        try {
            withTimeout(MTU_TIMEOUT_MS) {
                val signal = CompletableDeferred<Unit>()
                mtuReadySignal = signal
                if (gatt?.requestMtu(MTU_SIZE) != true) {
                    mtuReadySignal = null
                } else {
                    signal.await()
                }
            }
        } catch (e: TimeoutCancellationException) {
            android.util.Log.w("TrezorBLE", "MTU negotiation timed out; proceeding with default MTU")
            mtuReadySignal = null
        }

        try {
            withTimeout(SERVICE_DISCOVERY_TIMEOUT_MS) {
                val signal = CompletableDeferred<Unit>()
                servicesReadySignal = signal
                gatt?.discoverServices()
                signal.await()
            }
        } catch (e: TimeoutCancellationException) {
            tripTimeout("Trezor BLE service discovery", SERVICE_DISCOVERY_TIMEOUT_MS)
            throw HardwareWalletException.Transport(
                timeoutMessage("Trezor BLE service discovery", SERVICE_DISCOVERY_TIMEOUT_MS)
            )
        }
    }

    private fun knownDevice(adapter: BluetoothAdapter): BluetoothDevice? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val connected = manager?.getConnectedDevices(BluetoothProfile.GATT).orEmpty()
        val target = targetAddress
        if (target != null) return connected.firstOrNull { it.address == target }
        return connected.firstOrNull()
    }

    private fun connectGatt(device: BluetoothDevice) {
        connectedSignal = CompletableDeferred()
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    private suspend fun awaitConnected() {
        connectedSignal?.await()
    }

    private suspend fun scanForDevice(adapter: BluetoothAdapter): BluetoothDevice {
        val scanner = adapter.bluetoothLeScanner
            ?: throw HardwareWalletException.Transport("BLE scanner unavailable")
        val found = CompletableDeferred<BluetoothDevice>()
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(TREZOR_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val target = targetAddress
                if (target != null && device.address != target) return
                if (!found.isCompleted) found.complete(device)
            }

            override fun onScanFailed(errorCode: Int) {
                if (!found.isCompleted) {
                    found.completeExceptionally(
                        HardwareWalletException.Transport("BLE scan failed: $errorCode")
                    )
                }
            }
        }
        scanCallback = cb
        scanner.startScan(listOf(filter), settings, cb)
        return try {
            found.await()
        } finally {
            stopScan()
        }
    }

    private fun stopScan() {
        val cb = scanCallback ?: return
        scanCallback = null
        try {
            adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Throwable) {
        }
    }

    @Suppress("DEPRECATION")
    private fun writeValue(g: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = data
            g.writeCharacteristic(ch)
        }
    }

    private fun tripTimeout(label: String, timeoutMs: Long) {
        val err = HardwareWalletException.Transport(timeoutMessage(label, timeoutMs))
        failPending(err)
        forceTeardownLocked()
    }

    private fun timeoutMessage(label: String, timeoutMs: Long): String =
        "$label timed out after ${timeoutMs / 1000}s. Make sure your Trezor is unlocked, " +
            "Bluetooth is on, and the device is in range."

    private fun failPending(err: Throwable) {
        readSignal?.let { if (!it.isCompleted) it.completeExceptionally(err) }
        readSignal = null
        connectedSignal?.let { if (!it.isCompleted) it.completeExceptionally(err) }
        connectedSignal = null
        servicesReadySignal?.let { if (!it.isCompleted) it.completeExceptionally(err) }
        servicesReadySignal = null
        mtuReadySignal?.let { if (!it.isCompleted) it.completeExceptionally(err) }
        mtuReadySignal = null
        writeSignal?.let { if (!it.isCompleted) it.completeExceptionally(err) }
        writeSignal = null
    }

    /**
     * Unconditional teardown. Closes the GATT link and clears all cached
     * characteristics + buffered reports; the next call rebuilds against
     * a fresh connection. The caller (TrezorHardwareWallet) drives this
     * via its session-pin reset, mirroring iOS forceReset().
     */
    fun teardown() {
        stopScan()
        failPending(HardwareWalletException.Transport("Trezor session reset"))
        forceTeardownLocked()
    }

    private fun forceTeardownLocked() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Throwable) {
        }
        gatt = null
        writeChar = null
        notifyChar = null
        connectedSignal = null
        servicesReadySignal = null
        synchronized(inboxLock) { inbox.clear() }
    }

    // MARK: -- GATT callback

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    currentAddress = g.device.address
                    connectedSignal?.let { if (!it.isCompleted) it.complete(Unit) }
                    connectedSignal = null
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val err = HardwareWalletException.Transport("Trezor disconnected")
                    failPending(err)
                    writeChar = null
                    notifyChar = null
                }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid != TREZOR_WRITE_UUID) return
            val signal = writeSignal ?: return
            writeSignal = null
            if (!signal.isCompleted) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    signal.complete(Unit)
                } else {
                    signal.completeExceptionally(
                        HardwareWalletException.Transport("Trezor BLE write failed (status=$status)")
                    )
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            android.util.Log.d("TrezorBLE", "onMtuChanged mtu=$mtu status=$status")
            mtuReadySignal?.let { if (!it.isCompleted) it.complete(Unit) }
            mtuReadySignal = null
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(TREZOR_SERVICE_UUID)
            if (service == null) {
                servicesReadySignal?.let {
                    if (!it.isCompleted) it.completeExceptionally(
                        HardwareWalletException.Transport("Trezor service discovery failed")
                    )
                }
                servicesReadySignal = null
                return
            }
            writeChar = service.getCharacteristic(TREZOR_WRITE_UUID)
            notifyChar = service.getCharacteristic(TREZOR_NOTIFY_UUID)
            val notify = notifyChar
            if (notify == null || writeChar == null) {
                servicesReadySignal?.let {
                    if (!it.isCompleted) it.completeExceptionally(
                        HardwareWalletException.Transport("Trezor write/notify characteristic missing")
                    )
                }
                servicesReadySignal = null
                return
            }
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                writeCccd(g, cccd)
                // Resume happens in onDescriptorWrite so notifications are
                // actually live before the Rust client starts the exchange.
            } else {
                servicesReadySignal?.let { if (!it.isCompleted) it.complete(Unit) }
                servicesReadySignal = null
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                servicesReadySignal?.let { if (!it.isCompleted) it.complete(Unit) }
                servicesReadySignal = null
            }
        }

        @Deprecated("Pre-Tiramisu notify callback")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == TREZOR_NOTIFY_UUID) {
                deliver(characteristic.value)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == TREZOR_NOTIFY_UUID) deliver(value)
        }
    }

    private fun deliver(value: ByteArray?) {
        val data = value ?: return
        android.util.Log.d("TrezorBLE", "notify ${data.size}B")
        synchronized(inboxLock) {
            val signal = readSignal
            if (signal != null && !signal.isCompleted) {
                readSignal = null
                signal.complete(data)
            } else {
                inbox.addLast(data)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCccd(g: BluetoothGatt, cccd: BluetoothGattDescriptor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(cccd)
        }
    }

    companion object {
        val TREZOR_SERVICE_UUID: UUID = UUID.fromString("8c000001-a59b-4d58-a9ad-073df69fa1b1")
        val TREZOR_WRITE_UUID: UUID = UUID.fromString("8c000002-a59b-4d58-a9ad-073df69fa1b1")
        val TREZOR_NOTIFY_UUID: UUID = UUID.fromString("8c000003-a59b-4d58-a9ad-073df69fa1b1")

        // Standard Client Characteristic Configuration Descriptor.
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // 247 fits Trezor's 64-byte BLE frames with headroom; iOS auto-negotiates
        // ~185+. 5s is generous for the single MTU round-trip.
        private const val MTU_SIZE = 247
        private const val MTU_TIMEOUT_MS = 5_000L
        private const val WRITE_TIMEOUT_MS = 5_000L
        private const val DIRECT_CONNECT_TIMEOUT_MS = 15_000L
        private const val SCAN_TIMEOUT_MS = 25_000L
        private const val SERVICE_DISCOVERY_TIMEOUT_MS = 15_000L
        private const val READ_TIMEOUT_MS = 30_000L
    }
}
