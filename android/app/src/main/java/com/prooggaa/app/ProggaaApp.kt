package com.prooggaa.app

import android.app.Application

class ProggaaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        // Checks for new notifications about every 15 minutes while the app is closed.
        NotificationWorker.schedule(this)
    }
}
