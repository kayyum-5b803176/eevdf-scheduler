package com.eevdf.capabilities.notification

import android.content.Context
import android.util.Log
import com.eevdf.capabilities.settingsstorage.state.NotificationPrefs
import com.eevdf.kernel.eventbus.AlarmNotificationDecision
import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.eventbus.RequestTopics

/**
 * Answers [RequestTopics.ALARM_NOTIFICATION_DECISION] — the ONLY caller of
 * [AppForegroundTracker], [ForegroundAppDetector], [AlarmNotificationPolicy],
 * and [AlarmReliabilityChecker.canUseFullScreenIntent] for this purpose now.
 * alarm-ringer used to import all four directly and combine their results
 * itself; that whole orchestration moved here, verbatim, behind one request.
 *
 * `getForegroundPackage`'s try/catch is preserved from the original inline
 * code: a best-effort UsageStatsManager read that must degrade to "no match"
 * on any OEM/edge-case failure, never propagate and fail the request itself.
 */
class AlarmNotificationDecisionResponder(
    private val appContext: Context,
    bus: EventBus,
) {
    init {
        bus.respondTo(RequestTopics.ALARM_NOTIFICATION_DECISION, CAPABILITY_ID) {
            val appForeground = AppForegroundTracker.isAppInForeground
            val foregroundPkg = if (appForeground) null else try {
                ForegroundAppDetector.getForegroundPackage(appContext)
            } catch (e: Exception) {
                Log.w(TAG, "getForegroundPackage failed, treating as no match", e)
                null
            }
            val excludeAppMatch = !appForeground &&
                NotificationPrefs.isAppExcluded(appContext, foregroundPkg)

            val decision = AlarmNotificationPolicy.decide(
                appForeground = appForeground,
                excludeAppMatch = excludeAppMatch,
                lockScreenOverlayEnabled = NotificationPrefs.isLockScreenOverlayEnabled(appContext),
            )

            AlarmNotificationDecision(
                suppressBanner = decision.suppressBanner,
                attachFullScreenIntent = decision.attachFullScreenIntent,
                canUseFullScreenIntent = AlarmReliabilityChecker.canUseFullScreenIntent(appContext),
            )
        }
    }

    private companion object {
        const val CAPABILITY_ID = "notification"
        const val TAG = "AlarmNotifDecision"
    }
}
