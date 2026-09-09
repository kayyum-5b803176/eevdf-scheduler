import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
// ── Release signing ──────────────────────────────────────────────────────────
// The previous config signed release builds with the DEBUG key. An app shipped
// that way can never be updated under a real key, and Play will reject it.
//
// Create keystore.properties in the project root (it is gitignored):
//
//   storeFile=/absolute/path/to/release.jks
//   storePassword=...
//   keyAlias=...
//   keyPassword=...
//
// Until that file exists, release builds fall back to debug signing so the
// build still works — but they are NOT publishable, and the build prints a
// warning saying so.
val keystorePropsFile = rootProject.file("keystore.properties")
val hasReleaseKeystore = keystorePropsFile.exists()
val keystoreProps = Properties().apply {
    if (hasReleaseKeystore) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.eevdf.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.eevdf.scheduler"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "6.25.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "WARNING: keystore.properties not found — release build is signed with the " +
                        "DEBUG key and CANNOT be published to Play. See app/build.gradle.kts."
                )
                signingConfigs.getByName("debug")
            }
        }
        getByName("debug") { applicationIdSuffix = ".debug" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    sourceSets {
        getByName("test")        { java.srcDir("src/test/kotlin") }
        getByName("androidTest") { java.srcDir("src/androidTest/kotlin") }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName =
                "eevdf-scheduler-v${versionName}(${versionCode})-${buildType.name}.apk"
        }
    }
}
dependencies {
    // v6.9.0: :core, :data, :feature, :platform, :shared are gone — every one
    // of their files now lives in the capability that owns it. :app depends on
    // the full capability list because it is the composition root: it is the
    // one place allowed to know every capability that exists (rule 6).
    // :app is now a thin shell: manifest, launcher icons, and the dependency
    // on :composition, which owns all wiring. Capability deps move there too —
    // :app keeps them only because AGP needs every module contributing a
    // manifest fragment on the application's own compile classpath.
    implementation(project(":kernel"))
    implementation(project(":composition"))
    // Explicit, not transitive: app/src/main/AndroidManifest.xml references
    // @style/AppTheme, which design-system owns. Resource resolution needs the
    // dependency declared here directly.
    implementation(project(":capabilities:design-system"))

    implementation(project(":capabilities:sound"))
    implementation(project(":capabilities:vibration"))
    implementation(project(":capabilities:task-storage"))
    implementation(project(":capabilities:task-scheduling"))
    implementation(project(":capabilities:run-history"))
    implementation(project(":capabilities:notification"))
    implementation(project(":capabilities:settings-storage"))
    implementation(project(":capabilities:group-picker"))
    implementation(project(":capabilities:navigation-routes"))
    implementation(project(":capabilities:feature-toggles"))
    implementation(project(":capabilities:task-list-screen"))
    implementation(project(":capabilities:add-task-screen"))
    implementation(project(":capabilities:countdown-timer"))
    implementation(project(":capabilities:links-screen"))
    implementation(project(":capabilities:backup-restore"))
    implementation(project(":capabilities:app-foreground"))
    implementation(project(":capabilities:alarm-ringer"))
    implementation(project(":capabilities:call-autoswitch"))
    implementation(project(":capabilities:settings-screens"))
    implementation(project(":capabilities:stats-screens"))
    implementation(project(":capabilities:multi-device-sync"))
    implementation(project(":capabilities:event-log"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.mpandroidchart)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // ── Tests ────────────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
