// Process-wide multi-asset price cache, the Android port of iOS AssetPriceCache.
// Supersedes the Bitcoin-only BitcoinPriceCache and is shared by every chain.
//
// Crypto is ALWAYS priced in USD (the deep, universally-quoted market) and then
// crossed into the user's display currency via a separate USD -> fiat FX rate:
//
//     crypto -> USD  (CoinGecko /simple/price?vs_currencies=usd)
//     USD    -> fiat (open.er-api.com daily FX rates)
//
// CoinGecko's free /simple/price DOES support most currencies we offer (~63
// fiats, incl. AED / SAR / KWD / BHD), but NOT a handful we list -- COP, EGP,
// ISK, OMR, PEN, QAR, RON -- so a direct quote would silently drop those.
// Pricing in USD + an FX cross covers every currency AND decouples the
// crypto-price source from the fiat conversion: the spot endpoint is overridable
// (a paid CoinGecko tier, or a self-hosted CoinGecko-compatible proxy -- which
// can front an open / no-key source like DefiLlama coins.llama.fi or CoinCap,
// since those return USD only). The override must speak CoinGecko's /simple/price
// shape; the FX step then reaches all ~160 ISO currencies regardless.
//
// This cache is preference-agnostic: the app layer gates on
// FiatPreferences.showReferencePrices BEFORE calling, exactly as the Bitcoin
// dashboard already did. Aggressively cached (5-min crypto TTL, 6-hour FX TTL),
// soft-fails to stale/null on any network or parse error, and (when init'd with a
// Context) persists the last good snapshot to the same "UserDefaults" store
// FiatPreferences uses, so offline launches show stale numbers rather than "-".
//
// Reads (price/usdPrice) never fetch; callers refresh off the main thread via
// refreshPrice (or refreshUsd/refreshFx) and then read, matching how
// BitcoinPriceCache was driven.

package com.elabify.musnad.wallet.pricing

import android.content.Context
import android.content.SharedPreferences
import com.elabify.musnad.net.MaknoonHttp
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Currency
import java.util.concurrent.ConcurrentHashMap

object AssetPriceCache {

    private val http = MaknoonHttp()
    private var prefs: SharedPreferences? = null

    /** CoinGecko asset id -> spot price in USD. */
    private val usdByAsset = ConcurrentHashMap<String, Double>()
    private val usdFetchedAtMs = ConcurrentHashMap<String, Long>()

    /** Lowercase ISO code -> USD->fiat FX rate (e.g. "aed" -> 3.6725). */
    private val fxRates = ConcurrentHashMap<String, Double>()
    @Volatile private var fxFetchedAtMs: Long = 0L

    /** Spot-price base URL. Settable so the Settings override (a self-hosted
     *  proxy, paid-tier gateway, or an open no-key source like DefiLlama /
     *  CoinCap) applies to the shared cache. */
    @Volatile var baseURL: String = "https://api.coingecko.com/api/v3"

    /** Key-free FX provider; returns USD -> every ISO currency in one document,
     *  covering the currencies CoinGecko can't quote directly (COP, EGP, ISK,
     *  OMR, PEN, QAR, RON) and keeping the spot-price source swappable. */
    @Volatile var fxBaseURL: String = "https://open.er-api.com/v6/latest/USD"

    private const val TTL_MS = 5 * 60 * 1000L
    private const val FX_TTL_MS = 6 * 60 * 60 * 1000L
    private const val CACHE_KEY = "asset.price.cache.v1"

    /** Assets pre-warmed by [refreshAll]. price()/refresh work for ANY id passed,
     *  so this is only the preload set. Mirrors iOS coinGeckoIds (+ tron). */
    val coinGeckoIds: List<String> = listOf(
        "bitcoin", "ethereum",
        "polygon-ecosystem-token", "binancecoin", "avalanche-2", "mantle", "hyperliquid",
        "solana", "tron",
        "tether", "usd-coin", "dai",
    )

    /** Idempotent; enables disk persistence. Safe to call from any dashboard. */
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("UserDefaults", Context.MODE_PRIVATE)
        loadFromDisk()
    }

    // ---- reads (never fetch) -------------------------------------------------

    fun isUsdStale(assetId: String): Boolean {
        val last = usdFetchedAtMs[assetId] ?: return true
        return System.currentTimeMillis() - last >= TTL_MS
    }

    fun isFxStale(): Boolean = System.currentTimeMillis() - fxFetchedAtMs >= FX_TTL_MS

    /** Cached price of one unit of [assetId] in [fiat], or null if we have no
     *  USD spot yet (or, for non-USD, no FX rate yet). Does not fetch. */
    fun price(assetId: String, fiat: String): Double? {
        val usd = usdByAsset[assetId] ?: return null
        val fiatLower = fiat.lowercase()
        if (fiatLower == "usd") return usd
        val fx = fxRates[fiatLower] ?: return null
        return usd * fx
    }

    /** Format [amount] units of [assetId] as a fiat caption, e.g. "≈ $385.42".
     *  Null when we have no price. Uses the locale-aware currency symbol like
     *  iOS FiatCurrencyCatalog.format. */
    fun fiatCaption(amount: Double, assetId: String, fiat: String): String? {
        val rate = price(assetId, fiat) ?: return null
        return "≈ " + formatCurrency(amount * rate, fiat)
    }

    /** Locale-aware currency formatting ("$1,234.56", "AED 12.00", ...). Falls
     *  back to a plain "value CODE" form for codes the JVM doesn't know. */
    fun formatCurrency(value: Double, code: String): String = try {
        val nf = NumberFormat.getCurrencyInstance()
        nf.currency = Currency.getInstance(code.uppercase())
        nf.format(value)
    } catch (_: Throwable) {
        String.format("%,.2f %s", value, code.uppercase())
    }

    // ---- refreshes (blocking; call off the main thread) ----------------------

    /** Ensure the USD spot for [assetId] and (for non-USD) the FX table are
     *  fresh, then return the crossed price. Soft-fails to whatever is cached. */
    fun refreshPrice(assetId: String, fiat: String): Double? {
        if (isUsdStale(assetId)) refreshUsd(assetId)
        if (fiat.lowercase() != "usd" && isFxStale()) refreshFx()
        return price(assetId, fiat)
    }

    fun refreshUsd(assetId: String): Double? {
        try {
            val body = http.getJson("$baseURL/simple/price?ids=$assetId&vs_currencies=usd")
            val inner = JSONObject(body).optJSONObject(assetId)
            if (inner != null && inner.has("usd")) {
                val v = inner.getDouble("usd")
                usdByAsset[assetId] = v
                usdFetchedAtMs[assetId] = System.currentTimeMillis()
                persistToDisk()
                return v
            }
        } catch (_: Throwable) {
            // Soft-fail; keep the stale value (if any).
        }
        return usdByAsset[assetId]
    }

    fun refreshFx(): Boolean {
        try {
            val body = http.getJson(fxBaseURL)
            // { "result":"success", "base_code":"USD", "rates":{ "USD":1, "AED":3.6725, ... } }
            val ratesObj = JSONObject(body).optJSONObject("rates") ?: return false
            val parsed = HashMap<String, Double>()
            val keys = ratesObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                parsed[k.lowercase()] = ratesObj.getDouble(k)
            }
            if (parsed.isEmpty()) return false
            fxRates.clear()
            fxRates.putAll(parsed)
            fxFetchedAtMs = System.currentTimeMillis()
            persistToDisk()
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    /** Pre-warm every known asset's USD spot plus the FX table. Call on launch
     *  and on currency change. Blocking; call off the main thread. */
    fun refreshAll() {
        for (id in coinGeckoIds) refreshUsd(id)
        refreshFx()
    }

    // ---- persistence ---------------------------------------------------------

    private fun loadFromDisk() {
        val raw = prefs?.getString(CACHE_KEY, null) ?: return
        try {
            val o = JSONObject(raw)
            o.optJSONObject("rates")?.let { r ->
                r.keys().forEach { k -> usdByAsset[k] = r.getDouble(k) }
            }
            o.optJSONObject("lastFetchAt")?.let { l ->
                l.keys().forEach { k -> usdFetchedAtMs[k] = l.getLong(k) }
            }
            o.optJSONObject("fxRates")?.let { f ->
                f.keys().forEach { k -> fxRates[k] = f.getDouble(k) }
            }
            fxFetchedAtMs = o.optLong("fxFetchedAt", 0L)
        } catch (_: Throwable) {
            // Corrupt snapshot; ignore and re-fetch.
        }
    }

    private fun persistToDisk() {
        val p = prefs ?: return
        try {
            val o = JSONObject()
            o.put("rates", JSONObject(usdByAsset as Map<*, *>))
            o.put("lastFetchAt", JSONObject(usdFetchedAtMs as Map<*, *>))
            o.put("fxRates", JSONObject(fxRates as Map<*, *>))
            o.put("fxFetchedAt", fxFetchedAtMs)
            p.edit().putString(CACHE_KEY, o.toString()).apply()
        } catch (_: Throwable) {
            // Persistence is best-effort.
        }
    }
}
