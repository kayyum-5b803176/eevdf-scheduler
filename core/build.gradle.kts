plugins { id("org.jetbrains.kotlin.jvm") }
dependencies {
    // INTENTIONALLY EMPTY of Android/Room. If a core file ever needs them,
    // the build fails here — that is the architectural guard rail.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}
