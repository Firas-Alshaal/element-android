/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import io.socket.engineio.parser.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import timber.log.Timber
import java.io.BufferedInputStream
import java.net.Socket

class PttTcpReceiverService : Service() {

    private val serverPort = 8008 // Sender port to connect to
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
    private val audioBufferSize = 2048 // Proven optimal size for clear audio

    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var currentRoomId: String? = null
    private var myUserId: String? = null
    private var senderIp: String? = null
    private var currentSpeakerId: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentRoomId = intent?.getStringExtra("roomId")
        myUserId = intent?.getStringExtra("myUserId")
        senderIp = intent?.getStringExtra("senderIp") // Must pass sender IP
        currentSpeakerId = intent?.getStringExtra("speakerId")

        if (myUserId == null || currentRoomId == null || senderIp == null) {
            Timber.w("Missing parameters, stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        Timber.d("PttTcpReceiverService started")
        connectToSender()
        return START_STICKY
    }

    private fun connectToSender() {
        if (isRunning) return
        isRunning = true

        scope.launch {
            try {
                Timber.d("Connecting to $senderIp:$serverPort")
                val socket = Socket(senderIp, serverPort)
                Timber.d("Connected to sender")

                if (!socket.isConnected) {
                    stopSelf()
                    return@launch
                }

                try {
                    // Optimized input stream for clarity
                    val input = BufferedInputStream(socket.getInputStream(), audioBufferSize)
                    Timber.d("Starting audio reception from $currentSpeakerId in room $currentRoomId")

                    // Simple and effective AudioTrack setup
                    val audioTrack = AudioTrack.Builder()
                            .setAudioAttributes(
                                    AudioAttributes.Builder()
                                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                            .build()
                            )
                            .setAudioFormat(
                                    AudioFormat.Builder()
                                            .setEncoding(audioEncoding)
                                            .setSampleRate(sampleRate)
                                            .setChannelMask(channelConfig)
                                            .build()
                            )
                            .setBufferSizeInBytes(2048) // Proven optimal size
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build()

                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        audioManager.availableCommunicationDevices.firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        }?.let {
                            audioManager.setCommunicationDevice(it)
                        }
                    }

                    // Start audio playback
                    audioTrack.play()
                    Timber.d("AudioTrack state: ${audioTrack.state}, playState: ${audioTrack.playState}")

                    val buffer = ByteArray(audioBufferSize)
                    val expectedToken = currentRoomId?.hashCode()?.toString() + ":"
                    Timber.d("Expected token: '$expectedToken' for room: $currentRoomId")

                    var receivedPackets = 0
                    var validPackets = 0
                    var noDataCounter = 0

                    Timber.d("Starting audio reception loop - waiting for data...")

                    while (isRunning && !socket.isClosed) {
                        try {
                            val read = input.read(buffer)
                            Timber.d("Read attempt result: $read bytes")

                            if (read > 0) {
                                receivedPackets++

                                // Simple and fast token validation
                                val tokenBytes = expectedToken.toByteArray()
                                val tokenLength = tokenBytes.size
                                var tokenMatches = true

                                if (read >= tokenLength) {
                                    // Compare bytes directly without String conversion
                                    for (i in tokenBytes.indices) {
                                        if (buffer[i] != tokenBytes[i]) {
                                            tokenMatches = false
                                            break
                                        }
                                    }
                                } else {
                                    tokenMatches = false
                                }

                                if (tokenMatches) {
                                    validPackets++
                                    val audioDataLength = read - tokenLength

                                    if (validPackets <= 3) {
                                        Timber.d("Valid packet #$validPackets: $audioDataLength audio bytes")
                                    }

                                    if (audioDataLength > 0) {
                                        // Simple write with partial write handling
                                        var offset = tokenLength
                                        var remaining = audioDataLength

                                        while (remaining > 0) {
                                            val written = audioTrack.write(buffer, offset, remaining)
                                            if (written <= 0) break
                                            offset += written
                                            remaining -= written
                                        }
                                    }

                                    // Log every 10 valid packets for details
                                    if (validPackets % 10 == 1 && validPackets > 3) {
                                        Timber.d("Playing packet #$validPackets: $audioDataLength audio bytes")
                                    }
                                } else {
                                    // Log only first 3 invalid tokens
                                    if (receivedPackets - validPackets <= 3) {
                                        val hexPreview = buffer.take(minOf(10, read)).joinToString("") { "%02x".format(it) }
                                        Timber.w("Invalid token in packet #$receivedPackets: $hexPreview...")
                                    }
                                }

                                if (validPackets <= 3) { // Only first 3 packets to reduce logs
                                    Timber.d("Continuing loop... receivedPackets=$receivedPackets, validPackets=$validPackets")
                                }
                            } else if (read == 0) {
                                noDataCounter++
                                Timber.d("No data received (count: $noDataCounter)")
                                if (noDataCounter > 30) { // 3 seconds - faster timeout
                                    Timber.w("No data for too long - connection may be stale")
                                    break
                                }
                                Thread.sleep(100) // Short wait
                            } else {
                                Timber.w("Connection closed by sender (read=$read)")
                                break
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Error during audio reception")
                            break
                        }
                    }

                    Timber.d("Audio session ended - total packets: $receivedPackets, valid: $validPackets")

                    audioTrack.stop()
                    audioTrack.release()
                    socket.close()
                } catch (e: Exception) {
                    Timber.e("TCP Receiver error: ${e.message}")
                } finally {
                    isRunning = false
                    stopSelf()
                }
            } catch (e: Exception) {
                Timber.e("Connection error: ${e.message}")
                isRunning = false
                stopSelf()
            }
        }
    }



    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        scope.cancel()
        Timber.d("PttTcpReceiverService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class MatrixPttReceiver(
        private val context: Context,
        private val session: Session,
        private val roomId: String
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var audioTrack: AudioTrack? = null
    private var isRunning = false
    private var toDeviceListener: org.matrix.android.sdk.api.session.LiveEventListener? = null

    fun startListening() {
        Timber.d("MatrixPttReceiver.startListening() called for room: $roomId")

        // Protection against multiple instances
        synchronized(this) {
            if (isRunning) {
                Timber.d("MatrixPttReceiver already running, ignoring start request")
                return
            }

            isRunning = true
            Timber.d("MatrixPttReceiver isRunning set to true")
        }

        // Check room existence
        session.getRoom(roomId) ?: run {
            Timber.w("MatrixPttReceiver: room not found $roomId")
            synchronized(this) {
                isRunning = false
            }
            return
        }

        Timber.d("Starting MatrixPttReceiver (to-device) for room: $roomId")

        try {
            setupToDeviceListener()
        } catch (e: Exception) {
            Timber.e(e, "Failed to setup to-device listener")
            synchronized(this) {
                isRunning = false
            }
        }
    }

    private fun setupToDeviceListener() {
        Timber.d("Setting up to-device listener for PTT receiver")

        // Setup listener for to-device events directly
        toDeviceListener = object : org.matrix.android.sdk.api.session.LiveEventListener {
            override fun onLiveEvent(roomId: String, event: org.matrix.android.sdk.api.session.events.model.Event) {
                // Ignore regular live events
            }

            override fun onPaginatedEvent(roomId: String, event: org.matrix.android.sdk.api.session.events.model.Event) {
                // Ignore paginated events
            }

            override fun onEventDecrypted(event: org.matrix.android.sdk.api.session.events.model.Event, clearEvent: org.matrix.android.sdk.api.util.JsonDict) {
                // Ignore decryption events
            }

            override fun onEventDecryptionError(event: org.matrix.android.sdk.api.session.events.model.Event, cryptoError: org.matrix.android.sdk.api.session.crypto.MXCryptoError) {
                // Ignore decryption errors
            }

            override fun onLiveToDeviceEvent(event: org.matrix.android.sdk.api.session.events.model.Event) {
                // Here we receive to-device events!
                Timber.d("onLiveToDeviceEvent called with event type: ${event.type}")

                if (!isRunning) {
                    Timber.d("PTT receiver not running, ignoring event")
                    return
                }

                if (event.type == "m.ptt.audio") {
                    Timber.d("Received PTT audio to-device event!")
                    val content = event.content ?: run {
                        Timber.w("Event content is null")
                        return
                    }

                    val eventRoomId = content["room_id"] as? String
                    val senderId = content["sender_id"] as? String
                    Timber.d("Event details: roomId=$eventRoomId, senderId=$senderId, myUserId=${session.myUserId}")

                    // Filter events for correct room and ignore my own messages
                    if (eventRoomId == roomId && senderId != session.myUserId) {
                        val audioData = content["audio_data"] as? String
                        val encoding = content["encoding"] as? String

                        Timber.d("Audio data length: ${audioData?.length}, encoding: $encoding")

                        if (audioData != null && encoding == "pcm_16bit") {
                            try {
                                val pcm = Base64.decode(audioData, Base64.NO_WRAP)
                                Timber.d("Playing PTT audio chunk (${pcm.size} bytes)")
                                
                                // Simple solution: use simple function directly
                                playPcmData(pcm)
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to decode/play PTT to-device chunk")
                            }
                        } else {
                            Timber.w("Invalid audio data or encoding: audioData=${audioData?.length}, encoding=$encoding")
                        }
                    } else {
                        Timber.d("Event filtered out: room match=${eventRoomId == roomId}, sender match=${senderId != session.myUserId}")
                    }
                } else {
                    Timber.d("Ignoring non-PTT event type: ${event.type}")
                }
            }
        }
        
        // Register listener
        toDeviceListener?.let { listener ->
            Timber.d("Registering to-device listener with Matrix SDK")
            session.eventStreamService().addEventStreamListener(listener)
            Timber.d("PTT to-device listener registered successfully for room: $roomId")
        } ?: run {
            Timber.e("Failed to create to-device listener!")
        }
    }



    private fun playPcmDataInternal(data: ByteArray) {
        Timber.d("playPcmDataInternal called with ${data.size} bytes")

        // Comprehensive protection
        try {
            // Complete check before starting
            if (!isRunning) {
                Timber.w("Receiver not running, ignoring audio data")
                return
            }

            if (audioTrack == null) {
                Timber.d("Initializing AudioTrack for Matrix PTT")

                // Optimized settings for speed and clarity
                val sampleRate = 16000
                val bufferSize = 2048 // Proven optimal size for clarity

                Timber.d("AudioTrack: sampleRate=$sampleRate, bufferSize=$bufferSize")

                // Simple and effective AudioManager setup
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true

                // Setup communication device for newer versions
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        audioManager.availableCommunicationDevices.firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        }?.let {
                            audioManager.setCommunicationDevice(it)
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to set communication device")
                    }
                }

                // Create AudioTrack with improved retry logic
                var retryCount = 0
                var trackCreated = false

                while (retryCount < 3 && !trackCreated) {
                    try {
                        Timber.d("Creating AudioTrack (attempt ${retryCount + 1}/3)")

                        audioTrack = AudioTrack.Builder()
                                .setAudioAttributes(
                                        AudioAttributes.Builder()
                                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                                .build()
                                )
                                .setAudioFormat(
                                        AudioFormat.Builder()
                                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                                .setSampleRate(sampleRate)
                                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                                .build()
                                )
                                .setBufferSizeInBytes(bufferSize)
                                .setTransferMode(AudioTrack.MODE_STREAM)
                                .build()

                        if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                            trackCreated = true
                            Timber.d("✅ AudioTrack created successfully")
                        } else {
                            Timber.w("⚠️ AudioTrack creation failed, state: ${audioTrack?.state}")
                            audioTrack?.release()
                            audioTrack = null
                            retryCount++
                            if (retryCount < 3) Thread.sleep(50) // انتظار أقصر
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "💥 AudioTrack creation failed")
                        audioTrack?.release()
                        audioTrack = null
                        retryCount++
                        if (retryCount < 3) Thread.sleep(50)
                    }
                }

                if (!trackCreated) {
                    Timber.e("❌ Failed to initialize AudioTrack after 3 attempts!")
                    return
                }

                // 🎚️ ضبط مستوى الصوت
                try {
                    audioTrack?.setVolume(1.0f)
                    @Suppress("DEPRECATION")
                    audioTrack?.setStereoVolume(1.0f, 1.0f)
                } catch (e: Exception) {
                    Timber.w(e, "⚠️ Failed to set volume")
                }

                audioTrack?.play()
                Timber.d("🔊 AudioTrack started successfully")
            }

            // 🚨 فحص شامل للـ AudioTrack قبل كل عملية
            val currentTrack = audioTrack
            if (currentTrack == null) {
                Timber.e("❌ AudioTrack is null, cannot play audio")
                return
            }

            if (currentTrack.state != AudioTrack.STATE_INITIALIZED) {
                Timber.e("❌ AudioTrack not initialized, state: ${currentTrack.state}")
                return
            }

            // 🚨 فحص حالة التشغيل مع حماية من null
            try {
                if (currentTrack.playState == AudioTrack.PLAYSTATE_STOPPED) {
                    Timber.d("🔄 AudioTrack stopped, restarting...")
                    currentTrack.play()
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ Failed to check/restart AudioTrack")
                return
            }

            Timber.d("📝 Writing ${data.size} bytes to AudioTrack")

            // 🚀 كتابة مبسطة وفعالة - مثل الكود المحسن
            var offset = 0
            var remaining = data.size

            while (remaining > 0) {
                try {
                    val safeTrack = audioTrack
                    if (safeTrack == null || safeTrack.state != AudioTrack.STATE_INITIALIZED) {
                        Timber.e("❌ AudioTrack became invalid during write")
                        break
                    }

                    val written = safeTrack.write(data, offset, remaining)
                    if (written <= 0) break
                    offset += written
                    remaining -= written
                } catch (e: Exception) {
                    Timber.e(e, "💥 Write error")
                    break
                }
            }

            if (offset > 0) {
                Timber.d("✅ Successfully wrote $offset bytes to AudioTrack")
            }

        } catch (e: Exception) {
            Timber.e(e, "💥 Critical error in playPcmDataInternal")
            
            // 🚨 تنظيف في حالة الخطأ
            try {
                cleanupAudioTrack()
            } catch (cleanupError: Exception) {
                Timber.e(cleanupError, "❌ Error during cleanup")
            }
        }
    }

    /**
     * 🧹 تنظيف آمن ومحمي من الأخطاء للـ AudioTrack
     */
    private fun cleanupAudioTrack() {
        Timber.d("🧹 Starting safe AudioTrack cleanup")
        
        try {
            // 🚨 احفظ reference مؤقت لتجنب null pointer
            val trackToClean = audioTrack
            audioTrack = null // ضع null أولاً لمنع استخدامه

            if (trackToClean != null) {
                Timber.d("🧹 Cleaning up AudioTrack")

                // 🚨 إيقاف آمن
                try {
                    if (trackToClean.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        trackToClean.stop()
                        Timber.d("✅ AudioTrack stopped")
                    }
                } catch (e: Exception) {
                    Timber.w(e, "⚠️ Error stopping AudioTrack")
                }

                // 🚨 تحرير آمن
                try {
                    trackToClean.release()
                    Timber.d("✅ AudioTrack released")
                } catch (e: Exception) {
                    Timber.w(e, "⚠️ Error releasing AudioTrack")
                }
            }

            // تنظيف AudioManager settings
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.mode = AudioManager.MODE_NORMAL
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
                Timber.d("✅ AudioManager reset to normal")
            } catch (e: Exception) {
                Timber.w(e, "⚠️ Error resetting AudioManager")
            }

        } catch (e: Exception) {
            Timber.e(e, "❌ Critical error during AudioTrack cleanup")
            // تأكد من null حتى في حالة الخطأ الشديد
            audioTrack = null
        }
        
        Timber.d("🧹 AudioTrack cleanup completed")
    }

    // 🚨 دالة محمية لتشغيل الصوت مع حماية شاملة
    private fun playPcmData(data: ByteArray) {
        Timber.d("🔊 playPcmData called with ${data.size} bytes")
        
        // 🚨 فحص شامل قبل البدء
        try {
            // فحص حالة الـ receiver
            if (!isRunning) {
                Timber.w("⚠️ Receiver stopped, ignoring audio data")
                return
            }

            // فحص البيانات
            if (data.isEmpty()) {
                Timber.w("⚠️ Empty audio data")
                return
            }

            if (data.size > 512 * 1024) {
                Timber.w("⚠️ Audio data too large: ${data.size} bytes")
                return
            }

            // 🚨 تشغيل محمي مع synchronized
            synchronized(this) {
                if (isRunning) { // فحص مجدد داخل synchronized
                    playPcmDataInternal(data)
                } else {
                    Timber.w("⚠️ Receiver stopped during synchronized block")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "💥 Critical error in playPcmData")
            
            // 🚨 تنظيف طارئ مع حماية
            try {
                cleanupAudioTrack()
            } catch (cleanupError: Exception) {
                Timber.e(cleanupError, "❌ Emergency cleanup error")
            }
        }
    }



    fun stop() {
        Timber.d("🛑 Stopping MatrixPttReceiver...")
        
        // 🚨 إيقاف المعالجة أولاً
        isRunning = false

        // 🧹 إزالة to-device listener بأمان
        toDeviceListener?.let { listener ->
            try {
                session.eventStreamService().removeEventStreamListener(listener)
                Timber.d("🧹 PTT to-device listener removed")
            } catch (e: Exception) {
                Timber.w(e, "⚠️ Error removing to-device listener")
            }
        }
        toDeviceListener = null

        // 🚨 تنظيف AudioTrack بأمان تام
        try {
            cleanupAudioTrack()
        } catch (e: Exception) {
            Timber.e(e, "❌ Critical error during AudioTrack cleanup in stop()")
        }

        // 🚨 إلغاء scope بأمان
        try {
            scope.cancel()
            Timber.d("🧹 Coroutine scope cancelled")
        } catch (e: Exception) {
            Timber.w(e, "⚠️ Error cancelling scope")
        }

        Timber.d("🛑 MatrixPttReceiver stopped safely")
    }
}
