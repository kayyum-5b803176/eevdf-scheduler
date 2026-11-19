plugins {
    id("com.eevdf.android-library-convention")
}
android {
    namespace = "com.eevdf.capabilities.timercard"
    defaultConfig { minSdk = 31 }
    sourceSets {
        getByName("test") { java.srcDir("src/test/kotlin") }
    }
}
dependencies {
    implementation(project(":kernel"))
    implementation(project(":capabilities:design-system"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
