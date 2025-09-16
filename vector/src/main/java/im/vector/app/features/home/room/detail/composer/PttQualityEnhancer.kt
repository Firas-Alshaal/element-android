/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.content.Context
import im.vector.app.features.voice.NetworkAudioQualityManager
import timber.log.Timber

/**
 * PTT Quality Enhancement Utilities
 * Provides network-aware enhancements for PTT audio transmission
 */
object PttQualityEnhancer {
    
    /**
     * Add network-aware packet redundancy to Matrix PTT content
     */
    fun addPacketRedundancy(
        context: Context,
        content: MutableMap<String, Any>,
        currentSeq: Long,
        currentData: String,
        recentFrames: MutableList<Pair<Long, String>>,
        maxRedundantFrames: Int = 5
    ) {
        try {
            val redundancyLevel = NetworkAudioQualityManager.getPacketRedundancyLevel(context)
            
            // Store current frame for future redundancy
            recentFrames.add(currentSeq to currentData)
            if (recentFrames.size > maxRedundantFrames) {
                recentFrames.removeFirst()
            }
            
            // Add redundant frames based on network reliability
            if (redundancyLevel > 1 && recentFrames.size >= 2) {
                val redundantFrames = recentFrames.takeLast(minOf(redundancyLevel, recentFrames.size - 1))
                if (redundantFrames.isNotEmpty()) {
                    val redundantData = mutableMapOf<String, String>()
                    redundantFrames.forEach { (seq, data) ->
                        redundantData["frame_$seq"] = data
                    }
                    content["redundant_frames"] = redundantData
                    content["redundancy_level"] = redundancyLevel
                    
                    if (currentSeq % 20 == 0L) {
                        Timber.v("📡 Added ${redundantFrames.size} redundant frames for seq $currentSeq (network: ${NetworkAudioQualityManager.getCurrentNetworkType(context)})")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to add packet redundancy")
        }
    }
    
    /**
     * Process received Matrix PTT content with redundancy recovery
     */
    fun processRedundantFrames(content: Map<String, Any>): List<Pair<Long, String>> {
        val recoveredFrames = mutableListOf<Pair<Long, String>>()
        
        try {
            @Suppress("UNCHECKED_CAST")
            val redundantFrames = content["redundant_frames"] as? Map<String, String>
            val redundancyLevel = content["redundancy_level"] as? Int
            
            if (redundantFrames != null && redundancyLevel != null && redundancyLevel > 1) {
                redundantFrames.forEach { (frameKey, data) ->
                    if (frameKey.startsWith("frame_")) {
                        val seq = frameKey.substringAfter("frame_").toLongOrNull()
                        if (seq != null) {
                            recoveredFrames.add(seq to data)
                        }
                    }
                }
                
                if (recoveredFrames.isNotEmpty()) {
                    Timber.v("🔄 Recovered ${recoveredFrames.size} redundant frames")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to process redundant frames")
        }
        
        return recoveredFrames
    }
    
    /**
     * Get optimal frame timing based on network conditions
     */
    fun getOptimalFrameTiming(context: Context): FrameTiming {
        val networkType = NetworkAudioQualityManager.getCurrentNetworkType(context)
        
        return when (networkType) {
            "WIFI" -> FrameTiming(
                frameSize = 960,    // 20ms at 48kHz
                frameDurationMs = 20,
                maxLatency = 100
            )
            "4G", "LTE" -> FrameTiming(
                frameSize = 1440,   // 30ms at 48kHz  
                frameDurationMs = 30,
                maxLatency = 150
            )
            "3G" -> FrameTiming(
                frameSize = 960,    // 60ms at 16kHz
                frameDurationMs = 60,
                maxLatency = 300
            )
            else -> FrameTiming(
                frameSize = 1440,   // 30ms default
                frameDurationMs = 30,
                maxLatency = 200
            )
        }
    }
    
    data class FrameTiming(
        val frameSize: Int,
        val frameDurationMs: Int,
        val maxLatency: Int
    )
}
