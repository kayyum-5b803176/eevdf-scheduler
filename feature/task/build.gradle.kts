plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.eevdf.feature.task"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    buildFeatures { viewBinding = true }
}
dependencies {
    api(project(":core"))
    implementation(project(":data"))
    implementation(project(":shared"))
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("com.google.android.material:material:1.11.0")
}
