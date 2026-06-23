// Per-chain SDK Transport adapters. Each bridges the chain's
// UniFFI-generated foreign Transport callback (suspend exchange) into
// the shared LedgerBleTransport.sendApdu, mirroring the four
// *TransportAdapter classes in LedgerBLE.swift. The SDK client invokes
// `exchange(apdu)` per APDU; we route bytes through the one BLE channel
// and wrap the result in the chain-specific ExchangeResponse type.

package com.elabify.musnad.hardware.ledger

import com.elabify.musnad.hardware.HardwareWalletException
import uniffi.ledger_btc_core.ExchangeResponse as BtcExchangeResponse
import uniffi.ledger_btc_core.Transport as BtcTransport
import uniffi.ledger_btc_core.TransportException as BtcTransportException
import uniffi.ledger_eth_core.EthExchangeResponse
import uniffi.ledger_eth_core.EthLedgerTransport
import uniffi.ledger_eth_core.EthTransportException
import uniffi.ledger_sol_core.SolanaExchangeResponse
import uniffi.ledger_sol_core.SolanaLedgerTransport
import uniffi.ledger_sol_core.SolanaTransportException
import uniffi.ledger_tron_core.TronExchangeResponse
import uniffi.ledger_tron_core.TronLedgerTransport
import uniffi.ledger_tron_core.TronTransportException

internal class BitcoinTransportAdapter(private val ble: LedgerBleTransport) : BtcTransport {
    override suspend fun exchange(apdu: ByteArray): BtcExchangeResponse {
        try {
            val resp = ble.sendApdu(apdu)
            return BtcExchangeResponse(statusWord = resp.statusWord, data = resp.data)
        } catch (e: HardwareWalletException) {
            throw BtcTransportException.Io(reason = e.message ?: "BLE transport error")
        } catch (e: Exception) {
            throw BtcTransportException.Io(reason = e.message ?: e.toString())
        }
    }
}

internal class EthereumTransportAdapter(private val ble: LedgerBleTransport) : EthLedgerTransport {
    override suspend fun exchange(apdu: ByteArray): EthExchangeResponse {
        try {
            val resp = ble.sendApdu(apdu)
            return EthExchangeResponse(statusWord = resp.statusWord, data = resp.data)
        } catch (e: HardwareWalletException) {
            throw EthTransportException.Io(reason = e.message ?: "BLE transport error")
        } catch (e: Exception) {
            throw EthTransportException.Io(reason = e.message ?: e.toString())
        }
    }
}

internal class SolanaTransportAdapter(private val ble: LedgerBleTransport) : SolanaLedgerTransport {
    override suspend fun exchange(apdu: ByteArray): SolanaExchangeResponse {
        try {
            val resp = ble.sendApdu(apdu)
            return SolanaExchangeResponse(statusWord = resp.statusWord, data = resp.data)
        } catch (e: HardwareWalletException) {
            throw SolanaTransportException.Io(reason = e.message ?: "BLE transport error")
        } catch (e: Exception) {
            throw SolanaTransportException.Io(reason = e.message ?: e.toString())
        }
    }
}

internal class TronTransportAdapter(private val ble: LedgerBleTransport) : TronLedgerTransport {
    override suspend fun exchange(apdu: ByteArray): TronExchangeResponse {
        try {
            val resp = ble.sendApdu(apdu)
            return TronExchangeResponse(statusWord = resp.statusWord, data = resp.data)
        } catch (e: HardwareWalletException) {
            throw TronTransportException.Io(reason = e.message ?: "BLE transport error")
        } catch (e: Exception) {
            throw TronTransportException.Io(reason = e.message ?: e.toString())
        }
    }
}
