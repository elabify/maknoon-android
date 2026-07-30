// Root project: plugin versions + the GMS-free guardrail. App config lives
// in app/build.gradle.kts.

// Pin a newer standalone R8 than the one AGP 8.7.3 bundles, so R8 can parse
// Kotlin 2.2.0 metadata and stop emitting the ~312 "error parsing kotlin
// metadata" warnings on the release minify. Buildscript classpath only; AGP +
// Gradle are unchanged (far lower risk than a full AGP/Gradle bump). ADR-0068.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools:r8:8.10.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// ---- GMS-free guardrail (GrapheneOS). Same policy as the SDK build. ----
val forbiddenGroups = listOf(
    "com.google.android.gms",
    "com.google.firebase",
    "com.google.android.play",
    "com.google.mlkit",
    "com.google.android.gms.location",
)

tasks.register("checkNoGms") {
    group = "verification"
    description = "Fails if any GMS / Play Integrity / Firebase / ML Kit dependency is present (GrapheneOS)."
    doLast {
        val offenders = sortedSetOf<String>()
        allprojects.forEach { p ->
            p.configurations.filter { it.isCanBeResolved }.forEach { cfg ->
                runCatching {
                    cfg.incoming.resolutionResult.allComponents.forEach { c ->
                        val id = c.moduleVersion ?: return@forEach
                        if (forbiddenGroups.any { id.group == it || id.group.startsWith("$it.") }) {
                            offenders.add("${p.path}: ${id.group}:${id.name}:${id.version}")
                        }
                    }
                }
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "GMS-free policy violation (breaks GrapheneOS). Forbidden dependencies:\n  " +
                    offenders.joinToString("\n  ")
            )
        }
        println("checkNoGms: OK — no GMS / Play Integrity / Firebase / ML Kit dependencies.")
    }
}
