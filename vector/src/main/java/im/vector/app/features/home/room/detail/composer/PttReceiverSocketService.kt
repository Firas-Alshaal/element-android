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
import android.media.audiofx.AcousticEchoCanceler
import kotlin.math.pow
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import io.socket.engineio.parser.Base64
import im.vector.app.features.voice.NetworkAudioQualityManager
import im.vector.app.features.voice.OpusPttCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import timber.log.Timber
import java.io.BufferedInputStream
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

class PttTcpReceiverService : Service() {

    private val serverPort = 8008 // Sender port to connect to
    private val sampleRate = 16000 // ✅ عودة لـ 16kHz للاستقرار
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
    private val audioBufferSize = 2048 // ✅ تقليل للوضوح والسرعة

    private val jitterBuffer = LinkedBlockingQueue<ByteArray>(50)


    // ✅ إضافة متغيرات متكيفة مع الشبكة
    private var adaptiveSampleRate = sampleRate
    private var adaptiveBufferSize = audioBufferSize
    private var bytesPerTick = (sampleRate / 50) * 2

    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var playbackJob: Job? = null

    private var currentRoomId: String? = null
    private var myUserId: String? = null
    private var senderIp: String? = null
    private var currentSpeakerId: String? = null


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentRoomId = intent?.getStringExtra("roomId")
        myUserId = intent?.getStringExtra("myUserId")
        senderIp = intent?.getStringExtra("senderIp")
        currentSpeakerId = intent?.getStringExtra("speakerId")

        if (myUserId == null || currentRoomId == null || senderIp == null) {
            Timber.w("Missing parameters, stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        Timber.d("PttTcpReceiverService started")

        // ضبط متكيفة الشبكة
        val networkProfile = NetworkAudioQualityManager.getOptimalQualityProfile(this)
        adaptiveSampleRate = networkProfile.sampleRate
        adaptiveBufferSize = (audioBufferSize * networkProfile.bufferSizeMultiplier).toInt()
        bytesPerTick = (adaptiveSampleRate / 50) * 2 // 20ms ديناميكي

        Timber.d("🎯 TCP Receiver: Using adaptive settings - SampleRate: ${adaptiveSampleRate}Hz, BufferSize: ${adaptiveBufferSize}B, bytesPerTick=$bytesPerTick")

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
                    val input = BufferedInputStream(socket.getInputStream(), adaptiveBufferSize)
                    Timber.d("Starting audio reception from $currentSpeakerId in room $currentRoomId")

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
                                            .setSampleRate(adaptiveSampleRate)
                                            .setChannelMask(channelConfig)
                                            .build()
                            )
                            .setBufferSizeInBytes(adaptiveBufferSize * 2)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build()

                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        audioManager.availableCommunicationDevices.firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        }?.let { audioManager.setCommunicationDevice(it) }
                    }

                    audioTrack.setVolume(0.8f)


                    if (AcousticEchoCanceler.isAvailable()) {
                        try {
                            val aec = AcousticEchoCanceler.create(audioTrack.audioSessionId)
                            aec?.enabled = true
                            Timber.d("🎧 AEC enabled on receiver")
                        } catch (e: Exception) {
                            Timber.w(e, "AEC init failed")
                        }
                    }
                    // setPlaybackRate ليس ضروري طالما sampleRate مضبوط في AudioFormat

                    audioTrack.play()
                    Timber.d("AudioTrack state: ${audioTrack.state}, playState: ${audioTrack.playState}")

                    val buffer = ByteArray(adaptiveBufferSize)

                    // حضر التوكن مرة واحدة
                    val tokenBytes = (currentRoomId!!.hashCode().toString() + ":").toByteArray()
                    val tokenLength = tokenBytes.size
                    Timber.d("Expected token bytes length: $tokenLength")

                    var receivedPackets = 0
                    var validPackets = 0
                    var noDataCounter = 0

                    // Consumer: يسحب من jitterBuffer كل 20ms ويقسّم الإطارات الكبيرة
                    playbackJob = scope.launch {
                        var currentFrame: ByteArray? = null
                        var offset = 0
                        val silence = ByteArray(bytesPerTick) // 20ms صمت مضبوط

                        while (isRunning && audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            val tickStart = System.nanoTime()

                            if (currentFrame == null) {
                                currentFrame = jitterBuffer.poll(20, TimeUnit.MILLISECONDS)
                                offset = 0
                            }

                            if (currentFrame == null) {
                                // لا يوجد إطار جاهز → صمت قصير لتفادي التقطيع
                                audioTrack.write(silence, 0, silence.size)
                            } else {
                                val remaining = currentFrame.size - offset
                                val toWrite = min(bytesPerTick, remaining)
                                if (toWrite > 0) {
                                    audioTrack.write(currentFrame, offset, toWrite)
                                    offset += toWrite
                                }
                                if (offset >= currentFrame.size) {
                                    currentFrame = null
                                    offset = 0
                                }
                            }

                            // حافظ على cadence ~20ms
                            val elapsedMs = (System.nanoTime() - tickStart) / 1_000_000
                            if (elapsedMs < 20) delay(20 - elapsedMs)
                        }
                    }

                    Timber.d("Starting audio reception loop - waiting for data...")

                    while (isRunning && !socket.isClosed) {
                        try {
                            val read = input.read(buffer)
                            if (read > 0) {
                                receivedPackets++

                                var offset = 0
                                while (offset + tokenLength < read) {
                                    // تحقق من التوكن عند هذا الموضع
                                    var tokenMatches = true
                                    for (i in 0 until tokenLength) {
                                        if (buffer[offset + i] != tokenBytes[i]) {
                                            tokenMatches = false
                                            break
                                        }
                                    }

                                    if (tokenMatches) {
                                        // طول البيانات الصوتية بعد التوكن
                                        val remaining = read - (offset + tokenLength)
                                        if (remaining >= bytesPerTick) {
                                            // ناخذ frame كامل بحجم bytesPerTick (20ms)
                                            val audioFrame = buffer.copyOfRange(
                                                    offset + tokenLength,
                                                    offset + tokenLength + bytesPerTick
                                            )

                                            if (!jitterBuffer.offer(audioFrame)) {
                                                jitterBuffer.poll() // أسقط الأقدم
                                                jitterBuffer.offer(audioFrame)
                                            }

                                            validPackets++
                                            offset += tokenLength + bytesPerTick
                                        } else {
                                            // البيانات أقل من frame → نخليها لقراءة قادمة
                                            break
                                        }
                                    } else {
                                        // لو التوكن غير متطابق نزحلق بايت ونكمل
                                        offset++
                                    }
                                }

                                if (validPackets % 25 == 0) {
                                    Timber.d("🎶 Receiver: processed $validPackets valid frames so far")
                                }

                            } else if (read == 0) {
                                noDataCounter++
                                if (noDataCounter > 30) {
                                    Timber.w("No data for too long - connection may be stale")
                                    break
                                }
                                Thread.sleep(100)
                            } else {
                                Timber.w("Connection closed by sender (read=$read)")
                                break
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Error during audio reception loop")
                            break
                        }
                    }

                    Timber.d("Audio session ended - total packets: $receivedPackets, valid: $validPackets")

                    try { audioTrack.stop() } catch (_: Throwable) {}
                    try { audioTrack.release() } catch (_: Throwable) {}
                    try { socket.close() } catch (_: Throwable) {}

                } catch (e: Exception) {
                    Timber.e("TCP Receiver error: ${e.message}")
                } finally {
                    isRunning = false
                    playbackJob?.cancel()
                    jitterBuffer.clear()
                    stopSelf()
                }
            } catch (e: Exception) {
                Timber.e("Connection error: ${e.message}")
                isRunning = false
                playbackJob?.cancel()
                jitterBuffer.clear()
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        playbackJob?.cancel()
        scope.cancel()
        jitterBuffer.clear()
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
    private val frameChannel = Channel<PttFrame>(capacity = 128)
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var audioTrack: AudioTrack? = null
    private var isRunning = false
    private var toDeviceListener: org.matrix.android.sdk.api.session.LiveEventListener? = null

    // ✅ إضافة AdaptiveJitterBuffer للمستقبل الأساسي
    private val adaptiveJitterBuffer = AdaptiveJitterBuffer(context, minBufferFrames = 1, maxBufferFrames = 4)

    // ✅ Opus decoder مع PLC للقضاء على packet loss
    private var opusDecoder: OpusPttCodec.OpusDecoder? = null

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

        // ✅ تهيئة Opus decoder مع PLC
        opusDecoder = OpusPttCodec.OpusDecoder(context).apply {
            if (!initialize()) {
                Timber.w("⚠️ Failed to initialize Opus decoder - will use PCM fallback")
                opusDecoder = null
            }
        }

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
            opusDecoder?.release() // تنظيف عند الفشل
            opusDecoder = null
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
                    val content = event.content ?: return
                    val eventRoomId = content["room_id"] as? String
                    val senderId = content["sender_id"] as? String
                    if (eventRoomId == roomId && senderId != session.myUserId) {
                        val encoding = content["encoding"] as? String
                        val base64 = content["audio_data"] as? String ?: return
                        val seq = (content["seq"] as? Number)?.toLong() ?: 0L
                        val ts = (content["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()
                        val frameMs = (content["frame_ms"] as? Number)?.toInt() ?: 20

                        Timber.d("🔊 Processing PTT frame: seq=$seq, encoding=$encoding, size=${base64.length}")

                        @Suppress("UNUSED_VARIABLE")
                        val fecEnabled = content["fec_enabled"] as? Boolean ?: false

                        // ✅ معالجة Opus + FEC أو PCM
                        val finalPcm = when (encoding) {
                            "pcm_16bit" -> {
                                val pcmData = Base64.decode(base64, Base64.NO_WRAP)
                                Timber.d("🔍 DEBUG: Received PCM data size: ${pcmData.size}")

                                // ✅ فحص البيانات المشوهة
                                val shorts = ShortArray(pcmData.size / 2)
                                java.nio.ByteBuffer.wrap(pcmData).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                        .asShortBuffer().get(shorts)

                                val maxAmplitude = shorts.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                                val avgAmplitude = shorts.map { kotlin.math.abs(it.toInt()) }.average().toInt()

                                Timber.d("🔍 DEBUG: PCM samples: ${shorts.take(5).joinToString()}")
                                Timber.d("🔍 DEBUG: PCM range: ${shorts.minOrNull()} to ${shorts.maxOrNull()}")
                                Timber.d("🔍 DEBUG: PCM analysis - max=$maxAmplitude, avg=$avgAmplitude")

                                // ✅ تصحيح البيانات المشوهة إذا لزم الأمر
                                val correctedData = if (maxAmplitude > 25000 || avgAmplitude > 10000) {
                                    Timber.w("⚠️ WARNING: Received distorted audio data - applying correction")
                                    val correctedShorts = correctDistortedAudio(shorts)
                                    val correctedBytes = ByteArray(correctedShorts.size * 2)
                                    java.nio.ByteBuffer.wrap(correctedBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                            .asShortBuffer().put(correctedShorts)
                                    correctedBytes
                                } else {
                                    pcmData
                                }

                                val result = ensure20msPcm16(correctedData)
                                Timber.d("🔍 DEBUG: Final PCM size: ${result.size}")
                                result
                            }
                            "opus_fec" -> {
                                // ✅ إضافة معالجة Opus FEC
                                val fecData = Base64.decode(base64, Base64.NO_WRAP)
                                val shorts = opusDecoder?.decodeFrame(fecData)
                                        ?: opusDecoder?.decodeFrame(null) // PLC عند الفشل
                                        ?: ShortArray(320) // صمت
                                val bytes = ByteArray(shorts.size * 2)
                                java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                        .asShortBuffer().put(shorts)
                                ensure20msPcm16(bytes)
                            }
                            else -> {
                                // ✅ إضافة else clause للتعامل مع الترميزات غير المعروفة
                                Timber.w("⚠️ Unknown encoding: $encoding, treating as PCM")
                                val pcmData = Base64.decode(base64, Base64.NO_WRAP)
                                ensure20msPcm16(pcmData)
                            }

                        }

                        if (finalPcm.isNotEmpty()) {
                            frameChannel.trySend(PttFrame(seq, ts, frameMs, finalPcm))

                            if (encoding == "opus_fec" && seq % 10 == 1L) {
                                Timber.d("🔊 Received Enhanced+FEC frame #$seq (${finalPcm.size}B)")
                            }
                        }
                    } else {
                        Timber.d("🔊 Ignoring PTT event: roomId mismatch or self-sent")
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

    private suspend fun runPlaybackLoop() {
        elevateAudioThreadPriority()

        val sampleRate = 16000
        val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        )
//        val trackBuf = max(minBuf, 4096)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = true

        val maxVolM = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolM, 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }?.let {
                audioManager.setCommunicationDevice(it)
            }
        }

        val focusAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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

        val track = AudioTrack.Builder()
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
                .setBufferSizeInBytes(minBuf * 8)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

        audioTrack = track

//        runCatching {
//            if (NoiseSuppressor.isAvailable()) {
//                val ns = NoiseSuppressor.create(track.audioSessionId)
//                ns?.enabled = true
//                Timber.d("🎵 Noise Suppressor enabled for receiver")
//            }
//        }

//        if (NoiseSuppressor.isAvailable()) {
//            val ns = NoiseSuppressor.create(track.audioSessionId)
//            ns?.enabled = true
//            Timber.d("🎵 Noise Suppressor enabled for receiver")
//        }
//        if (AutomaticGainControl.isAvailable()) {
//            val agc = AutomaticGainControl.create(track.audioSessionId)
//            agc?.enabled = true
//            Timber.d("🎵 Automatic Gain Control enabled for receiver")
//        }
//        if (AcousticEchoCanceler.isAvailable()) {
//            val aec = AcousticEchoCanceler.create(track.audioSessionId)
//            aec?.enabled = true
//            Timber.d("🎵 Acoustic Echo Canceler enabled for receiver")
//        }

//        val loudness = try {
//            android.media.audiofx.LoudnessEnhancer(track.audioSessionId).apply {
//                setTargetGain(500) // ✅ تقليل من 2000 إلى 1000
//                enabled = true
//            }
//        } catch (e: Exception) {
//            Timber.w(e, "LoudnessEnhancer not available")
//            null
//        }
        try {
            track.setVolume(0.8f) // ✅ تقليل الصوت قليلاً لتجنب التشويش
            @Suppress("DEPRECATION")
            track.setStereoVolume(0.8f, 0.8f)
        } catch (e: Exception) {
            Timber.w(e, "⚠️ Failed to set volume")
        }

//        Timber.d("🔁 Playback loop started with AdaptiveJitterBuffer (trackBuf=$trackBuf)")

        var started = false
        var framesReceived = 0
        val framePeriod = 20L
        var lastArrival = SystemClock.elapsedRealtime()
        var underrunCount = 0

        while (isRunning) {
            val incoming = frameChannel.tryReceive().getOrNull()

            if (incoming != null) {
                adaptiveJitterBuffer.addFrame(incoming.pcm, incoming.ts, incoming.seq)
                val now = SystemClock.elapsedRealtime()
                val arrivalJitter = abs((now - lastArrival) - framePeriod)
                adaptiveJitterBuffer.adaptJitter(arrivalJitter)
                lastArrival = now
                framesReceived++

                // ✅ إصلاح: إضافة debugging للبيانات الواردة
                val shorts = ShortArray(incoming.pcm.size / 2)
                java.nio.ByteBuffer.wrap(incoming.pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer().get(shorts)
                Timber.d("📥 Frame #${incoming.seq} added to buffer (total: $framesReceived)")
                Timber.d("🔍 DEBUG: Frame data - size: ${incoming.pcm.size}, first samples: ${shorts.take(5).joinToString()}")
            }

            // 🚀 التشغيل بعد ما يتجمع buffer كافي
            if (!started && adaptiveJitterBuffer.isReady()) {
                track.play()
                started = true
                Timber.d("🚀 Playback started after buffering $framesReceived frames")
            }


            if (started) {
                val playFrame = adaptiveJitterBuffer.getNextFrame()
                if (playFrame != null) {
                    val written = track.write(playFrame, 0, playFrame.size)
                    if (written <= 0) {
                        underrunCount++
                        Timber.w("⚠️ AudioTrack write failed: $written (underrun #$underrunCount)")

                        // ✅ إعادة تشغيل AudioTrack عند الفشل المتكرر
                        if (underrunCount > 3) {
                            try {
                                track.stop()
                                Thread.sleep(50) // انتظار قصير
                                track.play()
                                underrunCount = 0
                                Timber.d("🔄 AudioTrack restarted after multiple underruns")
                            } catch (e: Exception) {
                                Timber.e(e, "❌ Failed to restart AudioTrack")
                            }
                        }
                    } else {
                        if (underrunCount > 0) {
                            underrunCount = 0 // إعادة تعيين العداد عند النجاح
                        }
                        Timber.d("✅ Written $written bytes to AudioTrack")
                    }
                } else {
                    // ✅ إضافة صمت عند عدم وجود بيانات لمنع underrun
                    val silence = ByteArray(640)
                    val written = track.write(silence, 0, silence.size)
                    if (written > 0) {
                        Timber.d("🔇 Playing silence to prevent underrun")
                    }
                }
                delay(framePeriod)
            } else {
                delay(5)
            }


            if (framesReceived % 10 == 0) {
                val stats = adaptiveJitterBuffer.getStats()
                Timber.d("📊 Jitter stats: size=${stats.currentSize}, target=${stats.targetSize}, jitter=${stats.jitterMs}ms")
            }
        }

        try {
            track.stop()
        } catch (_: Exception) {
        }
        try {
            track.release()
        } catch (_: Exception) {
        }
//        runCatching { loudness?.enabled = false }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        Timber.d("⏹️ Playback loop ended")
    }

    private fun ensure20msPcm16(bytes: ByteArray): ByteArray {
        // 20ms @16kHz mono 16-bit = 320 samples = 640 bytes
        val targetBytes = 640
        val result = when {
            bytes.size == targetBytes -> bytes
            bytes.size > targetBytes -> bytes.copyOf(targetBytes)
            else -> {
                val out = ByteArray(targetBytes)
                System.arraycopy(bytes, 0, out, 0, bytes.size)
                // بقية البايتات تبقى صفراً (صمت)
                out
            }
        }

        // ✅ إصلاح: تطبيق معالجة نقية للقضاء على التشويش
        return applyPureReceiverProcessing(result)
    }

    private fun applyPureReceiverProcessing(audioData: ByteArray): ByteArray {
        val shorts = ShortArray(audioData.size / 2)
        val buffer = java.nio.ByteBuffer.wrap(audioData).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        buffer.get(shorts)

        // ✅ تحليل شامل للبيانات
        val maxAmplitude = shorts.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
        val avgAmplitude = shorts.map { kotlin.math.abs(it.toInt()) }.average().toInt()
        val rmsAmplitude = kotlin.math.sqrt(shorts.map { it.toDouble() * it.toDouble() }.average()).toInt()

        Timber.d("🔍 Pure receiver analysis: max=$maxAmplitude, avg=$avgAmplitude, rms=$rmsAmplitude")

        // ✅ تطبيق معالجة نقية حسب نوع البيانات
        when {
            maxAmplitude < 50 -> {
                // بيانات ضعيفة جداً - تطبيق gain بسيط جداً
                applyReceiverMinimalGain(shorts, 1.1f)
                Timber.d("🔊 Receiver: Weak audio - applied minimal gain")
            }
            maxAmplitude > 15000 -> {
                // بيانات قوية - تطبيق soft clipping فقط
                applyReceiverSoftClipping(shorts, maxLevel = 14000)
                Timber.d("🔊 Receiver: Strong audio - applied soft clipping")
            }
            avgAmplitude < 200 -> {
                // بيانات متوسطة ضعيفة - تطبيق gain بسيط
                applyReceiverMinimalGain(shorts, 1.05f)
                Timber.d("🔊 Receiver: Medium-weak audio - applied minimal gain")
            }
            else -> {
                // بيانات جيدة - لا حاجة لتعديل
                Timber.d("�� Receiver: Good audio - no processing needed")
            }
        }

        // ✅ تطبيق noise reduction لطيف
        applyReceiverNoiseReduction(shorts, threshold = 30)

        val result = ByteArray(shorts.size * 2)
        val resultBuffer = java.nio.ByteBuffer.wrap(result).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        resultBuffer.put(shorts)

        return result
    }

    // ✅ تطبيق gain بسيط جداً في المستقبل
    private fun applyReceiverMinimalGain(samples: ShortArray, factor: Float) {
        for (i in samples.indices) {
            val sample = samples[i].toFloat()
            val amplified = sample * factor

            // ✅ تطبيق soft limiting لطيف
            val limited = when {
                amplified > 12000 -> 12000f + (amplified - 12000f) * 0.1f
                amplified < -12000 -> -12000f + (amplified + 12000f) * 0.1f
                else -> amplified
            }

            samples[i] = limited.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
        }
    }

    // ✅ تطبيق soft clipping لطيف في المستقبل
    private fun applyReceiverSoftClipping(samples: ShortArray, maxLevel: Int) {
        for (i in samples.indices) {
            val sample = samples[i].toFloat()
            val absSample = kotlin.math.abs(sample)

            if (absSample > maxLevel) {
                val sign = if (sample >= 0) 1f else -1f
                val normalized = absSample / maxLevel
                val clipped = kotlin.math.tanh(normalized * 0.7f) * maxLevel // ✅ تقليل tanh
                samples[i] = (clipped * sign).toInt().toShort()
            }
        }
    }

    // ✅ تطبيق noise reduction لطيف في المستقبل
    private fun applyReceiverNoiseReduction(samples: ShortArray, threshold: Int) {
        for (i in samples.indices) {
            val sample = samples[i].toFloat()
            val absSample = abs(sample)

            if (absSample < threshold) {
                val fadeFactor = (absSample / threshold.toFloat()).pow(0.3f)
                samples[i] = (sample * fadeFactor).toInt().toShort()
            }
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

            // 🚨 فحص شامل للـ AudioTrack قبل كل عملية
            val currentTrack = audioTrack
            if (currentTrack == null) {
                Timber.e("❌ AudioTrack is null, cannot play audio")
                return
            }

            // 🚨 فحص حالة التشغيل مع حماية من null
            if (currentTrack.playState == AudioTrack.PLAYSTATE_STOPPED) {
                Timber.d("🔄 AudioTrack stopped, restarting...")
                currentTrack.play()
            }

            Timber.d("📝 Writing ${data.size} bytes to AudioTrack")

            // 🚀 كتابة مبسطة وفعالة - مثل الكود المحسن
            var offset = 0
            var remaining = data.size

            while (remaining > 0) {
                try {
                    val written = currentTrack.write(data, offset, remaining)
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

        runCatching { opusDecoder?.release() }
        opusDecoder = null

        Timber.d("🛑 MatrixPttReceiver stopped safely")
    }
}
/*
private const val CHANNEL_ID = "enhanced_ptt_receiver_channel"

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

*/
/**
 * Create PTT notification for foreground service
 *//*


private fun createPttNotification(): Notification {
    return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Enhanced PTT Active")
            .setContentText("Listening for PTT messages in room")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
}
*/
