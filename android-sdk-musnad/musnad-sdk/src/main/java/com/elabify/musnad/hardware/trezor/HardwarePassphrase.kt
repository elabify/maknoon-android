// Trezor hidden (BIP39 passphrase) wallet support, app-side model.
// Ported 1:1 from iOS Maknoon/HardwareWallet/HardwarePassphrase.swift.
//
// On a Trezor the passphrase is a PER-SESSION value, not a device-
// stored, PIN-protected secret. Each distinct passphrase yields a
// different seed, hence different addresses, hence a distinct "hidden"
// wallet that coexists with the standard (empty-passphrase) wallet.
// Ledger has no analog: its passphrase lives on the device and is
// opaque to the host, so Ledger wallets are always "standard" and
// these types never touch the Ledger path.
//
// We intentionally NEVER persist a host-typed passphrase. A hidden
// wallet only records HOW its passphrase is entered, and the secret is
// supplied fresh at every signing:
//
//   * PassphraseChoice is the transient in-memory value handed to
//     TrezorHardwareWallet.applyPassphraseMode(_) for one operation.
//   * HardwarePassphraseRef is what gets persisted on a descriptor:
//     null = standard wallet, OnDevice = passphrase entered on the
//     Trezor each time, HostEntry = passphrase re-typed on the phone
//     each time (no secret is stored anywhere).

package com.elabify.musnad.hardware.trezor

import com.elabify.musnad.hardware.HardwareWalletException
import org.json.JSONObject

/** The passphrase mode chosen for a single operation. In-memory only. */
sealed class PassphraseChoice {
    /** Standard wallet, empty passphrase. Identical to Ledger behavior. */
    object Standard : PassphraseChoice()

    /** User types the passphrase on the Trezor; the phone never sees it. */
    object OnDevice : PassphraseChoice()

    /** User typed the passphrase on the host for this operation. */
    data class HostTyped(val passphrase: String) : PassphraseChoice()
}

/**
 * Persisted on a hardware wallet descriptor. `null` (absent) means the
 * standard wallet. It records only the ENTRY METHOD for a hidden
 * wallet, never the passphrase itself.
 */
enum class HardwarePassphraseRef(val wireId: String) {
    /** Passphrase is re-entered on the Trezor each session. */
    ON_DEVICE("onDevice"),

    /** Passphrase is re-typed on the phone at each signing (not stored). */
    HOST_ENTRY("hostEntry");

    /**
     * Whether signing this wallet needs a host-typed passphrase entered
     * up front (the send UI shows a passphrase field; on-device entry
     * and standard wallets don't need one).
     */
    val needsHostPassphrase: Boolean get() = this == HOST_ENTRY

    companion object {
        /**
         * Decode a persisted discriminator string tolerantly. A legacy
         * `hostStored` maps to `HOST_ENTRY` (its stored copy is just
         * abandoned), so descriptors never fail to decode.
         */
        fun fromWireId(raw: String?): HardwarePassphraseRef? = when (raw) {
            null -> null
            "onDevice" -> ON_DEVICE
            else -> HOST_ENTRY
        }

        /**
         * Resolve a persisted binding to the in-memory passphrase choice a
         * signing op opens its session with. `hostEntered` is the
         * passphrase the user just typed for THIS signing (never stored);
         * it is required for `HOST_ENTRY` and ignored otherwise.
         */
        @Throws(HardwareWalletException::class)
        fun resolveChoice(
            hidden: HardwarePassphraseRef?,
            hostEntered: String? = null,
        ): PassphraseChoice = when (hidden) {
            null -> PassphraseChoice.Standard
            ON_DEVICE -> PassphraseChoice.OnDevice
            HOST_ENTRY -> {
                val pass = hostEntered?.trim().orEmpty()
                if (pass.isEmpty()) {
                    throw HardwareWalletException.Transport(
                        "Enter this hidden wallet's passphrase to sign."
                    )
                }
                PassphraseChoice.HostTyped(pass)
            }
        }

        /**
         * The persisted binding for a wallet being added under `selection`.
         * Records only the entry method; no secret is written anywhere.
         */
        fun persist(selection: HiddenWalletSelection): HardwarePassphraseRef? = when (selection) {
            HiddenWalletSelection.STANDARD -> null
            HiddenWalletSelection.ON_DEVICE -> ON_DEVICE
            HiddenWalletSelection.HOST_TYPED -> HOST_ENTRY
        }

        // ----------------------------------------------------------------
        // JSONObject <-> wireId glue.
        //
        // The Bitcoin / Solana / Tron descriptors persist `hidden` as a
        // `JSONObject?` (opaque-object column), while this ref is fundamentally
        // a single discriminator string (the Ethereum descriptor stores it as a
        // bare string). These two helpers bridge the two representations so the
        // app reads / writes the ref consistently regardless of which
        // descriptor it sits on. The object shape is `{"ref":"onDevice"}` /
        // `{"ref":"hostEntry"}`; a null ref (standard wallet) is the absent key
        // (`null` JSONObject), never a stored empty object.
        // ----------------------------------------------------------------

        /** Decode a persisted `hidden` JSON object into a ref. A null object
         *  (standard wallet) decodes to null; anything else is parsed
         *  tolerantly via [fromWireId] (so a legacy `hostStored` still maps to
         *  HOST_ENTRY and descriptors never fail to decode). */
        fun fromJson(o: JSONObject?): HardwarePassphraseRef? {
            if (o == null) return null
            val raw = o.optString("ref", "").ifEmpty { null } ?: return null
            return fromWireId(raw)
        }

        /** Encode a ref back to the persisted `hidden` JSON object, or null for
         *  the standard wallet (so the descriptor omits the key entirely). */
        fun toJson(ref: HardwarePassphraseRef?): JSONObject? =
            ref?.let { JSONObject().put("ref", it.wireId) }
    }

    /** This ref as the persisted `hidden` JSON object (`{"ref":wireId}`). */
    fun toJson(): JSONObject = JSONObject().put("ref", wireId)
}

/**
 * The hidden-wallet selector shown in the Trezor add / discovery flows.
 * `STANDARD` reproduces exact Ledger behavior (empty passphrase) and is
 * the default everywhere.
 */
enum class HiddenWalletSelection(val displayName: String) {
    STANDARD("Standard"),
    ON_DEVICE("On device"),
    HOST_TYPED("Type here");

    /**
     * Transient choice handed to TrezorHardwareWallet.applyPassphraseMode
     * for one operation, carrying the typed passphrase in memory only.
     */
    fun choice(hostPassphrase: String): PassphraseChoice = when (this) {
        STANDARD -> PassphraseChoice.Standard
        ON_DEVICE -> PassphraseChoice.OnDevice
        HOST_TYPED -> PassphraseChoice.HostTyped(hostPassphrase)
    }

    /** Whether the choice is actionable yet (host-typed needs text). */
    fun isReady(hostPassphrase: String): Boolean =
        this != HOST_TYPED || hostPassphrase.isNotEmpty()

    /** Human-readable explanation for the selector footer. */
    val footer: String
        get() = when (this) {
            STANDARD ->
                "The standard wallet, no passphrase. This is what you normally use."
            ON_DEVICE ->
                "Open a hidden wallet by typing its passphrase on the Trezor. The phone never sees it. A different passphrase is a different wallet."
            HOST_TYPED ->
                "Type the hidden-wallet passphrase here. It is never saved: you re-enter it on this phone for every signing. A different passphrase is a different wallet."
        }
}
