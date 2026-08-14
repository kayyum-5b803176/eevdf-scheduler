plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "com.eevdf.data"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    ksp { arg("room.schemaLocation", "$projectDir/schemas") }

    // Room writes the exported schema JSON into data/schemas/ at compile time.
    // MigrationTestHelper reads it from androidTest assets, so the folder is
    // registered as an asset dir rather than the JSON being copied by hand.
    sourceSets {
        getByName("test") { java.srcDir("src/test/kotlin") }
        getByName("androidTest") {
            java.srcDir("src/androidTest/kotlin")
            assets.srcDir("$projectDir/schemas")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Unit tests here run against the real org.json jar (added below),
            // NOT the empty android.jar stubs, so BackupManager round trips for
            // real instead of silently returning defaults.
            isReturnDefaultValues = false
        }
    }
}
dependencies {
    api(project(":core"))
    api(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)

    // Hilt — repositories use @Inject constructors; @InstallIn modules live in :app
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // ── Unit tests (JVM) ─────────────────────────────────────────────────────
    // kotlin-reflect powers the field-coverage guards in BackupRoundTripCoverageTest
    // and TaskFieldClassificationTest: they enumerate Task's constructor so a new
    // field cannot be forgotten.
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("reflect"))

    // ── Instrumented tests (device/emulator) ─────────────────────────────────
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
