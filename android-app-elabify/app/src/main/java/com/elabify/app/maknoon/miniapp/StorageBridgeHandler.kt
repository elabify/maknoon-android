// "storage" namespace handler (window.maknoon.storage).
//
// A durable, backed-up key-value store for the mini app's own settings.
// Always available (no permission needed): it is strictly sandboxed to the
// calling app's installed-app id, so an app can only read/write its own
// bucket. Backed by MiniAppSettingsStore (SharedPreferences + encrypted vault).
//
// Android port of the iOS StorageBridgeHandler. The bridge is string-in /
// string-out (JSON): argsJson is the JS `params` serialized ("null" when the
// shim sent none), and the returned string IS the resolved JS promise value,
// so it must always be valid JSON. A null result is the literal "null".

package com.elabify.app.maknoon.miniapp

import org.json.JSONObject

class StorageBridgeHandler(
    private val installedAppId: String,
    private val store: MiniAppSettingsStore,
) : MiniAppNamespaceHandler {
    override val namespace = "storage"
    override val requiredPermission: String? = null

    override suspend fun handle(method: String, argsJson: String): String {
        val params = parseObject(argsJson)
        return when (method) {
            "storage.get" -> {
                val key = requireKey(params)
                val value = store.value(installedAppId, key)
                if (value == null) "null" else JSONObject.quote(value)
            }
            "storage.set" -> {
                val key = requireKey(params)
                val value = params?.optString("value", null)
                if (value == null || !params.has("value") || params.isNull("value")) {
                    throw MiniAppBridgeError.invalidParams("storage.set requires a string `value`")
                }
                try {
                    store.set(installedAppId, key, value)
                } catch (e: MiniAppSettingsException) {
                    // Quota / size failures surface as invalid-params with the
                    // store's user-facing message, matching iOS.
                    throw MiniAppBridgeError.invalidParams(e.message ?: e.toString())
                }
                "null"
            }
            "storage.remove" -> {
                val key = requireKey(params)
                store.remove(installedAppId, key)
                "null"
            }
            "storage.keys" -> org.json.JSONArray(store.keys(installedAppId)).toString()
            else -> throw MiniAppBridgeError.unsupported("storage.$method")
        }
    }

    private fun requireKey(params: JSONObject?): String {
        val key = params?.optString("key", null)
        if (key.isNullOrEmpty() || !params.has("key") || params.isNull("key")) {
            throw MiniAppBridgeError.invalidParams("requires a non-empty string `key`")
        }
        return key
    }

    private fun parseObject(argsJson: String): JSONObject? =
        runCatching { JSONObject(argsJson) }.getOrNull()
}
