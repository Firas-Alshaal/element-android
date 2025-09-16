/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.content.Context
import android.os.SystemClock
import im.vector.app.features.voice.NetworkAudioQualityManager
import timber.log.Timber
import java.util.PriorityQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Adaptive Jitter Buffer for PTT audio streaming
 * Automatically adjusts buffer size based on network conditions and jitter patterns
 *
 * Features:
 * - Dynamic buffer sizing based on network type
 * - Jitter measurement and adaptation
 * - Packet reordering support
 * - Playout smoothing for consistent audio quality
 */
/*
class AdaptiveJitterBuffer(
    private val context: Context,
    private val initialTargetSize: Int = 1       // ✅ MINIMAL START - بدء بـ 3 frames فقط
) {
    
    data class TimestampedFrame(
        val data: ByteArray,
        val timestamp: Long,
        val sequenceNumber: Long = 0,
        val arrivalTime: Long = System.currentTimeMillis()
    )
    
    private val buffer = LinkedBlockingQueue<TimestampedFrame>()
    private var targetBufferSize = initialTargetSize
    private var lastPlayoutTime = 0L
    private var jitterSum = 0L
    private var jitterCount = 0
    private var lastNetworkCheck = 0L
    private var adaptationCooldown = 0L
    
    // Enhanced network adaptation parameters for cross-network stability
    private val NETWORK_CHECK_INTERVAL = 1_000L  // ✅ تقليل إلى ثانية واحدة - أسرع adaptation
    private val ADAPTATION_COOLDOWN = 2_00L     // ✅ تقليل إلى 200ms - استجابة فورية
    private val MIN_BUFFER_SIZE = 4              // ✅ تقليل للاستقرار - منع التقطيع المفرط
    private val MAX_BUFFER_SIZE = 12              // ✅ تقليل لمنع التأخير المفرط
    
    init {
        updateTargetSizeForNetwork()
        Timber.d("🎧 Adaptive Jitter Buffer initialized with target size: $targetBufferSize")
    }
    
    */
/**
 * Add frame to buffer with timestamp for jitter calculation
 *//*

    fun addFrame(data: ByteArray, timestamp: Long = System.currentTimeMillis(), seq: Long = 0): Boolean {
        val frame = TimestampedFrame(data, timestamp, seq)
        
        // Calculate jitter if we have a previous frame
        if (lastPlayoutTime > 0) {
            val expectedInterval = 20L // 20ms expected for PTT frames
            val actualInterval = abs(timestamp - lastPlayoutTime)
            val jitter = abs(actualInterval - expectedInterval)
            
            jitterSum += jitter
            jitterCount++
            
            // Adapt buffer size based on jitter every 20 frames
            if (jitterCount >= 20) {
                val avgJitter = jitterSum / jitterCount
                adaptBufferSizeForJitter(avgJitter)
                jitterSum = 0
                jitterCount = 0
            }
        }
        
        // Periodic network adaptation
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastNetworkCheck > NETWORK_CHECK_INTERVAL) {
            updateTargetSizeForNetwork()
            lastNetworkCheck = currentTime
        }
        
        // Add frame to buffer
        if (buffer.size >= MAX_BUFFER_SIZE) {
            buffer.poll() // Drop oldest frame if buffer is full
            Timber.v("⚠️ Jitter buffer full, dropping oldest frame")
        }
        
        val added = buffer.offer(frame)
        if (!added) {
            Timber.w("❌ Failed to add frame to jitter buffer")
        }
        
        return added
    }
    
    */
/**
 * Get next frame from buffer with enhanced playout timing for network transitions
 *//*

    fun getNextFrame(timeoutMs: Long = 120): ByteArray? { // ✅ زيادة timeout لمنع التقطيع
        // ✅ BALANCED BUFFERING - توازن لمنع التقطيع المفرط
        val networkType = NetworkAudioQualityManager.getCurrentNetworkType(context)
        val currentCondition = NetworkAudioQualityManager.getCurrentNetworkCondition()
        
        // ✅ BALANCED BUFFERING - توازن لمنع التقطيع المفرط
        val requiredPreBuffer = if (lastPlayoutTime == 0L) 3 else 2 // ✅ تقليل لمنع التقطيع المفرط
        
        if (buffer.size < requiredPreBuffer) {
            Timber.v("🚀 BALANCED buffering... current: ${buffer.size}, required: $requiredPreBuffer (${networkType}, jitter=${currentCondition.jitterMs}ms)")
            return null
        }
        
        // ✅ إزالة كامل لـ Network transition detection - لا توقف أبداً
        // val currentTime = System.currentTimeMillis()
        // if (currentTime - lastNetworkCheck < NETWORK_CHECK_INTERVAL && buffer.size < 2) {
        //     Timber.v("🌐 Network transition detected - minimal hold: ${buffer.size}/${targetBufferSize}")
        //     return null
        // }
        
        val frame = buffer.poll(timeoutMs, TimeUnit.MILLISECONDS)
        if (frame != null) {
            lastPlayoutTime = frame.timestamp
            return frame.data
        }
        
        return null
    }
    
    */
/**
 * Adapt buffer size based on measured jitter
 *//*

    private fun adaptBufferSizeForJitter(avgJitter: Long) {
        if (System.currentTimeMillis() - adaptationCooldown < ADAPTATION_COOLDOWN) {
            return // Still in cooldown period
        }
        
        val currentTargetSize = targetBufferSize
        
        // ✅ BALANCED ADAPTATION - توازن لمنع التقطيع المفرط
        when {
            avgJitter > 1500L -> { // ✅ تقليل threshold لمنع التقطيع المفرط
                targetBufferSize = minOf(targetBufferSize + 1, MAX_BUFFER_SIZE) // ✅ زيادة +1 فقط
                Timber.d("📈 High jitter (${avgJitter}ms) → buffer increase: $currentTargetSize → $targetBufferSize")
            }
            avgJitter > 800L -> { // ✅ إضافة threshold متوسط
                targetBufferSize = minOf(targetBufferSize + 1, MAX_BUFFER_SIZE) // ✅ زيادة +1 فقط
                Timber.d("📈 Medium jitter (${avgJitter}ms) → buffer increase: $currentTargetSize → $targetBufferSize")
            }
            avgJitter < 100L && targetBufferSize > MIN_BUFFER_SIZE + 1 -> { // ✅ تقليل فقط عند jitter منخفض جداً
                targetBufferSize = maxOf(targetBufferSize - 1, MIN_BUFFER_SIZE)
                Timber.d("📉 Low jitter (${avgJitter}ms) → Decreased buffer: $currentTargetSize → $targetBufferSize")
            }
            else -> {
                Timber.v("✅ Stable jitter (${avgJitter}ms) → Buffer size maintained: $targetBufferSize")
            }
        }
        
        if (targetBufferSize != currentTargetSize) {
            adaptationCooldown = System.currentTimeMillis()
            
            // 🎯 Update network condition for auto-adaptive system
            val packetLossPercent = if (avgJitter > 1000L) 5f else if (avgJitter > 500L) 3f else 1f
            NetworkAudioQualityManager.updateNetworkCondition(
                jitterMs = avgJitter,
                packetLossPercent = packetLossPercent,
                signalStrength = -1 // Could be enhanced with actual signal strength
            )
        }
    }
    
    */
/**
 * Update target buffer size based on current network conditions
 *//*

    private fun updateTargetSizeForNetwork() {
        val networkOptimal = NetworkAudioQualityManager.getOptimalJitterBufferSize(context)
        val previousTarget = targetBufferSize
        
        // ✅ BALANCED NETWORK ADAPTATION - توازن لمنع التقطيع المفرط
        val networkLimited = minOf(networkOptimal, 12) // ✅ تقليل حد أقصى لمنع التقطيع المفرط
        targetBufferSize = ((targetBufferSize + networkLimited) / 2).coerceIn(MIN_BUFFER_SIZE, MAX_BUFFER_SIZE)
        
        // ✅ تقليل التغييرات - فقط إذا كان الفرق كبير
        if (abs(targetBufferSize - previousTarget) > 2) {
            targetBufferSize = if (targetBufferSize > previousTarget) previousTarget + 2 else previousTarget - 2
        }
        
        if (targetBufferSize != previousTarget) {
            val networkType = NetworkAudioQualityManager.getCurrentNetworkType(context)
            Timber.d("🌐 STABLE Network adaptation ($networkType): $previousTarget → $targetBufferSize buffer size (minimal change)")
        }
    }
    
    */
/**
 * Get current buffer statistics for monitoring
 *//*

    fun getBufferStats(): BufferStats {
        return BufferStats(
            currentSize = buffer.size,
            targetSize = targetBufferSize,
            averageJitter = if (jitterCount > 0) jitterSum / jitterCount else 0L,
            isUnderrun = buffer.isEmpty() && lastPlayoutTime > 0,
            isOverrun = buffer.size >= MAX_BUFFER_SIZE
        )
    }
    
    */
/**
 * Clear buffer (useful when switching PTT sessions)
 *//*

    fun clear() {
        buffer.clear()
        lastPlayoutTime = 0L
        jitterSum = 0L
        jitterCount = 0
        Timber.d("🧹 Jitter buffer cleared")
    }
    
    */
/**
 * Check if buffer is ready for playout (has minimum frames)
 *//*

    fun isReady(): Boolean {
        return buffer.size >= (targetBufferSize / 2) || lastPlayoutTime > 0
    }
    
    data class BufferStats(
        val currentSize: Int,
        val targetSize: Int, 
        val averageJitter: Long,
        val isUnderrun: Boolean,
        val isOverrun: Boolean
    )
}
*/


class AdaptiveJitterBuffer(
        private val context: Context,
        val frameDurationMs: Int = 20,       // مدة كل فريم (ثابتة من المرسل)
        private val minBufferFrames: Int = 2,        // ≈100ms بداية التشغيل
        private val maxBufferFrames: Int = 6        // ≈300ms أقصى حجم
) {

    data class TimestampedFrame(
            val data: ByteArray,
            val seq: Long,
            val ts: Long,
            val arrival: Long = System.currentTimeMillis()
    )

    // ✅ PriorityQueue تضمن تشغيل بالترتيب الصحيح حتى لو الحزم وصلت out-of-order
    private val buffer = PriorityQueue<TimestampedFrame>(compareBy { it.seq })
    private val SILENCE_20MS = ByteArray(640)

    private var targetSize = minBufferFrames
    private var lastSeq: Long = -1
    private var jitterEstimate = 0L
    private var lastAdaptation = 0L

    private var firstPlayTime: Long? = null
    private var frameIndex: Long = 0

    /**
     * إضافة فريم للـ buffer
     */
    fun addFrame(data: ByteArray, ts: Long, seq: Long): Boolean {
        Timber.d("📥 Adding frame #$seq to buffer (current size: ${buffer.size})")

        if (buffer.any { it.seq == seq }) {
            Timber.w("⚠️ Duplicate frame #$seq, ignoring")
            return false
        }

        if (buffer.size >= maxBufferFrames) {
            val dropped = buffer.poll()
            Timber.w("⚠️ Buffer full, dropping frame #${dropped?.seq}")
        }

        buffer.offer(TimestampedFrame(data, seq, ts, SystemClock.elapsedRealtime()))
        Timber.d("✅ Frame #$seq added successfully (buffer size: ${buffer.size})")
        return true
    }

    /**
     * إرجاع الفريم التالي الجاهز للتشغيل
     */
    fun getNextFrame(): ByteArray? {
        // ✅ مرونة أكثر - السماح بالتشغيل حتى لو كان الـ buffer أقل من target
        val minRequired = if (firstPlayTime == null) minBufferFrames else maxOf(1, targetSize - 2)

        if (buffer.size < minRequired) {
            Timber.d("⏳ Buffer not ready: ${buffer.size}/$minRequired")
            return null
        }

        val next = buffer.peek() ?: return null
        val now = SystemClock.elapsedRealtime()

        if (firstPlayTime == null) {
            firstPlayTime = now + (minBufferFrames * frameDurationMs)
            frameIndex = next.seq  // baseSeq
            Timber.d("🎬 First play time set: ${firstPlayTime}, base seq: $frameIndex")
        }

        val targetPlayTime = firstPlayTime!! + ((next.seq - frameIndex) * frameDurationMs)

        return if (now >= targetPlayTime) {
            val expected = if (lastSeq >= 0) lastSeq + 1 else next.seq

            when {
                // الفريم الصحيح في الرأس
                next.seq == expected -> {
                    lastSeq = next.seq
                    val frame = buffer.poll()?.data
                    Timber.d("🎵 Playing frame #${next.seq}")
                    frame
                }
                // فجوة: أعطِ 20ms صمت (أو PLC لو نقلته هنا)
                next.seq > expected -> {
                    lastSeq = expected // نتقدم خطوة واحدة على خط الزمن
                    Timber.w("🔇 Gap detected, playing silence for seq $expected")
                    SILENCE_20MS
                }
                // فريم متأخر/مكرر: تخلّص منه
                else -> {
                    val dropped = buffer.poll()
                    Timber.w("🗑️ Dropping late/duplicate frame #${dropped?.seq}")
                    null
                }
            }
        } else {
            Timber.d("⏰ Frame #${next.seq} not ready yet (${targetPlayTime - now}ms remaining)")
            null
        }
    }

    /**
     * حساب jitter والتكيف
     */
    fun adaptJitter(arrivalJitterMs: Long) {
        jitterEstimate = (0.9 * jitterEstimate + 0.1 * arrivalJitterMs).toLong()

        val now = SystemClock.elapsedRealtime()
        if (now - lastAdaptation < 200) return
        val prev = targetSize

        when {
            jitterEstimate > 100 && targetSize < maxBufferFrames -> { // ✅ تقليل threshold من 120 إلى 100
                targetSize++
                Timber.d("📈 High jitter (${jitterEstimate}ms) → buffer increased to $targetSize")
            }
            jitterEstimate < 30 && targetSize > minBufferFrames -> { // ✅ تقليل threshold من 40 إلى 30
                targetSize--
                Timber.d("📉 Low jitter (${jitterEstimate}ms) → buffer decreased to $targetSize")
            }
        }

        if (targetSize != prev) {
            lastAdaptation = now
            NetworkAudioQualityManager.updateNetworkCondition(
                    jitterMs = jitterEstimate,
                    packetLossPercent = if (jitterEstimate > 100) 3f else 1f,
                    signalStrength = -1
            )
        }
    }

    fun clear() {
        buffer.clear()
        lastSeq = -1
        jitterEstimate = 0
        firstPlayTime = null
        frameIndex = 0
        Timber.d("�� Jitter buffer cleared")
    }

    fun isReady(): Boolean {
        val ready = buffer.size >= minBufferFrames
        Timber.d("🔍 Buffer ready check: ${buffer.size}/$minBufferFrames = $ready")
        return ready
    }
    data class BufferStats(
            val currentSize: Int,
            val targetSize: Int,
            val jitterMs: Long,
            val underrun: Boolean,
            val overrun: Boolean
    )

    fun getStats(): BufferStats = BufferStats(
            currentSize = buffer.size,
            targetSize = targetSize,
            jitterMs = jitterEstimate,
            underrun = buffer.isEmpty(),
            overrun = buffer.size >= maxBufferFrames
    )
}
