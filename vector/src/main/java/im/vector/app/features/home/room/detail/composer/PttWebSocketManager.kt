/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

// ❌ كود UDP قديم معطل - النظام يستخدم TCP فقط الآن
/*
class PttManager(private val context: Context, private val session: Session? = null) {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var job: Job? = null
    private var recordingStartTime = 0L // ⭐ إضافة متتبع الوقت

    private val sampleRate = 16000
    private val scope = CoroutineScope(Dispatchers.IO)
    val packetInterval = 30L

    val frameSize = (sampleRate * 0.02).toInt() * 2 // 20ms PCM16 mono = 640 bytes
    val bufferSize = frameSize * 2 // لتغطية 40ms لكل إرسال

    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private val minimumRecordingDuration = 800L // ⭐ حد أدنى 800ms

    companion object {
        @Volatile
        var isCurrentlySending = false
            private set

        fun isSending(): Boolean = isCurrentlySending
    }

    private fun createAudioRecord(): AudioRecord? {
        return try {
            val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
            )

            if (audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                try {
                    val sessionId = audioRecord.audioSessionId
                    if (NoiseSuppressor.isAvailable()) {
                        NoiseSuppressor.create(sessionId)?.enabled = true
                    }
                    if (AcousticEchoCanceler.isAvailable()) {
                        AcousticEchoCanceler.create(sessionId)?.enabled = true
                    }
                    if (AutomaticGainControl.isAvailable()) {
                        AutomaticGainControl.create(sessionId)?.enabled = true
                    }
                } catch (e: Exception) {
                    Timber.w("Audio effects failed: ${e.message}")
                }
                audioRecord
            } else null
        } catch (e: Exception) {
            Timber.e(e, "AudioRecord creation failed")
            null
        }
    }

    private fun enhanceAudioBeforeSending(audioData: ByteArray): ByteArray {
        if (audioData.size < 4) return audioData

        val cleaned = audioData.copyOf()
        for (i in 0 until audioData.size - 1 step 2) {
            val sample = ((audioData[i + 1].toInt() shl 8) or (audioData[i].toInt() and 0xFF)).toShort()
            if (kotlin.math.abs(sample.toInt()) < 20) {
                val reduced = (sample / 2).toShort()
                cleaned[i] = (reduced.toInt() and 0xFF).toByte()
                cleaned[i + 1] = ((reduced.toInt() shr 8) and 0xFF).toByte()
            }
        }
        return cleaned
    }

    suspend fun startStreamingCoordinated(roomId: String): Boolean {
        if (isRecording) return false

        val myUserId = session?.myUserId ?: return false

        if (!PttMatrixSyncHandler.requestSpeakingFloor(roomId, myUserId)) {
            return false
        }

        scope.launch {
            try {
                val room = session.getRoom(roomId)
                room?.stateService()?.sendStateEvent(
                        "ptt.status", myUserId, mapOf(
                        "status" to "talking",
                        "userId" to myUserId
                )
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to send ptt.status")
            }
        }

        return startStreaming(roomId)
    }

    fun startStreaming(roomId: String): Boolean {
        if (isRecording) return false

        isCurrentlySending = true
        recordingStartTime = System.currentTimeMillis() // ⭐ حفظ وقت البداية

        Timber.d("🚀 PTT STARTING: recordingStartTime=$recordingStartTime")

        try {
            val stopIntent = Intent(context, PttReceiverService::class.java).apply {
                putExtra("roomId", roomId)
            }
            context.stopService(stopIntent)
        } catch (e: Exception) {
            Timber.w(e, "Failed to stop receiver service")
        }

        scope.launch {
            try {
                val groupAddress = InetAddress.getByName("255.255.255.255") // ⭐ إعادة broadcast للشبكة المحلية
                val port = 8008 // ✅ Fixed port for all PTT communication

                audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setOnAudioFocusChangeListener {
                                Timber.e("🎧 AUDIO FOCUS CHANGED: $it")
                                if (it == AudioManager.AUDIOFOCUS_LOSS) stopStreaming()
                            }
                            .build()
                    audioManager.requestAudioFocus(focusRequest!!)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.requestAudioFocus(
                            { if (it == AudioManager.AUDIOFOCUS_LOSS) stopStreaming() },
                            AudioManager.STREAM_VOICE_CALL,
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                    )
                }

                if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    Timber.w("Audio focus request denied")
                    isCurrentlySending = false
                    return@launch
                }

                audioRecord = createAudioRecord()
                if (audioRecord == null) {
                    isCurrentlySending = false
                    return@launch
                }

                val socket = DatagramSocket(null)
                val socketAddress = InetSocketAddress(0)
                socket.reuseAddress = true
                socket.bind(socketAddress)
                socket.broadcast = true
//                socket.soTimeout = 8000

                delay(300)

                try {
                    val warmupData = "WARMUP".toByteArray()
                    socket.send(DatagramPacket(warmupData, warmupData.size, groupAddress, port))
                    delay(100)
                } catch (e: Exception) {
                    Timber.w(e, "Warmup failed")
                }

                delay(100)

                val buffer = ByteArray(bufferSize)
                isRecording = true

                Timber.d("📹 RECORDING STARTED: bufferSize=$bufferSize, packetInterval=${packetInterval}ms")

                audioRecord?.let { record ->
                    record.startRecording()
                }

                job = launch {
                    try {
                        val localAudioRecord = audioRecord
                        if (localAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {

                            var sequenceNumber = 0
                            var packetsSent = 0 // ⭐ عداد الpackets المرسلة
                            var voicePackets = 0 // ⭐ عداد packets التي تحتوي على صوت
                            var totalLoops = 0 // ⭐ عداد التكرارات الكلية

                            while (localAudioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING && isRecording) {
                                totalLoops++
                                val bytesRead = localAudioRecord.read(buffer, 0, buffer.size)
                                Timber.d("🎙️ bytesRead=$bytesRead, recordingState=${localAudioRecord.recordingState}")

                                if (localAudioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                                    Timber.e("❌ AudioRecord stopped unexpectedly at loop #$totalLoops — trying to restart")
                                    try {
                                        localAudioRecord.stop()
                                        delay(100)
                                        localAudioRecord.startRecording()
                                        Timber.w("✅ AudioRecord restarted successfully")
                                        continue
                                    } catch (e: Exception) {
                                        Timber.e("‼️ Failed to restart AudioRecord: ${e.message}")
                                        Timber.i("♻️ Re-initializing AudioRecord...")

                                        try {
                                            audioRecord?.release()
                                            audioRecord = createAudioRecord()
                                            if (audioRecord != null) {
                                                audioRecord!!.startRecording()
                                                Timber.w("🔄 AudioRecord fully re-initialized successfully")
                                                continue
                                            } else {
                                                Timber.e("💥 AudioRecord re-initialization failed completely")
                                            }
                                        } catch (ex: Exception) {
                                            Timber.e("💥 Error during reinitialization: ${ex.message}")
                                        }

                                        break
                                    }
                                }

                                if (bytesRead > 0 && isRecording) {
                                    if (localAudioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                                        Timber.w("⚠️ AudioRecord unexpectedly stopped recording!")
                                    }
                                    val audioDataToSend = buffer.sliceArray(0 until bytesRead)

                                    var hasVoiceContent = false
                                    var maxAmplitude = 0

                                    for (i in 0 until audioDataToSend.size - 1 step 2) {
                                        val sample = ((audioDataToSend[i + 1].toInt() shl 8) or (audioDataToSend[i].toInt() and 0xFF)).toShort()
                                        val amplitude = kotlin.math.abs(sample.toInt())
                                        if (amplitude > maxAmplitude) maxAmplitude = amplitude
                                        if (amplitude > 40) { // ⭐ خفض من 110 إلى 80 لحساسية أفضل
                                            hasVoiceContent = true
                                        }
                                    }

                                    if (totalLoops % 10 == 0) { // كل 10 iterations
                                        Timber.d("🎤 RECORDING LOOP #$totalLoops: bytesRead=$bytesRead, maxAmplitude=$maxAmplitude, hasVoice=$hasVoiceContent")
                                    }

                                    voicePackets++
                                    val enhancedAudio = enhanceAudioBeforeSending(audioDataToSend)
                                    val packetData = ByteArray(4 + enhancedAudio.size)
                                    ByteBuffer.wrap(packetData).putInt(sequenceNumber)
                                    System.arraycopy(enhancedAudio, 0, packetData, 4, enhancedAudio.size)

                                    try {
                                        socket.send(DatagramPacket(packetData, packetData.size, groupAddress, port))
                                        packetsSent++ // ⭐ عداد الpackets

                                        if (packetsSent % 5 == 0) { // كل 5 packets
                                            Timber.d("📤 PACKET SENT #$packetsSent: seq=$sequenceNumber, size=${packetData.size} bytes")
                                        }
                                    } catch (e: Exception) {
                                        Timber.w("Transmission failed: ${e.message}")
                                    }

                                    sequenceNumber++
                                }

                                delay(packetInterval)
                            }

                            Timber.d("🏁 RECORDING FINISHED: totalLoops=$totalLoops, voicePackets=$voicePackets, packetsSent=$packetsSent") // ⭐ لوج الإحصائيات النهائية
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error while sending audio")
                    } finally {
                        try {
                            socket.close()
                        } catch (e: Exception) {
                            Timber.w("Error closing socket: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to start audio streaming")
                isCurrentlySending = false
                return@launch
            }
        }

        return true
    }

    suspend fun stopStreamingCoordinated(roomId: String) {
        val myUserId = session?.myUserId ?: return

        stopStreaming()
        PttMatrixSyncHandler.releaseSpeakingFloor(roomId, myUserId)

        try {
            val room = session.getRoom(roomId)
            room?.stateService()?.sendStateEvent(
                    "ptt.status", myUserId, mapOf(
                    "status" to "idle",
                    "userId" to myUserId
            )
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to send idle status")
        }
    }

    fun stopStreaming() {
        Timber.w("🛑 stopStreaming called while isRecording=$isRecording")

        val recordingDuration = System.currentTimeMillis() - recordingStartTime

        // ⭐ إذا كان التسجيل أقل من الحد الأدنى، انتظر قليلاً
        if (recordingDuration < minimumRecordingDuration && isRecording) {
            Timber.d("Recording too short (${recordingDuration}ms), extending to minimum duration")
            scope.launch {
                delay(minimumRecordingDuration - recordingDuration)
                performActualStop()
            }
        } else {
            performActualStop()
        }
    }

    private fun performActualStop() { // ⭐ دالة الإيقاف الفعلية
        isRecording = false

        // ⭐ انتظار قصير للـ job لتنتهي بشكل طبيعي
        scope.launch {
            delay(100) // انتظار 100ms للإنهاء الطبيعي
            job?.cancel()
            completeStopProcess()
        }
    }

    private fun completeStopProcess() { // ⭐ إنهاء العملية كاملة
        isCurrentlySending = false

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Timber.w("Error abandoning audio focus: ${e.message}")
        }

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Timber.w("Error stopping AudioRecord: ${e.message}")
        }

        audioRecord = null
        focusRequest = null
        job = null
    }
}
*/

class PttManager(
        private val context: Context,
        private val session: Session,
) {
    private var tcpSender: PttTcpSender? = null

    fun startStreamingCoordinated(roomId: String) {
        Timber.d("🚀 Starting robust PTT coordination for room: $roomId")

        val room = session.getRoom(roomId)
        if (room == null) {
            Timber.e("❌ Room $roomId not found - cannot start PTT")
            return
        }

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                // 1. إدارة IP مركزية وذكية
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
                    tcpSender = PttTcpSender(context = context, roomId = roomId)
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
            return
        }

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
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
            } catch (e: Exception) {
                Timber.e(e, "💥 Error during PTT stop coordination")
                // حتى لو فشل Matrix notification، نضمن تنظيف الموارد المحلية
            }
        }
    }

    fun stopStreaming() {
        tcpSender?.stopSending()
        tcpSender = null
    }

    private fun getLocalIpAddress(): String {
        return PttCoordinator.getLocalIpAddress()
    }
}

class PttTcpSender(
        private val context: Context,
        private val roomId: String
) {
    private val serverPort: Int = 8008
    private var audioRecord: AudioRecord? = null
    private var isSending = false
    private var socket: ServerSocket? = null
    private val clientSockets = mutableListOf<Socket>()
    private val clientStreams = mutableMapOf<Socket, BufferedOutputStream>() // New!
    private val scope = CoroutineScope(Dispatchers.IO)

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
                // 🎵 Audio Settings المحسنة للوضوح الأمثل
                val sampleRate = 16000
                val bufferSize = 2048 // ثابت ومجرب للوضوح

                val buffer = ByteArray(bufferSize)

//                val bufferSize = AudioRecord.getMinBufferSize(
//                    sampleRate,
//                    AudioFormat.CHANNEL_IN_MONO,
//                    AudioFormat.ENCODING_PCM_16BIT
//                ).coerceAtLeast(audioBufferSize)

                audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                )
                
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Timber.e("❌ AudioRecord initialization failed")
                    return@launch
                }

                audioRecord?.startRecording()
                val roomToken = "${roomId.hashCode()}:"
                Timber.d("🎤 Audio recording started for room: $roomId (token: $roomToken)")

                var packetCount = 0
                var totalBytesSent = 0

                while (isSending && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
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
                                        } catch (_: Exception) {}
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
            } catch (e: Exception) {
                Timber.e("Audio error: ${e.message}")
            }
        }
    }

    fun stopSending() {
        isSending = false
        audioRecord?.stop()
        audioRecord?.release()
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

        socket?.close()
        socket = null
        Timber.d("TCP Sender stopped")
    }
}
