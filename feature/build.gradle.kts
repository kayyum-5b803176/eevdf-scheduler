plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "com.eevdf.feature"
    compileSdk = 34
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Deliberately NOT one flat src/main/{kotlin,res}/. Each subfeature is
    // physically co-located — its own kotlin/ and res/ side by side — instead of
    // being scattered across type-based top-level folders. It is still exactly
    // one Android module: one namespace, one generated R class, one manifest.
    // Per-feature COMPILE isolation (task cannot import settings) is still the
    // job of scripts/check_architecture.sh, not the module system, until a
    // future phase splits these into real per-feature Gradle modules.
    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            val subfeatures = listOf(
                "task", "alarm", "autoswitch", "backup",
                "settings", "stats", "sync", "shared", "ui",
            )
            kotlin.srcDirs(subfeatures.map { "src/main/$it/kotlin" })
            res.srcDirs(subfeatures.map { "src/main/$it/res" })
        }
    }
}
dependencies {
    implementation(project(":contract"))
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":platform"))
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.material)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.mpandroidchart)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
