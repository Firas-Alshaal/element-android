/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

/**
 * Enhanced PTT Voice Message Helper
 * 
 * Handles saving PTT audio to Matrix for history and playback
 * Features:
 * - Automatic voice message creation from PTT audio
 * - Optimized file handling for mobile networks
 * - Matrix integration with proper metadata
 * - Waveform generation for UI
 */
class EnhancedPttVoiceMessageHelper @Inject constructor(
    private val context: Context
) {
    
    companion object {
        private const val PTT_AUDIO_DIR = "ptt_audio"
    }

    /**
     * Save PTT audio as voice message to Matrix
     */
    suspend fun savePttAsVoiceMessage(
        session: Session,
        roomId: String,
        audioData: ByteArray,
        durationMs: Long
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val room = session.getRoom(roomId)
            if (room == null) {
                Timber.e("Room $roomId not found - cannot save PTT voice message")
                return@withContext false
            }

            // Create temporary audio file
            val audioFile = createPttAudioFile(audioData)
            if (audioFile == null) {
                Timber.e("Failed to create PTT audio file")
                return@withContext false
            }

            // Generate waveform for UI (simplified)
            val waveform = generateSimpleWaveform(audioData)
            
            // Calculate actual duration from audio data
            val actualDurationMs = calculateAudioDuration(audioData, 16000, 1, 16)
            
            // Create content attachment data with correct format and duration
            val attachmentData = ContentAttachmentData(
                mimeType = "audio/wav", // Use WAV for PCM data compatibility
                type = ContentAttachmentData.Type.AUDIO,
                name = "PTT_${System.currentTimeMillis()}.wav",
                queryUri = android.net.Uri.fromFile(audioFile),
                size = audioFile.length(),
                duration = actualDurationMs, // Add actual duration
                waveform = waveform
            )

            // Send as voice message
            room.sendService().sendMedia(
                attachment = attachmentData,
                compressBeforeSending = true,
                roomIds = emptySet() // Send only to this room
            )

            // Don't delete immediately - let Matrix handle the cleanup after upload
            // Schedule cleanup for later
            scheduleFileCleanup(audioFile)

            Timber.d("✅ PTT voice message saved to room $roomId (${durationMs}ms, ${audioData.size} bytes)")
            true
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to save PTT as voice message")
            false
        }
    }

    /**
     * Create temporary audio file from PTT data
     */
    private suspend fun createPttAudioFile(audioData: ByteArray): File? = withContext(Dispatchers.IO) {
        try {
            // Create PTT audio directory
            val pttDir = File(context.cacheDir, PTT_AUDIO_DIR)
            if (!pttDir.exists()) {
                pttDir.mkdirs()
            }

            // Create temporary WAV audio file
            val audioFile = File(pttDir, "ptt_${System.currentTimeMillis()}.wav")
            
            // Create WAV file with proper header (16kHz for optimal voice quality)
            val wavData = createWavFile(audioData, 16000, 1, 16)
            
            FileOutputStream(audioFile).use { fos ->
                fos.write(wavData)
                fos.flush()
            }

            Timber.d("Created PTT WAV file: ${audioFile.absolutePath} (${wavData.size} bytes)")
            audioFile
        } catch (e: Exception) {
            Timber.e(e, "Failed to create PTT audio file")
            null
        }
    }

    /**
     * Create WAV file from raw PCM data
     */
    private fun createWavFile(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val dataSize = pcmData.size
        val totalSize = dataSize + 44 - 8
        
        val header = ByteArray(44)
        
        // RIFF header
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        
        // File size
        header[4] = (totalSize and 0xff).toByte()
        header[5] = ((totalSize shr 8) and 0xff).toByte()
        header[6] = ((totalSize shr 16) and 0xff).toByte()
        header[7] = ((totalSize shr 24) and 0xff).toByte()
        
        // WAVE header
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        
        // fmt subchunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        
        // Subchunk1Size (16 for PCM)
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        
        // AudioFormat (1 for PCM)
        header[20] = 1
        header[21] = 0
        
        // NumChannels
        header[22] = channels.toByte()
        header[23] = 0
        
        // SampleRate
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        
        // ByteRate
        val byteRate = sampleRate * channels * bitsPerSample / 8
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        
        // BlockAlign
        val blockAlign = channels * bitsPerSample / 8
        header[32] = blockAlign.toByte()
        header[33] = 0
        
        // BitsPerSample
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        
        // data subchunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        
        // Subchunk2Size
        header[40] = (dataSize and 0xff).toByte()
        header[41] = ((dataSize shr 8) and 0xff).toByte()
        header[42] = ((dataSize shr 16) and 0xff).toByte()
        header[43] = ((dataSize shr 24) and 0xff).toByte()
        
        // Combine header and data
        return header + pcmData
    }

    /**
     * Calculate audio duration from PCM data
     */
    private fun calculateAudioDuration(audioData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): Long {
        val bytesPerSample = bitsPerSample / 8
        val totalSamples = audioData.size / (channels * bytesPerSample)
        val durationSeconds = totalSamples.toDouble() / sampleRate
        return (durationSeconds * 1000).toLong() // Convert to milliseconds
    }

    /**
     * Generate simple waveform for UI display
     */
    private fun generateSimpleWaveform(audioData: ByteArray): List<Int> {
        val waveform = mutableListOf<Int>()
        val chunkSize = audioData.size / 50 // Create 50 waveform points
        
        if (chunkSize <= 0) return emptyList()
        
        for (i in 0 until 50) {
            val startIndex = i * chunkSize
            val endIndex = minOf(startIndex + chunkSize, audioData.size - 1)
            
            var maxAmplitude = 0
            for (j in startIndex until endIndex step 2) {
                if (j + 1 < audioData.size) {
                    // Convert 16-bit PCM to amplitude
                    val sample = (audioData[j].toInt() and 0xFF) or 
                                ((audioData[j + 1].toInt() and 0xFF) shl 8)
                    val amplitude = kotlin.math.abs(sample)
                    maxAmplitude = maxOf(maxAmplitude, amplitude)
                }
            }
            
            // Normalize to 0-100 range
            val normalizedAmplitude = (maxAmplitude * 100 / 32767).coerceIn(0, 100)
            waveform.add(normalizedAmplitude)
        }
        
        return waveform
    }

    /**
     * Schedule file cleanup after Matrix upload
     */
    private fun scheduleFileCleanup(file: File) {
        // Schedule cleanup after 5 minutes to allow Matrix upload to complete
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                if (file.exists()) {
                    file.delete()
                    Timber.d("Cleaned up PTT file after upload: ${file.name}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to cleanup PTT file: ${file.name}")
            }
        }, 5 * 60 * 1000) // 5 minutes
    }

    /**
     * Clean up old PTT audio files to save storage
     */
    suspend fun cleanupOldPttFiles() = withContext(Dispatchers.IO) {
        try {
            val pttDir = File(context.cacheDir, PTT_AUDIO_DIR)
            if (!pttDir.exists()) return@withContext
            
            val now = System.currentTimeMillis()
            val maxAge = 24 * 60 * 60 * 1000L // 24 hours
            
            pttDir.listFiles()?.forEach { file ->
                if (now - file.lastModified() > maxAge) {
                    file.delete()
                    Timber.d("Cleaned up old PTT file: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to cleanup old PTT files")
        }
    }

}
