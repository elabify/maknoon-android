plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// Coordinate so the super-app can consume this via a composite build
// (includeBuild) substituting com.elabify.musnad:musnad-sdk.
group = "com.elabify.musnad"
version = "0.1.0"

// WalletCore pulls full guava (with the real ListenableFuture); androidx pulls
// the empty `listenablefuture` stub. Drop the stub to avoid a duplicate class.
configurations.configureEach {
    exclude(group = "com.google.guava", module = "listenablefuture")
}

android {
    namespace = "com.elabify.musnad"
    compileSdk = 36

    defaultConfig {
        minSdk = 33 // Android 13; StrongBox + Class-3 biometric + key attestation.
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // mockwebserver / okhttp test deps bundle a duplicate OSGI manifest under
    // META-INF/versions/9; drop it so the androidTest APK packages cleanly.
    packaging {
        resources {
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    // Instrumented tests read the ML-DSA-65 KAT corpus straight from the
    // pq-crypto-rs source of truth (no copy), to assert the native .so on a
    // real device produces the same bytes as Apple CryptoKit.
    sourceSets {
        getByName("androidTest").assets.srcDir("${rootDir}/../pq-crypto-rs/test-vectors")
    }
}

dependencies {
    // Pure-Kotlin core (composite includeBuild) + native PQ (flatDir AAR).
    // `api` so app-level consumers (e.g. the CSCA bundle verifier, which
    // checks an ML-DSA-65 manifest signature over canonicalize(manifest))
    // can reach com.elabify.core.canonicalize across the composite build.
    api(libs.elabify.core)
    // `api` so app-level consumers (e.g. the mini-app MerchantIdentityStore,
    // which derives a merchant ML-DSA-65 key from a seed) can reach
    // uniffi.pq_crypto_core.* across the composite build.
    api(":library-release@aar") // pq-crypto-core AAR (flatDir)
    // MUST be the @aar variant: it bundles libjnidispatch.so per ABI. The
    // plain `jna` jar is desktop-only and fails on-device with
    // UnsatisfiedLinkError (com/sun/jna/android-aarch64/libjnidispatch.so).
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.biometric)
    implementation(libs.bouncycastle)

    // Encrypted persistence: Room over SQLCipher (DB key sealed by StrongBox).
    // `api` on room-runtime so app-level consumers (the Identity hub reads
    // credentials via MaknoonStore, whose DAOs return RoomDatabase-bound types)
    // can see androidx.room across the composite build.
    api(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher)

    // GMS-free networking (issuer pickup/reissue, verifier challenge, drop).
    implementation(libs.okhttp)

    // Bitcoin wallet (BDK; same lib version as the iOS app). `api` so the
    // app's Bitcoin UI can use bdk types the engine returns (AddressInfo,
    // KeychainKind, LocalOutput, CanonicalTx, Balance, OutPoint).
    api(libs.bdk.android)
    // EVM / Solana / Tron via Trust WalletCore (KMP binding).
    implementation(libs.walletcore)

    // Yubico yubikit-android (GMS-free: NFC/USB direct, no Play services).
    // `api` because YubiKeyClient's public signatures expose yubikit types
    // (YubiKeyDevice, Ctap2Session, ClientPin) the app's enroll screen drives.
    // The app additionally pulls :android (NfcYubiKeyManager) for the radio.
    api(libs.yubikit.core)
    api(libs.yubikit.fido)
    api(libs.yubikit.management)

    // Hardware-wallet UniFFI cores (BLE/USB signing). `api` so the app's
    // device UI can use their client types. Each is a flatDir AAR in hwlibs/.
    api(":ledger-btc-core@aar")
    api(":ledger-eth-core@aar")
    api(":ledger-sol-core@aar")
    api(":ledger-tron-core@aar")
    api(":trezor-core@aar")

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
}
