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
import android.media.AudioFocusRequest
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import timber.log.Timber
import java.io.BufferedInputStream
import java.net.Socket
import kotlin.math.max

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

private fun elevateAudioThreadPriority() {
    try {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
    } catch (_: Exception) {
    }
}

data class PttFrame(
        val seq: Long,
        val ts: Long,
        val frameMs: Int,
        val pcm: ByteArray
)

class MatrixPttReceiver(
        private val context: Context,
        private val session: Session,
        private val roomId: String
) {
    private val frameChannel = Channel<PttFrame>(capacity = 64)
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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

        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }

        Timber.d("Starting MatrixPttReceiver (to-device) for room: $roomId")


        while (true) {
            val r = frameChannel.tryReceive()
            if (r.isSuccess) continue else break
        }

        try {
            setupToDeviceListener()

            scope.launch {
                runPlaybackLoop()
            }
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

                /* if (event.type == "m.ptt.audio") {
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
                 }*/
                if (event.type == "m.ptt.audio") {
                    val content = event.content ?: return
                    val eventRoomId = content["room_id"] as? String
                    val senderId = content["sender_id"] as? String
                    if (eventRoomId == roomId && senderId != session.myUserId) {
                        val encoding = content["encoding"] as? String
                        val base64 = content["audio_data"] as? String ?: return
                        val seq = (content["seq"] as? Number)?.toLong() ?: 0L
                        val ts = (content["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()
                        val frameMs = (content["frame_ms"] as? Number)?.toInt() ?: 200

                        if (encoding == "pcm_16bit") {
                            val pcm = Base64.decode(base64, Base64.NO_WRAP)
                            // ادفع إلى القناة (عدم الحجب)
                            frameChannel.trySend(PttFrame(seq, ts, frameMs, pcm))
                        }
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

    private suspend fun runPlaybackLoop() {
        elevateAudioThreadPriority()

        val sampleRate = 16000
        val min = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        )
        // بوفر تشغيل أوسع (~300ms) بدل 2048
        val trackBuf = max(min, 320 /*10ms*/ * 30)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_NORMAL               // لا نستخدم مسار المكالمات هنا
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = true

// ارفع مسار الموسيقى لأنه يتوافق مع USAGE_MEDIA
        val maxVolM = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolM, 0)

// فوكس صوت (اختياري لكنه مفيد)
        val focusAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)                 // MEDIA بدل VOICE_COMMUNICATION
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        val focusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(focusAttrs)
                    .setOnAudioFocusChangeListener { }
                    .build()
        } else null
        val focusGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }

        if (focusGranted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Timber.w("⚠️ Audio focus not granted")
        }

// 2) أنشئ AudioTrack بمسار MEDIA
        val track = AudioTrack.Builder()
                .setAudioAttributes(
                        AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)         // هنا التبديل المهم
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
                .setBufferSizeInBytes(trackBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

        audioTrack = track

// 3) LoudnessEnhancer (لو متاح)
        val loudness = try {
            android.media.audiofx.LoudnessEnhancer(track.audioSessionId).apply {
                setTargetGain(1500) // ≈ +15 dB
                enabled = true
            }
        } catch (e: Exception) {
            Timber.w(e, "LoudnessEnhancer not available")
            null
        }

// 4) ضبط مستوى AudioTrack للأقصى (احتياطي)
        try {
            track.setVolume(1.0f)
            @Suppress("DEPRECATION")
            track.setStereoVolume(1.0f, 1.0f)
        } catch (e: Exception) {
            Timber.w(e, "Failed to set track volume")
        }

        // Jitter buffer: نرتب حسب seq
        val pq = java.util.PriorityQueue<PttFrame> { a, b ->
            when {
                a.seq != b.seq -> a.seq.compareTo(b.seq)
                else -> a.ts.compareTo(b.ts) // tie-breaker
            }
        }
        var started = false
        var bufferedMs = 0
        val prebufferMs = 180   // انتظر ~180ms قبل البدء
        val maxBufferedMs = 400 // لو زاد التراكم، نسقط أقدم إطار

        Timber.d("🔁 Playback loop started (prebuffer=${prebufferMs}ms, trackBuf=$trackBuf)")

        try {
            audioTrack?.setVolume(1.0f) // API >= 21
            @Suppress("DEPRECATION")
            audioTrack?.setStereoVolume(1.0f, 1.0f) // للأجهزة الأقدم
        } catch (e: Exception) {
            Timber.w(e, "⚠️ Failed to force max volume")
        }

        while (isRunning) {
            // استلم إطار أو انتظر قليلاً لتتيح التشغيل
            val frame = withTimeoutOrNull(30L) { frameChannel.receive() }
            if (frame != null) {
                pq.add(frame)
                bufferedMs += frame.frameMs
            }

            // ابدأ التشغيل بعد التراكم المطلوب
            if (!started && bufferedMs >= prebufferMs && pq.isNotEmpty()) {
                try {
                    track.play()
                } catch (_: Exception) {
                }
                started = true
                Timber.d("▶️ Playback started after prebuffer ${bufferedMs}ms")
            }

            // لو تراكم كبير، اسقط أقدم إطار لتقليل التأخير التراكمي
            while (bufferedMs > maxBufferedMs) {
                val dropped = pq.poll() ?: break
                bufferedMs -= dropped.frameMs
                Timber.d("⏩ Dropped frame seq=${dropped.seq} to reduce latency (bufferedMs=$bufferedMs)")
            }

            // شغّل بالترتيب طالما عندنا بيانات وبدأنا التشغيل
            while (started) {
                val next = pq.poll() ?: break
                bufferedMs -= next.frameMs

                var off = 0
                val data = next.pcm
                while (off < data.size) {
                    val w = track.write(data, off, data.size - off)
                    if (w <= 0) break
                    off += w
                }
            }
        }

        // تنظيف نهائي
        try {
            track.stop()
        } catch (_: Exception) {
        }
        try {
            track.release()
        } catch (_: Exception) {
        }
        runCatching { loudness?.enabled = false }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        Timber.d("⏹️ Playback loop ended")
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
