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
 */
data class MiniAppCapabilitySpec(
    val token: String,
    val tier: CapabilityTier,
    val label: String,
    /** Default reason; a catalog entry may override. */
    val reason: String,
    /** Material icon name (e.g. for androidx.compose.material.icons lookup). */
    val icon: String,
) {
    val id: String get() = token
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
            label = "Verify Credentials",
            reason = "Receive a customer's credentials and perform checks on them.",
            icon = "Badge",
        ),
        "payment" to MiniAppCapabilitySpec(
            token = "payment", tier = CapabilityTier.PER_USE,
            label = "Payments",
            reason = "Make a payment to a receiving address.",
            icon = "CreditCard",
        ),
        // Hierarchical per-network wallet capabilities (ADR-0057). The dotted
        // token is wallet.<network>.<capability>; granularity is per-network
        // (Ethereum covers all EVM chains). read is INSTALL (silent RPC reads +
        // connect + chain switch); write/sign are PER_USE (a native approval
        // sheet runs each time). Bitcoin/Solana are reserved by the ADR.
        "wallet.ethereum.read" to MiniAppCapabilitySpec(
            token = "wallet.ethereum.read", tier = CapabilityTier.INSTALL,
            label = "Ethereum: Network Access",
            reason = "Read Ethereum network state (balances, gas, contract reads) and switch chains.",
            icon = "Lan",
        ),
        "wallet.ethereum.write" to MiniAppCapabilitySpec(
            token = "wallet.ethereum.write", tier = CapabilityTier.PER_USE,
            label = "Ethereum: Write Transactions",
            reason = "Submit Ethereum transactions from your wallet. You approve each one.",
            icon = "Send",
        ),
        "wallet.ethereum.sign" to MiniAppCapabilitySpec(
            token = "wallet.ethereum.sign", tier = CapabilityTier.PER_USE,
            label = "Ethereum: Sign Messages",
            reason = "Sign Ethereum messages and typed data. You approve each one.",
            icon = "Draw",
        ),
        // Legacy flat token, superseded by wallet.ethereum.* (expanded at parse).
        "evm" to MiniAppCapabilitySpec(
            token = "evm", tier = CapabilityTier.PER_USE,
            label = "Ethereum wallet",
            reason = "Connect and request signatures or transactions",
            icon = "Link",
        ),
        "wallet" to MiniAppCapabilitySpec(
            token = "wallet", tier = CapabilityTier.INSTALL,
            label = "Wallets",
            reason = "See your wallet labels, addresses, and assets across networks and chains.",
            icon = "AccountBalanceWallet",
        ),
        "scan" to MiniAppCapabilitySpec(
            token = "scan", tier = CapabilityTier.PER_USE,
            label = "Scan codes",
            reason = "Open the camera to scan a QR or barcode",
            icon = "QrCodeScanner",
        ),
        "share" to MiniAppCapabilitySpec(
            token = "share", tier = CapabilityTier.INSTALL,
            label = "Share",
            reason = "Share content using the system share sheet",
            icon = "Share",
        ),
        "clipboard" to MiniAppCapabilitySpec(
            token = "clipboard", tier = CapabilityTier.INSTALL,
            label = "Clipboard",
            reason = "Copy text to your clipboard",
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
     */
    fun disclosable(tokens: Set<String>): List<MiniAppCapabilitySpec> =
        tokens.mapNotNull { specs[it.lowercase()] }
            .sortedWith(
                compareBy<MiniAppCapabilitySpec> { it.tier != CapabilityTier.PER_USE }
                    .thenBy { it.label }
            )
}
