plugins {
    id("com.eevdf.android-library-convention")
}
kotlin {
    // Phase 10, item 4. Scoped to :contract and :shared only — the two
    // smallest modules, where a wrong internal/public call is cheapest to
    // catch. See ARCHITECTURE.md Phase 10 item 4 for why the other 4 modules
    // (2652 declarations total) are out of scope for now.
    explicitApi()
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
