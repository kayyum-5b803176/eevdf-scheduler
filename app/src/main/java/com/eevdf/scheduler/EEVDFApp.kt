package com.eevdf.scheduler

import android.app.Application
import com.eevdf.scheduler.ui.NotificationHelper

class EEVDFApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
    }
}
