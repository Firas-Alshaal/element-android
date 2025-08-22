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
import androidx.lifecycle.asFlow
import io.socket.engineio.parser.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.LiveEventListener
import org.matrix.android.sdk.api.util.JsonDict
import timber.log.Timber
import java.io.BufferedInputStream
import java.net.Socket

class PttTcpReceiverService : Service() {

    private val serverPort = 8008 // Port المرسل الذي نتصل به
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
    private val audioBufferSize = 4096 // 🎯 توازن مثالي: يمنع الصدى ويضمن الوضوح

    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var currentRoomId: String? = null
    private var myUserId: String? = null
    private var senderIp: String? = null
    private var currentSpeakerId: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentRoomId = intent?.getStringExtra("roomId")
        myUserId = intent?.getStringExtra("myUserId")
        senderIp = intent?.getStringExtra("senderIp") // 🎯 يجب تمرير IP المرسل
        currentSpeakerId = intent?.getStringExtra("speakerId")

        if (myUserId == null || currentRoomId == null || senderIp == null) {
            Timber.w("⚠️ Missing parameters, stopping service")
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
                    // 🎵 Input stream محسن للوضوح
                    val input = BufferedInputStream(socket.getInputStream(), audioBufferSize)
                    Timber.d("🎙️ Starting audio reception from $currentSpeakerId in room $currentRoomId")

                    // ✅ الخطوة 2: إعداد AudioTrack محسن للجودة والاستجابة
                    val audioTrack = AudioTrack.Builder()
                            .setAudioAttributes(
                                    AudioAttributes.Builder()
                                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION) // 🎯 للمحادثات الصوتية
                                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH) // 🎯 محسن للكلام
                                            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED) // 🎯 ضمان وضوح الصوت
                                            .build()
                            )
                            .setAudioFormat(
                                    AudioFormat.Builder()
                                            .setEncoding(audioEncoding)
                                            .setSampleRate(sampleRate)
                                            .setChannelMask(channelConfig)
                                            .build()
                            )
                            .setBufferSizeInBytes(audioBufferSize) // 🎯 استخدام البافر المحسن
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) // 🎯 أداء للاستجابة السريعة
                            .build()

                    // 🎚️ رفع مستوى الصوت
                    audioTrack.setVolume(1.0f)
                    @Suppress("DEPRECATION")
                    audioTrack.setStereoVolume(1.0f, 1.0f)

                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                    
                    // 🎯 تحسينات لجودة الصوت ومنع الصدى
                    try {
                        // ضبط مستوى صوت متوازن
                        @Suppress("DEPRECATION")
                        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                        val optimalVolume = (maxVolume * 0.85).toInt() // 85% من الحد الأقصى
                        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, optimalVolume, 0)
                        
                        // تحسين إعدادات الصوت للوضوح
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            audioManager.setParameters("noise_suppression=off;echo_cancellation=off") // نتحكم نحن بالـ echo
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "⚠️ Failed to optimize audio settings")
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        audioManager.availableCommunicationDevices.firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        }?.let {
                            audioManager.setCommunicationDevice(it)
                        }
                    }

                    // ✅ تشغيل الصوت
                    audioTrack.play()
                    Timber.d("🎵 AudioTrack state: ${audioTrack.state}, playState: ${audioTrack.playState}")

                    val buffer = ByteArray(audioBufferSize)
                    val expectedToken = currentRoomId?.hashCode()?.toString() + ":"
                    Timber.d("Expected token: '$expectedToken' for room: $currentRoomId")

                    var receivedPackets = 0
                    var validPackets = 0
                    var noDataCounter = 0

                    Timber.d("🎧 Starting audio reception loop - waiting for data...")

                    while (isRunning && !socket.isClosed) {
                        try {
                            val read = input.read(buffer)
                            Timber.d("📊 Read attempt result: $read bytes")

                            if (read > 0) {
                                receivedPackets++

                                // ✅ Token validation مبسط وسريع
                                val tokenBytes = expectedToken.toByteArray()
                                val tokenLength = tokenBytes.size
                                var tokenMatches = true

                                if (read >= tokenLength) {
                                    // مقارنة البايتات مباشرة بدون تحويل إلى String
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
                                        Timber.d("✅ Valid packet #$validPackets: $audioDataLength audio bytes")
                                    }

                                    if (audioDataLength > 0) {
                                        // 🎯 استخراج بيانات الصوت
                                        val audioData = buffer.copyOfRange(tokenLength, read)

                                        // 🎯 تحسين جودة الصوت
                                        val enhancedAudio = enhanceAudioQuality(audioData)

                                        // ✅ كتابة الصوت المحسن
                                        var offset = 0
                                        var remaining = enhancedAudio.size

                                        while (remaining > 0) {
                                            val written = audioTrack.write(enhancedAudio, offset, remaining, AudioTrack.WRITE_BLOCKING)
                                            if (written <= 0) break
                                            offset += written
                                            remaining -= written
                                        }
                                    }

                                    // log كل 10 packets صحيحة للمزيد من التفاصيل
                                    if (validPackets % 10 == 1 && validPackets > 3) {
                                        Timber.d("🎧 Playing packet #$validPackets: $audioDataLength audio bytes")
                                    }
                                } else {
                                    // log أول 3 tokens خاطئة فقط
                                    if (receivedPackets - validPackets <= 3) {
                                        val hexPreview = buffer.take(minOf(10, read)).joinToString("") { "%02x".format(it) }
                                        Timber.w("❌ Invalid token in packet #$receivedPackets: $hexPreview...")
                                    }
                                }



                                if (validPackets <= 3) { // فقط أول 3 packets للتقليل من الـ logs
                                    Timber.d("🔄 Continuing loop... receivedPackets=$receivedPackets, validPackets=$validPackets")
                                }
                            } else if (read == 0) {
                                noDataCounter++
                                Timber.d("📭 No data received (count: $noDataCounter)")
                                if (noDataCounter > 30) { // 3 ثواني - timeout أسرع
                                    Timber.w("⚠️ No data for too long - connection may be stale")
                                    break
                                }
                                Thread.sleep(100) // انتظار قصير
                            } else {
                                Timber.w("❌ Connection closed by sender (read=$read)")
                                break
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "💥 Error during audio reception")
                            break
                        }
                    }

                    Timber.d("🏁 Audio session ended - total packets: $receivedPackets, valid: $validPackets")

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

    /**
     * 🎯 تحسين جودة الصوت وإزالة الضوضاء
     */
    private fun enhanceAudioQuality(audioData: ByteArray): ByteArray {
        // تحويل إلى short array للمعالجة
        val shortArray = ByteArray(audioData.size / 2).mapIndexed { index, _ ->
            (audioData[index * 2 + 1].toInt() shl 8 or (audioData[index * 2].toInt() and 0xFF)).toShort()
        }.toTypedArray()

        // 🎚️ تطبيق gain لرفع مستوى الصوت
        val gain = 2.5f // مضاعفة الصوت 2.5 مرة
        val processedShortArray = shortArray.map { sample ->
            val amplified = (sample * gain).toInt()
            when {
                amplified > Short.MAX_VALUE -> Short.MAX_VALUE
                amplified < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> amplified.toShort()
            }
        }.toTypedArray()

        // 🔇 تطبيق noise gate بسيط
        val noiseGateThreshold = 500 // عتبة الضوضاء
        val noiseGatedArray = processedShortArray.map { sample ->
            if (Math.abs(sample.toInt()) < noiseGateThreshold) {
                0.toShort() // كتم الضوضاء المنخفضة
            } else {
                sample
            }
        }.toTypedArray()

        // 🎵 تطبيق filter بسيط لتحسين الوضوح
        val filteredArray = applySimpleFilter(noiseGatedArray)

        // تحويل مرة أخرى إلى byte array
        return ByteArray(filteredArray.size * 2).also { result ->
            filteredArray.forEachIndexed { index, sample ->
                result[index * 2] = (sample.toInt() and 0xFF).toByte()
                result[index * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
            }
        }
    }

    /**
     * 🎵 تطبيق filter بسيط لتحسين وضوح الصوت
     */
    private fun applySimpleFilter(audioData: Array<Short>): Array<Short> {
        val result = Array(audioData.size) { 0.toShort() }

        for (i in 1 until audioData.size - 1) {
            // تطبيق averaging filter لتنعيم الصوت وإزالة الضوضاء العالية التردد
            val filtered = ((audioData[i - 1] + audioData[i] + audioData[i + 1]) / 3.0).toInt()
            result[i] = when {
                filtered > Short.MAX_VALUE -> Short.MAX_VALUE
                filtered < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> filtered.toShort()
            }
        }

        // الحفاظ على العينات الأولى والأخيرة
        result[0] = audioData[0]
        result[result.lastIndex] = audioData[audioData.lastIndex]

        return result
    }

    /**
     * 🎯 معالجة صوتية بسيطة وآمنة - رفع مستوى الصوت فقط
     */
    private fun amplifyAudioSimple(audioData: ByteArray): ByteArray {
        try {
            if (audioData.isEmpty() || audioData.size % 2 != 0) {
                return audioData
            }

            val result = ByteArray(audioData.size)
            val gain = 1.5f // رفع بسيط وآمن للصوت

            for (i in 0 until audioData.size step 2) {
                // قراءة العينة
                val byte1 = audioData[i].toInt() and 0xFF
                val byte2 = audioData[i + 1].toInt() and 0xFF
                var sample = (byte2 shl 8 or byte1).toShort()

                // تطبيق gain
                val amplified = (sample * gain).toInt()

                // clipping protection
                val finalSample = when {
                    amplified > Short.MAX_VALUE -> Short.MAX_VALUE
                    amplified < Short.MIN_VALUE -> Short.MIN_VALUE
                    else -> amplified.toShort()
                }

                // كتابة العينة
                result[i] = (finalSample.toInt() and 0xFF).toByte()
                result[i + 1] = ((finalSample.toInt() shr 8) and 0xFF).toByte()
            }

            return result
        } catch (e: Exception) {
            Timber.e(e, "💥 Error in amplifyAudioSimple, returning original data")
            return audioData
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        scope.cancel()
        Timber.d("🛑 PttTcpReceiverService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/*class MatrixPttReceiver(
        private val context: Context,
        private val session: Session,
        private val roomId: String
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var audioTrack: AudioTrack? = null
    private var isRunning = false

    fun startListening() {
        if (isRunning) return
        isRunning = true

        val room = session.getRoom(roomId) ?: return
        Timber.d("🎧 Starting MatrixPttReceiver for room: $roomId")

        val flow = room.stateService().getStateEventsLive(
                eventTypes = setOf("im.ptt.chunk"),
                stateKey = QueryStringValue.IsNotEmpty
        ).asFlow()

        flow.onEach { events ->
            if (!isRunning) return@onEach

            events.sortedBy { it.originServerTs ?: 0L }.forEach { event ->
                try {
                    val content = event.content ?: return@forEach
                    val dataBase64 = content["data"] as? String ?: return@forEach
                    val pcmData = Base64.decode(dataBase64, Base64.NO_WRAP)

                    playPcmData(pcmData)
                } catch (e: Exception) {
                    Timber.e(e, "❌ Failed to process Matrix chunk")
                }
            }
        }.launchIn(scope)
    }

    private fun playPcmData(data: ByteArray) {
        if (audioTrack == null) {
            val sampleRate = 16000
            val bufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            )

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

            audioTrack?.play()
            Timber.d("🔊 AudioTrack started")
        }

        audioTrack?.write(data, 0, data.size)
    }

    fun stop() {
        isRunning = false

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {
        }

        audioTrack = null
        scope.cancel()
        Timber.d("🛑 MatrixPttReceiver stopped")
    }
}*/


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
        Timber.d("🎧 MatrixPttReceiver.startListening() called for room: $roomId")

        // 🚨 حماية من تشغيل مثيلات متعددة
        synchronized(this) {
            if (isRunning) {
                Timber.d("⚠️ MatrixPttReceiver already running, ignoring start request")
                return
            }

            isRunning = true
            Timber.d("✅ MatrixPttReceiver isRunning set to true")
        }

        // 🚨 فحص وجود الغرفة
        session.getRoom(roomId) ?: run {
            Timber.w("⚠️ MatrixPttReceiver: room not found $roomId")
            synchronized(this) {
                isRunning = false
            }
            return
        }

        Timber.d("🎧 Starting MatrixPttReceiver (to-device) for room: $roomId")

        try {
            setupToDeviceListener()
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to setup to-device listener")
            synchronized(this) {
                isRunning = false
            }
        }
    }

    private fun setupToDeviceListener() {
        Timber.d("🎧 Setting up to-device listener for PTT receiver")

        // 🚀 إعداد مستمع لـ to-device events مباشرة
        toDeviceListener = object : org.matrix.android.sdk.api.session.LiveEventListener {
            override fun onLiveEvent(roomId: String, event: org.matrix.android.sdk.api.session.events.model.Event) {
                // نتجاهل live events العادية
            }

            override fun onPaginatedEvent(roomId: String, event: org.matrix.android.sdk.api.session.events.model.Event) {
                // نتجاهل paginated events
            }

            override fun onEventDecrypted(event: org.matrix.android.sdk.api.session.events.model.Event, clearEvent: org.matrix.android.sdk.api.util.JsonDict) {
                // نتجاهل decryption events
            }

            override fun onEventDecryptionError(event: org.matrix.android.sdk.api.session.events.model.Event, cryptoError: org.matrix.android.sdk.api.session.crypto.MXCryptoError) {
                // نتجاهل decryption errors
            }

            override fun onLiveToDeviceEvent(event: org.matrix.android.sdk.api.session.events.model.Event) {
                // 🎯 هنا نستقبل to-device events!
                Timber.d("🎯 onLiveToDeviceEvent called with event type: ${event.type}")

                if (!isRunning) {
                    Timber.d("🚫 PTT receiver not running, ignoring event")
                    return
                }

                if (event.type == "m.ptt.audio") {
                    Timber.d("✅ Received PTT audio to-device event!")
                    val content = event.content ?: run {
                        Timber.w("⚠️ Event content is null")
                        return
                    }

                    val eventRoomId = content["room_id"] as? String
                    val senderId = content["sender_id"] as? String
                    Timber.d("📋 Event details: roomId=$eventRoomId, senderId=$senderId, myUserId=${session.myUserId}")

                    // 🎯 فلترة الأحداث للغرفة الصحيحة وتجاهل رسائلي
                    if (eventRoomId == roomId && senderId != session.myUserId) {
                        val audioData = content["audio_data"] as? String
                        val encoding = content["encoding"] as? String

                        Timber.d("🎵 Audio data length: ${audioData?.length}, encoding: $encoding")

                        if (audioData != null && encoding == "pcm_16bit") {
                            try {
                                val pcm = Base64.decode(audioData, Base64.NO_WRAP)
                                Timber.d("🔊 Playing PTT audio chunk (${pcm.size} bytes)")
                                
                                // 🚨 حل مبسط: استخدام الدالة البسيطة مباشرة
                                playPcmData(pcm)
                            } catch (e: Exception) {
                                Timber.e(e, "❌ Failed to decode/play PTT to-device chunk")
                            }
                        } else {
                            Timber.w("⚠️ Invalid audio data or encoding: audioData=${audioData?.length}, encoding=$encoding")
                        }
                    } else {
                        Timber.d("🚫 Event filtered out: room match=${eventRoomId == roomId}, sender match=${senderId != session.myUserId}")
                    }
                } else {
                    Timber.d("🚫 Ignoring non-PTT event type: ${event.type}")
                }
            }
        }
        
        // تسجيل المستمع
        toDeviceListener?.let { listener ->
            Timber.d("📝 Registering to-device listener with Matrix SDK")
            session.eventStreamService().addEventStreamListener(listener)
            Timber.d("✅ PTT to-device listener registered successfully for room: $roomId")
        } ?: run {
            Timber.e("❌ Failed to create to-device listener!")
        }
    }

    // 🚨 دالة محمية جديدة لتشغيل الصوت بدون تعطل
    private suspend fun playPcmDataSafe(data: ByteArray) {
        Timber.d("🔊 playPcmDataSafe called with ${data.size} bytes on ${Thread.currentThread().name}")

        // 🚨 فحص أولي للبيانات
        if (data.isEmpty()) {
            Timber.w("⚠️ Empty audio data received, ignoring")
            return
        }

        if (data.size > 512 * 1024) { // 512KB limit لمنع OutOfMemoryError
            Timber.e("❌ Audio data too large: ${data.size} bytes, ignoring")
            return
        }

        // 🚨 فحص سلامة البيانات بحماية من الأخطاء
        val processedData = try {
            if (data.size % 2 != 0) {
                Timber.w("⚠️ Audio data size not even, truncating")
                data.copyOf(data.size - 1)
            } else {
                data
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Data validation failed")
            return
        }

        // 🚨 تشغيل على Main thread مع حماية من الأخطاء
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                // 🚨 Synchronized access لمنع concurrent modification
                synchronized(this@MatrixPttReceiver) {
                    try {
                        playPcmDataInternal(processedData)
                    } catch (e: Exception) {
                        Timber.e(e, "💥 Critical error in playPcmDataInternal")
                        // 🚨 تنظيف في حالة الخطأ
                        try {
                            cleanupAudioTrack()
                        } catch (cleanupError: Exception) {
                            Timber.e(cleanupError, "❌ Error during emergency cleanup")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "💥 Critical error in playPcmDataSafe context switch")
        }
    }

    private fun playPcmDataInternal(data: ByteArray) {
        Timber.d("🔊 playPcmDataInternal called with ${data.size} bytes")

        // 🚨 حماية شاملة من كل النواحي
        try {
            // 🚨 فحص شامل قبل البدء
            if (!isRunning) {
                Timber.w("⚠️ Receiver not running, ignoring audio data")
                return
            }

            if (audioTrack == null) {
                Timber.d("🎵 Initializing AudioTrack for Matrix PTT")

                // 🎯 إعدادات متوازنة: وضوح + سرعة استجابة
                val sampleRate = 16000
                val bufferSize = 4096 // 🎯 توازن مثالي: يمنع الصدى ويضمن الوضوح والاستمرارية

                Timber.d("🎵 AudioTrack: sampleRate=$sampleRate, bufferSize=$bufferSize")

                // ✅ إعداد AudioManager مع منع الصدى
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
                
                // 🎯 تحسينات لجودة الصوت ومنع الصدى
                try {
                    // ضبط مستوى صوت متوازن
                    @Suppress("DEPRECATION")
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                    val optimalVolume = (maxVolume * 0.85).toInt() // 85% من الحد الأقصى
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, optimalVolume, 0)
                    
                    // تحسين إعدادات الصوت للوضوح
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        audioManager.setParameters("noise_suppression=off;echo_cancellation=off") // نتحكم نحن بالـ echo
                    }
                } catch (e: Exception) {
                    Timber.w(e, "⚠️ Failed to optimize audio settings")
                }

                // ✅ إعداد جهاز الاتصال للإصدارات الجديدة
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        audioManager.availableCommunicationDevices.firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        }?.let {
                            audioManager.setCommunicationDevice(it)
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "⚠️ Failed to set communication device")
                    }
                }

                // 🚨 إنشاء AudioTrack مع retry logic محسن
                var retryCount = 0
                var trackCreated = false

                while (retryCount < 3 && !trackCreated) {
                    try {
                        Timber.d("🎵 Creating AudioTrack (attempt ${retryCount + 1}/3)")

                        audioTrack = AudioTrack.Builder()
                                .setAudioAttributes(
                                        AudioAttributes.Builder()
                                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED) // 🎯 ضمان وضوح الصوت
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
                                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) // 🎯 أداء للاستجابة السريعة
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

            // 🚀 كتابة محسنة للاستمرارية والوضوح
            var offset = 0
            var remaining = data.size
            var totalWritten = 0
            var writeErrors = 0
            val maxWriteErrors = 3

            while (remaining > 0 && writeErrors < maxWriteErrors) {
                try {
                    // 🚨 فحص مجدد لكل write operation
                    val safeTrack = audioTrack
                    if (safeTrack == null || safeTrack.state != AudioTrack.STATE_INITIALIZED) {
                        Timber.e("❌ AudioTrack became invalid during write")
                        break
                    }

                    // 🎯 استراتيجية كتابة ذكية
                    val toWrite = minOf(remaining, 4096) // chunks متوسطة للتوازن
                    val written = safeTrack.write(data, offset, toWrite, AudioTrack.WRITE_BLOCKING)

                    if (written > 0) {
                        offset += written
                        remaining -= written
                        totalWritten += written
                        writeErrors = 0 // reset error counter on success
                    } else if (written == 0) {
                        // AudioTrack busy, try smaller chunk
                        Thread.sleep(1)
                        writeErrors++
                    } else {
                        Timber.w("⚠️ AudioTrack.write returned $written")
                        writeErrors++
                    }
                } catch (e: Exception) {
                    Timber.e(e, "💥 Write error #${writeErrors + 1}")
                    writeErrors++
                    if (writeErrors < maxWriteErrors) {
                        Thread.sleep(2) // brief pause before retry
                    }
                }
            }

            if (totalWritten > 0) {
                Timber.d("✅ Successfully wrote $totalWritten bytes to AudioTrack")
            } else {
                Timber.w("⚠️ No data was written to AudioTrack")
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
                
                // 🎯 استعادة مستوى الصوت العادي
                try {
                    @Suppress("DEPRECATION")
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 
                        audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)
                } catch (volumeError: Exception) {
                    Timber.w(volumeError, "⚠️ Failed to restore volume")
                }
                
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

    /**
     * 🎯 معالجة صوتية بسيطة وآمنة - رفع مستوى الصوت فقط
     */
    private fun amplifyAudioSimple(audioData: ByteArray): ByteArray {
        try {
            if (audioData.isEmpty() || audioData.size % 2 != 0) {
                return audioData
            }

            val result = ByteArray(audioData.size)
            val gain = 1.5f // رفع بسيط وآمن للصوت

            for (i in 0 until audioData.size step 2) {
                // قراءة العينة
                val byte1 = audioData[i].toInt() and 0xFF
                val byte2 = audioData[i + 1].toInt() and 0xFF
                var sample = (byte2 shl 8 or byte1).toShort()

                // تطبيق gain
                val amplified = (sample * gain).toInt()

                // clipping protection
                val finalSample = when {
                    amplified > Short.MAX_VALUE -> Short.MAX_VALUE
                    amplified < Short.MIN_VALUE -> Short.MIN_VALUE
                    else -> amplified.toShort()
                }

                // كتابة العينة
                result[i] = (finalSample.toInt() and 0xFF).toByte()
                result[i + 1] = ((finalSample.toInt() shr 8) and 0xFF).toByte()
            }

            return result
        } catch (e: Exception) {
            Timber.e(e, "💥 Error in amplifyAudioSimple, returning original data")
            return audioData
        }
    }

    /**
     * 🎯 تحسين جودة الصوت وإزالة الضوضاء
     */
    private fun enhanceAudioQuality(audioData: ByteArray): ByteArray {
        // تحويل إلى short array للمعالجة
        val shortArray = ByteArray(audioData.size / 2).mapIndexed { index, _ ->
            (audioData[index * 2 + 1].toInt() shl 8 or (audioData[index * 2].toInt() and 0xFF)).toShort()
        }.toTypedArray()

        // 🎚️ تطبيق gain لرفع مستوى الصوت
        val gain = 2.5f // مضاعفة الصوت 2.5 مرة
        val processedShortArray = shortArray.map { sample ->
            val amplified = (sample * gain).toInt()
            when {
                amplified > Short.MAX_VALUE -> Short.MAX_VALUE
                amplified < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> amplified.toShort()
            }
        }.toTypedArray()

        // 🔇 تطبيق noise gate بسيط
        val noiseGateThreshold = 500 // عتبة الضوضاء
        val noiseGatedArray = processedShortArray.map { sample ->
            if (Math.abs(sample.toInt()) < noiseGateThreshold) {
                0.toShort() // كتم الضوضاء المنخفضة
            } else {
                sample
            }
        }.toTypedArray()

        // 🎵 تطبيق filter بسيط لتحسين الوضوح
        val filteredArray = applySimpleFilter(noiseGatedArray)

        // تحويل مرة أخرى إلى byte array
        return ByteArray(filteredArray.size * 2).also { result ->
            filteredArray.forEachIndexed { index, sample ->
                result[index * 2] = (sample.toInt() and 0xFF).toByte()
                result[index * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
            }
        }
    }

    /**
     * 🎵 تطبيق filter بسيط لتحسين وضوح الصوت
     */
    private fun applySimpleFilter(audioData: Array<Short>): Array<Short> {
        val result = Array(audioData.size) { 0.toShort() }

        for (i in 1 until audioData.size - 1) {
            // تطبيق averaging filter لتنعيم الصوت وإزالة الضوضاء العالية التردد
            val filtered = ((audioData[i - 1] + audioData[i] + audioData[i + 1]) / 3.0).toInt()
            result[i] = when {
                filtered > Short.MAX_VALUE -> Short.MAX_VALUE
                filtered < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> filtered.toShort()
            }
        }

        // الحفاظ على العينات الأولى والأخيرة
        result[0] = audioData[0]
        result[result.lastIndex] = audioData[audioData.lastIndex]

        return result
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
