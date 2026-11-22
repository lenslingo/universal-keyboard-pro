package com.ukpro.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class UkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, "Connection", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows while UK Pro is connected to your laptop"
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_FILES, "Received files", NotificationManager.IMPORTANCE_DEFAULT))
        UkRuntime.init(this)
    }

    companion object {
        const val CHANNEL_SERVICE = "connection"
        const val CHANNEL_FILES = "files"
    }
}
