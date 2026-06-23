// Root project: plugin versions + the GMS-free guardrail. App config lives
// in app/build.gradle.kts.

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
