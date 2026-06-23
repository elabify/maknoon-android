// Holder DID derivation, identical to iOS IdentitySandwich.holderDID:
//   did:elabify:sepolia:holder:0x<hex( rpo256Tagged(0x03, masterPublicKey)[0..20] )>
// Stable across delegation renewals and across passphrase-aware recovery on
// a new device (it depends only on the master public key).

package com.elabify.musnad.identity

import com.elabify.core.rpo256Tagged
import com.elabify.musnad.crypto.toHex

object HolderDid {
    /** RPO-256 domain tag for the holder-DID fingerprint. */
    private const val HOLDER_DID_TAG = 0x03
    private const val FINGERPRINT_LEN = 20
    const val PREFIX = "did:elabify:sepolia:holder:0x"

    fun fromMasterPublicKey(masterPublicKey: ByteArray): String {
        val fingerprint = rpo256Tagged(HOLDER_DID_TAG, masterPublicKey).copyOfRange(0, FINGERPRINT_LEN)
        return PREFIX + fingerprint.toHex()
    }
}
