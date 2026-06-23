// Maknoon Android SDK (com.elabify.musnad:musnad-sdk). The trust-critical
// core: PQ crypto, identity sandwich, secure storage, networking, key
// attestation, presentation. Consumed by the android-app-elabify super-app.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // The locally-built pq-crypto-core AAR (run pq-crypto-rs/android/build-aar.sh).
        flatDir { dirs("${rootDir}/../pq-crypto-rs/android/library/build/outputs/aar") }
        // The 5 hardware-wallet core AARs, copied with distinct names (each
        // crate's build-aar.sh emits "library-release.aar", so they're
        // renamed into hwlibs/ to avoid a flatDir name collision). Run
        // scripts/copy-hwlibs (or the per-crate build-aar.sh + copy) to refresh.
        flatDir { dirs("${rootDir}/hwlibs") }
    }
}

// Pure-Kotlin elabify-core (RPO-256, HKDF, Merkle, DID, BIP39) as a composite
// build so a source change is picked up without a publish step. Substitutes
// the `com.elabify:elabify-core` coordinate.
includeBuild("../elabify-core/bindings/kotlin")

rootProject.name = "musnad-sdk"
include(":musnad-sdk")
