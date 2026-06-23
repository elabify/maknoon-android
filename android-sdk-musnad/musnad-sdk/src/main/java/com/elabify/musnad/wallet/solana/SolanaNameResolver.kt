// SNS (Solana Name Service, ".sol") -> base58 address resolver, the Solana twin
// of ENSResolver. Resolution path: hashed-name -> name-registry account key
// (PDA under the SNS name program, parented to the .sol TLD authority) ->
// getAccountInfo -> the owner pubkey at byte offset 32 of the registry account.
//
// This resolves to the domain's OWNER (the registrant's wallet), which is the
// standard baseline a sender expects when no explicit SOL record is set. The
// optional V1/V2 "SOL record" override and subdomains are out of scope for this
// cut; single-level "<name>.sol" is handled.

package com.elabify.musnad.wallet.solana

class SNSException(val kind: Kind, val detail: String? = null) : Exception() {
    enum class Kind { MALFORMED_NAME, NOT_REGISTERED, BAD_RESPONSE, RPC_DOWN }

    override val message: String
        get() = when (kind) {
            Kind.MALFORMED_NAME -> "That doesn't look like a valid Solana name. Use the form name.sol."
            Kind.NOT_REGISTERED -> "That .sol name is not registered."
            Kind.BAD_RESPONSE -> "Unexpected SNS response: ${detail ?: ""}"
            Kind.RPC_DOWN -> "Couldn't reach the Solana RPC: ${detail ?: ""}."
        }
}

class SolanaNameResolver(private val rpcURL: String) {

    init { require(rpcURL.isNotBlank()) { "SNS RPC URL is blank" } }

    /** Resolve "<name>.sol" to the domain owner's base58 address. */
    fun resolve(domain: String): String {
        val name = normalize(domain) ?: throw SNSException(SNSException.Kind.MALFORMED_NAME)
        val key = SolanaPrimitives.solDomainAccountKey(name)
        val rpc = SolanaRPCClient(endpoint = rpcURL)
        val dataB64 = try {
            rpc.getAccountDataBase64(key)
        } catch (e: Exception) {
            throw SNSException(SNSException.Kind.RPC_DOWN, e.message)
        } ?: throw SNSException(SNSException.Kind.NOT_REGISTERED)
        val bytes = runCatching { android.util.Base64.decode(dataB64, android.util.Base64.DEFAULT) }
            .getOrElse { throw SNSException(SNSException.Kind.BAD_RESPONSE, "base64") }
        // NameRegistryState: parentName[32] | owner[32] | class[32] | data
        if (bytes.size < 64) throw SNSException(SNSException.Kind.BAD_RESPONSE, "short account (${bytes.size}B)")
        val owner = bytes.copyOfRange(32, 64)
        return SolanaPrimitives.base58Encode(owner)
    }

    companion object {
        /** Heuristic: is this a ".sol" name we should try SNS for? */
        fun looksLikeName(s: String): Boolean {
            val t = s.trim().lowercase()
            return t.endsWith(".sol") && t.length > 4 && !t.contains(" ") && !t.startsWith(".")
        }

        /** Single-level ".sol" -> the bare name; null if not a single-level .sol. */
        private fun normalize(domain: String): String? {
            val t = domain.trim().lowercase()
            if (!t.endsWith(".sol")) return null
            val name = t.removeSuffix(".sol")
            // Subdomains ("a.b.sol") need parent-chained derivation; out of scope.
            if (name.isEmpty() || name.contains(".")) return null
            return name
        }
    }
}
