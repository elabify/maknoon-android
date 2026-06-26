import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.play.publisher)
}

// WalletCore (via the SDK) pulls full guava; androidx pulls the empty
// `listenablefuture` stub. Drop the stub to avoid a duplicate class.
configurations.configureEach {
    exclude(group = "com.google.guava", module = "listenablefuture")
}

// Release signing is driven by a gitignored keystore.properties at the
// android-app-elabify root (never committed). When it is absent (a fresh
// clone, CI without secrets) release builds fall back to the debug key so the
// project still assembles; only builds made with the real keystore are
// shippable to Play / Obtainium. See RELEASE.md.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}

// Short git commit of the Android source this APK/AAB was built from, surfaced
// in Settings > About (replaces the old hardcoded "dev"). Falls back to "dev"
// when git is unavailable (e.g. a source archive without a .git dir).
fun gitShortSha(): String = try {
    ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
        .inputStream.bufferedReader().readText().trim()
        .ifEmpty { "dev" }
} catch (_: Exception) {
    "dev"
}

android {
    namespace = "com.elabify.app.maknoon"
    compileSdk = 36
    // Pin the NDK so AGP's release strip + native-debug-symbol tasks can find
    // the toolchain. Without it AGP silently skips stripping the prebuilt
    // jniLibs .so (shipping ~4.5 MB of debug info per core) and produces no
    // native symbol file for Play.
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "com.elabify.app.maknoon" // matches the iOS bundle id
        minSdk = 33
        targetSdk = 35
        versionCode = 11
        versionName = "0.6.2"
        buildConfigField("String", "GIT_COMMIT", "\"${gitShortSha()}\"")

        // Ship arm64-v8a only. It is the only ABI used by real 16 KB-page
        // devices (Pixel etc.) and the only 64-bit target the in-house Rust
        // cores build; every arm64-v8a .so is 16 KB-aligned. This drops the
        // x86_64 build of two third-party libs (JNA, WalletCore) that are still
        // 4 KB-aligned and would fail Google Play's 16 KB requirement, plus the
        // incidental armeabi/x86/mips ABIs that the cores never built for.
        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Embed a native symbol file in the AAB so Play can symbolicate
            // crashes/ANRs in the Rust + WalletCore .so libs. SYMBOL_TABLE gives
            // function names (readable stacks) without the size of FULL.
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
            // Real release key when keystore.properties is present, else the
            // debug key (keeps fresh clones / CI building, but unshippable).
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // The three BouncyCastle jars (bcprov/bcpkix/bcutil) each ship an
            // identical OSGi manifest under META-INF/versions/9; the merger
            // refuses the duplicate. Drop them (not needed at runtime).
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Gradle Play Publisher (ADR-0044 release automation). `./gradlew
// :app:publishReleaseBundle` builds the signed AAB and uploads it to the
// Internal testing track. Promote internal -> production in the Play Console.
//
// Auth (two supported paths; only the publish* tasks need it, so normal
// assemble/bundle builds work without either):
//   1. A gitignored service-account JSON at play-service-account.json (used if
//      present).
//   2. Application Default Credentials when no JSON is present (org policy
//      iam.disableServiceAccountKeyCreation blocks downloadable keys, so this
//      is the keyless path). Authenticate by impersonating the publisher SA:
//        gcloud auth application-default login \
//          --impersonate-service-account=play-publisher@PROJECT.iam.gserviceaccount.com
//      ADC then mints short-lived tokens as the SA (no key file on disk).
play {
    // Credential resolution (org policy blocks downloadable SA keys, so the
    // default is keyless impersonation):
    //   1. play-service-account.json (a real SA key) if present, else
    //   2. the gcloud Application Default Credentials file produced by
    //      `gcloud auth application-default login --impersonate-service-account=...`.
    //      It contains an impersonation config (no private key); GPP loads it via
    //      GoogleCredentials.fromStream, which understands that ADC type.
    //   3. GOOGLE_APPLICATION_CREDENTIALS (CI): GitHub Actions Workload Identity
    //      Federation (google-github-actions/auth) writes a keyless external_account
    //      ADC file and exports this env var. GoogleCredentials.fromStream loads it
    //      and impersonates the publisher SA, same as the local gcloud ADC.
    val saFile = file("play-service-account.json")
    val gac = System.getenv("GOOGLE_APPLICATION_CREDENTIALS")
    val adcFile = file(System.getProperty("user.home") + "/.config/gcloud/application_default_credentials.json")
    when {
        saFile.exists() -> serviceAccountCredentials.set(saFile)
        !gac.isNullOrBlank() && file(gac).exists() -> serviceAccountCredentials.set(file(gac))
        adcFile.exists() -> serviceAccountCredentials.set(adcFile)
    }
    track.set("internal")
    defaultToAppBundles.set(true)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric) // BiometricPrompt + FragmentActivity
    implementation(libs.zxing.core) // QR generation for receive addresses

    // ICAO 9303 eMRTD passport reading (NFC IsoDep) + Passive Auth (SOD CMS
    // chain verification against the CSCA bundle). GMS-free.
    implementation(libs.jmrtd)
    implementation(libs.scuba.sc.android)
    implementation(libs.bouncycastle)
    implementation(libs.bouncycastle.pkix)

    // Sandboxed mini-app host (WebView + SHA-256-pinned bundle serving).
    implementation(libs.androidx.webkit)

    // Yubico yubikit-android (GMS-free: NFC/USB direct, no Play services). The
    // app owns the NFC radio (NfcYubiKeyManager) for YubiKey taps and drives
    // YubiKeyDevice; the FIDO2 / management sessions come from the SDK
    // (YubiKeyClient), whose yubikit :fido + :management deps are `api`.
    implementation(libs.yubikit.android)
    implementation(libs.yubikit.core)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // The trust core (composite includeBuild of ../android-sdk-musnad).
    implementation("com.elabify.musnad:musnad-sdk")
}
