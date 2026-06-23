// CSCA trust store for on-device Passive Authentication.
//
// Fetches the issuer's signed CSCA bundle (GET /v1/issuer/csca-bundle), verifies
// the issuer's ML-DSA-65 signature over the manifest (which commits to the
// bundle's SHA-256), parses the concatenated-PEM CAFile into a set of trusted
// X.509 CSCA certificates, and caches both the raw PEM and the metadata on disk.
// Throttled refresh, mirroring the iOS CSCATrustStore actor.
//
// Trust model (v1, soft-badge): the bundle is fetched over TLS from a known
// issuer host and the manifest signature is self-consistent against the embedded
// issuer pubkey. The on-device verdict is advisory only; the issuer backend
// re-runs Passive Auth authoritatively at issuance. Hardening (pinning the
// issuer pubkey to the verified well-known doc / on-chain key) is a follow-up.
//
// Android specifics vs iOS:
//   - applicationSupportDirectory  -> Context.filesDir / "csca".
//   - UserDefaults                 -> SharedPreferences ("csca.bundle").
//   - URLSession + CryptoKit SHA256 -> SDK MaknoonHttp (OkHttp) + JCA SHA-256.
//   - ElabifyCore.canonicalize + MLDSAClient.verify -> com.elabify.core
//     canonicalize + com.elabify.musnad.crypto.MasterKey.verify (same crypto).
//
// Crypto + networking come from the SDK; nothing is reimplemented here.

package com.elabify.app.maknoon.iddocument

import android.content.Context
import com.elabify.core.canonicalize
import com.elabify.musnad.crypto.MasterKey
import com.elabify.musnad.crypto.hexToBytes
import com.elabify.musnad.net.MaknoonHttp
import com.elabify.musnad.net.NetworkException
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.Provider
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-disk, throttled trust store for the issuer-signed CSCA bundle.
 *
 * Thread-safety: refresh and the readers below guard shared state with a lock,
 * the Android analog of the iOS `actor` isolation. The network + crypto work
 * runs on Dispatchers.IO.
 *
 * @param appContext application context (used for filesDir + SharedPreferences).
 * @param baseUrl issuer base URL; defaults to the same host the SDK net clients
 *   use. The caller may pass a private issuer's base (Settings, Known issuers).
 * @param http SDK HTTP core (OkHttp, GMS-free); injectable for tests.
 */
class CSCATrustStore(
    appContext: Context,
    private val baseUrl: String = DEFAULT_ISSUER_BASE_URL,
    private val http: MaknoonHttp = MaknoonHttp(),
) {
    private val appContext: Context = appContext.applicationContext
    private val prefs = this.appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    /** Directory + file for the cached CAFile (concatenated PEM). */
    private val cacheDir: File
        get() = File(appContext.filesDir, "csca").apply { if (!exists()) mkdirs() }

    private val cafile: File
        get() = File(cacheDir, "csca-bundle.pem")

    /** The cached CAFile path if present (null before the first successful fetch). */
    val cafilePath: String?
        get() = cafile.takeIf { it.exists() }?.absolutePath

    val version: String?
        get() = prefs.getString(VERSION_KEY, null)

    /** Epoch milliseconds of the last successful install, or null. */
    val lastRefreshedAt: Long?
        get() = prefs.getLong(REFRESHED_AT_KEY, 0L).takeIf { it > 0L }

    val certCount: Int?
        get() = prefs.getInt(COUNT_KEY, 0).takeIf { it > 0 }

    /**
     * The trusted CSCA certificates parsed from the cached CAFile, or an empty
     * set if no bundle is installed (or it failed to parse). Re-parsed each
     * call so a refresh on another thread is picked up; cheap for a few hundred
     * certs and avoids stale in-memory caches.
     */
    fun trustedCertificates(): Set<X509Certificate> {
        val pem = cafile.takeIf { it.exists() }?.readText() ?: return emptySet()
        return parsePemCertificates(pem)
    }

    /**
     * Fetch + verify + cache the bundle. Throttled to once per refresh interval
     * unless [force]. Returns true if a fresh bundle was installed. Failures are
     * swallowed (any existing cache is kept), so this never throws.
     */
    suspend fun refresh(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (!force && cafilePath != null) {
                val last = lastRefreshedAt
                if (last != null && System.currentTimeMillis() - last < REFRESH_INTERVAL_MS) {
                    return@withContext false // cache is fresh enough
                }
            }
        }

        val url = baseUrl.trimEnd('/') + "/v1/issuer/csca-bundle"
        android.util.Log.d("CSCA", "refresh: GET $url (force=$force)")
        val body = try {
            http.getJson(url)
        } catch (e: NetworkException) {
            android.util.Log.w("CSCA", "refresh: getJson HTTP ${e.status}", e)
            return@withContext false
        } catch (e: Exception) {
            android.util.Log.w("CSCA", "refresh: getJson failed", e)
            return@withContext false
        }
        android.util.Log.d("CSCA", "refresh: got ${body.length} chars")

        val installed = installVerifiedBundle(body)
        if (installed == null) {
            android.util.Log.w("CSCA", "refresh: installVerifiedBundle returned null (verification failed)")
            return@withContext false
        }
        android.util.Log.d("CSCA", "refresh: installed version=${installed.version} count=${installed.count}")
        synchronized(lock) {
            prefs.edit()
                .putString(VERSION_KEY, installed.version)
                .putInt(COUNT_KEY, installed.count)
                .putLong(REFRESHED_AT_KEY, System.currentTimeMillis())
                .apply()
        }
        true
    }

    /** Parsed result of a verified bundle install. */
    private data class InstalledBundle(val version: String, val count: Int)

    /**
     * Verify the signed bundle and write the CAFile atomically. Returns the
     * metadata on success, null on any verification failure.
     *
     * Steps mirror iOS installVerifiedBundle:
     *   1. ML-DSA-65 signature over canonicalize(manifest).
     *   2. The PEM must hash (SHA-256) to the value the manifest committed to.
     *   3. Write the CAFile atomically.
     */
    private fun installVerifiedBundle(json: String): InstalledBundle? {
        val top = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }

        val manifest = top.optJSONObject("manifest") ?: return null
        val pkHex = top.optString("mlDsaPubkey").ifEmpty { return null }
        val sigHex = top.optString("signature").ifEmpty { return null }
        val bundlePem = top.optString("bundlePem").ifEmpty { return null }
        val expectedSha = manifest.optString("sha256").ifEmpty { return null }

        val pubkey = runCatching { hexToBytes(pkHex) }.getOrNull() ?: return null
        val sig = runCatching { hexToBytes(sigHex) }.getOrNull() ?: return null

        // 1. ML-DSA signature over the canonical JSON of the manifest. The
        //    canonical bytes must be byte-identical to what the issuer signed,
        //    so convert the org.json manifest into the plain Map/List/scalar
        //    tree the cross-platform canonicalize() expects.
        val canonical = try {
            canonicalize(jsonToCanonicalValue(manifest))
        } catch (_: Exception) {
            return null
        }
        if (!MasterKey.verify(publicKey = pubkey, signature = sig, message = canonical)) {
            android.util.Log.w("CSCA", "install: ML-DSA manifest signature verify FAILED")
            return null
        }

        // 2. The PEM must match the sha256 the manifest committed to.
        val digest = MessageDigest.getInstance("SHA-256").digest(bundlePem.toByteArray(Charsets.UTF_8))
        val shaHex = digest.joinToString("") { "%02x".format(it) }
        if (shaHex != expectedSha.lowercase()) {
            android.util.Log.w("CSCA", "install: PEM sha256 mismatch (got $shaHex want ${expectedSha.lowercase()})")
            return null
        }

        // Defensive: the PEM must contain at least one parseable CSCA cert,
        // otherwise the trust store would be silently empty.
        val parsedCount = parsePemCertificates(bundlePem).size
        android.util.Log.d("CSCA", "install: verified ok, parsed $parsedCount certs from PEM")
        if (parsedCount == 0) return null

        // 3. Write the CAFile atomically (write to a temp file, then rename).
        val tmp = File(cacheDir, "csca-bundle.pem.tmp")
        try {
            tmp.writeText(bundlePem)
            if (!tmp.renameTo(cafile)) {
                // renameTo can fail across some filesystems; fall back to copy.
                cafile.writeText(bundlePem)
                tmp.delete()
            }
        } catch (_: Exception) {
            tmp.delete()
            return null
        }

        val count = manifest.optInt("count", 0)
        // Version label: prefer generatedAt; fall back to the sha prefix.
        val version = manifest.optLongOrNull("generatedAt")?.let { "gen-$it" }
            ?: expectedSha.take(12)
        return InstalledBundle(version, count)
    }

    companion object {
        /**
         * Default issuer endpoint, the same host the SDK net clients and the
         * issuance client use. Configurable in Settings, Identity, Known issuers.
         */
        const val DEFAULT_ISSUER_BASE_URL = "https://musnad-issuer.elabify.com"

        private const val PREFS_NAME = "csca.bundle"
        private const val VERSION_KEY = "csca.bundle.version"
        private const val REFRESHED_AT_KEY = "csca.bundle.refreshedAt"
        private const val COUNT_KEY = "csca.bundle.count"

        // 7 days, matching iOS refreshIntervalSec.
        private const val REFRESH_INTERVAL_MS = 7L * 24L * 3600L * 1000L

        /**
         * The BouncyCastle X.509 CertificateFactory. The platform (Conscrypt)
         * provider rejects certificates that carry EXPLICIT EC domain parameters
         * ("Only named ECParameters supported"), which a large share of the ICAO
         * CSCA pool uses; that silently dropped those anchors and left the trust
         * store effectively empty, so Passive Auth read as "not checked". BC
         * parses the full pool, and matches the provider PassportPassiveAuthVerifier
         * already uses for the SOD / DS cert, so anchors and signer line up.
         */
        private val bcProvider: Provider by lazy { BouncyCastleProvider() }

        /**
         * Parse a concatenated-PEM CAFile into X.509 certificates. Robust to
         * non-certificate PEM blocks (comments, blank lines, stray text): each
         * BEGIN/END CERTIFICATE block is decoded independently and a block that
         * fails to parse is skipped rather than aborting the whole bundle.
         */
        fun parsePemCertificates(pem: String): Set<X509Certificate> {
            val cf = CertificateFactory.getInstance("X.509", bcProvider)
            val out = LinkedHashSet<X509Certificate>()
            var idx = 0
            while (true) {
                val begin = pem.indexOf(BEGIN_CERT, idx)
                if (begin < 0) break
                val endMarker = pem.indexOf(END_CERT, begin)
                if (endMarker < 0) break
                val end = endMarker + END_CERT.length
                val block = pem.substring(begin, end)
                runCatching {
                    cf.generateCertificate(ByteArrayInputStream(block.toByteArray(Charsets.US_ASCII)))
                }.getOrNull()?.let { cert ->
                    (cert as? X509Certificate)?.let { out.add(it) }
                }
                idx = end
            }
            return out
        }

        private const val BEGIN_CERT = "-----BEGIN CERTIFICATE-----"
        private const val END_CERT = "-----END CERTIFICATE-----"
    }
}

// ---- org.json -> canonicalize() value tree ----------------------------------
//
// canonicalize() accepts null, Boolean, Long/Int/BigInteger, integer-valued
// Double, String, List<Any?>, and Map<String, Any?>. org.json hands us
// JSONObject / JSONArray / boxed primitives, so walk the tree and normalize.
// Numbers come back as Integer/Long/Double from org.json; integer-valued
// Doubles are coerced to Long so they emit as integer literals (the issuer
// serializes the manifest with integer numbers only).

private fun jsonToCanonicalValue(value: Any?): Any? = when (value) {
    null, JSONObject.NULL -> null
    is JSONObject -> {
        val map = LinkedHashMap<String, Any?>()
        val keys = value.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = jsonToCanonicalValue(value.get(k))
        }
        map
    }
    is JSONArray -> (0 until value.length()).map { jsonToCanonicalValue(value.get(it)) }
    is Boolean -> value
    is Int -> value.toLong()
    is Long -> value
    is Double -> if (value % 1.0 == 0.0 && !value.isInfinite()) value.toLong() else value
    is Float -> jsonToCanonicalValue(value.toDouble())
    is String -> value
    else -> value.toString()
}

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null
