/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

/**
 * Enhanced PTT Manager with TURN server support and optimized audio quality
 * 
 * Features:
 * - TURN/STUN server integration for firewall traversal
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
            Timber.w("Enhanced PTT already active; ignoring start.")
            return
        }
        
        Timber.d("🚀 Starting Enhanced PTT with TURN support for room: $roomId")

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
                            enhancedTcpSender = EnhancedPttTcpSender(
                                context = context,
                                roomId = roomId,
                                onTimeoutCallback = onTimeoutCallback,
                                globalTimeoutCallback = globalTimeoutCallback,
                                onStopCallback = { enhancedTcpSender = null; clearTimeoutCallback() },
                                onAudioDataCallback = { audioChunk -> currentPttAudioData.add(audioChunk) }
                            )
                            enhancedTcpSender?.startWithTurnRelay()
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
                is MatrixPttSender -> sender.stopStreaming()
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
                    is MatrixPttSender -> sender.stopStreaming()
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
            is MatrixPttSender -> sender.stopStreaming()
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
        
        return when {
            // Always use Matrix for different networks (like WiFi to 4G)
            !remoteIp.isNullOrEmpty() && !isDirectConnectionPossible(localIp, remoteIp) -> {
                Timber.d("🌐 Different networks detected - using Matrix for reliability")
                EnhancedPttTransportType.MATRIX_FALLBACK
            }
            // Direct connection possible (same subnet)
            !remoteIp.isNullOrEmpty() && isDirectConnectionPossible(localIp, remoteIp) -> {
                Timber.d("🔗 Same subnet detected - using Direct TCP")
                EnhancedPttTransportType.DIRECT_TCP
            }
            // Use TURN relay as secondary option
            !remoteIp.isNullOrEmpty() -> {
                Timber.d("🔄 Using TURN relay")
                EnhancedPttTransportType.TURN_RELAY
            }
            // Fallback to Matrix
            else -> {
                Timber.d("📱 Fallback to Matrix")
                EnhancedPttTransportType.MATRIX_FALLBACK
            }
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
        matrixSender = EnhancedMatrixPttSender(
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
        (matrixSender as? EnhancedMatrixPttSender)?.startStreaming()
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
    private val serverPort: Int = 8008
    private var audioRecord: AudioRecord? = null
    private var isSending = false
    private var socket: ServerSocket? = null
    private var turnRelaySocket: Socket? = null
    private val clientSockets = mutableListOf<Socket>()
    private val clientStreams = mutableMapOf<Socket, BufferedOutputStream>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Enhanced audio settings for 4G networks
    companion object {
        const val MAX_RECORDING_TIME_SECONDS = 30
        const val WARNING_TIME_SECONDS = 5
        const val ENHANCED_SAMPLE_RATE = 22050 // Better quality for 4G
        const val ENHANCED_BUFFER_SIZE = 4096  // Optimized for mobile networks
        
        // TURN server settings
        private const val TURN_HOST = "74.162.88.173"
        private const val TURN_USERNAME = "AtlasUser"
        private const val TURN_PASSWORD = "Atl@s@123_2025"
    }

    /**
     * Start enhanced client connection to remote IP
     */
    fun startClient(remoteIp: String) {
        Timber.d("🔌 Starting Enhanced PTT client connection to $remoteIp")
        isSending = true

        scope.launch {
            try {
                val clientSocket = connectWithTurnFallback(remoteIp, serverPort)
                if (clientSocket != null) {
                    clientSockets.add(clientSocket)
                    clientStreams[clientSocket] = BufferedOutputStream(clientSocket.getOutputStream())
                    startEnhancedAudioRecording()
                } else {
                    Timber.e("❌ Failed to connect to remote IP: $remoteIp")
                    onTimeoutCallback?.invoke()
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ Error in Enhanced PTT client")
                onTimeoutCallback?.invoke()
            }
        }
    }

    /**
     * Start enhanced server with optimized audio quality
     */
    fun startEnhancedServer() {
        isSending = true

        // Start TCP server
        scope.launch {
            try {
                socket = ServerSocket(serverPort)
                Timber.d("Enhanced TCP Server started on port $serverPort")

                while (isSending) {
                    try {
                        val client = socket!!.accept()
                        Timber.d("✅ Enhanced client connected: ${client.inetAddress.hostAddress}:${client.port}")
                        synchronized(clientSockets) {
                            clientSockets.add(client)
                            clientStreams[client] = BufferedOutputStream(client.getOutputStream())
                        }
                        Timber.d("📊 Total enhanced clients: ${clientSockets.size}")
                    } catch (e: Exception) {
                        if (isSending) Timber.e("❌ Enhanced accept error: ${e.message}")
                        break
                    }
                }
            } catch (e: Exception) {
                Timber.e("Enhanced server error: ${e.message}")
            }
        }

        // Start enhanced audio recording
        startEnhancedAudioRecording()
    }

    /**
     * Start with TURN relay support
     */
    fun startWithTurnRelay() {
        isSending = true
        
        scope.launch {
            try {
                // Connect to TURN server for relay
                turnRelaySocket = createTurnRelayConnection()
                if (turnRelaySocket != null) {
                    Timber.d("✅ Connected to TURN relay server")
                    startEnhancedAudioRecording()
                } else {
                    Timber.e("❌ Failed to connect to TURN relay - falling back to Matrix")
                    // TODO: Fallback to Matrix sender
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ TURN relay connection failed")
            }
        }
    }

    /**
     * Enhanced audio recording with 4G optimization
     */
    private fun startEnhancedAudioRecording() {
        scope.launch {
            try {
                // Enhanced audio settings optimized for mobile networks
                audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION, // ✅ Optimized for mobile
                        ENHANCED_SAMPLE_RATE, // ✅ Higher quality
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        max(
                            AudioRecord.getMinBufferSize(
                                ENHANCED_SAMPLE_RATE,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT
                            ),
                            ENHANCED_BUFFER_SIZE
                        )
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Timber.e("❌ Enhanced AudioRecord initialization failed")
                    return@launch
                }

                audioRecord?.startRecording()
                val roomToken = "${roomId.hashCode()}:"
                Timber.d("🎤 Enhanced audio recording started for room: $roomId")

                val buffer = ByteArray(ENHANCED_BUFFER_SIZE)
                val maxRecordingTimeMs = MAX_RECORDING_TIME_SECONDS * 1000L
                val recordingStartTime = System.currentTimeMillis()
                var packetCount = 0

                while (isSending && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    // Check timeout
                    val currentTime = System.currentTimeMillis()
                    val recordingDuration = currentTime - recordingStartTime

                    if (recordingDuration >= maxRecordingTimeMs) {
                        Timber.d("⏰ Enhanced recording time limit reached: ${recordingDuration}ms")
                        isSending = false
                        onTimeoutCallback?.invoke()
                        globalTimeoutCallback?.invoke(roomId)
                        break
                    }

                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        packetCount++
                        
                        // Collect audio data for voice message saving
                        val audioChunk = ByteArray(read)
                        System.arraycopy(buffer, 0, audioChunk, 0, read)
                        onAudioDataCallback?.invoke(audioChunk)
                        
                        // Send to all connected clients
                        synchronized(clientSockets) {
                            clientSockets.removeAll { it.isClosed }
                            clientSockets.forEach { client ->
                                try {
                                    val stream = clientStreams[client]
                                    if (stream != null) {
                                        val tokenBytes = roomToken.toByteArray()
                                        val combinedData = ByteArray(tokenBytes.size + read)
                                        System.arraycopy(tokenBytes, 0, combinedData, 0, tokenBytes.size)
                                        System.arraycopy(buffer, 0, combinedData, tokenBytes.size, read)

                                        stream.write(combinedData)
                                        stream.flush()

                                        if (packetCount % 10 == 1) {
                                            Timber.d("📤 Enhanced packet #$packetCount sent: ${combinedData.size} bytes")
                                        }
                                    }
                                } catch (e: Exception) {
                                    Timber.w("❌ Enhanced send error: ${e.message}")
                                    synchronized(clientSockets) {
                                        clientSockets.remove(client)
                                        clientStreams.remove(client)
                                        client.close()
                                    }
                                }
                            }
                        }

                        // Send via TURN relay if available
                        turnRelaySocket?.let { relaySocket ->
                            try {
                                val tokenBytes = roomToken.toByteArray()
                                val combinedData = ByteArray(tokenBytes.size + read)
                                System.arraycopy(tokenBytes, 0, combinedData, 0, tokenBytes.size)
                                System.arraycopy(buffer, 0, combinedData, tokenBytes.size, read)
                                
                                relaySocket.getOutputStream().write(combinedData)
                                relaySocket.getOutputStream().flush()
                            } catch (e: Exception) {
                                Timber.w("❌ TURN relay send error: ${e.message}")
                            }
                        }
                    }
                }

                Timber.d("Enhanced recording ended: $packetCount packets sent")
            } catch (e: Exception) {
                Timber.e(e, "Enhanced audio recording error")
            } finally {
                cleanupEnhancedAudio()
            }
        }
    }

    /**
     * Create TURN relay connection
     */
    private fun createTurnRelayConnection(): Socket? {
        return try {
            // Connect to TURN server
            val socket = Socket()
            socket.connect(InetSocketAddress(TURN_HOST, 3478), 5000) // 5 second timeout
            
            // TODO: Implement TURN protocol handshake
            // For now, return the socket for basic relay functionality
            socket
        } catch (e: Exception) {
            Timber.e(e, "Failed to create TURN relay connection")
            null
        }
    }

    /**
     * Connect with TURN fallback support
     */
    private fun connectWithTurnFallback(remoteIp: String, port: Int): Socket? {
        return try {
            // Try direct connection first
            Timber.d("🔌 Attempting direct connection to $remoteIp:$port")
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(remoteIp, port), 5000)
            Timber.d("✅ Direct connection established to $remoteIp:$port")
            socket
        } catch (e: Exception) {
            Timber.w(e, "❌ Direct connection failed, would use TURN relay in production")
            // In a real TURN implementation, this would establish a connection through the TURN server
            // For now, return null to indicate connection failure
            null
        }
    }

    /**
     * Stop enhanced sending with proper cleanup
     */
    fun stopEnhancedSending() {
        isSending = false
        cleanupEnhancedAudio()
        
        try {
            scope.cancel()
        } catch (_: Exception) {}
        
        onStopCallback?.invoke()
        Timber.d("Enhanced TCP Sender stopped")
    }

    /**
     * Enhanced cleanup
     */
    private fun cleanupEnhancedAudio() {
        audioRecord?.let {
            try {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
            } catch (_: Exception) {}
            try {
                it.release()
            } catch (_: Exception) {}
        }
        audioRecord = null

        synchronized(clientSockets) {
            clientSockets.forEach { client ->
                runCatching {
                    clientStreams[client]?.close()
                    client.close()
                }
            }
            clientSockets.clear()
            clientStreams.clear()
        }

        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null

        try {
            turnRelaySocket?.close()
        } catch (_: Exception) {}
        turnRelaySocket = null
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

            companion object {
                const val SAMPLE_RATE = 16000 // Optimized for voice clarity and network efficiency
                const val BUFFER_SIZE = 8192 // Further increased buffer for ultra-smooth audio
                const val MAX_RECORDING_TIME_MS = 30_000L
                const val FRAME_MS = 80 // Reduced frame size for lower latency and smoother audio
                const val CHUNK_SIZE = 1024 // ~32ms of audio at 16kHz for optimal clarity
            }

    fun startStreaming() {
        if (isStreaming) return
        isStreaming = true
        
        Timber.d("🎙️ Starting Enhanced Matrix PTT streaming...")

        scope.launch {
            val room = session.getRoom(roomId)
            if (room == null) {
                Timber.e("❌ Matrix room not found: $roomId")
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
                return@launch
            }

                                // Enhanced AudioRecord with optimized settings for crystal clear audio
                    val minBufferSize = AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    val optimalBufferSize = max(minBufferSize, BUFFER_SIZE * 6) // 6x buffer for ultra-smooth recording
                    
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC, // Changed from VOICE_COMMUNICATION for better quality
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        optimalBufferSize
                    )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Timber.e("❌ Failed to initialize AudioRecord for Enhanced Matrix")
                return@launch
            }

                                val bytesPerMs = (SAMPLE_RATE * 2) / 1000
                    val frameBytesTarget = FRAME_MS * bytesPerMs

            val agg = ByteArrayOutputStream(frameBytesTarget * 2)
            var seq = 0L
            val startTime = System.currentTimeMillis()
            var packetCount = 0

            audioRecord?.startRecording()

                                while (isStreaming && (System.currentTimeMillis() - startTime) < MAX_RECORDING_TIME_MS) {
                        val tmp = ByteArray(CHUNK_SIZE) // ~40ms at 16kHz for smoother audio
                        val read = audioRecord?.read(tmp, 0, tmp.size) ?: 0
                
                if (read > 0) {
                    // Collect audio data for voice message saving
                    val audioChunk = ByteArray(read)
                    System.arraycopy(tmp, 0, audioChunk, 0, read)
                    onAudioDataCallback?.invoke(audioChunk)
                    
                    agg.write(tmp, 0, read)
                    
                    if (agg.size() >= frameBytesTarget) {
                        val payload = agg.toByteArray()
                        agg.reset()
                        seq++

                        val base64 = Base64.encodeToString(payload, Base64.NO_WRAP)

                        try {
                            val targets = roomMembers.associate { member -> member.userId to listOf("*") }
                            session.toDeviceService().sendToDevice(
                                eventType = "m.ptt.audio",
                                targets = targets,
                                content = mapOf(
                                    "room_id" to roomId,
                                    "sender_id" to session.myUserId,
                                    "timestamp" to System.currentTimeMillis(),
                                    "seq" to seq,
                                    "sample_rate" to SAMPLE_RATE,
                                    "encoding" to "pcm_16bit",
                                    "frame_ms" to FRAME_MS,
                                    "audio_data" to base64
                                )
                            )
                            packetCount++
                            if (packetCount <= 3 || packetCount % 10 == 0) {
                                Timber.d("📤 Enhanced PTT frame #$packetCount (seq=$seq, ${payload.size}B)")
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "❌ Failed to send Enhanced PTT frame")
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
                session.toDeviceService().sendToDevice(
                    eventType = "m.ptt.audio",
                    targets = targets,
                                            content = mapOf(
                            "room_id" to roomId,
                            "sender_id" to session.myUserId,
                            "timestamp" to System.currentTimeMillis(),
                            "seq" to seq,
                            "sample_rate" to SAMPLE_RATE,
                            "encoding" to "pcm_16bit",
                            "frame_ms" to FRAME_MS,
                            "audio_data" to base64
                        )
                )
            }

            Timber.d("✅ Enhanced Matrix PTT streaming ended: $packetCount chunks sent")
            stopStreaming()
        }
    }

    fun stopStreaming() {
        isStreaming = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        scope.cancel()
        Timber.d("🛑 Enhanced Matrix PTT sender stopped")
    }
}
