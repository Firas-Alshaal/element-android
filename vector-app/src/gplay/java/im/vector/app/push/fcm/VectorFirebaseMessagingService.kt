/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.push.fcm


import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
        Timber.tag(loggerTag.value).d("New Firebase token")
        Timber.tag(loggerTag.value).d("New Firebase token: $token")
        fcmHelper.storeFcmToken(token)
        if (
                vectorPreferences.areNotificationEnabledForDevice() &&
                activeSessionHolder.hasActiveSession() &&
                unifiedPushHelper.isEmbeddedDistributor()
        ) {
            scope.launch {
                pushersManager.enqueueRegisterPusher(
                        pushKey = token,
                        gateway = mdmService.getData(
                                mdmData = MdmData.DefaultPushGatewayUrl,
                                defaultValue = getString(im.vector.app.config.R.string.pusher_http_url),
                        ),
                )
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {

        Timber.d("🔥 onMessageReceived triggered")
        
        // Start keep-alive service to prevent app killing
        try {
            val keepAliveIntent = Intent(this, im.vector.app.core.services.CallKeepAliveService::class.java)
            ContextCompat.startForegroundService(this, keepAliveIntent)
            Timber.w("📱 Keep-alive service started from FCM")
        } catch (e: Exception) {
            Timber.e(e, "❌ CRITICAL: Failed to start keep-alive service from FCM")
        }

        Timber.d("🔥 message.notification = ${message.notification}")
        Timber.d("🔥 message.data = ${message.data}")

        message.data.forEach { (k, v) ->
            Timber.d("⧗ FCM payload key=\"$k\"  value=\"$v\"")
        }

        Timber.tag(loggerTag.value).d("New Firebase message: ${message.data}")
        Timber.d("✔ FCM data payload: ${message.data}")

        // **FIXED SOLUTION**: Handle PTT AND calls properly
        
        val isInBackground = !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        Timber.w("📱 FCM received in ${if (isInBackground) "BACKGROUND" else "FOREGROUND"}")
        
        // 1. Always check for PTT audio messages first (both foreground and background)
        val audioUrl = message.data["content_audio_url"]
        if (!audioUrl.isNullOrBlank()) {
            Timber.w("🎤 PTT message detected - playing audio")
            val intent = Intent(this, AudioPlaybackService::class.java).apply {
                putExtra("audio_url", audioUrl)
            }
            ContextCompat.startForegroundService(this, intent)
        }
        
        // 2. If in background, ALWAYS show call notification (for all non-PTT messages)
        if (isInBackground && audioUrl.isNullOrBlank()) {
            Timber.w("📞 Background FCM - showing call notification")
            showUniversalCallNotification(message.data)
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

    /**
     * Show call notification for ALL FCM pushes (aggressive approach)
     * This ensures calls work even when app is completely killed
     */
    private fun showUniversalCallNotification(data: Map<String, String>) {
        try {
            val eventId = data["event_id"] ?: System.currentTimeMillis().toString()
            val roomId = data["room_id"] ?: "unknown_room"
            val callerName = "Incoming Call"
            
            Timber.w("📞 UNIVERSAL: Showing call notification for FCM push")
            Timber.w("📞 Data: eventId=$eventId, roomId=$roomId")
            
            // Log all FCM data for debugging
            data.forEach { (k, v) ->
                Timber.w("📞 FCM Data: $k = $v")
            }
            
            // Create high-priority notification directly
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            
            // Create notification channel for calls
            val channel = NotificationChannel(
                "CALL_CHANNEL_UNIVERSAL",
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming calls"
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
            
            // Create notification
            val notification = NotificationCompat.Builder(this, "CALL_CHANNEL_UNIVERSAL")
                .setContentTitle(callerName)
                .setContentText("Incoming call")
                .setSmallIcon(R.drawable.ic_call_answer)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(false)
                .setOngoing(true)
                .setFullScreenIntent(null, true)
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
            
            // Last resort: Simple toast-like notification
            try {
                val simpleNotification = NotificationCompat.Builder(this, "DEFAULT_NOISY_NOTIFICATION_CHANNEL_ID")
                    .setContentTitle("Incoming Call")
                    .setContentText("You have an incoming call")
                    .setSmallIcon(R.drawable.ic_call_answer)
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
}
