// Live BLE glue for Ethereum hardware wallets (Ledger / Trezor), the app-side
// twin of HardwareSecondFactor for the EVM read + sign paths. It owns one BLE
// connection per operation and mirrors EXACTLY the proven connection pattern
// the discover sweep + HardwareSecondFactor use:
//
//   HardwareWalletFactory.make(kind) -> beginSession() -> identifyDevice()
//   (serial-match guard) -> [pin Trezor Standard wallet + clear path override]
//   -> the op -> endSession()
//
// plus the same stale-link transport retry (HardwareWalletException.Transport
// teardown + bounded backoff) so a fresh connect right after a prior session
// (the add / read / send flows happen back to back) does not fail on a GATT
// link that is still tearing down.
//
// This file carries TWO entry points:
//   - readEthereumAddress(account): the single-account read the Add screen uses
//     (a single-account version of the discovery ETH branch).
//   - EthereumDeviceSigner: the EthereumHardwareSigner the SDK send path calls
//     to route the EIP-1559 signature onto the device.
//
// Standard (non-hidden) hardware wallets only: like HardwareSecondFactor we pin
// the Trezor Standard passphrase and clear any derivation-path override before
// the op, so the address read at add time and the signature at send time derive
// from the same standard BIP44 account the descriptor recorded.

package com.elabify.app.maknoon.ui.wallet.ethereum

import com.elabify.app.maknoon.ui.wallet.common.withHardwareDevice
import com.elabify.musnad.devices.RegisteredDevice
import com.elabify.musnad.hardware.HardwareWallet
import com.elabify.musnad.hardware.trezor.HardwarePassphraseRef
import com.elabify.musnad.hardware.trezor.PassphraseChoice
import com.elabify.musnad.wallet.ethereum.EthereumHardwareSigner
import com.elabify.musnad.wallet.ethereum.EthereumTxPlan
import com.elabify.musnad.wallet.ethereum.EthereumWalletDescriptor
import kotlinx.coroutines.runBlocking

/**
 * Connect to [device] over BLE, run [op] inside one pinned session, and tear
 * down. A thin wrapper over the shared [withHardwareDevice] that threads the
 * resolved Trezor passphrase mode ([passphraseChoice]) and any custom
 * derivation-path override ([derivationPath]) so the address read at add time
 * and the signature at send time derive from the SAME (possibly hidden /
 * custom-path) wallet the descriptor recorded. Defaults to the standard,
 * standard-path wallet (the byte-for-byte prior Ethereum behaviour). Suspend;
 * call off the main thread.
 */
private suspend fun <T> withEthereumDevice(
    device: RegisteredDevice,
    passphraseChoice: PassphraseChoice = PassphraseChoice.Standard,
    derivationPath: String? = null,
    op: suspend (HardwareWallet) -> T,
): T = withHardwareDevice(
    device = device,
    passphraseChoice = passphraseChoice,
    derivationPath = derivationPath,
    op = op,
)

/**
 * Read the EVM (EIP-55) address for [account] off [device]. A single-account
 * version of the discovery ETH branch (getEthereumAddress), reusing the same
 * connection + serial-guard + retry. [hidden] / [hostPassphrase] /
 * [derivationPath] select a hidden (Trezor passphrase) or custom-path wallet so
 * the address read matches what the same wallet will sign with later; the
 * defaults read the standard BIP44 account. Suspend; call off the main thread.
 */
suspend fun readEthereumHardwareAddress(
    device: RegisteredDevice,
    account: Long,
    hidden: HardwarePassphraseRef? = null,
    derivationPath: String? = null,
    hostPassphrase: String? = null,
): String {
    val choice = HardwarePassphraseRef.resolveChoice(hidden, hostPassphrase)
    return withEthereumDevice(device, choice, derivationPath) { it.getEthereumAddress(account) }
}

/**
 * The EthereumHardwareSigner the SDK's EthereumWallet.sendHardware calls. It
 * connects to the wallet's bound [device] over BLE, hands the unsigned 0x02
 * EIP-1559 envelope to the device for an on-screen-confirmed signature, and
 * returns the 65-byte r||s||v the SDK reassembles + broadcasts. The recovery
 * byte is the type-2 parity bit (0/1) both vendors return directly.
 *
 * signEip1559 is a plain (non-suspend) call in the SDK contract, but the device
 * ops are suspend; sendHardware already runs on Dispatchers.IO (the send screen
 * wraps it in withContext(Dispatchers.IO)), so we bridge with runBlocking here,
 * exactly as the rest of the hardware glue does for its IO-bound device calls.
 */
class EthereumDeviceSigner(
    private val device: RegisteredDevice,
    private val account: Long,
    /** Optional Ledger CAL token blob for ERC-20 clear-signing; null for native
     *  sends and when no descriptor is available. */
    private val erc20Descriptor: ByteArray? = null,
    /** Host-typed hidden-wallet passphrase for THIS signing (never stored); null
     *  for standard / on-device-passphrase wallets. The hidden CONFIG + custom
     *  derivation path are read from the descriptor passed to signEip1559. */
    private val hostPassphrase: String? = null,
) : EthereumHardwareSigner {

    override fun signEip1559(
        plan: EthereumTxPlan,
        unsignedEnvelope: ByteArray,
        descriptor: EthereumWalletDescriptor,
    ): ByteArray = runBlocking {
        // Re-apply the descriptor's hidden (Trezor passphrase) mode + custom
        // derivation path so the device derives the SAME wallet that was added,
        // instead of always signing the standard BIP44 account.
        val hiddenRef = HardwarePassphraseRef.fromWireId(descriptor.hidden)
        val choice = HardwarePassphraseRef.resolveChoice(hiddenRef, hostPassphrase)
        withEthereumDevice(device, choice, descriptor.derivationPath) { wallet ->
            val sig = wallet.signEthereumTransaction(
                envelope = unsignedEnvelope,
                account = account,
                erc20Descriptor = erc20Descriptor,
            )
            // Reassemble r(32) || s(32) || v(1). v is the EIP-1559 parity bit.
            leftPad32(sig.r) + leftPad32(sig.s) + byteArrayOf((sig.v and 0x01).toByte())
        }
    }
}

/** Left-pad (or right-trim) a big-endian integer to exactly 32 bytes. */
private fun leftPad32(b: ByteArray): ByteArray {
    if (b.size == 32) return b
    if (b.size > 32) return b.copyOfRange(b.size - 32, b.size)
    val out = ByteArray(32)
    System.arraycopy(b, 0, out, 32 - b.size, b.size)
    return out
}
