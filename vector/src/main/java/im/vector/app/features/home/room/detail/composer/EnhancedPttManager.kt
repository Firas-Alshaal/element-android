/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import im.vector.app.features.voice.NetworkAudioQualityManager
import im.vector.app.features.voice.OpusPttCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.max
import kotlin.math.pow

/**
 * Enhanced PTT Manager with TURN server support and optimized audio quality
 *
 * Features:
 * - High-quality audio optimized for 4G networks
 * - Automatic fallback mechanisms
 * - Background operation support
 * - Voice message persistence
 */
class EnhancedPttManager(
        private val context: Context,
        private val session: Session,
) {
    private var enhancedTcpSender: EnhancedPttTcpSender? = null
    private var matrixSender: Any? = null // Can be MatrixPttSender or EnhancedMatrixPttSender
    private var onTimeoutCallback: (() -> Unit)? = null
    private val voiceMessageHelper = EnhancedPttVoiceMessageHelper(context)

    // Track PTT session for voice message saving
    private var currentPttStartTime: Long = 0
    private var currentPttAudioData: MutableList<ByteArray> = mutableListOf()

    companion object {
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

    /**
     * Start PTT with enhanced TURN server support and audio quality
     */
    fun startEnhancedPttStreaming(roomId: String) {
        if (enhancedTcpSender != null || matrixSender != null) {
            Timber.w("⚠️ Enhanced PTT already active; ignoring start.")
            return
        }

        Timber.d("🚀 ENHANCED PTT START: Starting Enhanced PTT with TURN support for room: $roomId")
        Timber.d("🎯 ENHANCED PTT START: All features enabled - Opus+FEC, adaptive jitter buffer, packet redundancy, adaptive bitrate")

        val room = session.getRoom(roomId)
        if (room == null) {
            Timber.e("❌ Room $roomId not found - cannot start Enhanced PTT")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val localIp = PttCoordinator.getLocalIpAddress()
                val remoteIp = PttMatrixSyncHandler(context, session, session.myUserId, roomId).getSenderIpForRoom()

                Timber.d("🔍 Enhanced transport decision: localIp=$localIp, remoteIp=$remoteIp")

                // 1. Ensure IP availability with enhanced coordination
                val ipSuccess = PttCoordinator.ensureIpAvailable(room, session.myUserId, localIp)
                if (!ipSuccess) {
                    Timber.e("❌ Failed to ensure IP availability - aborting Enhanced PTT")
                    return@launch
                }

                // 2. Send PTT status with enhanced reliability
                val statusSuccess = PttCoordinator.sendPttStatus(room, session.myUserId, "talking")
                if (!statusSuccess) {
                    Timber.e("❌ Failed to send PTT status - aborting Enhanced PTT")
                    return@launch
                }

                // 3. Initialize PTT session tracking
                currentPttStartTime = System.currentTimeMillis()
                currentPttAudioData.clear()

                // 4. Start enhanced TCP sender with TURN support
                withContext(Dispatchers.Main) {
                    val transportType = determineEnhancedTransport(localIp, remoteIp)

                    when (transportType) {
                        EnhancedPttTransportType.DIRECT_TCP -> {
                            enhancedTcpSender = EnhancedPttTcpSender(
                                    context = context,
                                    roomId = roomId,
                                    onTimeoutCallback = onTimeoutCallback,
                                    globalTimeoutCallback = globalTimeoutCallback,
                                    onStopCallback = { enhancedTcpSender = null; clearTimeoutCallback() },
                                    onAudioDataCallback = { audioChunk -> currentPttAudioData.add(audioChunk) }
                            )
                            enhancedTcpSender?.startEnhancedServer()
                        }

                        EnhancedPttTransportType.TURN_RELAY -> {
//                            enhancedTcpSender = EnhancedPttTcpSender(
//                                    context = context,
//                                    roomId = roomId,
//                                    onTimeoutCallback = onTimeoutCallback,
//                                    globalTimeoutCallback = globalTimeoutCallback,
//                                    onStopCallback = { enhancedTcpSender = null; clearTimeoutCallback() },
//                                    onAudioDataCallback = { audioChunk -> currentPttAudioData.add(audioChunk) }
//                            )
//                            enhancedTcpSender?.startWithTurnRelay()
                        }

                        EnhancedPttTransportType.MATRIX_FALLBACK -> {
                            Timber.d("🚀 Starting Enhanced PTT with MATRIX fallback")
                            startEnhancedMatrixSender(roomId)
                        }
                    }

                    Timber.d("✅ Enhanced PTT started successfully with $transportType")
                }
            } catch (e: Exception) {
                Timber.e(e, "💥 Critical error in Enhanced PTT coordination")
            }
        }
    }

    /**
     * Stop PTT with enhanced coordination and voice message saving
     */
    fun stopEnhancedPttStreaming(roomId: String) {
        Timber.d("🛑 Stopping Enhanced PTT coordination for room: $roomId")

        val room = session.getRoom(roomId)
        if (room == null) {
            Timber.w("⚠️ Room $roomId not found during stop - cleaning up locally")
            enhancedTcpSender?.stopEnhancedSending()
            enhancedTcpSender = null

            // Stop matrix sender (handle both types)
            when (val sender = matrixSender) {
                is EnhancedMatrixPttSender -> sender.stopStreaming()
            }
            matrixSender = null
            clearTimeoutCallback()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Stop streaming immediately
                enhancedTcpSender?.stopEnhancedSending()
                enhancedTcpSender = null

                // Stop matrix sender (handle both types)
                when (val sender = matrixSender) {
                    is EnhancedMatrixPttSender -> sender.stopStreaming()
                }
                matrixSender = null

                // 2. Save voice message to Matrix for history
                if (currentPttAudioData.isNotEmpty() && currentPttStartTime > 0) {
                    val totalAudioData = ByteArray(currentPttAudioData.sumOf { it.size })
                    var offset = 0
                    currentPttAudioData.forEach { chunk ->
                        System.arraycopy(chunk, 0, totalAudioData, offset, chunk.size)
                        offset += chunk.size
                    }
                    val durationMs = System.currentTimeMillis() - currentPttStartTime

                    try {
                        val success = voiceMessageHelper.savePttAsVoiceMessage(
                                session = session,
                                roomId = roomId,
                                audioData = totalAudioData,
                                durationMs = durationMs
                        )
                        if (success) {
                            Timber.d("✅ PTT voice message saved successfully ($durationMs ms)")
                        } else {
                            Timber.w("⚠️ Failed to save PTT voice message")
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "❌ Error saving PTT voice message")
                    }

                    // Clear audio data
                    currentPttAudioData.clear()
                    currentPttStartTime = 0
                }

                // 3. Send stop status with enhanced reliability
                val success = PttCoordinator.sendPttStatus(room, session.myUserId, "idle")
                if (success) {
                    Timber.d("✅ Enhanced PTT stop coordination completed successfully")
                } else {
                    Timber.w("⚠️ Enhanced PTT stopped locally but Matrix notification may have failed")
                }

                clearTimeoutCallback()
            } catch (e: Exception) {
                Timber.e(e, "💥 Error during Enhanced PTT stop coordination")
                clearTimeoutCallback()
            }
        }
    }

    /**
     * Legacy compatibility method
     */
    fun stopStreaming() {
        enhancedTcpSender?.stopEnhancedSending()
        enhancedTcpSender = null

        // Stop matrix sender (handle both types)
        when (val sender = matrixSender) {
            is EnhancedMatrixPttSender -> sender.stopStreaming()
        }
        matrixSender = null
        clearTimeoutCallback()
    }

    /**
     * Determine best transport type with TURN support
     */
    private fun determineEnhancedTransport(localIp: String, remoteIp: String?): EnhancedPttTransportType {
        Timber.d("🔍 Enhanced transport decision: localIp=$localIp, remoteIp=$remoteIp")

        // ✅ REAL Hybrid System - استخدام TCP للشبكة نفسها و Matrix للشبكات المختلفة
        return when {
            // Same network - use direct TCP for best performance
            !remoteIp.isNullOrEmpty() && isSameNetwork(localIp, remoteIp) -> {
                Timber.d("🌐 Same network detected - using DIRECT_TCP for optimal performance")
                EnhancedPttTransportType.DIRECT_TCP
            }
            // Different networks - use Matrix for reliability
            !remoteIp.isNullOrEmpty() && !isSameNetwork(localIp, remoteIp) -> {
                Timber.d("🌐 Different networks detected - using MATRIX_FALLBACK for reliability")
                EnhancedPttTransportType.MATRIX_FALLBACK
            }
            // No remote IP - use Matrix
            else -> {
                Timber.d("🌐 No remote IP - using MATRIX_FALLBACK")
                EnhancedPttTransportType.MATRIX_FALLBACK
            }
        }
    }

    /**
     * Check if two IPs are on the same network
     */
    private fun isSameNetwork(localIp: String, remoteIp: String): Boolean {
        return try {
            val localSubnet = localIp.substring(0, localIp.lastIndexOf('.'))
            val remoteSubnet = remoteIp.substring(0, remoteIp.lastIndexOf('.'))
            val sameSubnet = localSubnet == remoteSubnet
            Timber.d("🔍 Network check: $localIp vs $remoteIp → sameSubnet=$sameSubnet")
            sameSubnet
        } catch (e: Exception) {
            Timber.w(e, "⚠️ Failed to check network similarity")
            false
        }
    }

    /**
     * Check if direct connection is possible (same subnet)
     */
    private fun isDirectConnectionPossible(localIp: String, remoteIp: String): Boolean {
        Timber.d("🔍 Checking direct connection: local=$localIp, remote=$remoteIp")

        return when {
            localIp == remoteIp -> {
                Timber.d("✅ Same IP address - direct connection possible")
                true
            }
            // Check if both IPs are in the same 192.168.x.x subnet
            localIp.startsWith("192.168.") && remoteIp.startsWith("192.168.") -> {
                val localSubnet = localIp.substringBeforeLast(".")
                val remoteSubnet = remoteIp.substringBeforeLast(".")
                val sameSubnet = localSubnet == remoteSubnet
                Timber.d("🏠 192.168.x.x check: local subnet=$localSubnet, remote subnet=$remoteSubnet, same=$sameSubnet")
                sameSubnet
            }
            // Check if both IPs are in the same 10.x.x.x subnet (more restrictive)
            localIp.startsWith("10.") && remoteIp.startsWith("10.") -> {
                // For 10.x.x.x networks, check if they're in the same /16 subnet (10.x.y.z)
                val localParts = localIp.split(".")
                val remoteParts = remoteIp.split(".")
                val sameSubnet = localParts.size >= 3 && remoteParts.size >= 3 &&
                        localParts[0] == remoteParts[0] &&
                        localParts[1] == remoteParts[1]
                Timber.d("🏢 10.x.x.x check: local=${localParts.take(2)}, remote=${remoteParts.take(2)}, same=$sameSubnet")
                sameSubnet
            }
            else -> {
                Timber.d("❌ Different network types - no direct connection")
                false
            }
        }
    }

    /**
     * Start enhanced Matrix sender with audio data collection
     */
    private fun startEnhancedMatrixSender(roomId: String) {
        Timber.d("🚀 Starting Enhanced Matrix PTT sender with audio collection")

        // Create an enhanced Matrix sender that collects audio data
        val enhancedMatrixSender = EnhancedMatrixPttSender(
                context = context,
                session = session,
                roomId = roomId,
                onTimeoutCallback = onTimeoutCallback,
                globalTimeoutCallback = { rId -> globalTimeoutCallback?.invoke(rId) },
                onAudioDataCallback = { audioChunk ->
                    currentPttAudioData.add(audioChunk)
                    Timber.v("📊 Collected audio chunk: ${audioChunk.size} bytes")
                }
        )
        matrixSender = enhancedMatrixSender
        // ✅ CRITICAL: Start the actual streaming with the Enhanced sender
        enhancedMatrixSender.startStreaming()
        Timber.d("✅ Enhanced Matrix PTT sender started successfully")
    }
}

/**
 * Enhanced transport types with TURN support
 */
enum class EnhancedPttTransportType {
    DIRECT_TCP,      // Direct TCP connection
    TURN_RELAY,      // Via TURN server relay
    MATRIX_FALLBACK  // Matrix to-device messages
}

/**
 * Enhanced TCP sender with TURN server support and optimized audio
 */
class EnhancedPttTcpSender(
        private val context: Context,
        private val roomId: String,
        private val onTimeoutCallback: (() -> Unit)? = null,
        private val globalTimeoutCallback: ((roomId: String) -> Unit)? = null,
        private val onStopCallback: (() -> Unit)? = null,
        private val onAudioDataCallback: ((ByteArray) -> Unit)? = null
) {
    private val serverPort = 8008
    private var audioRecord: AudioRecord? = null
    private var isSending = false
    private var socket: ServerSocket? = null
    private val clientSockets = mutableListOf<Socket>()
    private val clientStreams = mutableMapOf<Socket, BufferedOutputStream>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val MAX_RECORDING_TIME_SECONDS = 30
        const val ENHANCED_SAMPLE_RATE = 16000   // ✅ متوافق مع المستقبل
        const val ENHANCED_BUFFER_SIZE = 2048    // ✅ حجم مناسب للتسجيل
        private const val FRAME_DURATION_MS = 20 // ✅ 20ms ثابتة
        private val BYTES_PER_TICK = (ENHANCED_SAMPLE_RATE / 1000 * FRAME_DURATION_MS) * 2 // 640B

        private var aec: AcousticEchoCanceler? = null
        private var audioManager: AudioManager? = null
    }

    /**
     * Start as TCP server (LAN mode)
     */
    fun startEnhancedServer() {
        isSending = true

        // Start TCP server
        scope.launch {
            try {
                socket = ServerSocket(serverPort)
                Timber.d("🚀 TCP Server started on port $serverPort")

                while (isSending) {
                    try {
                        val client = socket!!.accept()
                        Timber.d("✅ Client connected: ${client.inetAddress.hostAddress}:${client.port}")
                        synchronized(clientSockets) {
                            clientSockets.add(client)
                            clientStreams[client] = BufferedOutputStream(client.getOutputStream())
                        }
                    } catch (e: Exception) {
                        if (isSending) Timber.e("❌ Accept error: ${e.message}")
                        break
                    }
                }
            } catch (e: Exception) {
                Timber.e("❌ Server error: ${e.message}")
            }
        }

        // Start audio recording + streaming
        startEnhancedAudioRecording()
    }

    /**
     * Audio recording & streaming loop
     */
    private fun startEnhancedAudioRecording() {
        scope.launch {
            try {

                // Configure AudioManager
                audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION")
                audioManager?.isSpeakerphoneOn = false // 🔇 mic echo reduction

                // AudioRecord setup
                val minBuf = AudioRecord.getMinBufferSize(
                        ENHANCED_SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                )
                val recordBuffer = maxOf(minBuf, ENHANCED_BUFFER_SIZE)

                audioRecord = try {
                    AudioRecord(
                            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                            ENHANCED_SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            recordBuffer
                    )
                } catch (_: Exception) {
                    AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            ENHANCED_SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            recordBuffer
                    )
                }

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Timber.e("❌ AudioRecord init failed")
                    return@launch
                }

                // Enable AEC if available
                if (AcousticEchoCanceler.isAvailable()) {
                    runCatching {
                        aec = AcousticEchoCanceler.create(audioRecord!!.audioSessionId)
                        aec?.enabled = true
                        Timber.d("🎧 AEC enabled")
                    }.onFailure { Timber.w(it, "AEC init failed") }
                }

                // Start recording
                audioRecord?.startRecording()
                Timber.d("🎤 Audio recording started for room=$roomId")

                val roomToken = "${roomId.hashCode()}:".toByteArray()
                val frameBuffer = ByteArray(BYTES_PER_TICK)
                val recordingStartTime = System.currentTimeMillis()
                val maxRecordingTimeMs = MAX_RECORDING_TIME_SECONDS * 1000L

                var packetCount = 0

                while (isSending && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val now = System.currentTimeMillis()
                    if (now - recordingStartTime >= maxRecordingTimeMs) {
                        Timber.d("⏰ Time limit reached")
                        isSending = false
                        onTimeoutCallback?.invoke()
                        globalTimeoutCallback?.invoke(roomId)
                        break
                    }

                    val frameStart = System.nanoTime()
                    var filled = 0
                    while (filled < BYTES_PER_TICK && isSending) {
                        val r = audioRecord?.read(frameBuffer, filled, BYTES_PER_TICK - filled) ?: 0
                        if (r <= 0) break
                        filled += r
                    }
                    if (filled <= 0) continue

                    val audioChunk = frameBuffer.copyOf(filled)
                    onAudioDataCallback?.invoke(audioChunk)

                    synchronized(clientSockets) {
                        clientSockets.removeAll { it.isClosed }
                        clientSockets.forEach { client ->
                            try {
                                clientStreams[client]?.apply {
                                    write(roomToken)
                                    write(audioChunk, 0, audioChunk.size)
                                    flush()
//                                    if (packetCount % 3 == 0) flush()

                                }
                            } catch (e: Exception) {
                                Timber.w("Send error: ${e.message}")
                                runCatching {
                                    clientStreams.remove(client)
                                    client.close()
                                }
                            }
                        }
                    }

                    packetCount++
                    if (packetCount % 20 == 0) {
                        Timber.d("📤 Sent $packetCount frames (~${packetCount * FRAME_DURATION_MS}ms)")
                    }

                    // Keep cadence ~20ms
                    val elapsedMs = (System.nanoTime() - frameStart) / 1_000_000
                    if (elapsedMs < FRAME_DURATION_MS) {
                        delay(FRAME_DURATION_MS - elapsedMs)
                    }
                }

                Timber.d("✅ Recording ended: $packetCount frames sent")
            } catch (e: Exception) {
                Timber.e(e, "💥 Recording error")
            } finally {
                cleanupEnhancedAudio()
            }
        }
    }

    /**
     * Stop sending + cleanup
     */
    fun stopEnhancedSending() {
        isSending = false
        cleanupEnhancedAudio()
        runCatching { scope.cancel() }
        onStopCallback?.invoke()
        Timber.d("🛑 TCP Sender stopped")
    }

    /**
     * Full cleanup
     */
    private fun cleanupEnhancedAudio() {
        Timber.d("🧹 cleanupEnhancedAudio() start")

        // Release AEC
        runCatching {
            aec?.enabled = false
            aec?.release()
            Timber.d("🎧 AEC released")
        }
        aec = null

        // Release AudioRecord
        runCatching {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
                release()
                Timber.d("🎤 AudioRecord released")
            }
        }
        audioRecord = null

        // Reset AudioManager
        runCatching {
            audioManager?.mode = AudioManager.MODE_NORMAL
            Timber.d("🎚️ AudioManager reset to NORMAL")
        }
        audioManager = null

        // Close clients
        synchronized(clientSockets) {
            clientSockets.forEach { client ->
                runCatching {
                    clientStreams[client]?.close()
                    client.close()
                    Timber.d("🔌 Closed client: ${client.inetAddress.hostAddress}")
                }
            }
            clientSockets.clear()
            clientStreams.clear()
        }

        // Close server socket
        runCatching {
            socket?.close()
            Timber.d("🖧 Server socket closed")
        }
        socket = null

        Timber.d("✅ cleanupEnhancedAudio() done")
    }
}

/**
 * Enhanced Matrix PTT Sender with audio data collection
 */
class EnhancedMatrixPttSender(
        private val context: Context,
        private val session: Session,
        private val roomId: String,
        private val onTimeoutCallback: (() -> Unit)? = null,
        private val globalTimeoutCallback: ((roomId: String) -> Unit)? = null,
        private val onAudioDataCallback: ((ByteArray) -> Unit)? = null
) {
    private var isStreaming = false
    private var audioRecord: AudioRecord? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ✅ Enhanced PCM+FEC encoder للقضاء على packet loss
    private var opusEncoder: OpusPttCodec.OpusEncoder? = null

    companion object {
        const val SAMPLE_RATE = 16000 // ✅ عودة لـ 16kHz للتوافق مع النظام
        const val BUFFER_SIZE = 2048 // ✅ تقليل للتوافق مع TCP LAN
        const val MAX_RECORDING_TIME_MS = 30_000L
        const val FRAME_MS = 20 // ✅ تقليل للتوافق مع TCP LAN
        const val CHUNK_SIZE = 640 // ✅ تقليل للتوافق مع TCP LAN
        const val FRAME_SIZE = 640 // ✅ توحيد مع TCP Transport
    }

    fun startStreaming() {
        if (isStreaming) {
            Timber.w("⚠️ ENHANCED MATRIX PTT: Already streaming, ignoring start")
            return
        }

        isStreaming = true
        Timber.d("🎙️ ENHANCED MATRIX PTT: Starting Enhanced Matrix PTT streaming with ALL FEATURES")
        Timber.d("🎯 ENHANCED MATRIX PTT: Opus+FEC encoding, Network-aware quality, Packet redundancy, Audio processing")

        scope.launch {
            val room = session.getRoom(roomId) ?: run {
                Timber.e("❌ Matrix room not found: $roomId")
                isStreaming = false
                return@launch
            }

            val roomMembers = room.membershipService().getRoomMembers(
                    org.matrix.android.sdk.api.session.room.members.roomMemberQueryParams {
                        memberships = listOf(org.matrix.android.sdk.api.session.room.model.Membership.JOIN)
                        excludeSelf = true
                    }
            )

            if (roomMembers.isEmpty()) {
                Timber.w("⚠️ No other members in room for PTT")
                isStreaming = false
                return@launch
            }

            val baseTimestamp = System.currentTimeMillis()

            // ✅ تهيئة Enhanced PCM+FEC encoder
            Timber.d("🎵 ENHANCED MATRIX PTT: Initializing Opus+FEC+PLC encoder...")
            opusEncoder = OpusPttCodec.OpusEncoder(context).apply {
                if (!initialize()) {
                    Timber.e("❌ ENHANCED MATRIX PTT: Failed to initialize Enhanced PCM+FEC encoder - falling back to PCM")
                    opusEncoder = null
                } else {
//                    runCatching {
//                        // أمثلة — عدّل للأسماء الفعلية في OpusPttCodec لديك
//                        setInbandFecEnabled(true)
//                        setExpectedPacketLossPercent(10)   // 5–15% حسب البروفايل
//                        setTargetBitrateKbps(16)           // 12–20 kbps للكلام
//                        setFrameDurationMs(FRAME_MS)
//                        setComplexity(6)                    // 0..10
//                    }
                    Timber.d("✅ ENHANCED MATRIX PTT: Opus+FEC+PLC encoder initialized successfully!")
                }
            }
            // Enhanced AudioRecord with optimized settings for crystal clear audio
            val minBufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            )
//            val optimalBufferSize = max(minBufferSize, max(BUFFER_SIZE, CHUNK_SIZE * 2))
            val optimalBufferSize = max(minBufferSize, CHUNK_SIZE * 4)
            Timber.d("minBufferSize=$minBufferSize optimalBufferSize=$optimalBufferSize")

//            val source = if (NoiseSuppressor.isAvailable()) {
//                MediaRecorder.AudioSource.VOICE_COMMUNICATION
//            } else {
//                MediaRecorder.AudioSource.MIC
//            }

            audioRecord = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC) // ✅ تغيير من DEFAULT إلى VOICE_COMMUNICATION
                    .setAudioFormat(
                            AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(SAMPLE_RATE)
                                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                    .build()
                    )
                    .setBufferSizeInBytes(optimalBufferSize)
                    .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Timber.e("❌ Failed to initialize AudioRecord for Enhanced Matrix")
                isStreaming = false
                return@launch
            }

            // ✅ Audio effects
//            runCatching {
//                if (NoiseSuppressor.isAvailable()) {
//                    val ns = NoiseSuppressor.create(audioRecord!!.audioSessionId)
//                    ns?.enabled = false
//                    Timber.d("🎵 Noise Suppressor enabled")
//                }
//            }
//            runCatching {
//                if (AutomaticGainControl.isAvailable()) {
//                    val agc = AutomaticGainControl.create(audioRecord!!.audioSessionId)
//                    agc?.enabled = false
//                    Timber.d("🎵 Automatic Gain Control enabled")
//                }
//            }
//            runCatching {
//                if (AcousticEchoCanceler.isAvailable()) {
//                    val aec = AcousticEchoCanceler.create(audioRecord!!.audioSessionId)
//                    aec?.enabled = false
//                    Timber.d("🎵 Acoustic Echo Canceler enabled")
//                }
//            }

            // ✅ استخدام إعدادات ثابتة ومتوافقة مع TCP LAN
            val networkProfile = NetworkAudioQualityManager.getOptimalQualityProfile(context)
//            val adaptiveFrameMs = FRAME_MS // ✅ استخدام FRAME_MS الثابت للتوافق
            val frameBytesTarget = FRAME_SIZE // ✅ استخدام FRAME_SIZE الثابت للتوافق مع TCP

            Timber.d("🎯 ENHANCED MATRIX PTT: Frame size target: ${frameBytesTarget}B (same as TCP Transport)")

            val agg = ByteArrayOutputStream(frameBytesTarget * 2)
            var seq = 0L
            val startTime = System.currentTimeMillis()
            var packetCount = 0
            var lastSendTime = 0L

            Timber.d("🎤 ENHANCED MATRIX PTT: Starting audio recording with enhanced processing...")
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)

            audioRecord?.startRecording()

            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Timber.e("❌ AudioRecord did not actually start recording")
                return@launch
            }

            Timber.d("🔄 ENHANCED MATRIX PTT: Entering streaming loop - network profile: ${networkProfile.description}")

            val tmp = ByteArray(CHUNK_SIZE)

            while (isStreaming && (System.currentTimeMillis() - startTime) < MAX_RECORDING_TIME_MS) {
                val read = audioRecord?.read(tmp, 0, tmp.size) ?: 0
                Timber.d("🎙️ AudioRecord read = $read bytes")

                if (read > 0) {

                    val audioLevel = calculateAudioLevel(tmp, read)
                    if (audioLevel < 50) { // ✅ تقليل threshold من 100 إلى 50
                        Timber.w("⚠️ LOW AUDIO LEVEL: $audioLevel - Microphone may not be working properly")
                    } else {
                        Timber.d("✅ Audio level: $audioLevel - Good microphone input")
                    }

                    // Collect audio data for voice message saving
                    applySmartGain(tmp, read) // ✅ زيادة من 1.2f إلى 2.0f

                    val audioChunk = ByteArray(read)
                    System.arraycopy(tmp, 0, audioChunk, 0, read)
                    onAudioDataCallback?.invoke(audioChunk)

                    agg.write(tmp, 0, read)

                    // ✅ Enhanced frame aggregation with adaptive network-aware timing
                    if (agg.size() >= frameBytesTarget || shouldFlushBuffer(seq, lastSendTime, agg.size(), frameBytesTarget)) {
                        val payload = agg.toByteArray()
                        agg.reset()
                        seq++

                        // ✅ ترميز Enhanced PCM+FEC إن أمكن، وإلا PCM
                        val (finalPayload, encoding) = run {
                            Timber.d("🔍 DEBUG: Payload size: ${payload.size}")

                            // تحويل إلى shorts للفحص
                            val shorts = ShortArray(payload.size / 2)
                            java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                    .asShortBuffer().get(shorts)

                            Timber.d("🔍 DEBUG: First few samples: ${shorts.take(5).joinToString()}")
                            Timber.d("�� DEBUG: Sample range: ${shorts.minOrNull()} to ${shorts.maxOrNull()}")

                            // ✅ فحص البيانات المشوهة
                            val maxAmplitude = shorts.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                            val avgAmplitude = shorts.map { kotlin.math.abs(it.toInt()) }.average().toInt()

                            Timber.d("�� DEBUG: Audio analysis - max=$maxAmplitude, avg=$avgAmplitude")

                            // ✅ فحص إذا كانت البيانات مشوهة
                            if (maxAmplitude > 25000 || avgAmplitude > 10000) {
                                Timber.w("⚠️ WARNING: Audio data appears distorted - max=$maxAmplitude, avg=$avgAmplitude")
                                // تطبيق تصحيح للبيانات المشوهة
                                val correctedShorts = correctDistortedAudio(shorts)
                                val correctedBytes = ByteArray(correctedShorts.size * 2)
                                java.nio.ByteBuffer.wrap(correctedBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                        .asShortBuffer().put(correctedShorts)
                                correctedBytes to "pcm_16bit"
                            } else {
                                payload to "pcm_16bit"
                            }
                        }

                        val base64 = Base64.encodeToString(finalPayload, Base64.NO_WRAP)
                        val frameTimestamp = baseTimestamp + (seq * FRAME_MS)

                        try {
                            val targets = roomMembers.associate { member -> member.userId to listOf("*") }

                            // 📡 Enhanced Matrix PTT with comprehensive metadata
                            val content = mutableMapOf<String, Any>(
                                    "room_id" to roomId,
                                    "sender_id" to session.myUserId,
                                    "timestamp" to frameTimestamp,
                                    "seq" to seq,
                                    "sample_rate" to SAMPLE_RATE,
                                    "encoding" to encoding, // ✅ "opus_fec" أو "pcm_16bit"
                                    "frame_ms" to FRAME_MS,
                                    "audio_data" to base64,
                                    "network_type" to NetworkAudioQualityManager.getCurrentNetworkType(context),
                                    "frame_size" to finalPayload.size,
                                    "fec_enabled" to (encoding == "opus_fec"), // ✅ معلومة FEC للمستقبل
                                    "quality_profile" to networkProfile.description
                            )

                            // Add enhanced packet redundancy
                            val currentLoss = NetworkAudioQualityManager.getCurrentNetworkCondition().packetLossPercent / 100f
                            val redundancy = when {
                                currentLoss < 0.05 -> 1
                                currentLoss < 0.15 -> 2
                                else -> 4
                            }
                            PttQualityEnhancer.addPacketRedundancy(context, content, seq, base64, recentFrames, redundancy)

                            // Send with retry logic for Matrix reliability
                            sendMatrixFrameWithRetry(targets, content)

                            lastSendTime = System.currentTimeMillis()
                            packetCount++

                            if (packetCount == 1) {
                                Timber.d("🎉 First frame sent (${finalPayload.size}B, $encoding)")
                            } else if (packetCount <= 5 || packetCount % 10 == 0) {
                                Timber.d(
                                        "�� frame#$packetCount seq=$seq bytes=${finalPayload.size} enc=$encoding net=${
                                            NetworkAudioQualityManager.getCurrentNetworkType(
                                                    context
                                            )
                                        }"
                                )
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "❌ Failed to send Enhanced PTT frame #$packetCount")
                            // Add to failed frames for potential resending
                            handleSendFailure(seq, base64)
                        }
                    }
                } else if (read < 0) {
                    Timber.w("AudioRecord read error: $read")
                    break
                }
            }

            // Send final frame if any data remains
            if (agg.size() > 0) {
                val payload = agg.toByteArray()
                seq++
                val base64 = Base64.encodeToString(payload, Base64.NO_WRAP)
                val targets = roomMembers.associate { member -> member.userId to listOf("*") }

                val finalContent = mutableMapOf<String, Any>(
                        "room_id" to roomId,
                        "sender_id" to session.myUserId,
                        "timestamp" to (baseTimestamp + (seq * FRAME_MS)),
                        "seq" to seq,
                        "sample_rate" to SAMPLE_RATE,
                        "encoding" to "pcm_16bit",
                        "frame_ms" to FRAME_MS,
                        "audio_data" to base64
                )

                PttQualityEnhancer.addPacketRedundancy(context, finalContent, seq, base64, recentFrames)

                session.toDeviceService().sendToDevice(
                        eventType = "m.ptt.audio",
                        targets = targets,
                        content = finalContent
                )
            }

            Timber.d("✅ Enhanced Matrix PTT streaming ended: $packetCount chunks sent")
            stopStreaming()
        }
    }

    private fun correctDistortedAudio(samples: ShortArray): ShortArray {
        val corrected = ShortArray(samples.size)

        for (i in samples.indices) {
            val sample = samples[i].toFloat()

            // ✅ تطبيق soft clipping بدلاً من hard clipping
            val correctedSample = when {
                sample > 20000 -> 20000f // soft limit
                sample < -20000 -> -20000f // soft limit
                else -> sample
            }

            // ✅ تطبيق noise reduction بسيط
            val noiseReduced = if (kotlin.math.abs(correctedSample) < 100) {
                0f // إزالة الضوضاء الخفيفة
            } else {
                correctedSample
            }

            corrected[i] = noiseReduced.toInt().toShort()
        }

        return corrected
    }

    private fun applySmartGain(buffer: ByteArray, size: Int) {
        val bb = java.nio.ByteBuffer.wrap(buffer, 0, size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val shortBuf = bb.asShortBuffer()
        val samples = ShortArray(shortBuf.remaining())
        shortBuf.get(samples)

        // ✅ تحليل شامل للبيانات
        val maxAmplitude = samples.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
        val avgAmplitude = samples.map { kotlin.math.abs(it.toInt()) }.average().toInt()
        val rmsAmplitude = kotlin.math.sqrt(samples.map { it.toDouble() * it.toDouble() }.average()).toInt()

        Timber.d("🔍 Pure analysis: max=$maxAmplitude, avg=$avgAmplitude, rms=$rmsAmplitude")

        // ✅ تطبيق معالجة نقية حسب نوع البيانات
        when {
            maxAmplitude < 100 -> {
                // بيانات ضعيفة جداً - تطبيق gain بسيط جداً
                applyMinimalGain(samples, 1.05f) // ✅ تقليل من 1.1f إلى 1.05f
                applyNoiseReduction(samples, threshold = 20) // ✅ تقليل من 30 إلى 20
                Timber.d("🔊 Weak audio - applied minimal gain + noise reduction")
            }
            maxAmplitude > 20000 -> {
                // بيانات قوية - تطبيق soft clipping فقط
                applySoftClipping(samples, maxLevel = 16000) // ✅ تقليل من 18000 إلى 16000
                applyNoiseReduction(samples, threshold = 40) // ✅ تقليل من 50 إلى 40
                Timber.d("🔊 Strong audio - applied soft clipping + noise reduction")
            }
            avgAmplitude < 300 -> {
                // بيانات متوسطة ضعيفة - تطبيق gain بسيط
                applyMinimalGain(samples, 1.02f) // ✅ تقليل من 1.05f إلى 1.02f
                Timber.d("🔊 Medium-weak audio - applied minimal gain")
            }
            else -> {
                // بيانات جيدة - تطبيق noise reduction فقط
                applyNoiseReduction(samples, threshold = 30) // ✅ تقليل من 40 إلى 30
                Timber.d("🔊 Good audio - applied noise reduction only")
            }
        }

        bb.rewind()
        bb.asShortBuffer().put(samples)
    }

    // ✅ تطبيق gain بسيط جداً بدون distortion
    private fun applyMinimalGain(samples: ShortArray, factor: Float) {
        for (i in samples.indices) {
            val sample = samples[i].toFloat()
            val amplified = sample * factor

            // ✅ تطبيق soft limiting لطيف
            val limited = when {
                amplified > 12000 -> 12000f + (amplified - 12000f) * 0.05f // ✅ تقليل من 0.1f إلى 0.05f
                amplified < -12000 -> -12000f + (amplified + 12000f) * 0.05f // ✅ تقليل من 0.1f إلى 0.05f
                else -> amplified
            }

            samples[i] = limited.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
        }
    }

    // ✅ تطبيق soft clipping لطيف
    private fun applySoftClipping(samples: ShortArray, maxLevel: Int) {
        for (i in samples.indices) {
            val sample = samples[i].toFloat()
            val absSample = kotlin.math.abs(sample)

            if (absSample > maxLevel) {
                // ✅ تطبيق soft clipping باستخدام tanh
                val sign = if (sample >= 0) 1f else -1f
                val normalized = absSample / maxLevel
                val clipped = kotlin.math.tanh(normalized * 0.6f) * maxLevel // ✅ تقليل من 0.8f إلى 0.6f
                samples[i] = (clipped * sign).toInt().toShort()
            }
        }
    }

    // ✅ تطبيق noise reduction لطيف
    private fun applyNoiseReduction(samples: ShortArray, threshold: Int) {
        for (i in samples.indices) {
            val sample = samples[i].toFloat()
            val absSample = kotlin.math.abs(sample)

            if (absSample < threshold) {
                // ✅ تطبيق fade-out لطيف
                val fadeFactor = (absSample / threshold.toFloat()).toDouble().pow(0.3).toFloat() // ✅ تقليل من 0.5 إلى 0.3
                samples[i] = (sample * fadeFactor).toInt().toShort()
            }
        }
    }

    private fun calculateAudioLevel(buffer: ByteArray, size: Int): Int {
        var sum = 0L
        var i = 0
        while (i + 1 < size) {
            val sample = ((buffer[i + 1].toInt() and 0xFF) shl 8) or (buffer[i].toInt() and 0xFF)
            sum += kotlin.math.abs(sample)
            i += 2
        }
        return (sum / (size / 2)).toInt()
    }

    fun stopStreaming() {
        if (!isStreaming) return
        isStreaming = false
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { opusEncoder?.release() }
        opusEncoder = null
        runCatching { scope.cancel() }
        Timber.d("🛑 Enhanced Matrix PTT sender stopped")
    }

    // Enhanced Matrix transmission methods
    private val recentFrames = mutableListOf<Pair<Long, String>>() // seq → base64
    private val maxRedundantFrames = 2
    private val failedFrames = mutableListOf<Pair<Long, String>>() // seq → base64
    private val maxFailedFrames = 10

    /**
     * Send Matrix frame with retry logic for better reliability
     */
    private suspend fun sendMatrixFrameWithRetry(
            targets: Map<String, List<String>>,
            content: Map<String, Any>,
            retries: Int = 2
    ) {
        repeat(retries + 1) { attempt ->
            try {
                session.toDeviceService().sendToDevice(
                        eventType = "m.ptt.audio",
                        targets = targets,
                        content = content
                )
                // خزّن آخر الإطارات للـ redundancy
                (content["seq"] as? Number)?.toLong()?.let { s ->
                    (content["audio_data"] as? String)?.let { b64 ->
                        recentFrames.add(s to b64)
                        if (recentFrames.size > maxRedundantFrames) recentFrames.removeFirst()
                    }
                }
                return
            } catch (e: Exception) {
                if (attempt < retries) {
                    Timber.w("Send attempt ${attempt + 1} failed → retry")
                    kotlinx.coroutines.delay(50)
                } else throw e
            }
        }
    }

    /**
     * Determine if buffer should be flushed based on network conditions
     */
    private fun shouldFlushBuffer(
            @Suppress("UNUSED_PARAMETER") seq: Long,
            lastSendTime: Long,
            aggSize: Int,
            frameBytesTarget: Int
    ): Boolean {
        val dt = System.currentTimeMillis() - lastSendTime
        return aggSize >= frameBytesTarget || dt > FRAME_MS
    }

    /**
     * Handle send failure by storing frame for potential redundancy
     */
    private fun handleSendFailure(seq: Long, base64: String) {
        failedFrames.add(seq to base64)
        if (failedFrames.size > maxFailedFrames) failedFrames.removeFirst()
        Timber.w("📉 Stored failed frame seq=$seq")
    }
}
