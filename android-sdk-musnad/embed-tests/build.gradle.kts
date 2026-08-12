// Embed-tests: a standalone module that depends ONLY on the published SDK facade
// (`:musnad-sdk` via a project dependency here; a third party would use the Maven
// coordinate). It exercises every public API in `com.elabify.maknoon` so the facade is
// proven complete and consumable from outside the SDK, and doubles as integrator docs.
// See the wire-format spec and android-sdk-musnad/README.md section 10.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// WalletCore pulls full guava; androidx pulls the empty listenablefuture stub. Drop the
// stub to avoid a duplicate class (mirrors :musnad-sdk).
configurations.configureEach {
    exclude(group = "com.google.guava", module = "listenablefuture")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.elabify.maknoon.embedtests"
    compileSdk = 36
    defaultConfig {
        minSdk = 33
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The ONLY dependency a host needs. Everything the facade returns is reachable through it.
    implementation(project(":musnad-sdk"))
    implementation(libs.coroutines.core)
    implementation(libs.androidx.core)
}
