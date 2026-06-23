// Wire models for the LNDHub client, ported 1:1 from iOS LNDHubClient.swift.
//
// The shapes vary wildly across LNDHub implementations: some serialise numeric
// fields as strings, some use `amt`/`amount` instead of `value`, some use
// `description` instead of `memo`, some return a top-level `{txs:[...]}` /
// `{invoices:[...]}` envelope instead of a bare array. The flexible JSON
// helpers below accept all of those, matching the iOS decodeInt64Flex /
// decodeStringFlex behaviour.

package com.elabify.musnad.wallet.lightning

import org.json.JSONArray
import org.json.JSONObject

/** A receive invoice (BOLT11 payment request). */
data class LightningInvoice(
    /** BOLT11-encoded payment request string (`lnbc...`). */
    val paymentRequest: String,
    /** Amount in millisatoshi (LNDHub returns string in some forks, int in
     *  others; we accept both). */
    val amt: String? = null,
    /** User-supplied memo / description. */
    val memo: String? = null,
)

/**
 * One entry from LNDHub `/gettxs` (outgoing payments) OR `/getuserinvoices`
 * (incoming). Mirrors the iOS LightningTx struct, including the isOutgoing /
 * id derivation.
 */
data class LightningTx(
    val paymentHash: String? = null,
    val paymentPreimage: String? = null,
    val value: Long? = null,
    val fee: Long? = null,
    val memo: String? = null,
    val timestamp: Long? = null,
    val type: String? = null,
    /** True if this is an incoming invoice the payer settled. Only meaningful
     *  for `/getuserinvoices` rows; `/gettxs` payments don't carry this. */
    val isPaid: Boolean? = null,
) {
    val id: String get() = paymentHash ?: "${timestamp ?: 0L}|${value ?: 0L}"

    val isOutgoing: Boolean
        get() {
            val t = (type ?: "").lowercase()
            // user_invoice / user_invoice_settled = incoming; everything else
            // (paid_invoice, outgoing, user_outgoing) = outgoing.
            if (t.contains("invoice") && !t.contains("paid")) return false
            return t.contains("paid") || t == "outgoing" || t == "user_outgoing"
        }

    companion object {
        /** Parse one row, accepting string-or-number numeric fields and the
         *  alternate key spellings the iOS decoder tolerated. */
        fun fromJson(o: JSONObject): LightningTx = LightningTx(
            paymentHash = o.stringFlex("payment_hash"),
            paymentPreimage = o.stringFlex("payment_preimage"),
            value = o.int64Flex("value") ?: o.int64Flex("amount") ?: o.int64Flex("amt"),
            fee = o.int64Flex("fee") ?: o.int64Flex("fees"),
            memo = o.stringFlex("memo") ?: o.stringFlex("description"),
            timestamp = o.int64Flex("timestamp") ?: o.int64Flex("time") ?: o.int64Flex("settled_at"),
            type = o.stringFlex("type"),
            isPaid = if (o.has("ispaid") && !o.isNull("ispaid")) o.optBoolean("ispaid") else null,
        )
    }
}

// ---- flexible JSON helpers (port of decodeInt64Flex / decodeStringFlex) ----

/** Int64 from a number or a numeric string; null for missing/null/unparseable. */
internal fun JSONObject.int64Flex(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    val v = get(key)
    return when (v) {
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull() ?: v.trim().toDoubleOrNull()?.toLong()
        else -> null
    }
}

/** String from a string or a number; null for missing/null. */
internal fun JSONObject.stringFlex(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return when (val v = get(key)) {
        is String -> v
        is Number -> v.toString()
        else -> null
    }
}

/** Parse a JSON body that is either a bare array or `{<envelopeKey>:[...]}`.
 *  Returns null when neither shape matches. */
internal fun parseTxArray(body: String, envelopeKey: String): List<JSONObject>? {
    val trimmed = body.trimStart()
    if (trimmed.startsWith("[")) {
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return null
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }
    val obj = runCatching { JSONObject(body) }.getOrNull() ?: return null
    if (obj.has(envelopeKey) && !obj.isNull(envelopeKey)) {
        val arr = obj.optJSONArray(envelopeKey) ?: return null
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }
    return null
}
