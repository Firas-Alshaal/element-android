/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.content.Context
import android.content.Intent
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.asFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.query.QueryStringValue
import timber.log.Timber

class PttMatrixSyncHandler(
        private val context: Context,
        private val session: Session,
        private val myUserId: String,
        private val roomId: String
) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // ✅ POLICE RADIO PROTOCOL: Track who has the speaking floor
    companion object {
        @Volatile
        private var currentSpeakerInRoom = mutableMapOf<String, String?>()
        
        @Volatile
        private var speakerStartTime = mutableMapOf<String, Long>()
        
        @Volatile
        private var lastProcessedEventTime = mutableMapOf<String, Long>()
        
        private const val MAX_SPEAKING_TIME_MS = 30000L // 30 seconds max speaking time
        
        /**
         * Check if someone else is currently speaking in this room
         * @param roomId The room to check
         * @param myUserId Current user ID
         * @return Pair<Boolean, String?> - (isChannelBusy, currentSpeakerUserId)
         */
        fun isChannelBusy(roomId: String, myUserId: String): Pair<Boolean, String?> {
            val currentSpeaker = currentSpeakerInRoom[roomId]
            val speakingTime = speakerStartTime[roomId]?.let { System.currentTimeMillis() - it } ?: 0
            
            return when {
                currentSpeaker == null -> Pair(false, null) // Channel free
                currentSpeaker == myUserId -> Pair(false, currentSpeaker) // I'm already speaking
                speakingTime > MAX_SPEAKING_TIME_MS -> {
                    // Auto-release if someone is holding the floor too long
                    Timber.w("🚨 Auto-releasing floor from $currentSpeaker (${speakingTime}ms > ${MAX_SPEAKING_TIME_MS}ms)")
                    currentSpeakerInRoom[roomId] = null
                    speakerStartTime.remove(roomId)
                    Pair(false, null)
                }
                else -> Pair(true, currentSpeaker) // Channel busy by someone else
            }
        }
        
        /**
         * Request the speaking floor for police radio protocol
         * @param roomId Room ID
         * @param userId User requesting the floor
         * @return Boolean - true if floor granted, false if denied
         */
        fun requestSpeakingFloor(roomId: String, userId: String): Boolean {
            val (isBusy, currentSpeaker) = isChannelBusy(roomId, userId)
            
            return if (!isBusy) {
                currentSpeakerInRoom[roomId] = userId
                speakerStartTime[roomId] = System.currentTimeMillis()
                Timber.d("🎤 FLOOR GRANTED to $userId in room $roomId")
                true
            } else {
                Timber.w("🚫 FLOOR DENIED to $userId - $currentSpeaker is already speaking in room $roomId")
                false
            }
        }
        
        /**
         * Release the speaking floor
         * @param roomId Room ID
         * @param userId User releasing the floor
         */
        fun releaseSpeakingFloor(roomId: String, userId: String) {
            val currentSpeaker = currentSpeakerInRoom[roomId]
            if (currentSpeaker == userId) {
                currentSpeakerInRoom[roomId] = null
                speakerStartTime.remove(roomId)
                Timber.d("🔇 FLOOR RELEASED by $userId in room $roomId")
            } else {
                Timber.w("⚠️ $userId tried to release floor but $currentSpeaker is speaking in room $roomId")
            }
        }
        
        /**
         * Get current speaker info for UI display
         */
        fun getCurrentSpeaker(roomId: String): String? = currentSpeakerInRoom[roomId]
        
        /**
         * Get speaking duration for current speaker
         */
        fun getSpeakingDuration(roomId: String): Long {
            return speakerStartTime[roomId]?.let { System.currentTimeMillis() - it } ?: 0
        }
    }

    fun startListening() {
        val room = session.getRoom(roomId) ?: return

        Timber.d("🟢 PttMatrixSyncHandler: startListening() for room $roomId")

        val liveData = room.stateService().getStateEventsLive(
                setOf("ptt.status"),
                QueryStringValue.IsNotEmpty
        )

        // تحويل LiveData إلى Flow
        liveData.asFlow().onEach { events ->
            Timber.d("📥 Received ptt.status events: ${events.size} events")

            events.forEach { event ->
                Timber.d("🔎 Event content: ${event.content}")
                handleEvent(event)
            }
        }.launchIn(scope)
    }

    private fun handleEvent(event: Event) {
        val content = event.content ?: return
        val status = content["status"] as? String ?: return
        val speakerId = content["userId"] as? String ?: return
        val eventTimestamp = event.originServerTs ?: 0L

        Timber.d("👂 handleEvent: status=$status, speakerId=$speakerId, myUserId=$myUserId, timestamp=$eventTimestamp")

        if (speakerId == myUserId) {
            Timber.d("🔕 Skipping my own status event")
            return
        }
        
        // ✅ CRITICAL FIX: Ignore events older than 10 seconds to prevent stale event processing
        val currentTime = System.currentTimeMillis()
        val eventAge = currentTime - eventTimestamp
        if (eventAge > 10_000) {
            Timber.d("⏰ Ignoring stale event (${eventAge}ms old): status=$status, speakerId=$speakerId")
            return
        }
        
        // ✅ SEQUENCE PROTECTION: Only process events that are newer than the last processed event for this speaker
        val lastEventTime = lastProcessedEventTime[speakerId] ?: 0L
        if (eventTimestamp <= lastEventTime) {
            Timber.d("⏰ Ignoring out-of-order event from $speakerId (timestamp=$eventTimestamp <= last=$lastEventTime)")
            return
        }
        lastProcessedEventTime[speakerId] = eventTimestamp
        
        when (status) {
            "talking" -> {
                // ✅ POLICE RADIO PROTOCOL: Update floor control
                currentSpeakerInRoom[roomId] = speakerId
                speakerStartTime[roomId] = System.currentTimeMillis()
                
                Timber.d("🎙 POLICE RADIO: $speakerId has the floor - starting receiver service")
                
                // ✅ INSTANT START: Launch receiver service immediately with priority
                scope.launch(Dispatchers.Main.immediate) {
                    startReceiverService(speakerId)
                }
            }
            "idle" -> {
                // ✅ POLICE RADIO PROTOCOL: Release floor only if this speaker currently has it
                if (currentSpeakerInRoom[roomId] == speakerId) {
                    currentSpeakerInRoom[roomId] = null
                    speakerStartTime.remove(roomId)
                    Timber.d("🔇 POLICE RADIO: $speakerId released the floor")
                    Timber.d("🔇 Stopping receiver service")
                    stopReceiverService()
                } else {
                    Timber.d("🔕 Ignoring idle event from $speakerId (not current speaker: ${currentSpeakerInRoom[roomId]})")
                }
            }
            else -> {
                Timber.d("⚠️ Unknown status: $status")
            }
        }
    }

    private fun startReceiverService(speakerId: String) {
        val intent = Intent(context, PttReceiverService::class.java).apply {
            putExtra("roomId", roomId)
            putExtra("myUserId", myUserId)
            putExtra("speakerId", speakerId)
        }
        
        val port = roomId.hashCode() and 0xFFFF
        Timber.d("🚀 Launching PttReceiverService: speakerId=$speakerId, roomId=$roomId, port=$port")

        try {
            context.startService(intent)
            Timber.d("✅ PttReceiverService started successfully")
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to start PttReceiverService")
        }
    }

    private fun stopReceiverService() {
        Timber.d("🛑 Stopping PttReceiverService for room=$roomId")
        val intent = Intent(context, PttReceiverService::class.java).apply {
            putExtra("roomId", roomId)
        }
        try {
            context.stopService(intent)
            Timber.d("✅ PttReceiverService stopped successfully")
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to stop PttReceiverService")
        }
    }
}
