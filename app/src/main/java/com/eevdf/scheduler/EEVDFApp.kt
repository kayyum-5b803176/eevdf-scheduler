package com.eevdf.scheduler

import android.app.Application

class EEVDFApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Notification channels are created lazily inside AlarmForegroundService.onCreate()
        // No setup needed here.
    }
}
