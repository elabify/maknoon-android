package com.elabify.app.maknoon.miniapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Bridge -> host navigation requests. Today: "open the Ethereum wallet the tx
 * used" (after a swap). AppsScreen collects [openWallet] and performs the pop +
 * tab switch. Modeled on ApprovalGate's single-active StateFlow; shared per host
 * (constructed by the handler factory, observed by AppsScreen).
 */
class MiniAppNavCoordinator {
    data class OpenWalletRequest(val id: Long, val chainId: Long?, val address: String?)

    private val _openWallet = MutableStateFlow<OpenWalletRequest?>(null)
    val openWallet: StateFlow<OpenWalletRequest?> = _openWallet.asStateFlow()
    private var counter = 0L

    fun requestOpenWallet(chainId: Long?, address: String?) {
        _openWallet.value = OpenWalletRequest(++counter, chainId, address)
    }

    /** Clear once the host has handled this request. */
    fun consume(req: OpenWalletRequest) {
        if (_openWallet.value?.id == req.id) _openWallet.value = null
    }
}

/**
 * window.maknoon.walletView.open({ chainId?, address? }). Records the request on
 * the nav coordinator; AppsScreen leaves the mini app and opens the Ethereum
 * wallet + chain the tx used. Gated by wallet.ethereum.read (same as the pools
 * registry read), so only EVM-capable apps can use it.
 */
class OpenWalletBridgeHandler(
    private val nav: MiniAppNavCoordinator,
) : MiniAppNamespaceHandler {
    override val namespace = "walletView"
    override val requiredPermission: String = "wallet.ethereum.read"

    override suspend fun handle(method: String, argsJson: String): String = when (method) {
        "walletView.open" -> {
            val o = runCatching { JSONObject(argsJson) }.getOrNull()
            val chainId = o?.optLong("chainId", -1L)?.takeIf { it >= 0 }
            val address = o?.optStringOrNull("address")
            nav.requestOpenWallet(chainId, address)
            "null"
        }
        else -> throw MiniAppBridgeError.unsupported("walletView.$method")
    }
}
