import java.io.File

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt)
}

// ── Static analysis ──────────────────────────────────────────────────────────
// v6.9.0: the per-module source list is gone. Every capability follows the same
// layout, so one glob covers all of them and adding capability N+1 needs no
// edit here — consistent with rule 6 (only composition knows the full list).
detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    source.setFrom(
        files(
            "app/src/main/kotlin",
            "contract/src/main/kotlin",
            "kernel/src/main/kotlin",
        ) + file("capabilities")
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .map { File(it, "src/main/kotlin") }
            .filter { it.exists() }
    )
    parallel = true
    ignoreFailures = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        xml.required.set(true)
        txt.required.set(false)
        sarif.required.set(false)
    }
}

// ── Architecture guard ───────────────────────────────────────────────────────
// The old script checked "does feature A import feature B's internals" against
// an allowlist, because the feature packages were not real modules. They are
// now: an illegal import is a Gradle/compile error, not something a grep has to
// catch. What the rewritten script checks instead is the rules the compiler
// still cannot see — bus-only communication, no hardcoded topic strings, and
// the DB version/migration/schema agreement it always checked.
val checkArchitecture by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks bus-only communication, topic-constant usage, and DB schema agreement."
    workingDir = rootDir
    commandLine("bash", "$rootDir/scripts/check_architecture.sh")

    onlyIf {
        val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        val exe = if (isWindows) "bash.exe" else "bash"
        val hasBash = System.getenv("PATH").orEmpty()
            .split(File.pathSeparator)
            .any { dir -> dir.isNotBlank() && File(dir, exe).exists() }
        if (!hasBash) logger.lifecycle("checkArchitecture skipped: '$exe' not found on PATH.")
        hasBash
    }
}

tasks.register("verifyAll") {
    group = "verification"
    description = "Everything CI runs: architecture guard, detekt, and all unit tests."
    dependsOn(checkArchitecture)
    dependsOn(
        ":kernel:test",
        ":capabilities:task-scheduling:test",
        ":capabilities:task-storage:test",
        ":capabilities:run-history:test",
        ":capabilities:backup-restore:test",
        ":capabilities:multi-device-sync:test",
        ":capabilities:navigation-routes:test",
        ":app:testDebugUnitTest",
    )
    dependsOn("detekt")
}
