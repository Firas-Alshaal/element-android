/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import timber.log.Timber

/**
 * Network-aware audio quality manager for PTT systems
 * Provides adaptive bitrate and quality settings based on current network conditions
 */
object NetworkAudioQualityManager {

    /**
     * Audio quality profiles optimized for different network types
     */
    data class AudioQualityProfile(
        val bitrate: Int,
        val sampleRate: Int, 
        val bufferSizeMultiplier: Float,
        val description: String
    )
    
    /**
     * Adaptive quality profile with min/default/max levels
     */
    data class AdaptiveQualityProfile(
        val min: AudioQualityProfile,
        val default: AudioQualityProfile,
        val max: AudioQualityProfile
    )
    
    /**
     * Network condition metrics for adaptation
     */
    data class NetworkCondition(
        val jitterMs: Long = 0,
        val packetLossPercent: Float = 0f,
        val signalStrength: Int = -1,
        val isStable: Boolean = true
    )

    // 🌐 WiFi Auto-Adaptive Profile
    private val WIFI_ADAPTIVE = AdaptiveQualityProfile(
        min = AudioQualityProfile(
            bitrate = 32 * 1024,        // Min: 32kbps لـ latency منخفض
            sampleRate = 16000,
            bufferSizeMultiplier = 1.5f,
            description = "WiFi Fast/Low Latency"
        ),
        default = AudioQualityProfile(
            bitrate = 40 * 1024,        // Default: 40kbps متوازن
            sampleRate = 16000,
            bufferSizeMultiplier = 2.0f,
            description = "WiFi Balanced"
        ),
        max = AudioQualityProfile(
            bitrate = 48 * 1024,        // Max: 48kbps أقصى جودة
            sampleRate = 16000,
            bufferSizeMultiplier = 2.5f,
            description = "WiFi Safe/Anti-Cut"
        )
    )

    // 📱 4G/LTE Auto-Adaptive Profile
    private val LTE_ADAPTIVE = AdaptiveQualityProfile(
        min = AudioQualityProfile(
            bitrate = 24 * 1024,        // Min: 24kbps سريع
            sampleRate = 16000,
            bufferSizeMultiplier = 1.6f,
            description = "4G Fast/Low Latency"
        ),
        default = AudioQualityProfile(
            bitrate = 28 * 1024,        // Default: 28kbps متوازن
            sampleRate = 16000,
            bufferSizeMultiplier = 1.8f,
            description = "4G Balanced"
        ),
        max = AudioQualityProfile(
            bitrate = 32 * 1024,        // Max: 32kbps آمن
            sampleRate = 16000,
            bufferSizeMultiplier = 2.2f,
            description = "4G Safe/Anti-Cut"
        )
    )

    // 📶 3G Auto-Adaptive Profile
    private val MOBILE_3G_ADAPTIVE = AdaptiveQualityProfile(
        min = AudioQualityProfile(
            bitrate = 12 * 1024,        // Min: 12kbps سريع نسبياً
            sampleRate = 16000,
            bufferSizeMultiplier = 1.0f,
            description = "3G Fast"
        ),
        default = AudioQualityProfile(
            bitrate = 14 * 1024,        // Default: 14kbps متوازن
            sampleRate = 16000,
            bufferSizeMultiplier = 1.2f,
            description = "3G Balanced"
        ),
        max = AudioQualityProfile(
            bitrate = 16 * 1024,        // Max: 16kbps أقصى للـ 3G
            sampleRate = 16000,
            bufferSizeMultiplier = 1.5f,
            description = "3G Safe/Anti-Cut"
        )
    )

    // 🌐 Default Auto-Adaptive Profile (same as WiFi)
    private val DEFAULT_ADAPTIVE = WIFI_ADAPTIVE
    
    // Current network condition (updated in real-time)
    private var currentCondition = NetworkCondition()

    /**
     * Get optimal audio quality profile based on current network conditions
     */
    /**
     * Update network condition for real-time adaptation
     */
    fun updateNetworkCondition(jitterMs: Long, packetLossPercent: Float = 0f, signalStrength: Int = -1) {
        currentCondition = NetworkCondition(
            jitterMs = jitterMs,
            packetLossPercent = packetLossPercent,
            signalStrength = signalStrength,
            isStable = jitterMs < 100L && packetLossPercent < 2f
        )
    }

    fun getOptimalQualityProfile(context: Context): AudioQualityProfile {
        val networkType = getCurrentNetworkType(context)
        val adaptiveProfile = when (networkType) {
            "WIFI", "WIFI_FAST" -> WIFI_ADAPTIVE
            "4G", "4G_FAST", "LTE" -> LTE_ADAPTIVE
            "3G" -> MOBILE_3G_ADAPTIVE
            else -> DEFAULT_ADAPTIVE
        }
        
        // 🎯 Auto-Adaptive Selection Algorithm
        val selectedProfile = selectAdaptiveLevel(adaptiveProfile, currentCondition)
        
        android.util.Log.d("PTT", "🌐 Network: $networkType → Profile: ${selectedProfile.description} (${selectedProfile.bitrate/1024}kbps)")
        return selectedProfile
    }
    
    /**
     * Smart algorithm to select Min/Default/Max based on network condition
     */
    private fun selectAdaptiveLevel(profile: AdaptiveQualityProfile, condition: NetworkCondition): AudioQualityProfile {
        return when {
            // 🚀 MIN Profile: شبكة ممتازة - latency منخفض جداً
            condition.jitterMs < 50L && condition.packetLossPercent < 1f && condition.isStable -> {
                android.util.Log.d("PTT", "🚀 AUTO: MIN profile selected - jitter=${condition.jitterMs}ms, loss=${condition.packetLossPercent}%")
                profile.min
            }
            
            // 🛡️ MAX Profile: شبكة سيئة - أمان ضد التقطيع
            condition.jitterMs > 200L || condition.packetLossPercent > 5f || !condition.isStable -> {
                android.util.Log.d("PTT", "🛡️ AUTO: MAX profile selected - jitter=${condition.jitterMs}ms, loss=${condition.packetLossPercent}%")
                profile.max
            }
            
            // ⚖️ DEFAULT Profile: شبكة متوازنة
            else -> {
                android.util.Log.d("PTT", "⚖️ AUTO: DEFAULT profile selected - jitter=${condition.jitterMs}ms, loss=${condition.packetLossPercent}%")
                profile.default
            }
        }
    }

    /**
     * Get current network type for quality adaptation
     */
    fun getCurrentNetworkType(context: Context): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "UNKNOWN"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return "NONE"
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "UNKNOWN"

            return when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    // Try to detect cellular generation
                    when {
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && 
                        capabilities.linkDownstreamBandwidthKbps > 5000 -> "4G" // >5Mbps likely 4G+
                        capabilities.linkDownstreamBandwidthKbps > 1000 -> "4G"  // >1Mbps likely 4G
                        else -> "3G" // Lower bandwidth, assume 3G
                    }
                }
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                else -> "OTHER"
            }
        } else {
            // Legacy API for older Android versions
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            return when (networkInfo?.type) {
                @Suppress("DEPRECATION")
                ConnectivityManager.TYPE_WIFI -> "WIFI"
                @Suppress("DEPRECATION")
                ConnectivityManager.TYPE_MOBILE -> {
                    @Suppress("DEPRECATION")
                    when (networkInfo.subtype) {
                        android.telephony.TelephonyManager.NETWORK_TYPE_LTE,
                        android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP,
                        android.telephony.TelephonyManager.NETWORK_TYPE_EHRPD -> "4G"
                        android.telephony.TelephonyManager.NETWORK_TYPE_UMTS,
                        android.telephony.TelephonyManager.NETWORK_TYPE_HSPA -> "3G"
                        else -> "MOBILE"
                    }
                }
                else -> "OTHER"
            }
        }
    }

    /**
     * Calculate network-aware frame size for optimal transmission
     */
    fun getOptimalFrameSize(context: Context): Int {
        val networkType = getCurrentNetworkType(context)
        return when (networkType) {
            "WIFI" -> 960        // 20ms at 48kHz for low latency
            "4G", "LTE" -> 1440  // 30ms at 48kHz for balance
            "3G" -> 960          // 60ms at 16kHz for efficiency
            else -> 1440         // 30ms default
        }
    }

    /**
     * Get network-aware jitter buffer target size - Enhanced for cross-network stability
     */
    /**
     * Get auto-adaptive jitter buffer size based on network condition
     */
    fun getOptimalJitterBufferSize(context: Context): Int {
        val networkType = getCurrentNetworkType(context)
        
        // Base jitter buffer sizes for each network type
        val (minSize, defaultSize, maxSize) = when (networkType) {
            "WIFI", "WIFI_FAST" -> Triple(20, 30, 40)    // WiFi: 20-40 frames
            "4G", "4G_FAST", "LTE" -> Triple(40, 50, 70) // 4G: 40-70 frames
            "3G" -> Triple(60, 80, 100)                  // 3G: 60-100 frames
            else -> Triple(20, 30, 40)                   // Default: WiFi-like
        }
        
        // Auto-adaptive selection based on current condition
        val selectedSize = when {
            currentCondition.jitterMs < 50L && currentCondition.packetLossPercent < 1f -> minSize
            currentCondition.jitterMs > 200L || currentCondition.packetLossPercent > 5f -> maxSize
            else -> defaultSize
        }
        
        android.util.Log.d("PTT", "🎧 AUTO: JitterBuffer=${selectedSize} frames (${networkType}, jitter=${currentCondition.jitterMs}ms)")
        return selectedSize
    }

    /**
     * Check if network supports high-quality PTT streaming
     */
    fun isHighQualityNetworkAvailable(context: Context): Boolean {
        val networkType = getCurrentNetworkType(context)
        return networkType in listOf("WIFI", "4G", "LTE", "ETHERNET")
    }

    /**
     * Get recommended redundancy level based on network reliability - Enhanced for transitions
     */
    /**
     * Get auto-adaptive packet redundancy level based on network condition
     */
    fun getPacketRedundancyLevel(context: Context): Int {
        val networkType = getCurrentNetworkType(context)
        
        // Base redundancy levels for each network type
        val (minLevel, defaultLevel, maxLevel) = when (networkType) {
            "WIFI", "WIFI_FAST" -> Triple(2, 3, 4)       // WiFi: ×2–4 redundancy
            "4G", "4G_FAST", "LTE" -> Triple(4, 5, 7)    // 4G: ×4–7 redundancy
            "3G" -> Triple(7, 9, 10)                     // 3G: ×7–10 redundancy
            else -> Triple(2, 3, 4)                      // Default: WiFi-like
        }
        
        // Auto-adaptive selection based on current condition
        val selectedLevel = when {
            currentCondition.jitterMs < 50L && currentCondition.packetLossPercent < 1f -> minLevel
            currentCondition.jitterMs > 200L || currentCondition.packetLossPercent > 5f -> maxLevel
            else -> defaultLevel
        }
        
        android.util.Log.d("PTT", "📡 AUTO: Redundancy=×${selectedLevel} (${networkType}, loss=${currentCondition.packetLossPercent}%)")
        return selectedLevel
    }
    
    /**
     * Get current network condition for external components
     */
    fun getCurrentNetworkCondition(): NetworkCondition = currentCondition
}
