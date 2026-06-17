plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.eevdf.data"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    ksp { arg("room.schemaLocation", "$projectDir/schemas") }
}
dependencies {
    api(project(":core"))
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
