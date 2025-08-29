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
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import timber.log.Timber
import javax.inject.Inject

/**
 * **SESSION-BASED KEEP-ALIVE SERVICE**: Background protection ONLY when user is logged in
 *
 * This service:
 * - Starts ONLY when user has an active session (logged in)
 * - Stops automatically when user logs out
 * - Shows non-dismissible notification to prevent user from killing it
 * - Ensures reliable FCM delivery for calls and PTT messages
 */
@AndroidEntryPoint
class CallKeepAliveService : Service() {

    @Inject
    lateinit var activeSessionHolder: ActiveSessionHolder

    companion object {
        const val NOTIFICATION_ID = 1337
        const val CHANNEL_ID = "CALL_KEEPALIVE_CHANNEL"
    }

    override fun onCreate() {
        super.onCreate()
        val androidVersion = android.os.Build.VERSION.SDK_INT

        Timber.w("📱 CallKeepAliveService CREATED for Android $androidVersion - session-based protection")
        
        // **CRITICAL**: Always create notification channel and start foreground FIRST
        // to avoid ForegroundServiceDidNotStartInTimeException
        createNotificationChannel()
        
        val session = activeSessionHolder.getSafeActiveSession()
        if (session == null) {
            Timber.w("❌ No active session - starting with placeholder then stopping")
            // Start foreground first to satisfy Android requirements
            startAsForeground("Unknown")
            // Then stop the service after a brief delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                stopSelf()
            }, 100) // 100ms delay to ensure foreground is established
            return
        }
 
        Timber.w("✅ Active session found for user: ${session.myUserId}")
        startAsForeground(session.myUserId)
        Timber.w("🔐 Keep-alive service running - user ${session.myUserId} protected from system killing")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // **SESSION CHECK**: Stop if no active session
        if (!activeSessionHolder.hasActiveSession() || activeSessionHolder.getSafeActiveSession() == null) {
            Timber.w("❌ Session lost - stopSelf() from onStartCommand()")
            stopSelf()
            return START_NOT_STICKY
        }

        Timber.w("📱 CallKeepAliveService STARTED - session-based protection active")
        return START_STICKY // Restart if killed (only when session exists)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                        CHANNEL_ID,
                        "Call Keep-Alive",
                        NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps the app ready to receive calls/messages"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun startAsForeground(userId: String) {
        val userName = userId.substringBefore(":").ifEmpty { "User" }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Ready for calls")
                .setContentText("$userName is ready to receive calls and messages")
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setWhen(0) // Prevents timestamp display
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setAutoCancel(false)
                .setDeleteIntent(null) // CRITICAL: Prevents user from swiping away
                .setLocalOnly(true) // Doesn't sync to other devices
                .setGroup("call_service_group") // Group for better management
                .setGroupSummary(false)

        if (android.os.Build.VERSION.SDK_INT >= 34) {
            builder.setForegroundServiceBehavior(
                    NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
            )
        }
        val notification = builder.build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.w("🧹 CallKeepAliveService destroyed")

        // **SESSION CHECK**: Only restart if user is still logged in
//        if (activeSessionHolder.hasActiveSession()) {
//            val session = activeSessionHolder.getSafeActiveSession()
//            if (session != null) {
//                Timber.w("📱 CallKeepAliveService destroyed but session exists - restarting for user ${session.myUserId}")
//                // Restart only if session is active
//                val intent = Intent(this, CallKeepAliveService::class.java)
//                startService(intent)
//            } else {
//                Timber.w("📱 CallKeepAliveService destroyed - session is null, not restarting")
//            }
//        } else {
//            Timber.w("📱 CallKeepAliveService destroyed - no active session, not restarting")
//        }
    }
}
