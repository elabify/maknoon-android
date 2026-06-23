// Trezor USB byte pipe. Same TrezorTransport seam as the BLE transport,
// over android.hardware.usb bulk transfer. The THP wire protocol lives
// in the trezor-core Rust crate; this class only moves raw 64-byte USB
// reports in and out of the device's bulk IN / OUT endpoints.
//
// Trezor's USB interface exposes a vendor (WebUSB) interface with one
// bulk IN and one bulk OUT endpoint. We claim that interface, then read
// one report per readChunk() and write one per writeChunk(); the Rust
// THP layer reassembles multi-report messages, so this class never
// inspects the bytes.

package com.elabify.musnad.hardware.trezor

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.elabify.musnad.hardware.HardwareWalletException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.trezor_core.TrezorTransport
import uniffi.trezor_core.TrezorTransportException

/**
 * GATT-less USB transport to a Trezor. Construct with the [UsbManager]
 * and the already-permission-granted [UsbDevice] (the host requests USB
 * permission before constructing this, the Android analog of being
 * physically plugged in). The connection is opened lazily on first I/O.
 */
class TrezorUsbTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
) : TrezorTransport {

    /** Stable identifier the wallet records (USB has no BLE MAC). */
    val deviceName: String get() = device.deviceName

    private val connectionLock = Mutex()

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null

    override suspend fun writeChunk(data: ByteArray) {
        try {
            ensureOpen()
            val conn = connection ?: throw HardwareWalletException.Transport("Trezor USB not connected")
            val out = endpointOut ?: throw HardwareWalletException.Transport("Trezor USB OUT endpoint missing")
            withContext(Dispatchers.IO) {
                val sent = conn.bulkTransfer(out, data, data.size, WRITE_TIMEOUT_MS)
                if (sent < 0) {
                    throw HardwareWalletException.Transport("Trezor USB write failed")
                }
            }
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
            ensureOpen()
            val conn = connection ?: throw HardwareWalletException.Transport("Trezor USB not connected")
            val inEp = endpointIn ?: throw HardwareWalletException.Transport("Trezor USB IN endpoint missing")
            val size = if (inEp.maxPacketSize > 0) inEp.maxPacketSize else REPORT_SIZE
            return withContext(Dispatchers.IO) {
                val buf = ByteArray(size)
                val read = conn.bulkTransfer(inEp, buf, buf.size, READ_TIMEOUT_MS)
                if (read < 0) {
                    throw TrezorTransportException.Timeout("Trezor USB read timed out")
                }
                if (read == buf.size) buf else buf.copyOfRange(0, read)
            }
        } catch (e: HardwareWalletException) {
            throw TrezorTransportException.Io(e.message ?: "read failed")
        } catch (e: TrezorTransportException) {
            throw e
        } catch (e: Throwable) {
            throw TrezorTransportException.Io(e.message ?: "read failed")
        }
    }

    private suspend fun ensureOpen() = connectionLock.withLock {
        if (connection != null && endpointIn != null && endpointOut != null) return@withLock

        if (!usbManager.hasPermission(device)) {
            throw HardwareWalletException.Transport(
                "Grant USB permission for the Trezor, then try again."
            )
        }

        // Find the first interface exposing both a bulk IN and a bulk OUT
        // endpoint (Trezor's WebUSB / vendor interface).
        var chosen: UsbInterface? = null
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var foundIn: UsbEndpoint? = null
            var foundOut: UsbEndpoint? = null
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) foundIn = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT) foundOut = ep
            }
            if (foundIn != null && foundOut != null) {
                chosen = iface
                inEp = foundIn
                outEp = foundOut
                break
            }
        }
        val iface = chosen
            ?: throw HardwareWalletException.Transport("No Trezor bulk interface on this USB device")

        val conn = usbManager.openDevice(device)
            ?: throw HardwareWalletException.Transport("Could not open the Trezor USB device")
        if (!conn.claimInterface(iface, true)) {
            conn.close()
            throw HardwareWalletException.Transport("Could not claim the Trezor USB interface")
        }

        connection = conn
        usbInterface = iface
        endpointIn = inEp
        endpointOut = outEp
    }

    /** Release the interface and close the connection. */
    fun teardown() {
        val conn = connection
        val iface = usbInterface
        if (conn != null && iface != null) {
            try {
                conn.releaseInterface(iface)
            } catch (_: Throwable) {
            }
        }
        try {
            conn?.close()
        } catch (_: Throwable) {
        }
        connection = null
        usbInterface = null
        endpointIn = null
        endpointOut = null
    }

    companion object {
        private const val REPORT_SIZE = 64
        private const val WRITE_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}
