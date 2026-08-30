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
// Runs across every module's Kotlin source from the root, so there is one
// command and one config file rather than per-module duplication.
//
// ignoreFailures is TRUE for now: turning detekt on against 20k existing lines
// would fail the build on day one and you would disable it. Fix the baseline
// issues, then flip this to false so new violations block merges.
detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    source.setFrom(
        files(
            "app/src/main/kotlin",
            "contract/src/main/kotlin",
            "core/src/main/kotlin",
            "data/src/main/kotlin",
            "platform/src/main/kotlin",
            "shared/src/main/kotlin",
        )
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
// Enforces the boundaries that the compiler cannot yet enforce, because the
// feature packages still live inside :app rather than being real modules.
// Once Phase 2 splits them out, most of this becomes redundant and the Gradle
// dependency graph does the work instead.
val checkArchitecture by tasks.registering(Exec::class) {
    group = "verification"
    description = "Fails if a feature package imports another feature's internals, " +
        "or if the DB version, migration count and exported schemas disagree."
    workingDir = rootDir
    commandLine("bash", "$rootDir/scripts/check_architecture.sh")

    // Windows without Git Bash on PATH: skip rather than fail the whole build.
    // CI runs on Linux, so the guard is still enforced before any merge.
    // Deliberately avoids org.gradle.internal.* — internal APIs break between
    // Gradle versions and this file must be boring.
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
    dependsOn(":testing:test", ":data:testDebugUnitTest", ":app:testDebugUnitTest")
    dependsOn("detekt")
}
