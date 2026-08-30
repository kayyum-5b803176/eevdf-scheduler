plugins {
    id("com.eevdf.android-library-convention")
}
android {
    namespace = "com.eevdf.contract"
    defaultConfig { minSdk = 26 }
}
dependencies {
    // INTENTIONALLY minimal. A contract is a promise between features, not a
    // place to accumulate dependencies. AppRoutes needs android.content for
    // Context/Intent; AlarmController, OverlayController and AlarmActions need
    // nothing beyond the Kotlin stdlib. If a contract file ever needs :core,
    // :data or a feature type, that is a sign it stopped being a contract.
}
