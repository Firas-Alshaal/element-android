/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import im.vector.app.R
import timber.log.Timber

/**
 * Keep-alive service to ensure FCM works when app is in background
 * This prevents the app from being completely killed by the system
 */
class CallKeepAliveService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1337
        const val CHANNEL_ID = "CALL_KEEPALIVE_CHANNEL"
    }

    override fun onCreate() {
        super.onCreate()
        Timber.w("📱 CallKeepAliveService CREATED - protecting app from being killed")
        createNotificationChannel()
        startForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.w("📱 CallKeepAliveService STARTED - app is now protected")
        return START_STICKY // Restart if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Call Service",
            NotificationManager.IMPORTANCE_MIN // Minimal importance to be silent
        ).apply {
            description = "Keeps the app running to receive calls"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun startForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ready for calls")
            .setContentText("Element is ready to receive calls")
            .setSmallIcon(R.drawable.ic_call_answer)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("📱 CallKeepAliveService destroyed - restarting...")
        
        // Restart immediately if destroyed
        val intent = Intent(this, CallKeepAliveService::class.java)
        startService(intent)
    }
}
