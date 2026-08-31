plugins { alias(libs.plugins.kotlin.jvm) }
kotlin {
    // Phase 10, item 4. Scoped to :shared and :contract only — see
    // ARCHITECTURE.md Phase 10 item 4.
    explicitApi()
}
