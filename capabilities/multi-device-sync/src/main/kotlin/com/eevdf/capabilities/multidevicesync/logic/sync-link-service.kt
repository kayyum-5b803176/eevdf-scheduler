package com.eevdf.capabilities.multidevicesync.logic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * "Keep syncing when the app is closed".
 *
 * A foreground service of type connectedDevice whose only job is to keep this
 * app's process alive (and say so in a permanent, silent notification) while
 * the Bluetooth link to the other phone is held open by [SyncCoordinator]. It
 * does no work of its own: the coordinator — a singleton in the same process —
 * keeps the link, merges what arrives and keeps alarms in step ([AlarmSync]).
 *
 * Started only while the app is on screen (Android does not allow starting a
 * foreground service from the background), and kept until switched off.
 */
class SyncLinkService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Sync with another phone", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
        val peer = intent?.getStringExtra(EXTRA_PEER) ?: lastPeer ?: "the other phone"
        lastPeer = peer
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Syncing with $peer")
            .setContentText("Keeps timers and alarms in step while the app is closed")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        return try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            START_STICKY
        } catch (_: Exception) {
            // Missing "Nearby devices" permission, or Android refused: just don't run.
            stopSelf()
            START_NOT_STICKY
        }
    }

    companion object {
        private const val CHANNEL = "eevdf_sync_link"
        private const val NOTIFICATION_ID = 7421
        private const val EXTRA_PEER = "peer"
        @Volatile private var lastPeer: String? = null

        /** Returns false if Android refused (for example because the app is not on screen). */
        fun start(context: Context, peerName: String?): Boolean = try {
            context.startForegroundService(Intent(context, SyncLinkService::class.java).putExtra(EXTRA_PEER, peerName))
            true
        } catch (_: Exception) {
            false
        }

        fun stop(context: Context) {
            try { context.stopService(Intent(context, SyncLinkService::class.java)) } catch (_: Exception) { }
        }
    }
}
