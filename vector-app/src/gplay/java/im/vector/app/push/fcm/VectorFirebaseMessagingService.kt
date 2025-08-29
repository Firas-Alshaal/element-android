/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.push.fcm

import android.app.NotificationChannel
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.pushers.FcmHelper
import im.vector.app.core.pushers.PushParser
import im.vector.app.core.pushers.PushersManager
import im.vector.app.core.pushers.UnifiedPushHelper
import im.vector.app.core.pushers.VectorPushHandler
import im.vector.app.features.mdm.MdmData
import im.vector.app.features.mdm.MdmService
import im.vector.app.features.settings.VectorPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.logger.LoggerTag
import timber.log.Timber
import javax.inject.Inject

private val loggerTag = LoggerTag("Push", LoggerTag.SYNC)

@AndroidEntryPoint
class VectorFirebaseMessagingService : FirebaseMessagingService() {
    @Inject lateinit var fcmHelper: FcmHelper
    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var activeSessionHolder: ActiveSessionHolder
    @Inject lateinit var pushersManager: PushersManager
    @Inject lateinit var pushParser: PushParser
    @Inject lateinit var vectorPushHandler: VectorPushHandler
    @Inject lateinit var unifiedPushHelper: UnifiedPushHelper
    @Inject lateinit var mdmService: MdmService

    private val scope = CoroutineScope(SupervisorJob())

    init {
        Timber.d("🔥 VectorFirebaseMessagingService initialized")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNewToken(token: String) {
        val androidVersion = android.os.Build.VERSION.SDK_INT
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()

        Timber.w("🔑🔑🔑 FCM NEW TOKEN ON ANDROID $androidVersion 🔑🔑🔑")
        Timber.w("📱 Device: $manufacturer (API $androidVersion)")
        Timber.w("🔑 Token: ${token.take(20)}...${token.takeLast(10)}")
        Timber.w("⏰ Timestamp: ${System.currentTimeMillis()}")

        // **CRITICAL ANDROID 14 DEBUG**: Extra token logging
        if (androidVersion == android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Timber.w("🚨 ANDROID 14 NEW TOKEN - DEBUGGING MODE 🚨")
            Timber.w("📱 Battery optimized: ${isBatteryOptimized()}")
            Timber.w("🔔 Notifications enabled: ${areNotificationsEnabled()}")
            Timber.w("📡 App standby bucket: ${getAppStandbyBucket()}")
        }

        Timber.tag(loggerTag.value).d("New Firebase token")
        Timber.tag(loggerTag.value).d("New Firebase token: $token")
        fcmHelper.storeFcmToken(token)
        if (
                vectorPreferences.areNotificationEnabledForDevice() &&
                activeSessionHolder.hasActiveSession() &&
                unifiedPushHelper.isEmbeddedDistributor()
        ) {
            Timber.w("🔄 Registering pusher with new token on Android $androidVersion")
            scope.launch {
                pushersManager.enqueueRegisterPusher(
                        pushKey = token,
                        gateway = mdmService.getData(
                                mdmData = MdmData.DefaultPushGatewayUrl,
                                defaultValue = getString(im.vector.app.config.R.string.pusher_http_url),
                        ),
                )
                Timber.w("✅ Pusher registration enqueued on Android $androidVersion")
            }
        } else {
            Timber.e("❌ Cannot register pusher on Android $androidVersion - conditions not met:")
            Timber.e("   Notifications enabled: ${vectorPreferences.areNotificationEnabledForDevice()}")
            Timber.e("   Has active session: ${activeSessionHolder.hasActiveSession()}")
            Timber.e("   Is embedded distributor: ${unifiedPushHelper.isEmbeddedDistributor()}")
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {

        val androidVersion = android.os.Build.VERSION.SDK_INT
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        val deviceModel = android.os.Build.MODEL

        Timber.w("🔥🔥🔥 FCM MESSAGE RECEIVED ON ANDROID $androidVersion 🔥🔥🔥")
        Timber.w("📱 Device: $manufacturer $deviceModel (API $androidVersion)")
        Timber.w("⏰ Timestamp: ${System.currentTimeMillis()}")
        Timber.w("🌐 Network: ${getNetworkInfo()}")

        // **SESSION CHECK**: Only process FCM if user is logged in
        if (!activeSessionHolder.hasActiveSession()) {
            Timber.w("❌ FCM received but no active session - ignoring notification")
            Timber.w("🚪 User is logged out - FCM processing skipped")
            return
        }

        val session = activeSessionHolder.getSafeActiveSession()
        if (session == null) {
            Timber.w("❌ FCM received but session is null - ignoring notification")
            return
        }

        Timber.w("✅ FCM processing for logged in user: ${session.myUserId}")

        // **CRITICAL ANDROID 14 DEBUG**: Extra logging for Android 14
        if (androidVersion == android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Timber.w("🚨 ANDROID 14 FCM RECEIVED - DEBUGGING MODE 🚨")
            Timber.w("📱 Battery optimized: ${isBatteryOptimized()}")
            Timber.w("🔔 Notifications enabled: ${areNotificationsEnabled()}")
            Timber.w("🔋 Device idle: ${isDeviceIdle()}")
            Timber.w("🔑 FCM Token status: ${getFcmTokenStatus()}")
            Timber.w("📡 App standby bucket: ${getAppStandbyBucket()}")
        }

        // **ANDROID 15 COMPATIBLE**: Start keep-alive service ONLY when allowed
        try {
            // **SAFE SERVICE START**: Only attempt if we're allowed to start foreground services
            val keepAliveIntent = Intent(this, im.vector.app.core.services.CallKeepAliveService::class.java)

            // Android 15+ requires special handling for foreground service starts
            if (androidVersion >= 35) { // Android 15+
                Timber.w("📱 Android 15+ detected - using safe service start")
                // Try to start, but catch the specific exception if not allowed
                try {
                    ContextCompat.startForegroundService(this, keepAliveIntent)
                    Timber.w("✅ Keep-alive service started successfully on Android $androidVersion")
                } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
                    Timber.w("⚠️ Android 15+: Foreground service not allowed in background - using wake lock only")
                    // Don't fail, just proceed without the service
                }
            } else {
                // Android 14 and below - normal start
                ContextCompat.startForegroundService(this, keepAliveIntent)
                Timber.w("📱 Keep-alive service started on Android $androidVersion")
            }

            // **UNIVERSAL WAKE LOCK**: Always acquire for critical processing
            acquireUniversalWakeLock()
        } catch (e: Exception) {
            Timber.w(e, "⚠️ Service start failed - continuing with wake lock only")
            // Still acquire wake lock even if service failed
            try {
                acquireUniversalWakeLock()
            } catch (e2: Exception) {
                Timber.e(e2, "❌ CRITICAL: Even wake lock failed")
            }
        }

        Timber.d("🔥 message.notification = ${message.notification}")
        Timber.d("🔥 message.data = ${message.data}")

        message.data.forEach { (k, v) ->
            Timber.d("⧗ FCM payload key=\"$k\"  value=\"$v\"")
        }

        Timber.tag(loggerTag.value).d("New Firebase message: ${message.data}")
        Timber.d("✔ FCM data payload: ${message.data}")

        // **UNIVERSAL SOLUTION**: Handle PTT AND calls for ALL Android versions (6.0+)

        val isInBackground = !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

        Timber.w("📱 FCM received in ${if (isInBackground) "BACKGROUND" else "FOREGROUND"}")
        Timber.w("📱 Device: $manufacturer (Android $androidVersion - API $androidVersion)")

        // **UNIVERSAL BACKGROUND APPROACH**: Work on ALL Android versions
        val shouldShowBackgroundNotification = isInBackground

        // 1. Always check for PTT audio messages first (both foreground and background)
        val audioUrl = message.data["content_audio_url"]
        if (!audioUrl.isNullOrBlank()) {
            Timber.w("🎤 PTT message detected - playing audio (Android $androidVersion)")
            val intent = Intent(this, AudioPlaybackService::class.java).apply {
                putExtra("audio_url", audioUrl)
            }
            ContextCompat.startForegroundService(this, intent)
        }

        // 2. UNIVERSAL: Show notification for ALL background messages (works on Android 6.0+)
        if (shouldShowBackgroundNotification && audioUrl.isNullOrBlank()) {
            Timber.w("📞 Universal Background - showing call notification for Android $androidVersion")
            showUniversalCallNotification(message.data, androidVersion)
        }

        // 3. Always process normally for Matrix sync
        try {
            pushParser.parsePushDataFcm(message.data).let {
                vectorPushHandler.handle(it)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed normal FCM processing")
        }
    }

    private fun buildFullScreenPendingIntent(roomId: String, callerName: String): PendingIntent {
        val intent = Intent(this, im.vector.app.features.call.VectorCallActivity::class.java).apply {
            action = "im.vector.app.action.SHOW_INCOMING_CALL"
            putExtra("EXTRA_ROOM_ID", roomId)
            putExtra("EXTRA_CALLER_NAME", callerName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, roomId.hashCode(), intent, flags)
    }

    /**
     * Show call notification for ALL FCM pushes (aggressive approach)
     * This ensures calls work even when app is completely killed
     */
    private fun showUniversalCallNotification(data: Map<String, String>, androidVersion: Int) {
        try {
            val eventId = data["event_id"] ?: System.currentTimeMillis().toString()
            val roomId = data["room_id"] ?: "unknown_room"
            val callerName = "Incoming Call"

            Timber.w("📞 UNIVERSAL: Showing call notification for FCM push")
            Timber.w("📞 Data: eventId=$eventId, roomId=$roomId")
            Timber.w("📞 Android Version: $androidVersion (${android.os.Build.VERSION.RELEASE})")

            // Log all FCM data for debugging
            data.forEach { (k, v) ->
                Timber.w("📞 FCM Data: $k = $v")
            }

            // Create high-priority notification directly
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            // **UNIVERSAL COMPATIBILITY**: Choose appropriate channel for ANY Android version
            val channelId = when {
                androidVersion >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> "CALL_CHANNEL_UNIVERSAL_V14" // Android 14+
                androidVersion >= android.os.Build.VERSION_CODES.O -> "CALL_CHANNEL_UNIVERSAL" // Android 8+
                else -> "DEFAULT_NOISY_NOTIFICATION_CHANNEL_ID" // Android 6-7 fallback
            }

            // **UNIVERSAL CHANNEL CREATION**: Only for Android 8+ (Oreo), skip for older versions
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                    !channelId.contains("DEFAULT_NOISY")) {

                val channel = NotificationChannel(
                        channelId,
                        when {
                            channelId.contains("V14") -> "Calls (Enhanced for Android 14+)"
                            else -> "Incoming Calls (Universal)"
                        },
                        NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = when {
                        channelId.contains("V14") -> "Enhanced call notifications for Android 14+"
                        else -> "Universal call notifications for Android 8+"
                    }
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), null)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 1000, 500, 1000)
                    setShowBadge(true)

                    // Enhanced features for newer Android versions
                    if (channelId.contains("V14")) {
                        setBypassDnd(true)
                        enableLights(true)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    }
                }
                notificationManager.createNotificationChannel(channel)
                Timber.w("📞 Created notification channel: $channelId for Android $androidVersion")
            } else {
                Timber.w("📞 Using system default channel for Android $androidVersion")
            }

            // Create notification with correct channel
            Timber.w("📞 Using notification channel: $channelId")

            val pi = buildFullScreenPendingIntent(roomId, callerName)

            val notification = NotificationCompat.Builder(this, channelId)
                    .setContentTitle(callerName)
                    .setContentText("Incoming call")
                    .setSmallIcon(R.drawable.ic_notification)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setAutoCancel(false)
                    .setOngoing(true)
                    .setFullScreenIntent(pi, true)
                    .setContentIntent(pi)

                    .build()

            // Show notification immediately
            notificationManager.notify(eventId.hashCode(), notification)

            // ALSO start call service for ringing
            val callIntent = Intent(this, im.vector.app.core.services.CallAndroidService::class.java).apply {
                action = "im.vector.app.core.services.CallService.ACTION_INCOMING_RINGING_CALL"
                putExtra("EXTRA_CALL_ID", eventId)
                putExtra("EXTRA_IS_IN_BG", true)
                putExtra("EXTRA_ROOM_ID", roomId)
                putExtra("EXTRA_CALLER_NAME", callerName)
            }

            try {
                ContextCompat.startForegroundService(this, callIntent)
                Timber.w("📞 Call service started successfully")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start call service, notification still shown")
            }

            Timber.w("📞 ✅ UNIVERSAL call notification shown successfully for $callerName")
        } catch (e: Exception) {
            Timber.e(e, "❌ CRITICAL: Failed to show universal call notification")

            // Last resort: Simple toast-like notification (universal compatibility)
            try {
                val fallbackChannelId = when {
                    androidVersion >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> "CALL_CHANNEL_UNIVERSAL_V14"
                    androidVersion >= android.os.Build.VERSION_CODES.O -> "CALL_CHANNEL_UNIVERSAL"
                    else -> "DEFAULT_NOISY_NOTIFICATION_CHANNEL_ID"
                }
                val simpleNotification = NotificationCompat.Builder(this, fallbackChannelId)
                        .setContentTitle("Incoming Call")
                        .setContentText("You have an incoming call")
                        .setSmallIcon(R.drawable.ic_notification)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .build()

                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(999, simpleNotification)
                Timber.w("📞 Fallback notification shown")
            } catch (e2: Exception) {
                Timber.e(e2, "Even fallback notification failed")
            }
        }
    }

    /**
     * **UNIVERSAL COMPATIBILITY**: Acquire temporary wake lock for critical FCM processing
     * Works on ALL Android versions (6.0+) with enhanced protection for newer versions
     */
    private fun acquireUniversalWakeLock() {
        try {
            val powerManager = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            val androidVersion = android.os.Build.VERSION.SDK_INT

            val wakeLock = powerManager.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "Element::FCM-Universal-API$androidVersion"
            )

            // Adjust timeout based on Android version
            val timeout = when {
                androidVersion >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> 30_000L // Android 14+: 30 seconds
                androidVersion >= android.os.Build.VERSION_CODES.P -> 25_000L // Android 9+: 25 seconds  
                androidVersion >= android.os.Build.VERSION_CODES.O -> 20_000L // Android 8+: 20 seconds
                else -> 15_000L // Android 6-7: 15 seconds
            }

            wakeLock.acquire(timeout)
            Timber.w("🔧 Universal wake lock acquired for ${timeout / 1000}s on Android $androidVersion")

            // Release after processing with safety margin
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                        Timber.w("🔧 Universal wake lock released on Android $androidVersion")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to release universal wake lock")
                }
            }, timeout - 5_000) // Release 5 seconds before timeout
        } catch (e: Exception) {
            Timber.e(e, "Failed to acquire universal wake lock")
        }
    }

    /**
     * **ANDROID 14 DEBUGGING**: Get comprehensive device state information
     */
    private fun getNetworkInfo(): String {
        return try {
            val connectivityManager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

            when {
                networkCapabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                networkCapabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile"
                networkCapabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                else -> "Unknown/None"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun isBatteryOptimized(): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val powerManager = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                val isIgnored = powerManager.isIgnoringBatteryOptimizations(packageName)
                !isIgnored
            } else {
                false
            }
        } catch (e: Exception) {
            true
        }
    }

    private fun areNotificationsEnabled(): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                val notificationManager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                notificationManager.areNotificationsEnabled()
            } else {
                true // Assume enabled on older versions
            }
        } catch (e: Exception) {
            false // Assume disabled if we can't check
        }
    }

    private fun isDeviceIdle(): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val powerManager = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                powerManager.isDeviceIdleMode
            } else {
                false // No idle mode on Android < 6
            }
        } catch (e: Exception) {
            false // Assume not idle if we can't check
        }
    }

    private fun getFcmTokenStatus(): String {
        return try {
            // Check if FCM is available and initialized
            val fcmInstance = com.google.firebase.messaging.FirebaseMessaging.getInstance()

            "Available (Auto-init: ${fcmInstance.isAutoInitEnabled})"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun getAppStandbyBucket(): String {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val usageStatsManager = getSystemService(android.content.Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
                when (usageStatsManager.appStandbyBucket) {
                    android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "ACTIVE (Best)"
                    android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "WORKING_SET (Good)"
                    android.app.usage.UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "FREQUENT (Limited)"
                    android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE (Very Limited)"
                    android.app.usage.UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "RESTRICTED (Blocked)"
                    else -> "UNKNOWN (${usageStatsManager.appStandbyBucket})"
                }
            } else {
                "Not Available (Android < 9)"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
