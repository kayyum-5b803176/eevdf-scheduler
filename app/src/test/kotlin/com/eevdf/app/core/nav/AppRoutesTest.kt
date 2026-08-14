package com.eevdf.app.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Restores the compile-time safety that [AppRoutes] trades away by resolving
 * screens by name.
 *
 * Rename, move or delete a routed Activity without updating AppRoutes and this
 * test fails — the same feedback the compiler used to give, one test run later
 * and long before a user taps a dead button.
 */
class AppRoutesTest {

    @Test fun `every route resolves to a real class`() {
        val missing = AppRoutes.ALL_ROUTES.filter { fqcn ->
            runCatching { Class.forName(fqcn) }.isFailure
        }
        assertTrue(
            buildString {
                appendLine("${missing.size} route(s) in AppRoutes do not resolve.")
                appendLine("An Activity was renamed, moved or deleted — update AppRoutes:")
                missing.forEach { appendLine("  - $it") }
            },
            missing.isEmpty(),
        )
    }

    @Test fun `every route is an Activity`() {
        val notActivities = AppRoutes.ALL_ROUTES.filter { fqcn ->
            val cls = runCatching { Class.forName(fqcn) }.getOrNull()
            cls != null && !android.app.Activity::class.java.isAssignableFrom(cls)
        }
        assertTrue("routes that are not Activities: $notActivities", notActivities.isEmpty())
    }

    @Test fun `ALL_ROUTES has no duplicates and lists every constant`() {
        assertEquals(
            "ALL_ROUTES contains duplicates",
            AppRoutes.ALL_ROUTES.size,
            AppRoutes.ALL_ROUTES.distinct().size,
        )
        // Reflect over the declared String constants so a new route added
        // without being appended to ALL_ROUTES is caught here.
        val declared = AppRoutes::class.java.declaredFields
            .filter { it.type == String::class.java }
            .mapNotNull { it.isAccessible = true; it.get(AppRoutes) as? String }
            .filter { it.startsWith("com.eevdf.app.feature.") }
            .toSet()
        val forgotten = declared - AppRoutes.ALL_ROUTES.toSet()
        assertTrue("route constant(s) missing from ALL_ROUTES: $forgotten", forgotten.isEmpty())
    }
}
