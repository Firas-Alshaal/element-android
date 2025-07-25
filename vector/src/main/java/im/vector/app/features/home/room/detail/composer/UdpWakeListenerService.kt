/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

//import android.app.Service
//import android.content.Intent
//import android.os.Build
//import android.os.IBinder
//import dagger.hilt.android.AndroidEntryPoint
//import im.vector.app.core.di.ActiveSessionHolder
//import timber.log.Timber
//import java.net.DatagramPacket
//import java.net.DatagramSocket
//import java.net.URLDecoder
//import java.net.URLEncoder
//import javax.inject.Inject
//
//@AndroidEntryPoint
//class UdpWakeListenerService : Service() {
//
//    private var isListening = false
//    private var listenThread: Thread? = null
//    @Inject lateinit var activeSessionHolder: ActiveSessionHolder
//
//    private var currentUserId: String? = null
//
//    override fun onCreate() {
//        super.onCreate()
//        currentUserId = activeSessionHolder.getActiveSession().myUserId
//        Timber.d("👤 Current user ID in service: $currentUserId")
//    }
//
//    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
//        Timber.d("🚀 UdpWakeListenerService started")
//        if (currentUserId.isNullOrEmpty()) {
//            Timber.e("❌ currentUserId is null or empty. Stopping service.")
//            stopSelf()
//            return START_NOT_STICKY
//        }
//
//
//        if (!isListening) {
//            isListening = true
//            listenForWakeSignal()
//        }
//        return START_STICKY
//    }
//
//    private fun listenForWakeSignal() {
//        listenThread = Thread {
//            try {
//                val socket = DatagramSocket(9999)
//                val buffer = ByteArray(1024)
//                while (isListening) {
//                    val packet = DatagramPacket(buffer, buffer.size)
//                    socket.receive(packet)
//                    val message = String(packet.data, 0, packet.length)
//
//                    Timber.d("📨 Wake signal received: $message")
//                    if (message.startsWith("start_ptt:")) {
//                        val parts = message.removePrefix("start_ptt:").split(":", limit = 3)
//                        if (parts.size < 2) {
//                            Timber.w("⚠️ Invalid wake message format: $message")
//                            continue
//                        }
//
//                        val roomId = URLDecoder.decode(parts[0], "UTF-8")
//                        val senderUserId = URLDecoder.decode(parts[1], "UTF-8")
//
//                        Timber.d("👤 Current user ID in service: $currentUserId")
//                        if (senderUserId == currentUserId) {
//                            Timber.d("🔕 Ignoring wake signal from self: $senderUserId")
//                            continue
//                        }
//
//
//
//                        Timber.d("📡 Valid wake signal for roomId=$roomId from userId=$senderUserId")
//
//                        val socketUrl = "ws://10.100.10.162:8686?roomId=${URLEncoder.encode(roomId, "UTF-8")}"
//
//                        val intent = Intent(this, PttReceiverSocketService::class.java).apply {
//                            putExtra("socket_url", socketUrl)
//                            putExtra("sender_user_id", senderUserId)
//                            putExtra("current_user_id", currentUserId)
//                        }
//                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                            startForegroundService(intent) // API 26+
//                        } else {
//                            startService(intent) // API 21+
//                        }
//                    }
//                }
//                socket.close()
//            } catch (e: Exception) {
//                Timber.e(e, "❌ Failed to listen for UDP signal")
//            }
//        }
//        listenThread?.start()
//    }
//
//    override fun onDestroy() {
//        isListening = false
//        listenThread?.interrupt()
//        super.onDestroy()
//    }
//
//    override fun onBind(intent: Intent?): IBinder? = null
//}
