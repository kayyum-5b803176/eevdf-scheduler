package com.eevdf.shared

/**
 * Kill switches for shippable features.
 *
 * Every feature added from now on gets an entry here and is gated at its single
 * entry point. The purpose is narrow and important: when a new feature turns
 * out to break something in the field, you flip a flag instead of shipping a
 * hotfix and waiting on review.
 *
 * Rules that keep this from becoming its own maintenance burden:
 *  - One flag per feature, never per code path. Flags are not configuration.
 *  - [defaultEnabled] = false for a feature's first release, true once stable.
 *  - DELETE the flag once the feature has been stable for one full release.
 *    Long-lived flags create 2^n combinations that nobody tests.
 */
public enum class FeatureFlag(
    public val key: String,
    public val defaultEnabled: Boolean,
    public val description: String,
) {
    AUTO_SWITCH_BUBBLE(
        key = "ff_autoswitch_bubble",
        defaultEnabled = true,
        description = "Floating call bubble + automatic task switching on call state",
    ),
    MULTI_USER_SYNC(
        key = "ff_multi_user_sync",
        defaultEnabled = true,
        description = "Multi-user database sync and conflict resolution",
    ),
    STATS_CHARTS(
        key = "ff_stats_charts",
        defaultEnabled = true,
        description = "MPAndroidChart-backed statistics screens",
    ),
    HARDWARE_KEY_ACTIONS(
        key = "ff_hardware_key_actions",
        defaultEnabled = true,
        description = "Volume/hardware key shortcut handling",
    ),
    ;

    public companion object {
        public fun fromKey(key: String): FeatureFlag? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Read-side of the flag system. Implemented over SharedPreferences in :app.
 * Kept as an interface here so :core and feature code can depend on the concept
 * without depending on Android.
 */
public interface FeatureFlags {
    public fun isEnabled(flag: FeatureFlag): Boolean

    /** Runs [block] only when [flag] is on, returning null otherwise. */
    public fun <T> whenEnabled(flag: FeatureFlag, block: () -> T): T? =
        if (isEnabled(flag)) block() else null

    /** All flags on. Useful as a test default and as a safe fallback. */
    public object AllEnabled : FeatureFlags {
        override fun isEnabled(flag: FeatureFlag): Boolean = true
    }
}
