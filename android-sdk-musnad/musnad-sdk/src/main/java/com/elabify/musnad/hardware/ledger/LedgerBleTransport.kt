// Ledger Nano X BLE APDU channel for Android, ported 1:1 from the iOS
// LedgerBLE CoreBluetooth state machine. Owns the GATT plumbing (scan,
// connect, characteristic discovery, notify subscription) and the
// Ledger 5-byte BLE APDU framing with 153-byte MTU chunking +
// multi-packet reassembly. Exposes a single suspend `sendApdu` that
// the per-chain SDK Transport adapters route through.
//
// Wire reference (Ledger public docs + ledger-live-mobile), identical
// to LedgerBLE.swift:
//
//   Service UUID:           13d63400-2c97-0004-0000-4c6564676572
//   Write characteristic:   13d63400-2c97-0004-0002-4c6564676572
//   Notify characteristic:  13d63400-2c97-0004-0001-4c6564676572
//
// Each BLE packet:
//   byte 0      TAG (0x05 for APDU)
//   bytes 1-2   packet index (big endian)
//   bytes 3-4   total APDU length, only in the first packet
//   bytes 5+    APDU bytes (chunked across packets)
//
// Keep-alive heartbeat: periodic reads of the standard Battery Level
// characteristic (separate GATT service) generate LL traffic that
// resets the Ledger's BLE supervision-timeout countdown while the user
// reads the on-device confirmation screen. Verified-stable at 400ms
// initial delay + 500ms interval; do NOT tighten (CBError 6 / GATT
// disconnects appear mid-protocol otherwise).

package com.elabify.musnad.hardware.ledger

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
import android.os.ParcelUuid
import com.elabify.musnad.hardware.HardwareWalletException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Android GATT APDU channel for a Ledger Nano X. One instance is bound
 * to (at most) one physical device for its lifetime. The per-chain SDK
 * Transport adapters call [sendApdu]; everything below is the BLE
 * machinery that gets a complete APDU on the air and reassembles the
 * response.
 *
 * Construct with the app [Context] (for the system BluetoothManager).
 * Optionally pin a [targetAddress] (device MAC) before connecting so a
 * reconnect filters to exactly the device the user paired.
 */
internal class LedgerBleTransport(
    private val appContext: Context,
) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("13d63400-2c97-0004-0000-4c6564676572")
        val WRITE_UUID: UUID = UUID.fromString("13d63400-2c97-0004-0002-4c6564676572")
        val NOTIFY_UUID: UUID = UUID.fromString("13d63400-2c97-0004-0001-4c6564676572")

        // Standard Battery Service + Battery Level, used purely as a
        // keep-alive read target during long on-device confirmations.
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        // Client Characteristic Configuration Descriptor (enable notify).
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val BLE_APDU_TAG: Byte = 0x05

        // Verified-stable on iOS: cap the on-air chunk at 153 bytes even
        // when the OS reports a larger MTU. Matches the macOS reference
        // client across 200+ SIGN_PSBT rounds.
        const val SAFE_MTU = 153

        // Heartbeat tuning. DO NOT tighten: 400ms warmup + 500ms interval
        // is the verified-stable configuration. Tighter intervals
        // interleave reads with the inbound notify stream and cause
        // mid-protocol disconnects.
        const val HEARTBEAT_INITIAL_DELAY_MS = 400L
        const val HEARTBEAT_INTERVAL_MS = 500L

        const val SCAN_TIMEOUT_MS = 25_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val SERVICES_TIMEOUT_MS = 15_000L

        // Per-APDU response timeout. The Bitcoin app rarely needs more than a
        // few seconds per APDU; the long pole is the SIGN_PSBT on-device
        // confirmation the user sits on. 20s gives a reasonable read+approve
        // window while still failing a non-responsive device well under a
        // minute (the retry helper does not re-attempt an APDU TIMEOUT).
        const val APDU_TIMEOUT_MS = 20_000L

        // A larger ATT MTU is negotiated before any APDU traffic. Android
        // defaults to 23 (20 usable bytes); the Ledger BLE stack will not
        // emit notify frames sized for the host until the ATT MTU exchange
        // has happened, so without this the write goes out and NO notify
        // ever comes back (the exact GET_MASTER_FINGERPRINT hang). iOS never
        // hit this because CoreBluetooth auto-negotiates ~185+. Mirrors the
        // working TrezorBleTransport.
        const val MTU_SIZE = 247
        const val MTU_TIMEOUT_MS = 5_000L
        // Wait for onCharacteristicWrite between framed packets: Android
        // permits one outstanding GATT write at a time, so firing the next
        // packet before the previous ack drops it silently and the device
        // waits forever for the rest of the APDU.
        const val WRITE_TIMEOUT_MS = 5_000L

        // Stable marker embedded in the APDU-timeout transport message so the
        // app-side retry helper can recognise "device never answered the
        // APDU" (do not retry, fail fast) vs a stale-link connect failure
        // (worth a reconnect). Keep in sync with HardwareDeviceConnection.
        const val APDU_TIMEOUT_MARKER = "[apdu-timeout]"
    }

    /**
     * When non-null, scan + connect hard-filter to ONLY this device MAC,
     * so we never accidentally bind to a different Ledger the user also
     * paired. Set by the caller before the first op binds to a specific
     * physical device. With no target set (the unknown-pairing pair
     * flow), the first matching peripheral wins.
     */
    @Volatile
    var targetAddress: String? = null

    /** The MAC of the device we are (or were last) connected to. */
    @Volatile
    var connectedAddress: String? = null
        private set

    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var batteryLevelChar: BluetoothGattCharacteristic? = null

    // Serializes APDU exchanges: BLE allows only one in-flight exchange.
    private val exchangeMutex = Mutex()
    // Guards the GATT connect/discover bring-up.
    private val connectMutex = Mutex()

    // Reassembly state for the single in-flight APDU response.
    private val pendingApdu = AtomicReference<PendingApdu?>(null)

    private class PendingApdu {
        var totalLength: Int = -1
        val buffer = ArrayList<Byte>()
        val deferred = CompletableDeferred<ByteArray>()
    }

    // GATT lifecycle continuations.
    private var connectCont: CancellableContinuation<Unit>? = null
    private var servicesCont: CancellableContinuation<Unit>? = null

    // Signalled from onMtuChanged once the ATT MTU exchange completes (or is
    // refused); best-effort, the bring-up proceeds either way.
    private var mtuSignal: CompletableDeferred<Unit>? = null
    // Signalled from onCharacteristicWrite so writeFramedPackets sends one
    // framed packet at a time (Android allows one in-flight GATT write).
    private var writeSignal: CompletableDeferred<Unit>? = null

    // Reference-counted session pin. While > 0, per-op teardown is a
    // no-op so a multi-step caller (identify -> fingerprint -> xpub xN
    // for Discover) keeps one BLE connection. Mirrors LedgerBLE.swift.
    private var sessionPinCount: Int = 0

    fun beginSession() {
        synchronized(this) { sessionPinCount += 1 }
    }

    fun endSession() {
        val shouldTeardown: Boolean
        synchronized(this) {
            sessionPinCount = maxOf(0, sessionPinCount - 1)
            shouldTeardown = sessionPinCount == 0
        }
        if (shouldTeardown) forceTeardown()
    }

    /** Per-op teardown: a no-op while a session is pinned. */
    fun resetSession() {
        val pinned: Boolean
        synchronized(this) { pinned = sessionPinCount > 0 }
        if (!pinned) forceTeardown()
    }

    @SuppressLint("MissingPermission")
    private fun forceTeardown() {
        scanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
        scanCallback = null
        gatt?.let {
            try { it.disconnect() } catch (_: SecurityException) {}
            try { it.close() } catch (_: SecurityException) {}
        }
        gatt = null
        writeChar = null
        notifyChar = null
        batteryLevelChar = null
        pendingApdu.getAndSet(null)?.deferred?.completeExceptionally(
            HardwareWalletException.Transport("Ledger session reset"),
        )
        connectCont?.let { if (it.isActive) it.resumeWith(Result.failure(HardwareWalletException.Transport("Ledger session reset"))) }
        connectCont = null
        servicesCont?.let { if (it.isActive) it.resumeWith(Result.failure(HardwareWalletException.Transport("Ledger session reset"))) }
        servicesCont = null
        mtuSignal?.let { if (!it.isCompleted) it.complete(Unit) }
        mtuSignal = null
        writeSignal?.let { if (!it.isCompleted) it.completeExceptionally(HardwareWalletException.Transport("Ledger session reset")) }
        writeSignal = null
    }

    /**
     * Send a complete APDU (header + Lc + data, no Le) and await the
     * reassembled response payload + status word. Mirrors
     * LedgerBLE.sendAPDU: ensures the GATT link, fires off the framed
     * packets, runs the keep-alive heartbeat, and races a 30s timeout.
     */
    suspend fun sendApdu(apdu: ByteArray): ApduResponse = exchangeMutex.withLock {
        ensureConnected()
        val writeC = writeChar
        val g = gatt
        if (writeC == null || g == null) {
            throw HardwareWalletException.Transport("Not connected to Ledger over BLE")
        }
        val dbgCla = if (apdu.isNotEmpty()) apdu[0].toInt() and 0xFF else -1
        val dbgIns = if (apdu.size > 1) apdu[1].toInt() and 0xFF else -1

        // Drop any stale pending state from a prior aborted call.
        pendingApdu.getAndSet(null)?.deferred?.completeExceptionally(
            HardwareWalletException.Transport("Previous APDU was abandoned"),
        )

        val pending = PendingApdu()
        pendingApdu.set(pending)

        val mtu = SAFE_MTU
        val raw = try {
            withTimeout(APDU_TIMEOUT_MS) {
                writeFramedPackets(g, writeC, apdu, mtu)
                runWithHeartbeat(g) { pending.deferred.await() }
            }
        } catch (e: TimeoutCancellationException) {
            pendingApdu.set(null)
            android.util.Log.w("LedgerDbg", "sendApdu TIMEOUT cla=0x${dbgCla.toString(16)} ins=0x${dbgIns.toString(16)}")
            throw HardwareWalletException.Transport(
                "Ledger APDU response timed out. Make sure your Ledger is awake (press a button), " +
                    "Bluetooth is on, and the right app is open. $APDU_TIMEOUT_MARKER",
            )
        }

        if (raw.size < 2) {
            throw HardwareWalletException.Transport("APDU response too short (${raw.size} bytes)")
        }
        val sw = ((raw[raw.size - 2].toInt() and 0xFF) shl 8) or (raw[raw.size - 1].toInt() and 0xFF)
        val data = raw.copyOfRange(0, raw.size - 2)
        return ApduResponse(data = data, statusWord = sw.toUShort())
    }

    /** APDU response: payload bytes (no SW) + the 16-bit status word. */
    data class ApduResponse(val data: ByteArray, val statusWord: UShort)

    // ------------------------------------------------------------------
    // GATT bring-up
    // ------------------------------------------------------------------

    /** Bring up the BLE link (connect + service discovery) WITHOUT sending an
     *  APDU. Callers that only need the connection up + the device MAC (e.g.
     *  identifyDevice) use this instead of a dashboard command: this Ledger does
     *  not answer GET_APP_AND_VERSION (B0 01) reliably over BLE, and sending it
     *  timed out the serial guard and broke every Ledger flow. The MAC is set as
     *  a side effect of connecting (connectedAddress). */
    suspend fun connect() {
        ensureConnected()
    }

    @SuppressLint("MissingPermission")
    private suspend fun ensureConnected() = connectMutex.withLock {
        val g = gatt
        if (g != null && writeChar != null && notifyChar != null && isConnected(g)) {
            return@withLock
        }
        // Tear down any stale cached state before rebuilding.
        scanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
        scanCallback = null
        gatt?.let {
            try { it.disconnect() } catch (_: SecurityException) {}
            try { it.close() } catch (_: SecurityException) {}
        }
        gatt = null
        writeChar = null
        notifyChar = null
        batteryLevelChar = null

        val a = adapter ?: throw HardwareWalletException.Transport("Bluetooth unavailable on this device")
        if (!a.isEnabled) throw HardwareWalletException.Transport("Bluetooth is off")

        // First try an already-bonded / connected Ledger (filtered to
        // the target MAC if pinned), like iOS retrieveConnectedPeripherals.
        val known = findKnownDevice(a)
        val device: BluetoothDevice = if (known != null) {
            known
        } else {
            scanForDevice(a)
        }
        connectedAddress = device.address

        // Connect.
        try {
            withTimeout(CONNECT_TIMEOUT_MS) {
                suspendCancellable<Unit> { cont ->
                    connectCont = cont
                    gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                }
            }
        } catch (e: TimeoutCancellationException) {
            forceTeardown()
            throw HardwareWalletException.Transport(
                "connect failed: timed out. Wake the Ledger, make sure Bluetooth is on, " +
                    "then retry. If it persists, Forget the Nano X in Android Bluetooth settings and re-pair.",
            )
        }

        // Negotiate a larger ATT MTU BEFORE service discovery + APDU traffic.
        // Android defaults to 23 (20 usable bytes); the Ledger BLE stack does
        // not emit notify frames until this exchange happens, so without it
        // the first APDU write goes out and no notify ever returns. Best-
        // effort: proceed on failure / timeout (some stacks ignore it).
        try {
            withTimeout(MTU_TIMEOUT_MS) {
                val signal = CompletableDeferred<Unit>()
                mtuSignal = signal
                if (gatt?.requestMtu(MTU_SIZE) != true) {
                    mtuSignal = null
                } else {
                    signal.await()
                }
            }
        } catch (e: TimeoutCancellationException) {
            android.util.Log.w("LedgerDbg", "MTU negotiation timed out; proceeding with default MTU")
            mtuSignal = null
        }

        // Discover services + characteristics, subscribe to notify.
        try {
            withTimeout(SERVICES_TIMEOUT_MS) {
                suspendCancellable<Unit> { cont ->
                    servicesCont = cont
                    gatt?.discoverServices()
                }
            }
        } catch (e: TimeoutCancellationException) {
            forceTeardown()
            throw HardwareWalletException.Transport("Ledger BLE service discovery timed out")
        }
    }

    @SuppressLint("MissingPermission")
    private fun findKnownDevice(a: BluetoothAdapter): BluetoothDevice? {
        val manager = bluetoothManager ?: return null
        val connected = try {
            manager.getConnectedDevices(BluetoothProfile.GATT)
        } catch (_: SecurityException) {
            emptyList<BluetoothDevice>()
        }
        val target = targetAddress
        // We can't read the device's advertised services from a bonded
        // handle here, so when a target MAC is pinned, match it directly;
        // otherwise fall back to the first system-connected device (the
        // SERVICE_UUID scan handles the unknown case).
        if (target != null) {
            return connected.firstOrNull { it.address == target }
                ?: try { a.getRemoteDevice(target) } catch (_: IllegalArgumentException) { null }
        }
        return null
    }

    private var scanCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    private suspend fun scanForDevice(a: BluetoothAdapter): BluetoothDevice {
        val scanner = a.bluetoothLeScanner
            ?: throw HardwareWalletException.Transport("BLE scanner unavailable")
        val target = targetAddress
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        return try {
            withTimeout(SCAN_TIMEOUT_MS) {
                suspendCancellable<BluetoothDevice> { cont ->
                    val cb = object : ScanCallback() {
                        override fun onScanResult(callbackType: Int, result: ScanResult) {
                            val dev = result.device
                            // Multi-device safety: ignore other Ledgers
                            // when a target MAC was pinned.
                            if (target != null && dev.address != target) return
                            scanner.stopScan(this)
                            scanCallback = null
                            if (cont.isActive) cont.resumeWith(Result.success(dev))
                        }

                        override fun onScanFailed(errorCode: Int) {
                            scanCallback = null
                            if (cont.isActive) {
                                cont.resumeWith(
                                    Result.failure(
                                        HardwareWalletException.Transport("BLE scan failed (code $errorCode)"),
                                    ),
                                )
                            }
                        }
                    }
                    scanCallback = cb
                    cont.invokeOnCancellation {
                        try { scanner.stopScan(cb) } catch (_: Exception) {}
                        scanCallback = null
                    }
                    scanner.startScan(filters, settings, cb)
                }
            }
        } catch (e: TimeoutCancellationException) {
            scanCallback?.let { scanner.stopScan(it) }
            scanCallback = null
            throw HardwareWalletException.Transport(
                "Didn't see your Ledger. Make sure it's unlocked, the right app is open, " +
                    "and Bluetooth is on, then retry.",
            )
        }
    }

    private fun isConnected(g: BluetoothGatt): Boolean {
        val manager = bluetoothManager ?: return false
        return try {
            manager.getConnectionState(g.device, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED
        } catch (_: SecurityException) {
            false
        }
    }

    // ------------------------------------------------------------------
    // Framing + write
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private suspend fun writeFramedPackets(
        g: BluetoothGatt,
        writeC: BluetoothGattCharacteristic,
        apdu: ByteArray,
        mtu: Int,
    ) {
        for (packet in framedPackets(apdu, mtu)) {
            // Serialize: Android permits one outstanding GATT write at a time,
            // so install a write signal and wait for onCharacteristicWrite
            // before sending the next packet. Firing them back-to-back drops
            // every packet after the first and the device waits forever for
            // the rest of the APDU (then we time out). iOS CoreBluetooth
            // queues these writes for us; Android does not.
            val signal = CompletableDeferred<Unit>()
            writeSignal = signal
            // Newer API (33+) signature first; fall back to the
            // deprecated setValue + writeCharacteristic for older AARs.
            val ok = try {
                g.writeCharacteristic(
                    writeC,
                    packet,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothGatt.GATT_SUCCESS
            } catch (_: NoSuchMethodError) {
                writeC.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                writeC.value = packet
                g.writeCharacteristic(writeC)
            }
            if (!ok) {
                writeSignal = null
                pendingApdu.getAndSet(null)?.deferred?.completeExceptionally(
                    HardwareWalletException.Transport("BLE characteristic write failed"),
                )
                return
            }
            try {
                withTimeout(WRITE_TIMEOUT_MS) { signal.await() }
            } catch (e: TimeoutCancellationException) {
                writeSignal = null
                android.util.Log.w("LedgerDbg", "framed-packet write ack timed out")
                pendingApdu.getAndSet(null)?.deferred?.completeExceptionally(
                    HardwareWalletException.Transport("BLE characteristic write ack timed out"),
                )
                return
            }
        }
    }

    /**
     * Ledger 5-byte BLE framing: TAG || idx(BE16) || [totalLen(BE16) only
     * on packet 0] || payload. Chunked at `mtu`. Mirrors framedPackets
     * in LedgerBLE.swift.
     */
    private fun framedPackets(apdu: ByteArray, mtu: Int): List<ByteArray> {
        val packets = ArrayList<ByteArray>()
        var index = 0
        var offset = 0
        while (offset < apdu.size) {
            val out = ArrayList<Byte>()
            out.add(BLE_APDU_TAG)
            out.add(((index ushr 8) and 0xFF).toByte())
            out.add((index and 0xFF).toByte())
            var take = mtu - 3
            if (index == 0) {
                out.add(((apdu.size ushr 8) and 0xFF).toByte())
                out.add((apdu.size and 0xFF).toByte())
                take -= 2
            }
            val end = minOf(offset + take, apdu.size)
            for (i in offset until end) out.add(apdu[i])
            packets.add(out.toByteArray())
            offset = end
            index += 1
        }
        return packets
    }

    /** Strip framing from one inbound notify packet, append to the
     *  reassembly buffer, complete the deferred when the full APDU lands. */
    private fun onNotifyPacket(value: ByteArray) {
        val pending = pendingApdu.get() ?: return
        if (value.size < 3 || value[0] != BLE_APDU_TAG) return
        val packetIdx = ((value[1].toInt() and 0xFF) shl 8) or (value[2].toInt() and 0xFF)
        var payloadStart = 3
        if (packetIdx == 0) {
            if (value.size < 5) return
            pending.totalLength = ((value[3].toInt() and 0xFF) shl 8) or (value[4].toInt() and 0xFF)
            payloadStart = 5
        }
        for (i in payloadStart until value.size) pending.buffer.add(value[i])
        if (pending.totalLength >= 0 && pending.buffer.size >= pending.totalLength) {
            pendingApdu.set(null)
            val full = ByteArray(pending.totalLength) { pending.buffer[it] }
            pending.deferred.complete(full)
        }
    }

    // ------------------------------------------------------------------
    // Keep-alive heartbeat
    // ------------------------------------------------------------------

    /**
     * Runs [block] (awaiting the APDU response) while periodically
     * reading the Battery Level characteristic to keep the BLE link
     * alive. 400ms warmup, 500ms interval. Verified-stable; do NOT
     * tighten.
     */
    @SuppressLint("MissingPermission")
    private suspend fun <T> runWithHeartbeat(g: BluetoothGatt, block: suspend () -> T): T =
        coroutineScope {
            val heartbeat = launch {
                try {
                    delay(HEARTBEAT_INITIAL_DELAY_MS)
                    while (isActive) {
                        val batChar = batteryLevelChar
                        if (batChar == null || !isConnected(g)) break
                        try { g.readCharacteristic(batChar) } catch (_: SecurityException) { break }
                        delay(HEARTBEAT_INTERVAL_MS)
                    }
                } catch (_: Exception) {
                    // Cancellation: the parent op completed.
                }
            }
            try {
                block()
            } finally {
                heartbeat.cancel()
            }
        }

    // ------------------------------------------------------------------
    // GATT callback
    // ------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectCont?.let { if (it.isActive) it.resumeWith(Result.success(Unit)) }
                    connectCont = null
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val msg = "Ledger disconnected (status $status). Wake + unlock the device, " +
                        "make sure the right app is open, then retry."
                    pendingApdu.getAndSet(null)?.deferred?.completeExceptionally(
                        HardwareWalletException.Transport(msg),
                    )
                    connectCont?.let { if (it.isActive) it.resumeWith(Result.failure(HardwareWalletException.Transport(msg))) }
                    connectCont = null
                    servicesCont?.let { if (it.isActive) it.resumeWith(Result.failure(HardwareWalletException.Transport(msg))) }
                    servicesCont = null
                    writeChar = null
                    notifyChar = null
                    batteryLevelChar = null
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            android.util.Log.d("LedgerDbg", "onMtuChanged mtu=$mtu status=$status")
            mtuSignal?.let { if (!it.isCompleted) it.complete(Unit) }
            mtuSignal = null
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid != WRITE_UUID) return
            val signal = writeSignal ?: return
            writeSignal = null
            if (signal.isCompleted) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                signal.complete(Unit)
            } else {
                signal.completeExceptionally(
                    HardwareWalletException.Transport("Ledger BLE write failed (status=$status)"),
                )
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                servicesCont?.let {
                    if (it.isActive) it.resumeWith(Result.failure(HardwareWalletException.Transport("service discovery failed ($status)")))
                }
                servicesCont = null
                return
            }
            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                servicesCont?.let {
                    if (it.isActive) it.resumeWith(Result.failure(HardwareWalletException.Transport("Ledger APDU service not found")))
                }
                servicesCont = null
                return
            }
            writeChar = service.getCharacteristic(WRITE_UUID)
            notifyChar = service.getCharacteristic(NOTIFY_UUID)
            // Battery service is best-effort for keep-alive only.
            batteryLevelChar = g.getService(BATTERY_SERVICE_UUID)?.getCharacteristic(BATTERY_LEVEL_UUID)

            val nc = notifyChar
            if (writeChar == null || nc == null) {
                servicesCont?.let {
                    if (it.isActive) it.resumeWith(Result.failure(HardwareWalletException.Transport("Ledger write/notify characteristic missing")))
                }
                servicesCont = null
                return
            }
            // Subscribe: enable locally + write the CCCD with the value that
            // matches the characteristic's ACTUAL property. iOS setNotifyValue
            // auto-picks notify-vs-indicate; Android does not, and writing the
            // wrong one means the device never pushes its APDU responses (the
            // exact "write acked, zero notify back" symptom). Log both chars'
            // properties so a device run is conclusive about notify/indicate +
            // the write char's write-with/without-response support.
            g.setCharacteristicNotification(nc, true)
            val props = nc.properties
            val hasNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            val hasIndicate = (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            val cccdValue = if (!hasNotify && hasIndicate) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            android.util.Log.d(
                "LedgerDbg",
                "notify char props=0x${props.toString(16)} notify=$hasNotify indicate=$hasIndicate -> ${if (cccdValue.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) "INDICATE" else "NOTIFY"}; " +
                    "write char props=0x${writeChar?.properties?.toString(16)}",
            )
            val cccd = nc.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                @Suppress("DEPRECATION")
                try {
                    g.writeDescriptor(cccd, cccdValue)
                } catch (_: NoSuchMethodError) {
                    cccd.value = cccdValue
                    g.writeDescriptor(cccd)
                }
                // Readiness completes on onDescriptorWrite below.
            } else {
                // No CCCD: notifications enabled locally is the best we
                // can do; proceed.
                servicesCont?.let { if (it.isActive) it.resumeWith(Result.success(Unit)) }
                servicesCont = null
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD_UUID) {
                android.util.Log.d("LedgerDbg", "notifications enabled (CCCD write status=$status)")
                servicesCont?.let { if (it.isActive) it.resumeWith(Result.success(Unit)) }
                servicesCont = null
            }
        }

        // API 33+ delivers the value directly.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == NOTIFY_UUID) onNotifyPacket(value)
        }

        @Suppress("DEPRECATION")
        @Deprecated("Pre-API-33 callback retained for older AAR runtimes")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == NOTIFY_UUID) {
                characteristic.value?.let { onNotifyPacket(it) }
            }
        }

        // Battery reads are keep-alive only; consume silently so they
        // never reach the APDU state machine.
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            // no-op (keep-alive)
        }
    }
}

// Small bridge from a CancellableContinuation-style suspend point. We
// keep this local helper rather than pulling in extra deps; it mirrors
// the iOS withCheckedThrowingContinuation pattern.
private suspend inline fun <T> suspendCancellable(
    crossinline block: (CancellableContinuation<T>) -> Unit,
): T = kotlinx.coroutines.suspendCancellableCoroutine { cont -> block(cont) }
