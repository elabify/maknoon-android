import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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

android {
    namespace = "com.elabify.app.maknoon"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.elabify.app.maknoon" // matches the iOS bundle id
        minSdk = 33
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0"
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
