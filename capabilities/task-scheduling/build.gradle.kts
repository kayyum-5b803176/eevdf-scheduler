plugins {
    id("com.eevdf.android-library-convention")
}
android {
    namespace = "com.eevdf.capabilities.taskscheduling"
    defaultConfig { minSdk = 26 }
    sourceSets {
        getByName("test") { java.srcDir("src/test/kotlin") }
    }
}
dependencies {
    implementation(project(":kernel"))
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}
