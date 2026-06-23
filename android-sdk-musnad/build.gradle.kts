// Root project: declares plugin versions; module config lives in
// musnad-sdk/build.gradle.kts.

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
}

// ---- GMS-free guardrail (the load-bearing GrapheneOS constraint). ----
// Fails the build if any resolvable configuration pulls in Google Mobile
// Services, Play Integrity, Firebase, or bundled ML Kit. Run in CI.
val forbiddenGroups = listOf(
    "com.google.android.gms",
    "com.google.firebase",
    "com.google.android.play",   // play-integrity, etc.
    "com.google.mlkit",
    "com.google.android.gms.location",
)

tasks.register("checkNoGms") {
    group = "verification"
    description = "Fails if any GMS / Play Integrity / Firebase / ML Kit dependency is present (GrapheneOS)."
    doLast {
        val offenders = sortedSetOf<String>()
        allprojects.forEach { p ->
            p.configurations
                .filter { it.isCanBeResolved }
                .forEach { cfg ->
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
