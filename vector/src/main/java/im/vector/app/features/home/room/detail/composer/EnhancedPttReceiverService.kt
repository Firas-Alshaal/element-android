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
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import im.vector.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Enhanced PTT Receiver Service with TURN support and background operation
 *
 * Features:
 * - Background PTT reception
 * - TURN server support for firewall traversal
 * - High-quality audio playback optimized for 4G
 * - Automatic audio focus management
 * - Foreground service for reliability
 */
class EnhancedPttReceiverService : Service() {

    private val serverPort = 8008
    private val enhancedSampleRate = 16000 // Enhanced quality
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
    private val enhancedAudioBufferSize = 640 * 8 // Optimized buffer

    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var currentRoomId: String? = null
    private var myUserId: String? = null
    private var senderIp: String? = null
    private var currentSpeakerId: String? = null
    private var audioTrack: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private val jitterBuffer = LinkedBlockingQueue<ByteArray>(20)

    companion object {
        private const val NOTIFICATION_ID = 12345
        private const val CHANNEL_ID = "enhanced_ptt_receiver_channel"
        private const val TURN_HOST = "74.162.88.173"
        private const val TURN_USERNAME = "AtlasUser"
        private const val TURN_PASSWORD = "Atl@s@123_2025"
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        Timber.d("Enhanced PTT Receiver Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentRoomId = intent?.getStringExtra("roomId")
        myUserId = intent?.getStringExtra("myUserId")
        senderIp = intent?.getStringExtra("senderIp")
        currentSpeakerId = intent?.getStringExtra("speakerId")

        if (currentRoomId == null || myUserId == null) {
            Timber.w("Enhanced PTT Receiver: Missing parameters, stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        // Start as foreground service for background operation
        startForeground(NOTIFICATION_ID, createPttNotification())

        Timber.d("Enhanced PTT Receiver Service started for room: $currentRoomId")

        if (currentSpeakerId == "PRESTART") {
            // Pre-start mode - just initialize and wait
            Timber.d("Enhanced PTT Receiver pre-started and ready")
        } else if (!senderIp.isNullOrEmpty()) {
            connectToEnhancedSender()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Connect to enhanced sender with TURN fallback
     */
    private fun connectToEnhancedSender() {
        if (isRunning) return
        isRunning = true

        scope.launch {
            try {
                val socket = createEnhancedConnection()
                if (socket == null) {
                    Timber.e("Enhanced PTT: Failed to connect to sender")
                    stopSelf()
                    return@launch
                }

                Timber.d("✅ Enhanced PTT: Connected to sender")
                setupEnhancedAudioPlayback()
                startEnhancedAudioReception(socket)
            } catch (e: Exception) {
                Timber.e(e, "Enhanced PTT connection error")
                stopSelf()
            }
        }
    }

    /**
     * Create enhanced connection with TURN fallback
     */
    private fun createEnhancedConnection(): Socket? {
        return try {
            // Try direct connection first
            Timber.d("Attempting direct connection to $senderIp:$serverPort")
            val socket = Socket()
            socket.connect(InetSocketAddress(senderIp, serverPort), 3000) // 3 second timeout
            Timber.d("✅ Direct connection successful")
            socket
        } catch (e: Exception) {
            Timber.w("Direct connection failed, trying TURN relay: ${e.message}")
            createTurnRelayConnection()
        }
    }

    /**
     * Create TURN relay connection for firewall traversal
     */
    private fun createTurnRelayConnection(): Socket? {
        return try {
            Timber.d("Connecting via TURN relay: $TURN_HOST")
            val socket = Socket()
            socket.connect(InetSocketAddress(TURN_HOST, 3478), 5000) // 5 second timeout

            // TODO: Implement full TURN protocol handshake
            // For now, basic relay connection
            Timber.d("✅ TURN relay connection established")
            socket
        } catch (e: Exception) {
            Timber.e(e, "TURN relay connection failed")
            null
        }
    }

    /**
     * Setup enhanced audio playback with focus management
     */
    private fun setupEnhancedAudioPlayback() {
        try {

            val minBuf = AudioTrack.getMinBufferSize(enhancedSampleRate, channelConfig, audioEncoding)

            // Request audio focus for high-priority playback
            requestEnhancedAudioFocus()

            // Create enhanced AudioTrack
            audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                            AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED) // Ensure audible
                                    .build()
                    )
                    .setAudioFormat(
                            AudioFormat.Builder()
                                    .setEncoding(audioEncoding)
                                    .setSampleRate(enhancedSampleRate)
                                    .setChannelMask(channelConfig)
                                    .build()
                    )
                    .setBufferSizeInBytes(max(minBuf, enhancedAudioBufferSize))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()

            // Enhanced audio manager configuration for background operation
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager?.isSpeakerphoneOn = true

            // Force maximum volume for PTT
            val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)

            val maxMusicVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusicVol, 0)

            val maxCallVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) ?: 7
            audioManager?.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxCallVol, 0)

            // Force speaker output on newer Android versions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager?.availableCommunicationDevices?.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }?.let { speakerDevice ->
                    audioManager?.setCommunicationDevice(speakerDevice)
                    Timber.d("✅ Communication device set to built-in speaker")
                }
            }

            audioTrack?.play()
            Timber.d("✅ Enhanced audio playback setup complete")
        } catch (e: Exception) {
            Timber.e(e, "Enhanced audio setup failed")
        }
    }

    /**
     * Request enhanced audio focus for high-priority PTT with background support
     */
    private fun requestEnhancedAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setAudioAttributes(
                                AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                        .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED) // Force audibility
                                        .build()
                        )
                        .setAcceptsDelayedFocusGain(false)
                        .setWillPauseWhenDucked(false)
                        .setOnAudioFocusChangeListener { focusChange ->
                            when (focusChange) {
                                AudioManager.AUDIOFOCUS_GAIN -> {
                                    Timber.d("🔊 Audio focus gained for PTT")
                                    audioTrack?.setVolume(1.0f)
                                }
                                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                                    Timber.d("🔇 Audio focus lost temporarily")
                                }
                                AudioManager.AUDIOFOCUS_LOSS -> {
                                    Timber.d("🔇 Audio focus lost permanently")
                                }
                            }
                        }
                        .build()

                val result = audioManager?.requestAudioFocus(audioFocusRequest!!)
                Timber.d("Audio focus request result: $result")
            } else {
                @Suppress("DEPRECATION")
                val result = audioManager?.requestAudioFocus(
                        { focusChange ->
                            when (focusChange) {
                                AudioManager.AUDIOFOCUS_GAIN -> audioTrack?.setVolume(1.0f)
                                else -> Timber.d("Audio focus changed: $focusChange")
                            }
                        },
                        AudioManager.STREAM_VOICE_CALL,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
                Timber.d("Legacy audio focus request result: $result")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to request audio focus")
        }
    }

    /**
     * Start enhanced audio reception with optimized processing
     */
    private fun startEnhancedAudioReception(socket: Socket) {
        scope.launch {
            try {
                val input = BufferedInputStream(socket.getInputStream(), enhancedAudioBufferSize)
                val buffer = ByteArray(enhancedAudioBufferSize)
                val expectedToken = currentRoomId?.hashCode()?.toString() + ":"

                Timber.d("🎧 Enhanced audio reception started - token: '$expectedToken'")

                var receivedPackets = 0
                var validPackets = 0
                var silenceCount = 0

                // Launch separate playback loop
                launch {
                    var prebuffered = false
                    while (isRunning && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        val frame = jitterBuffer.poll(50, TimeUnit.MILLISECONDS)
                        if (frame != null) {
                            audioTrack?.write(frame, 0, frame.size, AudioTrack.WRITE_BLOCKING)
                            if (!prebuffered && jitterBuffer.size >= 8) {
                                prebuffered = true
                                Timber.d("✅ Pre-buffer complete, smooth playback started")
                            }
                            silenceCount = 0
                        } else {
                            silenceCount++

                            // optional: play silence to keep stream stable
                            if (silenceCount >= 3) { // ~150ms gap
                                audioTrack?.write(ByteArray(640), 0, 640, AudioTrack.WRITE_BLOCKING)
                                silenceCount = 0
                            }
                        }
                    }
                }

                // Read loop → enqueue into jitter buffer
                while (isRunning && !socket.isClosed) {
                    val read = input.read(buffer)
                    if (read > 0) {
                        receivedPackets++

                        if (isValidEnhancedPacket(buffer, read, expectedToken)) {
                            validPackets++
                            val tokenLength = expectedToken.toByteArray().size
                            val audioDataLength = read - tokenLength
                            if (audioDataLength > 0) {
                                val frame = buffer.copyOfRange(tokenLength, read)
                                if (!jitterBuffer.offer(frame)) {
                                    jitterBuffer.poll() // drop one old frame
                                    jitterBuffer.offer(frame)
                                }
                            }
                            if (validPackets % 20 == 1) {
                                Timber.d("🔊 Buffered #$validPackets (${audioDataLength}B), jitterBuffer=${jitterBuffer.size}")
                            }
                        }
                    } else if (read < 0) {
                        Timber.w("Enhanced reception error: read=$read")
                        break
                    }
                }

                Timber.d("Enhanced reception ended: $validPackets/$receivedPackets packets")
            } catch (e: Exception) {
                Timber.e(e, "Enhanced reception failed")
            } finally {
                cleanupEnhancedAudio()
                stopSelf()
            }
        }
    }

    /**
     * Validate enhanced packet with improved token checking
     */
    private fun isValidEnhancedPacket(buffer: ByteArray, read: Int, expectedToken: String): Boolean {
        val tokenBytes = expectedToken.toByteArray()

        if (read < tokenBytes.size + 320) return false

        for (i in tokenBytes.indices) {
            if (buffer[i] != tokenBytes[i]) {
                return false
            }
        }

        return true
    }

    /**
     * Play enhanced audio with optimized buffering
     */
//    private fun playEnhancedAudio(buffer: ByteArray, offset: Int, length: Int) {
//        try {
//            var remaining = length
//            var currentOffset = offset
//
//            while (remaining > 0 && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
//                val written = audioTrack?.write(buffer, currentOffset, remaining) ?: 0
//                if (written <= 0) break
//
//                currentOffset += written
//                remaining -= written
//            }
//        } catch (e: Exception) {
//            Timber.w(e, "Enhanced audio playback error")
//        }
//    }

    /**
     * Enhanced cleanup with proper resource management
     */
    private fun cleanupEnhancedAudio() {
        isRunning = false

        // Release audio focus
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { request ->
                audioManager?.abandonAudioFocusRequest(request)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }

        // Cleanup audio track
        audioTrack?.let { track ->
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
                track.flush()
                track.release()
            } catch (e: Exception) {
                Timber.w(e, "AudioTrack cleanup error")
            }
        }
        audioTrack = null

        // Reset audio manager
        audioManager?.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        audioManager?.isSpeakerphoneOn = false

        try {
            scope.cancel()
        } catch (_: Exception) {
        }

        Timber.d("Enhanced PTT Receiver cleanup completed")
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupEnhancedAudio()
        Timber.d("Enhanced PTT Receiver Service destroyed")
    }

    /**
     * Create notification channel for foreground service
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Enhanced PTT Receiver",
                    NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Enhanced PTT audio reception service"
                setShowBadge(false)
                setSound(null, null)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Create PTT notification for foreground service
     */
    private fun createPttNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Enhanced PTT Active")
                .setContentText("Listening for PTT messages in room")
                .setSmallIcon(R.drawable.ic_room_profile_notification)
                .setOngoing(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
    }
}
