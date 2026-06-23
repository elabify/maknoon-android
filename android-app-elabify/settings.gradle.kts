// Maknoon super-app (applicationId com.elabify.app.maknoon). UI + wallets +
// hardware transports + mini-app host. Depends on the android-sdk-musnad
// trust core (wired in at P1; see ONBOARDING / plan for the link strategy).

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
        // The pq-crypto-core AAR is a flatDir dep of the SDK; the app must
        // also see this repo to resolve it across the composite build.
        // (Run pq-crypto-rs/android/build-aar.sh first.)
        flatDir { dirs("${rootDir}/../pq-crypto-rs/android/library/build/outputs/aar") }
        // The 5 hardware-wallet core AARs are `api` deps of the SDK, so the
        // app must also resolve them across the composite build. Same dir the
        // SDK reads (android-sdk-musnad/hwlibs). Run scripts/copy-hwlibs.
        flatDir { dirs("${rootDir}/../android-sdk-musnad/hwlibs") }
    }
}

// Consume the SDK from source via a composite build; it substitutes the
// com.elabify.musnad:musnad-sdk coordinate (and brings its own composite
// includeBuild of elabify-core + the flatDir pq-crypto AAR).
includeBuild("../android-sdk-musnad")

rootProject.name = "maknoon"
include(":app")
