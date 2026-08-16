plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    `maven-publish`
}

// Coordinate so the super-app can consume this via a composite build
// (includeBuild) substituting com.elabify.musnad:musnad-sdk.
group = "com.elabify.musnad"

// Overridable so a release can stamp the tag without editing this file:
//   ./gradlew :musnad-sdk:publishToMavenLocal -PsdkVersion=0.7.2
//
// The default deliberately tracks the app's versionName rather than staying on
// the old standalone 0.1.0. The SDK and the app ship from one commit, so two
// unrelated version lines only invite "which 0.1.0 was that" questions. When
// the SDK genuinely releases independently this can fork, and the versioning
// rules in the SDK README apply from that point.
version = (findProperty("sdkVersion") as String?) ?: "0.7.2"

// WalletCore pulls full guava (with the real ListenableFuture); androidx pulls
// the empty `listenablefuture` stub. Drop the stub to avoid a duplicate class.
configurations.configureEach {
    exclude(group = "com.google.guava", module = "listenablefuture")
}

// Kotlin JVM target via the compilerOptions DSL (the old android.kotlinOptions
// jvmTarget is deprecated).
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
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
    implementation("net.java.dev.jna:jna:5.17.0@aar")

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
    // Real org.json for JVM unit tests. The android.jar the unit-test source
    // set compiles against ships org.json as a stub whose methods throw, so
    // anything that parses a credential, presentation or catalog payload was
    // untestable off-device. Test-only: the device still uses Android's own
    // implementation, and this is the same API.
    testImplementation("org.json:json:20240303")

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
}

// ---------------------------------------------------------------------------
// Publishing
//
// Produces the release AAR + a POM so a consumer can depend on
// com.elabify.musnad:musnad-sdk instead of cloning this repo and its five
// sibling Rust crates. `publishToMavenLocal` is the useful local target;
// a remote repository is a separate decision (ADR-0029 anticipated
// "*-sdk-musnad packages from tagged subpaths" but it was never implemented).
//
// KNOWN INCOMPLETE, and worth being blunt about rather than shipping a POM
// that looks usable and is not. The six `api(":name@aar")` entries above are
// flatDir dependencies on locally-built artifacts (the pq-crypto core and the
// five hardware-wallet UniFFI cores). flatDir carries no version.
//
// Checked against the generated POM rather than assumed: they are NOT omitted.
// All six appear as <dependency> entries with an artifactId and a groupId but
// NO <version>. That is worse than leaving them out, because a consumer's
// build tries to resolve them and fails, instead of failing later and more
// obviously at link time. 6 of the 24 dependencies are in this state.
//
// Closing it needs those six AARs published as real coordinates too, which is
// a bigger piece of work than this module: each is built by a Rust toolchain
// + cargo-ndk from a separate crate. Until then this target is for consuming
// the SDK inside this workspace, and `verifyPublication` below reports the
// gap rather than letting an integrator discover it.
// ---------------------------------------------------------------------------
// Publishing to a REMOTE repository is refused while the POM cannot resolve.
// publishToMavenLocal stays available: consuming the artifact inside this
// workspace is exactly what it is for, and `verifyPublication` reports the gap.
// What this stops is publishing something that looks usable to an outsider and
// is not, which is a harder mistake to walk back than a blocked task.
gradle.taskGraph.whenReady {
    val remote = allTasks.any {
        it.project == project && it.name.startsWith("publish") &&
            it.name.contains("Repository") && !it.name.contains("MavenLocal")
    }
    if (remote && !project.hasProperty("allowIncompletePom")) {
        throw GradleException(
            "Refusing to publish remotely: 6 of this artifact's dependencies are " +
                "locally-built Rust/UniFFI AARs declared via flatDir, so the POM " +
                "emits them without a <version> and an external consumer fails at " +
                "resolution. Publish those cores as real coordinates first. Run " +
                ":musnad-sdk:verifyPublication for the list. Override with " +
                "-PallowIncompletePom only if you know why.",
        )
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
            artifactId = "musnad-sdk"
            pom {
                name.set("Musnad SDK")
                description.set(
                    "Post-quantum identity, credentials and self-custody wallet for Android. " +
                        "Public API: the com.elabify.maknoon facade.",
                )
                licenses {
                    license { name.set("See LICENSE in the distribution") }
                }
            }
        }
    }
}

/**
 * Reads the GENERATED POM and reports every dependency an external consumer
 * could not resolve.
 *
 * Deliberately parses the real output rather than restating a hardcoded list.
 * The first version of this task asserted the flatDir AARs were "absent from
 * the POM"; reading the file showed they are present but version-less, which
 * fails a consumer's build in a different and more confusing way. A check that
 * describes what someone believes is worth very little.
 *
 * Run after generating the POM, e.g.
 *   ./gradlew :musnad-sdk:publishToMavenLocal :musnad-sdk:verifyPublication
 */
tasks.register("verifyPublication") {
    group = "verification"
    description = "Reports dependencies in the generated POM that a consumer cannot resolve."
    val pomDir = layout.buildDirectory.dir("publications/release")
    doLast {
        val pom = pomDir.get().asFile.resolve("pom-default.xml")
        if (!pom.exists()) {
            logger.lifecycle("no POM generated yet; run a publish task first")
            return@doLast
        }
        val deps = Regex("<dependency>(.*?)</dependency>", RegexOption.DOT_MATCHES_ALL)
            .findAll(pom.readText()).map { it.groupValues[1] }.toList()
        val broken = deps.filter { !it.contains("<version>") || !it.contains("<groupId>") }
            .map { Regex("<artifactId>(.*?)</artifactId>").find(it)?.groupValues?.get(1) ?: "?" }

        logger.lifecycle("musnad-sdk $version: ${deps.size} dependencies in the POM")
        if (broken.isEmpty()) {
            logger.lifecycle("  all resolvable by coordinate")
            return@doLast
        }
        logger.lifecycle("  ${broken.size} NOT resolvable by an external consumer:")
        broken.forEach { logger.lifecycle("    - $it (flatDir AAR: no version)") }
        logger.lifecycle(
            "  These are the locally-built Rust/UniFFI cores. Publish them as " +
                "real coordinates before advertising this artifact externally.",
        )
    }
}
