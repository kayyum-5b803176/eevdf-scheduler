plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Kernel stays a pure Kotlin/JVM module, same as :core — no Android import is
// ever valid here (rule 1: kernel is the only trusted, non-pluggable code,
// and it must stay small and dependency-free forever). Enforce this the same
// way :core's purity is enforced today, via check_architecture.sh's
// existing "no android/androidx/dagger/javax.inject import" scan extended to
// this module's path once composition/build/check-architecture.sh is
// rewritten in Phase 6.

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
