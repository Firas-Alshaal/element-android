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
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import im.vector.app.features.home.room.detail.composer.PttManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PttReceiverService : Service() {
    private var socket: DatagramSocket? = null
    private var job: Job? = null
    private var isReceiving = false
    private var currentRoomId: String? = null
    private var focusRequest: AudioFocusRequest? = null
    private lateinit var audioManager: AudioManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var sequenceInitialized = false
    private var expectedSequence = 0
    private val outOfOrderPackets = mutableMapOf<Int, ByteArray>()
    private var lastPacketTime = 0L
    private val receivedPackets = mutableSetOf<Int>()

    private val sampleRate = 16000
    val bytesPerMs = sampleRate * 2 / 1000 // = 32
    val frameSize = bytesPerMs * 40 // = 1280
    val audioBufferSize = frameSize * 2 // = 2560

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val roomId = intent?.getStringExtra("roomId") ?: return START_NOT_STICKY
        val myUserId = intent.getStringExtra("myUserId")
        val currentSpeakerId = intent.getStringExtra("speakerId")

        Timber.d("🎯 PttReceiverService.onStartCommand: roomId=$roomId, myUserId=$myUserId, speakerId=$currentSpeakerId")

        if (myUserId == null && currentSpeakerId == null) {
            Timber.w("⚠️ Missing user IDs, stopping service")
            stopReceiving()
            stopSelf()
            return START_NOT_STICKY
        }

        if (myUserId == null || currentSpeakerId == null) {
            Timber.w("⚠️ Incomplete user IDs: myUserId=$myUserId, speakerId=$currentSpeakerId")
            return START_NOT_STICKY
        }

        if (currentSpeakerId == "PRESTART") {
            Timber.d("🔄 PRESTART mode: roomId=$roomId")
            currentRoomId = roomId
            preInitializeReceiver()
            return START_STICKY
        }

        if (myUserId == currentSpeakerId) {
            Timber.d("🔕 Self-sending detected, stopping service")
            stopReceiving()
            stopSelf()
            return START_NOT_STICKY
        }

        stopReceiving()
        currentRoomId = roomId

        val port = roomId.hashCode() and 0xFFFF
        Timber.d("📡 Setting up receiver: port=$port")

        scope.launch {
            job?.join()
        startReceiving(port, myUserId, currentSpeakerId)
        }
        return START_STICKY
    }

    private var preInitializedAudioTrack: AudioTrack? = null
    private var isPreInitialized = false

    private fun preInitializeReceiver() {
        scope.launch {
            try {
                audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

                val bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).let { minBuffer ->
                    minBuffer * 3
                }

                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setLegacyStreamType(AudioManager.STREAM_VOICE_CALL)
                    .build()

                preInitializedAudioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    AudioTrack.Builder()
                        .setAudioAttributes(audioAttributes)
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
                } else {
                    @Suppress("DEPRECATION")
                    AudioTrack(
                        AudioManager.STREAM_VOICE_CALL,
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize,
                        AudioTrack.MODE_STREAM
                    )
                }

                isPreInitialized = true
            } catch (e: Exception) {
                Timber.e(e, "Failed to pre-initialize receiver")
                preInitializedAudioTrack?.release()
                preInitializedAudioTrack = null
                isPreInitialized = false
            }
        }
    }

    private fun startReceiving(port: Int, myUserId: String, currentSpeakerId: String) {

        if (isReceiving || myUserId == currentSpeakerId || PttManager.isSending()) {
            Timber.d("🚫 RECEIVER BLOCKED: isReceiving=$isReceiving, myUserId=$myUserId, currentSpeakerId=$currentSpeakerId, isSending=${PttManager.isSending()}")
            return
        }

        synchronized(this) {
            if (isReceiving) return
            isReceiving = true
        }

        Timber.d("🚀 STARTING RECEIVER: port=$port, myUserId=$myUserId, speakerId=$currentSpeakerId")

        job = scope.launch {
            try {
                val audioTrack: AudioTrack

                if (isPreInitialized && preInitializedAudioTrack != null) {
                    audioTrack = preInitializedAudioTrack!!
                    socket = DatagramSocket(null)
                    val socketAddress = InetSocketAddress(port)
                    socket?.reuseAddress = true
                    socket?.bind(socketAddress)
                    socket?.broadcast = true
                    Timber.d("🔌 SOCKET BOUND (PRE-INIT): port=$port, address=${socket?.localAddress}")
                } else {
                    audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setLegacyStreamType(AudioManager.STREAM_VOICE_CALL)
                        .build()

                    audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        AudioTrack.Builder()
                                .setAudioAttributes(audioAttributes)
                                .setAudioFormat(
                                        AudioFormat.Builder()
                                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                                .setSampleRate(sampleRate)
                                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                                .build()
                                )
                                .setBufferSizeInBytes(audioBufferSize)
                                .setTransferMode(AudioTrack.MODE_STREAM)
                                .build()
                    } else {
                        @Suppress("DEPRECATION")
                        AudioTrack(
                                AudioManager.STREAM_VOICE_CALL,
                                sampleRate,
                                AudioFormat.CHANNEL_OUT_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                audioBufferSize,
                                AudioTrack.MODE_STREAM
                        )
                    }
                    socket = DatagramSocket(null)
                    val socketAddress = InetSocketAddress(port)
                    socket?.reuseAddress = true
                    socket?.bind(socketAddress)
                    socket?.broadcast = true
                    Timber.d("🔌 SOCKET BOUND (FRESH): port=$port, address=${socket?.localAddress}")
                }

                try {
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                    val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                    val comfortableVolume = (maxVolume * 0.8).toInt()
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, comfortableVolume, 0)

                    // ⭐ Audio Routing Debug
                    Timber.d("🔊 AUDIO SETTINGS: maxVolume=$maxVolume, currentVolume=$currentVolume, setTo=$comfortableVolume")
                    @Suppress("DEPRECATION")
                    Timber.d("🔊 AUDIO MODE: mode=${audioManager.mode}, speakerphone=${audioManager.isSpeakerphoneOn}")

                    // ⭐ Force speakerphone on for debugging
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                    Timber.d("🔊 FORCED SPEAKERPHONE ON")
                } catch (e: Exception) {
                    Timber.w("Volume setting failed: ${e.message}")
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).build()
                    audioManager.requestAudioFocus(focusRequest!!)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                }

                val quickAudioQueue = LinkedBlockingQueue<ByteArray>()

                // ⭐ LOG SOCKET STATUS
                Timber.d("🔌 SOCKET STATUS: localPort=${socket?.localPort}, reuseAddress=${socket?.reuseAddress}, broadcast=${socket?.broadcast}, bound=${socket?.isBound}")

                val receiverJob = launch {
                    val buffer = ByteArray(65536)
                    var lastActiveTime = System.currentTimeMillis()
                    var totalPacketsReceived = 0 // ⭐ عداد الpackets المستقبلة

                    Timber.d("📡 RECEIVER STARTED: Listening on port $port")

                    try {
                        while (isActive && isReceiving) {
                            try {
                                val packet = DatagramPacket(buffer, buffer.size)
                                socket?.receive(packet) ?: break

                                totalPacketsReceived++

                                if (totalPacketsReceived % 5 == 0) { // كل 5 packets
                                    Timber.d("📥 PACKETS RECEIVED: #$totalPacketsReceived from ${packet.address}:${packet.port}")
                                }

                                if (packet.length < 4) {
                                    Timber.w("⚠️ PACKET TOO SHORT: ${packet.length} bytes")
                                    continue
                                }

                                val sequence = ByteBuffer.wrap(packet.data, 0, 4).int
                                val audioData = packet.data.sliceArray(4 until packet.length)

                                if (totalPacketsReceived % 10 == 0) { // كل 10 packets
                                    Timber.d("🔢 PACKET DETAILS: seq=$sequence, size=${audioData.size}, expected=$expectedSequence")
                                }

                                lastActiveTime = System.currentTimeMillis()

                                            if (receivedPackets.contains(sequence)) {
                                    Timber.d("🔄 DUPLICATE PACKET: seq=$sequence")
                                                continue
                                            }
                                            receivedPackets.add(sequence)

                                val maxToKeep = expectedSequence - 25
                                receivedPackets.removeAll { it < maxToKeep }

                                            if (!sequenceInitialized) {
                                                expectedSequence = sequence
                                                sequenceInitialized = true
                                                lastPacketTime = System.currentTimeMillis()

                                    Timber.d("🎬 SEQUENCE INITIALIZED: seq=$sequence")

                                    var hasSignificantAudio = false
                                    for (i in 0 until audioData.size - 1 step 2) {
                                        val sample = ((audioData[i + 1].toInt() shl 8) or (audioData[i].toInt() and 0xFF)).toShort()
                                        if (kotlin.math.abs(sample.toInt()) > 80) {
                                            hasSignificantAudio = true
                                            break
                                        }
                                    }

                                    if (hasSignificantAudio) {
                                        val processedAudio = enhanceAudioData(audioData)
                                        quickAudioQueue.put(processedAudio)
                                        Timber.d("🔊 FIRST PACKET QUEUED: seq=$sequence")
                                    } else {
                                        Timber.d("🔇 FIRST PACKET NO AUDIO: seq=$sequence")
                                    }

                                    // ⭐ CRITICAL FIX: زيادة expectedSequence بعد معالجة أول packet
                                    expectedSequence++
                                    Timber.d("✅ NEXT EXPECTED: seq=$expectedSequence")
                                } else if (sequence == expectedSequence) {
                                    expectedSequence++
                                                    lastPacketTime = System.currentTimeMillis()

                                    var hasSignificantAudio = false
                                    for (i in 0 until audioData.size - 1 step 2) {
                                        val sample = ((audioData[i + 1].toInt() shl 8) or (audioData[i].toInt() and 0xFF)).toShort()
                                        if (kotlin.math.abs(sample.toInt()) > 80) {
                                            hasSignificantAudio = true
                                            break
                                        }
                                    }

                                    if (hasSignificantAudio) {
                                        val processedAudio = enhanceAudioData(audioData)
                                        quickAudioQueue.put(processedAudio)
                                        Timber.d("🔊 IN-ORDER PACKET QUEUED: seq=$sequence, next=$expectedSequence")
                                    }

                                    // Process any buffered out-of-order packets that are now in sequence
                                    var processedBuffered = 0
                                                    while (outOfOrderPackets.containsKey(expectedSequence)) {
                                        val bufferedAudio = outOfOrderPackets.remove(expectedSequence)!!
                                        var hasBufferedAudio = false
                                        for (i in 0 until bufferedAudio.size - 1 step 2) {
                                            val sample = ((bufferedAudio[i + 1].toInt() shl 8) or (bufferedAudio[i].toInt() and 0xFF)).toShort()
                                            if (kotlin.math.abs(sample.toInt()) > 80) {
                                                hasBufferedAudio = true
                                                break
                                            }
                                        }

                                        if (hasBufferedAudio) {
                                            val processedAudio = enhanceAudioData(bufferedAudio)
                                            quickAudioQueue.put(processedAudio)
                                            processedBuffered++
                                        }
                                        expectedSequence++
                                        lastPacketTime = System.currentTimeMillis()
                                    }

                                    if (processedBuffered > 0) {
                                        Timber.d("🔄 PROCESSED BUFFERED: $processedBuffered packets, next=$expectedSequence")
                                    }
                                } else if (sequence > expectedSequence && sequence <= expectedSequence + 100) { // زيادة النطاق من 50 إلى 100
                                    var hasValidAudio = false
                                    for (i in 0 until audioData.size - 1 step 2) {
                                        val sample = ((audioData[i + 1].toInt() shl 8) or (audioData[i].toInt() and 0xFF)).toShort()
                                        if (kotlin.math.abs(sample.toInt()) > 80) {
                                            hasValidAudio = true
                                            break
                                        }
                                    }
                                    if (hasValidAudio) {
                                        outOfOrderPackets[sequence] = audioData
                                        if (outOfOrderPackets.size % 10 == 0) { // log كل 10 buffered packets
                                            Timber.d("📦 OUT-OF-ORDER BUFFERED: seq=$sequence (expected=$expectedSequence), buffered=${outOfOrderPackets.size}")
                                        }
                                    }
                                } else {
                                    if (sequence < expectedSequence) {
                                        Timber.d("⏪ LATE PACKET: seq=$sequence (expected=$expectedSequence)")
                                    } else {
                                        Timber.d("❌ PACKET REJECTED: seq=$sequence (expected=$expectedSequence, range=$expectedSequence-${expectedSequence + 100})")
                                    }
                                }
                            } catch (e: SocketException) {
                                if (isReceiving) Timber.w("Socket error: ${e.message}")
                                break
                            } catch (e: Exception) {
                                Timber.e("Receiver error: ${e.message}")
                            }

                            if (System.currentTimeMillis() - lastActiveTime > 8000) {
                                Timber.d("⏰ TIMEOUT: No audio received for 8 seconds, stopping receiver")
                                stopReceiving()
                                cancel()
                            }
                        }
                    } finally {
                        Timber.d("🏁 RECEIVER FINISHED: totalPacketsReceived=$totalPacketsReceived")
                    }
                }

                    val playerJob = launch {
                    var packetsPlayed = 0
                    var consecutiveEmptyChecks = 0
                    var totalBytesWritten = 0

                    audioTrack.play()
                    Timber.d("🎵 PLAYER STARTED: AudioTrack playing")

                    try {
                        while (isActive && isReceiving) {
                            try {
                                val audioData = quickAudioQueue.poll(50, TimeUnit.MILLISECONDS)

                                if (audioData != null) {
                                    consecutiveEmptyChecks = 0
                                    val bytesWritten = audioTrack.write(audioData, 0, audioData.size)
                                    if (bytesWritten > 0) {
                                        packetsPlayed++
                                        totalBytesWritten += bytesWritten

                                        if (packetsPlayed % 5 == 0) { // كل 5 packets
                                            Timber.d("🔊 AUDIO PLAYING: packet #$packetsPlayed, ${audioData.size} bytes, total=${totalBytesWritten} bytes")
                                        }
                                    } else if (bytesWritten < 0) {
                                        Timber.e("❌ AudioTrack write failed: $bytesWritten")
                                    }
                                } else {
                                    consecutiveEmptyChecks++
                                    if (consecutiveEmptyChecks % 50 == 0) { // كل 50 empty checks (2.5 ثانية)
                                        Timber.d("⏳ WAITING FOR AUDIO: empty checks=$consecutiveEmptyChecks, queue size=${quickAudioQueue.size}")
                                    }
                                    if (consecutiveEmptyChecks > 200) { // 200 * 50ms = 10 ثوانٍ
                                        Timber.d("⏰ TIMEOUT: No audio received for extended period, stopping player")
                                        break
                                    }
                                }
                            } catch (e: InterruptedException) {
                                Timber.d("🔕 Player thread interrupted")
                                break
                            } catch (e: Exception) {
                                Timber.e("❌ Player error: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e("❌ Player thread error: ${e.message}")
                    } finally {
                        Timber.d("🏁 PLAYER FINISHED: $packetsPlayed packets played, $totalBytesWritten total bytes")
                        try {
                            audioTrack.stop()
                            audioTrack.release()
                        } catch (e: Exception) {
                            Timber.w("⚠️ AudioTrack cleanup error: ${e.message}")
                        }
                    }
                }

                receiverJob.join()
                playerJob.join()

            } catch (e: Exception) {
                Timber.e(e, "Error in audio receiver")
            } finally {
                try {
                    socket?.close()
                } catch (e: Exception) {
                    Timber.w("Socket cleanup error: ${e.message}")
                }
            }
        }
    }

    private fun enhanceAudioData(audioData: ByteArray): ByteArray {
        if (audioData.size < 6) return audioData

        val smoothed = audioData.copyOf()

        for (i in 2 until audioData.size - 2 step 2) {
            val current = ((audioData[i + 1].toInt() shl 8) or (audioData[i].toInt() and 0xFF)).toShort()
            val prev = ((audioData[i - 1].toInt() shl 8) or (audioData[i - 2].toInt() and 0xFF)).toShort()
            val next = if (i + 3 < audioData.size) {
                ((audioData[i + 3].toInt() shl 8) or (audioData[i + 2].toInt() and 0xFF)).toShort()
            } else current

            val amplitude = kotlin.math.abs(current.toInt())
            if (amplitude < 30) { // ⭐ خفض من 80 إلى 30 ليطابق المرسل
                val reduced = (current / 2).toShort()
                smoothed[i] = (reduced.toInt() and 0xFF).toByte()
                smoothed[i + 1] = ((reduced.toInt() shr 8) and 0xFF).toByte()
            } else {
                val prevDiff = kotlin.math.abs(current - prev)
                val nextDiff = kotlin.math.abs(current - next)

                if (prevDiff > 6000 && nextDiff > 6000) {
                    val smoothedValue = ((prev.toInt() + current.toInt() + next.toInt()) / 3).toShort()
                    smoothed[i] = (smoothedValue.toInt() and 0xFF).toByte()
                    smoothed[i + 1] = ((smoothedValue.toInt() shr 8) and 0xFF).toByte()
                }
            }
        }
        return smoothed
    }

    private fun stopReceiving() {
        if (!isReceiving) return

        isReceiving = false
        job?.cancel()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Timber.w("Audio focus cleanup error: ${e.message}")
        }

        try {
            socket?.close()
        } catch (e: Exception) {
            Timber.w("Socket cleanup error: ${e.message}")
        }

        socket = null
            focusRequest = null
        job = null

        sequenceInitialized = false
        expectedSequence = 0
        outOfOrderPackets.clear()
        receivedPackets.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopReceiving()
        scope.cancel()

        try {
            preInitializedAudioTrack?.release()
        } catch (e: Exception) {
            Timber.w("Pre-initialized AudioTrack cleanup error: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
