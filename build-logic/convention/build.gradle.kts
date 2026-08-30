plugins {
    `kotlin-dsl`
}

dependencies {
    // Precompiled script plugins need the actual plugin implementations on
    // their own classpath to configure them programmatically.
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidLibraryConvention") {
            id = "com.eevdf.android-library-convention"
            implementationClass = "com.eevdf.buildlogic.AndroidLibraryConventionPlugin"
        }
    }
}
