/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject

@AndroidEntryPoint
class UdpWakeListenerService : Service() {

    private var isListening = false
    private var listenThread: Thread? = null
    @Inject lateinit var activeSessionHolder: ActiveSessionHolder

    private var currentUserId: String? = null

    companion object {
        private const val CHANNEL_ID = "udp_wake_listener_channel"
        private const val NOTIFICATION_ID = 1001
        private const val UDP_PORT = 9999
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Use getSafeActiveSession() to avoid crash when no session exists
        currentUserId = activeSessionHolder.getSafeActiveSession()?.myUserId
        Timber.d("👤 Current user ID in service: $currentUserId")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("🚀 UdpWakeListenerService started")

        // Always start as foreground service first to avoid ForegroundServiceDidNotStartInTimeException
        try {
            startForeground(NOTIFICATION_ID, createNotification())
            Timber.d("✅ UdpWakeListenerService started as foreground service")
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to start as foreground service, continuing as background service")
            // Continue as background service if foreground fails
        }

        // Check session availability after starting foreground
        if (currentUserId.isNullOrEmpty()) {
            Timber.e("❌ currentUserId is null or empty. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isListening) {
            isListening = true
            listenForWakeSignal()
        }
        return START_STICKY // Ensure service restarts if killed
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                    CHANNEL_ID,
                    "PTT Wake Listener",
                    NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Listens for PTT wake signals in background"
                setShowBadge(false)
                setSound(null, null)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, UdpWakeListenerService::class.java)
        val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("PTT Background Service")
                .setContentText("Listening for PTT calls and messages")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
    }

    private fun listenForWakeSignal() {
        listenThread = Thread {
            try {
                val socket = DatagramSocket(UDP_PORT)
                val buffer = ByteArray(1024)
                Timber.d("🎧 UDP Wake Listener started on port $UDP_PORT")

                while (isListening) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val message = String(packet.data, 0, packet.length)

                        Timber.d("📨 Wake signal received: $message")

                        if (message.startsWith("start_ptt:")) {
                            val parts = message.removePrefix("start_ptt:").split(":", limit = 3)
                            if (parts.size < 2) {
                                Timber.w("⚠️ Invalid wake message format: $message")
                                continue
                            }

                            val roomId = URLDecoder.decode(parts[0], "UTF-8")
                            val senderUserId = URLDecoder.decode(parts[1], "UTF-8")

                            Timber.d("👤 Current user ID in service: $currentUserId")
                            if (senderUserId == currentUserId) {
                                Timber.d("🔕 Ignoring wake signal from self: $senderUserId")
                                continue
                            }

                            Timber.d("📡 Valid wake signal for roomId=$roomId from userId=$senderUserId")

                            // Start PTT receiver service
                            val receiverIntent = Intent(this, PttTcpReceiverService::class.java).apply {
                                putExtra("roomId", roomId)
                                putExtra("myUserId", currentUserId)
                                putExtra("senderIp", packet.address.hostAddress)
                                putExtra("speakerId", senderUserId)
                            }

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(receiverIntent)
                            } else {
                                startService(receiverIntent)
                            }

                            Timber.d("🚀 PTT Receiver service started for room: $roomId")
                        }
                    } catch (e: Exception) {
                        if (isListening) {
                            Timber.e(e, "❌ Error receiving UDP packet")
                        }
                    }
                }
                socket.close()
                Timber.d("🎧 UDP Wake Listener stopped")
            } catch (e: Exception) {
                Timber.e(e, "❌ Failed to start UDP listener")
            }
        }
        listenThread?.start()
    }

    override fun onDestroy() {
        Timber.d("🛑 UdpWakeListenerService destroyed")
        isListening = false
        listenThread?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
