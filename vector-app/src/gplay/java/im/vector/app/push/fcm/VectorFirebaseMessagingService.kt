/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.push.fcm


import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
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

        Timber.d("🔥 message.notification = ${message.notification}")
        Timber.d("🔥 message.data = ${message.data}")

        message.data.forEach { (k, v) ->
            Timber.d("⧗ FCM payload key=\"$k\"  value=\"$v\"")
        }

        Timber.tag(loggerTag.value).d("New Firebase message: ${message.data}")
        Timber.d("✔ FCM data payload: ${message.data}")


        // Handle normal Matrix push messages
        try {
            pushParser.parsePushDataFcm(message.data).let {
                vectorPushHandler.handle(it)
            }
        } catch (failure: Throwable) {
            Timber.e(failure, "Failed to handle incoming FCM message")
        }


        val audioUrl = message.data["content_audio_url"]
        if (!audioUrl.isNullOrBlank()) {
//            playAudio(audioUrl)
            Timber.d("🔊 Received PTT audio URL: $audioUrl")
            val intent = Intent(this, AudioPlaybackService::class.java).apply {
                putExtra("audio_url", audioUrl)
            }
            ContextCompat.startForegroundService(this, intent)
            return
        }

        val body = message.notification?.body ?: message.data["body"] ?: "New message"
        val builder = NotificationCompat.Builder(this, "DEFAULT_NOISY_NOTIFICATION_CHANNEL_ID")
                .setSmallIcon(im.vector.app.R.drawable.ic_notification)
                .setContentTitle("New Message")
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            with(NotificationManagerCompat.from(this)) {
                notify(System.currentTimeMillis().toInt(), builder.build())
            }
        } else {
            Timber.w("No POST_NOTIFICATIONS permission granted. Notification not shown.")
        }


    }
}
