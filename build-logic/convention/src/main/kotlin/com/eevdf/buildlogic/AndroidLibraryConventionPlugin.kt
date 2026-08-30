package com.eevdf.buildlogic

import com.android.build.gradle.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Applies `com.android.library` + `org.jetbrains.kotlin.android` and sets the
 * handful of values every Android library module in this project needs
 * identically: compileSdk, Java/Kotlin target version.
 *
 * Deliberately does NOT set `namespace` or `minSdk` — those genuinely differ
 * per module (`:contract`/`:data`/`:platform` use minSdk 26, `:feature` uses
 * 31, matching the app's real floor) and stay declared in each module's own
 * build.gradle.kts, right next to its `dependencies {}` block.
 *
 * Phase 10, item 3: before this plugin existed, compileSdk/compileOptions/
 * jvmTarget were five copies of the same six lines across contract/data/
 * feature/platform's build.gradle.kts — no drift found when checked, but
 * nothing was stopping one from silently diverging on a future edit either.
 * Now there is exactly one place these values live.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.library")
                apply("org.jetbrains.kotlin.android")
            }

            extensions.configure<LibraryExtension> {
                compileSdk = 34

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }

            tasks.withType(KotlinCompile::class.java).configureEach {
                compilerOptions.jvmTarget.set(
                    org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
                )
            }
        }
    }
}
