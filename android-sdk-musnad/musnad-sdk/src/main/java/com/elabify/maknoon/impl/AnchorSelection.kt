// Which on-chain anchor the facade verifies against, in one place.
//
// ADR-0022's amendment changed what `rootCurrent` means. It used to be a
// freshness question answered by RevocationRegistry.isRootRecent, and it fired
// whenever a batch root was present. It is now a provenance question: was this
// root genuinely published by the issuer, answered by fetching the anchor
// TRANSACTION and looking for a RootUpdated(did, root) log from the expected
// registry. So OnChainVerifier gates the whole tier on `anchorBatchTxHash`
// being non-empty, and the parameter defaults to null.
//
// The facade was written before that change and passes only `anchorBatchRoot`.
// It still compiles, and the tier silently stops running: `rootCurrent` stays
// at its initial Unknown("Carries no on-chain anchor for a supported network"),
// which is not merely absent but wrong, since the credential does carry one.
// Nothing fails, and `fullyVerified` just quietly never becomes true.
//
// Three call sites had the same bug, each with its own inline
// `anchors.firstOrNull()`. They select through here now so a future change to
// what an anchor means cannot fix two of them and miss the third.

package com.elabify.maknoon.impl

import com.elabify.musnad.present.AnchorDescriptor
import com.elabify.musnad.present.AnchorEntry

/** Sepolia. Preferred when a credential is anchored on several chains. */
private const val SEPOLIA_CAIP2 = "eip155:11155111"

/**
 * Pick the anchor to verify against.
 *
 * Prefers Sepolia, then falls back to the first entry. The app makes the
 * richer choice of "the first anchor whose chain has a configured RPC", but
 * [MaknoonConfig] carries a single `chainRpcUrl` rather than a per-chain map,
 * so the facade cannot ask that question yet. Recorded in the facade contract
 * as a known gap: a credential anchored only on a chain the host has no RPC
 * for will report `rootCurrent` unknown rather than failing.
 */
internal fun AnchorDescriptor?.selected(): AnchorEntry? {
    val all = this?.anchors.orEmpty()
    return all.firstOrNull { it.chain == SEPOLIA_CAIP2 } ?: all.firstOrNull()
}

/**
 * The anchor's own registry address, or null to let the verifier fall back to
 * the one in RegistryConfig.
 *
 * Revocation and root are read on the ANCHOR's chain, which is not necessarily
 * the identity chain, so the anchor names the registry that actually holds
 * them. Dropping it silently reads the wrong contract whenever a credential is
 * anchored somewhere other than the identity chain.
 */
internal fun AnchorEntry?.registryOrNull(): String? = this?.registry?.takeIf { it.isNotBlank() }

/** The anchor transaction hash. `rootCurrent` does not run without it. */
internal fun AnchorEntry?.txHashOrNull(): String? = this?.batchTxHash?.takeIf { it.isNotBlank() }

/** The batch root. */
internal fun AnchorEntry?.rootOrNull(): String? = this?.batchRoot?.takeIf { it.isNotBlank() }
