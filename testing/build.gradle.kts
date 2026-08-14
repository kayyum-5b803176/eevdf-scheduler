plugins { alias(libs.plugins.kotlin.jvm) }
dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
