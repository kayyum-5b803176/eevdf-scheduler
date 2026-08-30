pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
    // Share the ROOT project's version catalog rather than declaring AGP/Kotlin
    // versions a second time here. The entire point of this module is a single
    // source of truth for build config — a second, independently-versioned
    // catalog inside build-logic would just relocate the drift problem instead
    // of fixing it.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
