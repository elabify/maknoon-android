// Single source of truth for mini-app capabilities: the things a dApp can
// ask the host to do. Android port of the iOS MiniAppCapability.swift.
//
// Each capability token (the same string a namespace handler declares as
// its requiredPermission) has a consent tier, a human-facing label and
// reason, and a Material icon name for the install/settings UI.
//
// Tiers:
//   AUTO    : always available, low-risk; not declared, not shown, no consent
//             (storage, fiat, device, haptics, biometric gate).
//   INSTALL : must be declared and SHOWN at install for the user to accept;
//             granted for the app's lifetime (addressBook, wallet read,
//             share, clipboard).
//   PER_USE : declared at install AND prompted natively every time it runs
//             (identity disclosure, payments, signing, camera scan, NFC tap).
//
// Trust: the host mediates all of these; raw sensors and keys never reach JS.

package com.elabify.app.maknoon.miniapp

import android.content.Context
import androidx.annotation.StringRes
import com.elabify.app.maknoon.R

/** Consent tier for a capability token. */
enum class CapabilityTier(val rawValue: String) {
    AUTO("auto"),
    INSTALL("install"),
    PER_USE("perUse");

    companion object {
        fun fromRaw(value: String?): CapabilityTier? =
            entries.firstOrNull { it.rawValue == value }
    }
}

/**
 * Describes a declarable capability: its token, consent tier, a label and
 * default reason for the disclosure UI, and a Material icon name. A catalog
 * entry may override the reason at install time.
 *
 * Label and reason are string RESOURCES, not text: this registry is a static
 * map with no Context, and it is the copy the consent sheet shows before a user
 * grants a mini-app anything. Resolving at read time also means an in-app
 * language change re-renders it, which a text-valued map could not do.
 */
data class MiniAppCapabilitySpec(
    val token: String,
    val tier: CapabilityTier,
    @StringRes val labelRes: Int,
    /** Default reason; a catalog entry may override. */
    @StringRes val reasonRes: Int,
    /** Material icon name (e.g. for androidx.compose.material.icons lookup). */
    val icon: String,
) {
    val id: String get() = token

    fun label(res: Context): String = res.getString(labelRes)

    fun reason(res: Context): String = res.getString(reasonRes)
}

/**
 * Registry of known declarable capabilities. Tokens not in this map are
 * treated as AUTO (no declaration or consent needed). Mirrors the iOS
 * MiniAppCapabilityRegistry.
 */
object MiniAppCapabilityRegistry {

    /** Known declarable capabilities (tier INSTALL / PER_USE). */
    val specs: Map<String, MiniAppCapabilitySpec> = mapOf(
        "identity" to MiniAppCapabilitySpec(
            token = "identity", tier = CapabilityTier.PER_USE,
            labelRes = R.string.app_cap_identity_label,
            reasonRes = R.string.app_cap_identity_reason,
            icon = "Badge",
        ),
        "payment" to MiniAppCapabilitySpec(
            token = "payment", tier = CapabilityTier.PER_USE,
            labelRes = R.string.app_cap_payment_label,
            reasonRes = R.string.app_cap_payment_reason,
            icon = "CreditCard",
        ),
        // Hierarchical per-network wallet capabilities (ADR-0057). The dotted
        // token is wallet.<network>.<capability>; granularity is per-network
        // (Ethereum covers all EVM chains). read is INSTALL (silent RPC reads +
        // connect + chain switch); write/sign are PER_USE (a native approval
        // sheet runs each time). Bitcoin/Solana are reserved by the ADR.
        "wallet.ethereum.read" to MiniAppCapabilitySpec(
            token = "wallet.ethereum.read", tier = CapabilityTier.INSTALL,
            labelRes = R.string.app_cap_eth_read_label,
            reasonRes = R.string.app_cap_eth_read_reason,
            icon = "Lan",
        ),
        "wallet.ethereum.write" to MiniAppCapabilitySpec(
            token = "wallet.ethereum.write", tier = CapabilityTier.PER_USE,
            labelRes = R.string.app_cap_eth_write_label,
            reasonRes = R.string.app_cap_eth_write_reason,
            icon = "Send",
        ),
        "wallet.ethereum.sign" to MiniAppCapabilitySpec(
            token = "wallet.ethereum.sign", tier = CapabilityTier.PER_USE,
            labelRes = R.string.app_cap_eth_sign_label,
            reasonRes = R.string.app_cap_eth_sign_reason,
            icon = "Draw",
        ),
        // Legacy flat token, superseded by wallet.ethereum.* (expanded at parse).
        "evm" to MiniAppCapabilitySpec(
            token = "evm", tier = CapabilityTier.PER_USE,
            labelRes = R.string.app_cap_evm_label,
            reasonRes = R.string.app_cap_evm_reason,
            icon = "Link",
        ),
        "wallet" to MiniAppCapabilitySpec(
            token = "wallet", tier = CapabilityTier.INSTALL,
            labelRes = R.string.app_cap_wallet_label,
            reasonRes = R.string.app_cap_wallet_reason,
            icon = "AccountBalanceWallet",
        ),
        "scan" to MiniAppCapabilitySpec(
            token = "scan", tier = CapabilityTier.PER_USE,
            labelRes = R.string.app_cap_scan_label,
            reasonRes = R.string.app_cap_scan_reason,
            icon = "QrCodeScanner",
        ),
        "share" to MiniAppCapabilitySpec(
            token = "share", tier = CapabilityTier.INSTALL,
            labelRes = R.string.app_cap_share_label,
            reasonRes = R.string.app_cap_share_reason,
            icon = "Share",
        ),
        "clipboard" to MiniAppCapabilitySpec(
            token = "clipboard", tier = CapabilityTier.INSTALL,
            labelRes = R.string.app_cap_clipboard_label,
            reasonRes = R.string.app_cap_clipboard_reason,
            icon = "ContentCopy",
        ),
    )

    /**
     * Expand legacy flat capability tokens to their hierarchical equivalents
     * (ADR-0057 back-compat): "evm" -> wallet.ethereum.{read,write,sign}. Used
     * by every catalog-parse path so old declarations keep working.
     */
    fun expandLegacyCapabilities(tokens: Set<String>): Set<String> {
        if (!tokens.any { it.equals("evm", ignoreCase = true) }) return tokens
        val out = tokens.filterNot { it.equals("evm", ignoreCase = true) }.toMutableSet()
        out += setOf("wallet.ethereum.read", "wallet.ethereum.write", "wallet.ethereum.sign")
        return out
    }

    /** Spec for a token (case-insensitive), or null when the token is AUTO. */
    fun spec(token: String): MiniAppCapabilitySpec? = specs[token.lowercase()]

    /** True when a token needs no declaration or consent. */
    fun isAuto(token: String): Boolean = specs[token.lowercase()] == null

    /**
     * Specs for the given tokens that should be disclosed at install
     * (INSTALL / PER_USE), sorted PER_USE-first then alphabetically by label.
     * AUTO and unknown tokens are dropped.
     *
     * [res] resolves the labels the sort runs on: the order the consent sheet
     * shows must follow the LOCALIZED label, not the English one.
     */
    fun disclosable(tokens: Set<String>, res: Context): List<MiniAppCapabilitySpec> =
        tokens.mapNotNull { specs[it.lowercase()] }
            .sortedWith(
                compareBy<MiniAppCapabilitySpec> { it.tier != CapabilityTier.PER_USE }
                    .thenBy { it.label(res) }
            )
}
