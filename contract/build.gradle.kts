plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.eevdf.contract"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    // INTENTIONALLY minimal. A contract is a promise between features, not a
    // place to accumulate dependencies. AppRoutes needs android.content for
    // Context/Intent; AlarmController, OverlayController and AlarmActions need
    // nothing beyond the Kotlin stdlib. If a contract file ever needs :core,
    // :data or a feature type, that is a sign it stopped being a contract.
}
