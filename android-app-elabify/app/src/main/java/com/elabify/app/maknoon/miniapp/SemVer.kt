// Minimal semantic-version parse/compare + a dApp-compatibility verdict for the
// Apps catalog UI. Android port of the iOS Maknoon/SemVer.swift.
//
// A catalog entry may declare `requiresMaknoonVersion` (e.g. "0.4.1"). We compare
// it against the running app version (BuildConfig.VERSION_NAME) to render a
// compatibility badge AND gate the install button. Entries that omit the
// requirement (or unparsable host versions) are flagged "unknown support" but
// remain installable, matching iOS.

package com.elabify.app.maknoon.miniapp

import com.elabify.app.maknoon.BuildConfig

/**
 * Minimal semantic version. Parses "1.2.3", "v1.2", "0.4.1-beta" (any
 * pre-release / build-metadata suffix is ignored), mirroring iOS SemVer.
 */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        fun parse(raw: String?): SemVer? {
            if (raw == null) return null
            var s = raw.trim()
            if (s.startsWith("v") || s.startsWith("V")) s = s.drop(1)
            // Drop any pre-release / build metadata.
            val cut = s.indexOfFirst { it == '-' || it == '+' }
            if (cut >= 0) s = s.substring(0, cut)
            val parts = s.split(".")
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val minor = if (parts.size > 1) (parts[1].toIntOrNull() ?: 0) else 0
            val patch = if (parts.size > 2) (parts[2].toIntOrNull() ?: 0) else 0
            return SemVer(major, minor, patch)
        }
    }
}

/** The running Maknoon (app) version surface, mirroring iOS MaknoonVersion. */
object MaknoonVersion {
    /** Marketing version (BuildConfig.VERSION_NAME), e.g. "0.6.0". */
    val currentString: String get() = BuildConfig.VERSION_NAME
    val current: SemVer? get() = SemVer.parse(currentString)
}

/**
 * Compatibility of a catalog entry with the running Maknoon app. An entry may
 * declare a lower bound (`requiresMaknoonVersion`, min host) AND an upper bound
 * (`supersededAtMaknoonVersion`, the host version at/above which this dApp
 * version is no longer supported). Compatible iff required <= host < superseded.
 */
sealed class DAppCompatibility {
    data class Compatible(val host: String) : DAppCompatibility()
    data class RecommendsNewer(val required: String, val host: String) : DAppCompatibility()
    /** Host is at/above the version where this dApp version was superseded
     *  (the app needs an update for this newer Maknoon). */
    data class Superseded(val supersededAt: String, val host: String) : DAppCompatibility()
    object Unknown : DAppCompatibility()

    /** True when a fresh install should be blocked: host below the min, or
     *  at/above the upper bound. */
    val blocksInstall: Boolean get() = this is RecommendsNewer || this is Superseded

    /** Warn (non-blocking) when an already-installed app is opened on a host that
     *  has since moved out of the supported range. */
    val warnsAtOpen: Boolean get() = blocksInstall

    /** Badge label, mirroring iOS DAppCompatibility.label. */
    val label: String
        get() = when (this) {
            is Compatible -> "Compatible (Maknoon $host)"
            is RecommendsNewer -> "Requires Maknoon $required (you have $host)"
            is Superseded -> "Needs an update for Maknoon $host (superseded at $supersededAt)"
            is Unknown -> "Unknown support"
        }

    companion object {
        fun evaluate(
            requiresMaknoonVersion: String?,
            supersededAtMaknoonVersion: String? = null,
        ): DAppCompatibility {
            val host = MaknoonVersion.current ?: return Unknown
            val required = SemVer.parse(requiresMaknoonVersion)
            if (required != null && host < required) {
                return RecommendsNewer(required.toString(), host.toString())
            }
            val superseded = SemVer.parse(supersededAtMaknoonVersion)
            if (superseded != null && host >= superseded) {
                return Superseded(superseded.toString(), host.toString())
            }
            if (requiresMaknoonVersion == null && supersededAtMaknoonVersion == null) return Unknown
            return Compatible(host.toString())
        }
    }
}
