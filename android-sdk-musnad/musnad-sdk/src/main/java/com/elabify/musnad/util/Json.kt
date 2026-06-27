package com.elabify.musnad.util

import org.json.JSONObject

// Canonical JSON helpers. `optStringOrNull` is the no-warning equivalent of the old
// `JSONObject.optStringOrNull(key)` idiom: K2 (KT-73255) flags passing the `null`
// literal to Java's `optString(String, String)` non-null fallback param. This reads via
// `getString` (guarded by `has`/`isNull`) so there is no null literal, while preserving
// the exact semantics: null if the key is absent or JSON null, else the value (which may
// be the empty string).
internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null
