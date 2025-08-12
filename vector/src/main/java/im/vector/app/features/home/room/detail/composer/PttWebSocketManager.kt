/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import timber.log.Timber
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket

class PttManager(
        private val context: Context,
        private val session: Session,
) {
    private var tcpSender: PttTcpSender? = null
    
    // Add callback for timeout notification
    private var onTimeoutCallback: (() -> Unit)? = null
    
    companion object {
        // Global timeout callback for UI notifications
        private var globalTimeoutCallback: ((roomId: String) -> Unit)? = null
        
        fun setGlobalTimeoutCallback(callback: (roomId: String) -> Unit) {
            globalTimeoutCallback = callback
        }
        
        fun clearGlobalTimeoutCallback() {
            globalTimeoutCallback = null
        }
    }
    
    fun setOnTimeoutCallback(callback: () -> Unit) {
        onTimeoutCallback = callback
    }
    
    fun clearTimeoutCallback() {
        onTimeoutCallback = null
    }

    fun startStreamingCoordinated(roomId: String) {

        if (tcpSender != null) {
            Timber.w("PTT already active; ignoring start.")
            return
        }
        Timber.d("🚀 Starting robust PTT coordination for room: $roomId")

        val room = session.getRoom(roomId)
        if (room == null) {
            Timber.e("❌ Room $roomId not found - cannot start PTT")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 🎯 1. تحديد أولوية الغرفة من topic
                /*val roomSummary = room.roomSummary()
                val roomTopic = roomSummary?.topic
                val roomPriority = PttMatrixSyncHandler.extractRoomPriority(roomTopic)
                PttMatrixSyncHandler.updateRoomPriority(roomId, roomPriority)

                Timber.d("🎯 Room priority: $roomId → $roomPriority (topic: '$roomTopic')")

                // 🚨 2. طلب التحدث مع مراعاة الأولويات والصلاحيات الإدارية
                var floorGranted = PttMatrixSyncHandler.requestSpeakingFloorWithPriority(roomId, session.myUserId)

                if (!floorGranted) {
                    // 🛡️ المحاولة الثانية: فحص التجاوز الإداري داخل نفس الغرفة
                    val adminOverride = PttMatrixSyncHandler.requestAdminOverride(room, session.myUserId)
                    if (adminOverride) {
                        floorGranted = true
                        Timber.d("✅ PTT granted via admin override")
                    } else {
                        Timber.w("🚫 PTT denied - no priority override and not an admin")
                        return@launch
                    }
                }*/

                // 3. إدارة IP مركزية وذكية
                val success = PttCoordinator.ensureIpAvailable(room, session.myUserId, getLocalIpAddress())
                if (!success) {
                    Timber.e("❌ Failed to ensure IP availability - aborting PTT")
                    return@launch
                }

                // 2. إرسال إشارة البدء مع تأكيد
                val statusSuccess = PttCoordinator.sendPttStatus(room, session.myUserId, "talking")
                if (!statusSuccess) {
                    Timber.e("❌ Failed to send PTT status - aborting")
                    return@launch
                }

                // 3. بدء TCP Server بعد ضمان Matrix coordination
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    tcpSender = PttTcpSender(
                        context = context, 
                        roomId = roomId, 
                        onTimeoutCallback = onTimeoutCallback,
                        globalTimeoutCallback = globalTimeoutCallback
                    )
                    tcpSender?.startServer()
                    Timber.d("✅ PTT coordination completed successfully")
                }
            } catch (e: Exception) {
                Timber.e(e, "💥 Critical error in PTT coordination")
            }
        }
    }

    fun stopStreamingCoordinated(roomId: String) {
        Timber.d("🛑 Stopping PTT coordination for room: $roomId")

        val room = session.getRoom(roomId)
        if (room == null) {
            Timber.w("⚠️ Room $roomId not found during stop - cleaning up locally")
            tcpSender?.stopSending()
            tcpSender = null
            // ✅ Clear timeout callback when stopping
            clearTimeoutCallback()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. إيقاف TCP Server فوراً
                tcpSender?.stopSending()
                tcpSender = null

                // 2. إرسال إشارة الإيقاف مع ضمان الوصول
                val success = PttCoordinator.sendPttStatus(room, session.myUserId, "idle")
                if (success) {
                    Timber.d("✅ PTT stop coordination completed successfully")
                } else {
                    Timber.w("⚠️ PTT stopped locally but Matrix notification may have failed")
                }
                
                // ✅ Clear timeout callback when stopping
                clearTimeoutCallback()
            } catch (e: Exception) {
                Timber.e(e, "💥 Error during PTT stop coordination")
                // حتى لو فشل Matrix notification، نضمن تنظيف الموارد المحلية
                clearTimeoutCallback()
            }
        }
    }

    fun stopStreaming() {
        tcpSender?.stopSending()
        tcpSender = null
        // ✅ Clear timeout callback when stopping
        clearTimeoutCallback()
    }

    private fun getLocalIpAddress(): String {
        return PttCoordinator.getLocalIpAddress()
    }
}

class PttTcpSender(
        private val context: Context, 
        private val roomId: String,
        private val onTimeoutCallback: (() -> Unit)? = null,
        private val globalTimeoutCallback: ((roomId: String) -> Unit)? = null
) {
    private val serverPort: Int = 8008
    private var audioRecord: AudioRecord? = null
    private var isSending = false
    private var socket: ServerSocket? = null
    private val clientSockets = mutableListOf<Socket>()
    private val clientStreams = mutableMapOf<Socket, BufferedOutputStream>() // New!
    private val scope = CoroutineScope(Dispatchers.IO)

    // ⏱️ إعدادات الحد الأقصى للوقت - قابلة للتعديل
    companion object {
        const val MAX_RECORDING_TIME_SECONDS = 30 // 30 ثانية حد أقصى
        const val WARNING_TIME_SECONDS = 5 // تحذير قبل 5 ثواني من النهاية
    }

    fun startServer() {
        isSending = true

        // بدء السيرفر TCP
        scope.launch {
            try {
                socket = ServerSocket(serverPort)
                Timber.d("TCP Server started on port $serverPort")

                while (isSending) {
                    try {
                        Timber.d("🔌 Waiting for client connections...")
                        val client = socket!!.accept()
                        Timber.d("✅ Client connected: ${client.inetAddress.hostAddress}:${client.port}")
                        synchronized(clientSockets) {
                            clientSockets.add(client)
                            clientStreams[client] = BufferedOutputStream(client.getOutputStream()) // Create stream once
                        }
                        Timber.d("📊 Total connected clients: ${clientSockets.size}")
                    } catch (e: Exception) {
                        Timber.e("❌ Accept error: ${e.message}")
                        break
                    }
                }
            } catch (e: Exception) {
                Timber.e("Server error: ${e.message}")
            }
        }

        // بدء التسجيل المحسن للوضوح
        scope.launch {
            try {
                val sampleRate = 16000
                val bufferSize = 2048 // ثابت ومجرب للوضوح

                val buffer = ByteArray(bufferSize)


                audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Timber.e("❌ AudioRecord initialization failed")
                    return@launch
                }

                audioRecord?.startRecording()
                val roomToken = "${roomId.hashCode()}:"
                Timber.d("🎤 Audio recording started for room: $roomId (token: $roomToken)")

                // ⏱️ مؤقت الحد الأقصى للرسائل الصوتية
                val maxRecordingTimeMs = MAX_RECORDING_TIME_SECONDS * 1000L
                val recordingStartTime = System.currentTimeMillis()

                var packetCount = 0
                var totalBytesSent = 0

                while (isSending && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    // ⏱️ فحص الحد الأقصى للوقت
                    val currentTime = System.currentTimeMillis()
                    val recordingDuration = currentTime - recordingStartTime

                    if (recordingDuration >= maxRecordingTimeMs) {
                        Timber.d("⏰ Recording time limit reached: ${recordingDuration}ms, stopping...")
                        isSending = false   // ensure other loops stop too
                        try {
                            socket?.close()
                        } catch (_: Exception) {
                        }
                        
                        // ✅ Notify UI about timeout
                        onTimeoutCallback?.invoke()
                        
                        // ✅ Notify global timeout callback
                        globalTimeoutCallback?.invoke(roomId)
                        
                        break
                    }

                    // تحذير عند اقتراب انتهاء الوقت
                    val warningTimeMs = maxRecordingTimeMs - (WARNING_TIME_SECONDS * 1000L)
                    if (recordingDuration >= warningTimeMs && recordingDuration < maxRecordingTimeMs && packetCount % 10 == 0) {
                        val remainingSeconds = (maxRecordingTimeMs - recordingDuration) / 1000
                        Timber.d("⚠️ Recording time warning: ${remainingSeconds}s remaining")
                    }

                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        packetCount++
                        synchronized(clientSockets) {
                            clientSockets.removeAll { it.isClosed }
                            clientSockets.forEach { client ->

                                try {
                                    val stream = clientStreams[client]
                                    if (stream != null) {
                                        // ✅ إضافة room token قبل الصوت
                                        val tokenBytes = roomToken.toByteArray()
                                        val combinedData = ByteArray(tokenBytes.size + read)
                                        System.arraycopy(tokenBytes, 0, combinedData, 0, tokenBytes.size)
                                        System.arraycopy(buffer, 0, combinedData, tokenBytes.size, read)

                                        stream.write(combinedData)
                                        stream.flush() // ✅ Flush كل packet للتأكد من الوصول الفوري
                                        totalBytesSent += combinedData.size

                                        if (packetCount <= 3) { // أول 3 packets فقط للتقليل من logs
                                            Timber.d("📤 Packet #$packetCount sent: token='$roomToken' + $read audio bytes = ${combinedData.size} total bytes")
                                        }

                                        // Log
                                        if (packetCount % 5 == 1) {
                                            Timber.d("📤 Sent packet #$packetCount: ${read} audio bytes to ${client.inetAddress.hostAddress} (total: ${totalBytesSent} bytes)")
                                        }
                                    }
                                } catch (e: Exception) {
                                    Timber.w("❌ Send error to ${client.inetAddress.hostAddress}: ${e.message}")
                                    synchronized(clientSockets) {
                                        clientSockets.remove(client)
                                        clientStreams.remove(client)
                                        try {
                                            client.close()
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                            }
                        }
                    } else if (read == 0) {
                        Timber.d("📭 No audio data to send")
                    } else {
                        Timber.w("❌ AudioRecord read error: $read")
                        break
                    }
                }

                // 📊 إحصائيات نهاية التسجيل
                val finalDuration = System.currentTimeMillis() - recordingStartTime
                Timber.d("🏁 Recording ended: ${finalDuration}ms duration, $packetCount packets sent, ${totalBytesSent} total bytes")
            } catch (e: Exception) {
                Timber.e("Audio error: ${e.message}")
            } finally {
                // Defensive cleanup (idempotent with stopSending)
                audioRecord?.run {
                    try {
                        stop()
                    } catch (_: Exception) {
                    }
                    try {
                        release()
                    } catch (_: Exception) {
                    }
                }
                audioRecord = null
                synchronized(clientSockets) {
                    clientSockets.forEach {
                        runCatching {
                            clientStreams[it]?.close()
                            it.close()
                        }
                    }
                    clientSockets.clear()
                    clientStreams.clear()
                }
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
                socket = null
            }
        }
    }

    fun stopSending() {
        isSending = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null

        // Mic cleanup (guarded)
        audioRecord?.let {
            try {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        audioRecord = null

        synchronized(clientSockets) {
            clientSockets.forEach {
                runCatching {
                    clientStreams[it]?.close()
                    it.close()
                }
            }
            clientSockets.clear()
            clientStreams.clear()
        }

        try {
            scope.cancel()
        } catch (_: Exception) {
        }

        Timber.d("TCP Sender stopped")
    }
}
