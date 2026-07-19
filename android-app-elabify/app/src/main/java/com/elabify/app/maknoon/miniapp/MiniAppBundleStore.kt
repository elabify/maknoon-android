// Downloads, integrity-checks, caches, and locates mini-app bundles.
// Android port of the iOS MiniAppBundleStore.swift.
//
// A mini app is a set of static files (HTML/CSS/JS/images). Rather than
// ship a zip, a bundle is described by a manifest.json that lists every
// file with its SHA-256. Trust chains from a single hash the catalog
// curator pins (the entry's manifestSha256):
//
//   catalog entry  --manifestSha256-->  manifest.json
//   manifest.json  --per-file sha256-->  each file
//
// Download flow (ensureBundle):
//   1. GET the manifest, verify sha256(bytes) == manifestSha256.
//   2. For each listed file, GET it relative to the manifest directory,
//      verify its sha256, write it under the per-(app,version) cache dir.
//   3. Any mismatch, transport error, or path-traversal attempt aborts the
//      whole install and removes the partial dir. We never serve a
//      half-verified bundle.
//
// Cache layout (private app storage, never on external storage):
//   <filesDir>/miniapps/<installedAppId-sanitized>/<version>/<files...>
//
// Re-download only happens when the version is absent from the cache, so
// opening an installed app offline serves the cached copy.
//
// Cache-first (ADR-0060): a NORMAL open serves the locally cached bundle for
// the pinned manifest hash WITHOUT touching the network. We key the cache dir
// on the pinned hash, so we can locate the verified bundle from the pin alone
// (no live manifest fetch). This is what keeps an installed app working after
// its bundle is re-published upstream with a new hash: the old, pinned version
// keeps serving until the user explicitly updates it (see MiniAppInstallRegistry
// computeUpdates / applyUpdate). The integrity check against the pinned hash
// runs only at DOWNLOAD time (install or update), never on every open. A small
// manifest sidecar written at download time lets a cache-first open recover the
// entry path + version offline.
//
// The store also exposes byte lookup by request path (bytesFor) so the
// WebView asset loader can serve verified files without re-reading the
// manifest. Path traversal is rejected on the read path too.

package com.elabify.app.maknoon.miniapp

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/** Wire format of a mini app's manifest.json (decoded with org.json). */
data class MiniAppManifest(
    /**
     * App version string. Used as the cache-dir name; bump to force a
     * re-download. Free-form but should be monotonic (e.g. "1.0.3").
     */
    val version: String,
    /**
     * Relative path of the page the WebView loads first. Defaults to
     * "index.html" when omitted.
     */
    val entry: String?,
    /** Every file the bundle ships. Paths are relative and forward-slashed. */
    val files: List<FileEntry>,
) {
    data class FileEntry(val path: String, val sha256: String)

    val entryPath: String get() = entry ?: "index.html"

    companion object {
        fun parse(json: String): MiniAppManifest {
            val o = JSONObject(json)
            val arr = o.optJSONArray("files")
            val files = buildList {
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val f = arr.getJSONObject(i)
                        add(FileEntry(f.getString("path"), f.getString("sha256")))
                    }
                }
            }
            return MiniAppManifest(
                version = o.getString("version"),
                entry = if (o.has("entry") && !o.isNull("entry")) o.getString("entry") else null,
                files = files,
            )
        }
    }
}

/** On-disk, integrity-verified mini-app bundle ready to serve. */
data class MiniAppBundle(
    val appId: String,
    val version: String,
    /** Directory holding the verified files. Serve paths relative to here. */
    val rootDir: File,
    val entryPath: String,
) {
    /**
     * Resolve a request path to a verified file inside [rootDir], rejecting
     * traversal. "" maps to the entry page. Returns null when the path
     * escapes the bundle or the file is absent. Used by the asset loader.
     */
    fun resolve(requestPath: String): File? {
        var path = requestPath.trimStart('/')
        if (path.isEmpty()) path = entryPath
        val comps = path.split("/").filter { it.isNotEmpty() }
        if (comps.contains("..") || comps.contains(".")) return null
        var dest = rootDir
        for (c in comps) dest = File(dest, c)
        val rootCanon = rootDir.canonicalPath
        val destCanon = dest.canonicalPath
        if (destCanon != rootCanon && !destCanon.startsWith(rootCanon + File.separator)) return null
        if (!dest.isFile) return null
        return dest
    }

    /** Verified bytes for a request path, or null. Convenience over [resolve]. */
    fun bytesFor(requestPath: String): ByteArray? = resolve(requestPath)?.readBytes()
}

/** Typed failure with a user-facing message (mirrors iOS MiniAppBundleError). */
class MiniAppBundleException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind {
        BAD_MANIFEST_URL,
        MANIFEST_HASH_MISMATCH,
        FILE_HASH_MISMATCH,
        PATH_TRAVERSAL,
        TRANSPORT,
        DECODE,
        EMPTY,
    }

    companion object {
        fun badManifestUrl() =
            MiniAppBundleException(Kind.BAD_MANIFEST_URL, "This app has no valid bundle URL.")
        fun manifestHashMismatch() =
            MiniAppBundleException(Kind.MANIFEST_HASH_MISMATCH, "The app manifest failed its integrity check.")
        fun fileHashMismatch(path: String) =
            MiniAppBundleException(Kind.FILE_HASH_MISMATCH, "App file $path failed its integrity check.")
        fun pathTraversal(path: String) =
            MiniAppBundleException(Kind.PATH_TRAVERSAL, "App file path $path is not allowed.")
        fun transport(msg: String) =
            MiniAppBundleException(Kind.TRANSPORT, "Could not download the app: $msg")
        fun decode(msg: String) =
            MiniAppBundleException(Kind.DECODE, "The app manifest is malformed: $msg")
        fun empty() =
            MiniAppBundleException(Kind.EMPTY, "The app manifest lists no files.")
    }
}

/**
 * Fetches and caches verified mini-app bundles from the public Pages origin.
 *
 * Construct one with the application context, or use [shared] (initialized
 * once at app start via [init]). Methods are suspend and run their IO on
 * [Dispatchers.IO], so call from a coroutine.
 */
class MiniAppBundleStore(context: Context) {

    private val appContext = context.applicationContext

    /** Root cache dir: <filesDir>/miniapps. Created lazily. */
    private fun miniappsRoot(): File =
        File(appContext.filesDir, "miniapps").apply { if (!exists()) mkdirs() }

    private fun appCacheDir(installedAppId: String): File {
        // installedAppId is "<storeId>::<appId>". Sanitize "::" and any
        // path-hostile characters into a flat directory name.
        val safe = installedAppId
            .replace("::", "__")
            .replace("/", "_")
            .replace("..", "_")
        return File(miniappsRoot(), safe)
    }

    /**
     * Return a ready-to-serve bundle for the installed app, downloading and
     * verifying it if the pinned version is not already cached.
     *
     * @param installedAppId stable per-install id ("<storeId>::<appId>").
     * @param appId catalog app id (used as the served web-origin host).
     * @param manifestUrl absolute URL of manifest.json on the Pages origin.
     * @param manifestSha256 lowercase-hex SHA-256 the catalog curator pinned.
     */
    @Throws(MiniAppBundleException::class)
    suspend fun ensureBundle(
        installedAppId: String,
        appId: String,
        manifestUrl: String,
        manifestSha256: String,
    ): MiniAppBundle = withContext(Dispatchers.IO) {
        val pinnedSha = manifestSha256.lowercase()

        // 0. Cache-first: serve the verified bundle for this exact pinned hash
        //    with NO network. This is the normal-open path. It also means a
        //    bundle re-published upstream (new hash) never breaks the installed,
        //    pinned version: opening the app keeps working on the old bundle
        //    until the user explicitly updates it.
        cachedBundle(installedAppId, appId, pinnedSha)?.let { return@withContext it }

        // De-duplicate concurrent downloads of the SAME pinned bundle (an
        // install/update prefetch racing the open that triggered it); without
        // this they clobber the shared temp dir and one surfaces a spurious
        // "index.html failed its integrity check" (a Retry, running alone, works).
        val mutex = downloadMutexes.getOrPut("$installedAppId|$pinnedSha") { Mutex() }
        mutex.withLock {
            // Re-check under the lock: a racing call may have just finished it.
            cachedBundle(installedAppId, appId, pinnedSha)
                ?: downloadBundle(installedAppId, appId, manifestUrl, pinnedSha)
        }
    }

    private val downloadMutexes = ConcurrentHashMap<String, Mutex>()

    /** Download + verify + cache the pinned bundle. Serialized per
     *  (installedAppId, pinnedSha) by ensureBundle so concurrent calls coalesce
     *  instead of clobbering the shared temp dir. */
    @Throws(MiniAppBundleException::class)
    private suspend fun downloadBundle(
        installedAppId: String,
        appId: String,
        manifestUrl: String,
        pinnedSha: String,
    ): MiniAppBundle {
        val manifestUri = runCatching { URL(manifestUrl) }.getOrNull()
            ?: throw MiniAppBundleException.badManifestUrl()

        // 1. Fetch + pin the manifest. A mismatch HERE is a real tamper /
        //    misconfiguration at download time (the pin does not match the
        //    bytes we just fetched), so it is correctly fatal.
        val manifestData = fetch(manifestUri)
        if (hexSha256(manifestData) != pinnedSha) {
            throw MiniAppBundleException.manifestHashMismatch()
        }
        val manifest = runCatching { MiniAppManifest.parse(String(manifestData, Charsets.UTF_8)) }
            .getOrElse { throw MiniAppBundleException.decode(it.message ?: "parse error") }
        if (manifest.files.isEmpty()) throw MiniAppBundleException.empty()

        val appDir = appCacheDir(installedAppId)
        // Key the cache dir by version AND the manifest sha (parity with iOS), so
        // switching channels or a same-version re-publish never serves a stale
        // bundle: a different manifest -> a different dir -> a fresh download.
        val cacheKey = sanitizeComponent(manifest.version) + "-" + pinnedSha.take(12)
        val versionDir = File(appDir, cacheKey)

        // Already cached + complete? Serve it without touching the network.
        if (File(versionDir, manifest.entryPath).exists()) {
            return MiniAppBundle(appId, manifest.version, versionDir, manifest.entryPath)
        }

        // 2. Download into a temp dir, verifying each file, then move into
        //    place atomically so a crash mid-download never leaves a partial
        //    bundle that looks complete.
        val tmpDir = File(appDir, ".tmp-$cacheKey")
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()

        val manifestDir = manifestUri.toString().substringBeforeLast('/', "")
        try {
            for (file in manifest.files) {
                val dest = safeDestination(tmpDir, file.path)
                val fileUrl = runCatching { URL("$manifestDir/${file.path}") }.getOrNull()
                    ?: throw MiniAppBundleException.transport("bad file url ${file.path}")
                val data = fetch(fileUrl)
                if (hexSha256(data) != file.sha256.lowercase()) {
                    throw MiniAppBundleException.fileHashMismatch(file.path)
                }
                dest.parentFile?.mkdirs()
                dest.writeBytes(data)
            }
        } catch (e: MiniAppBundleException) {
            tmpDir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            tmpDir.deleteRecursively()
            throw MiniAppBundleException.transport(e.message ?: "download failed")
        }

        // Require the entry file to exist after verifying everything.
        if (!File(tmpDir, manifest.entryPath).exists()) {
            tmpDir.deleteRecursively()
            throw MiniAppBundleException.fileHashMismatch(manifest.entryPath)
        }

        // Persist the (already pin-verified) manifest bytes alongside the files
        // so a later cache-first open can recover the entry path + version
        // without any network fetch.
        runCatching { File(tmpDir, META_FILE_NAME).writeBytes(manifestData) }

        // 3. Swap temp -> versionDir.
        versionDir.deleteRecursively()
        appDir.mkdirs()
        if (!tmpDir.renameTo(versionDir)) {
            // renameTo can fail across some filesystems; fall back to copy.
            tmpDir.copyRecursively(versionDir, overwrite = true)
            tmpDir.deleteRecursively()
        }

        return MiniAppBundle(appId, manifest.version, versionDir, manifest.entryPath)
    }

    /** Remove every cached version of an app (called on uninstall). */
    fun evict(installedAppId: String) {
        runCatching { appCacheDir(installedAppId).deleteRecursively() }
    }

    /**
     * Locate an already-downloaded, complete bundle for the pinned manifest
     * hash, WITHOUT any network. Version dirs are named "<version>-<sha12>", so
     * the pinned hash alone identifies the directory. Returns null when the
     * pinned version is not cached (first open, or a fresh update pin).
     */
    private fun cachedBundle(installedAppId: String, appId: String, pinnedSha: String): MiniAppBundle? {
        val appDir = appCacheDir(installedAppId)
        if (!appDir.exists()) return null
        val suffix = "-" + pinnedSha.take(12)
        val dirs = appDir.listFiles()?.filter { it.isDirectory && it.name.endsWith(suffix) } ?: return null
        for (dir in dirs) {
            // Prefer the persisted manifest sidecar; fall back to the default
            // entry path for bundles cached before the sidecar existed.
            val meta = readMeta(dir)
            val entryPath = meta?.entryPath ?: "index.html"
            val version = meta?.version ?: dir.name.removeSuffix(suffix)
            if (File(dir, entryPath).exists()) {
                return MiniAppBundle(appId, version, dir, entryPath)
            }
        }
        return null
    }

    /**
     * Decode the manifest sidecar written at download time. null for legacy
     * caches that predate it.
     */
    private fun readMeta(versionDir: File): MiniAppManifest? {
        val f = File(versionDir, META_FILE_NAME)
        if (!f.isFile) return null
        return runCatching { MiniAppManifest.parse(f.readText(Charsets.UTF_8)) }.getOrNull()
    }

    // ---- helpers ----

    private fun fetch(url: URL): ByteArray {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        conn.requestMethod = "GET"
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw MiniAppBundleException.transport("HTTP $code for ${url.path.substringAfterLast('/')}")
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Resolve [relativePath] under [root], rejecting absolute paths, "..",
     * and anything that escapes the bundle directory.
     */
    private fun safeDestination(root: File, relativePath: String): File {
        val comps = relativePath.split("/").filter { it.isNotEmpty() }
        if (relativePath.startsWith("/") ||
            comps.contains("..") ||
            comps.contains(".") ||
            comps.isEmpty()
        ) {
            throw MiniAppBundleException.pathTraversal(relativePath)
        }
        var dest = root
        for (c in comps) dest = File(dest, c)
        val rootCanon = root.canonicalPath
        val destCanon = dest.canonicalPath
        if (destCanon != rootCanon && !destCanon.startsWith(rootCanon + File.separator)) {
            throw MiniAppBundleException.pathTraversal(relativePath)
        }
        return dest
    }

    private fun sanitizeComponent(s: String): String {
        val allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.-_"
        val cleaned = s.filter { allowed.contains(it) }
        return cleaned.ifEmpty { "0" }
    }

    companion object {
        /**
         * Sidecar written inside each version dir at download time: the raw
         * manifest bytes. Lets a cache-first open recover the entry path +
         * version without re-fetching the manifest. Leading dot keeps it out of
         * the way; it is never listed in manifest.files so a bundle can never
         * legitimately ship this name.
         */
        private const val META_FILE_NAME = ".maknoon-manifest.json"

        @Volatile
        private var instance: MiniAppBundleStore? = null

        /** Initialize the process-wide store. Call once at app start. */
        fun init(context: Context): MiniAppBundleStore =
            instance ?: synchronized(this) {
                instance ?: MiniAppBundleStore(context).also { instance = it }
            }

        /** The process-wide store (must have been [init]ialized first). */
        val shared: MiniAppBundleStore
            get() = instance ?: error("MiniAppBundleStore.init(context) not called")

        fun hexSha256(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data)
                .joinToString("") { "%02x".format(it) }
    }
}
