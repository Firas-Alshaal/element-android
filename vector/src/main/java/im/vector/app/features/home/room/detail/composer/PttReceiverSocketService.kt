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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.BufferedInputStream
import java.net.Socket

class PttTcpReceiverService : Service() {

    private val serverPort = 8008 // Port المرسل الذي نتصل به
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
    private val audioBufferSize = 2048 // زيادة حجم البافر لتجنب الـ underruns

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

                    // ✅ الخطوة 2: إعداد AudioTrack
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
                            .setBufferSizeInBytes(2048) // ضعف الحجم للمزيد من الـ buffering
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
                                        // ✅ كتابة مبسطة مثل الكود القديم مع partial write handling
                                        var offset = tokenLength
                                        var remaining = audioDataLength

                                        while (remaining > 0) {
                                            val written = audioTrack.write(buffer, offset, remaining)
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

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        scope.cancel()
        Timber.d("🛑 PttTcpReceiverService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
