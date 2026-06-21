plugins { alias(libs.plugins.kotlin.jvm) }
dependencies {
    // INTENTIONALLY EMPTY of Android/Room/Hilt. If a core file ever needs them,
    // the build fails here — that is the architectural guard rail. Core stays a
    // pure, deterministic JVM module; its services are wired into Hilt graphs
    // from :app via @Provides bindings, never by annotating core classes.
    implementation(libs.kotlinx.coroutines.core)
}
