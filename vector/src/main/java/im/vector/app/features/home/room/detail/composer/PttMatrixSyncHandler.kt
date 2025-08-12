/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.content.Context
import android.content.Intent
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
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.getStateEvent
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
        
        // 🎯 نظام الأولويات للغرف
        private const val DEFAULT_PRIORITY = 0
        private const val HIGH_PRIORITY = 1
        private const val EMERGENCY_PRIORITY = 2
        
        // 📊 تتبع أولويات الغرف
        private val roomPriorities = mutableMapOf<String, Int>()
        
        // 📢 callback للإشعار بالقطع بسبب الأولوية
        private var onPriorityOverrideCallback: ((stoppedRoomId: String, newRoomId: String) -> Unit)? = null
        
        // 📊 إحصائيات التجاوز الإداري
        private var adminOverrideCount = 0
        
        /**
         * تعيين callback للإشعار عند قطع التسجيل بسبب الأولوية
         */
        fun setOnPriorityOverrideCallback(callback: (stoppedRoomId: String, newRoomId: String) -> Unit) {
            onPriorityOverrideCallback = callback
        }
        
        /**
         * استخراج مستوى الأولوية من topic الغرفة
         * 🔤 المتوقع في topic: "priority:1" أو "emergency" أو "high" 
         */
        fun extractRoomPriority(roomTopic: String?): Int {
            if (roomTopic.isNullOrBlank()) return DEFAULT_PRIORITY
            
            val topic = roomTopic.lowercase()
            return when {
                topic.contains("emergency") || topic.contains("طوارئ") || topic.contains("priority:2") -> EMERGENCY_PRIORITY
                topic.contains("high") || topic.contains("عالي") || topic.contains("priority:1") -> HIGH_PRIORITY
                topic.contains("priority:0") || topic.contains("default") -> DEFAULT_PRIORITY
                else -> DEFAULT_PRIORITY
            }
        }
        
        /**
         * تحديث أولوية الغرفة في الذاكرة
         */
        fun updateRoomPriority(roomId: String, priority: Int) {
            roomPriorities[roomId] = priority
            Timber.d("🎯 Room priority updated: $roomId → priority $priority")
        }
        
        /**
         * الحصول على أولوية الغرفة
         */
        fun getRoomPriority(roomId: String): Int {
            return roomPriorities[roomId] ?: DEFAULT_PRIORITY
        }
        
        /**
         * فحص صلاحيات المشرف في الغرفة
         * @param room الغرفة للفحص فيها
         * @param userId المستخدم المراد فحص صلاحياته
         * @return Boolean - true إذا كان مشرف، false إذا لم يكن
         */
        fun isUserAdmin(room: org.matrix.android.sdk.api.session.room.Room, userId: String): Boolean {
            return try {
                val powerLevelsEvent = room.getStateEvent(
                    org.matrix.android.sdk.api.session.events.model.EventType.STATE_ROOM_POWER_LEVELS,
                    org.matrix.android.sdk.api.query.QueryStringValue.IsEmpty
                )
                val powerLevelsContent = powerLevelsEvent?.content?.toModel<org.matrix.android.sdk.api.session.room.model.PowerLevelsContent>()
                
                if (powerLevelsContent != null) {
                    val helper = org.matrix.android.sdk.api.session.room.powerlevels.PowerLevelsHelper(powerLevelsContent)
                    val userPowerLevel = helper.getUserPowerLevelValue(userId)
                    
                    // اعتبار المستخدم مشرف إذا كان power level >= 50
                    val adminThreshold = 50
                    val isAdmin = userPowerLevel >= adminThreshold
                    
                    Timber.d("🛡️ Admin check: $userId power level = $userPowerLevel, admin threshold = $adminThreshold, is admin = $isAdmin")
                    isAdmin
                } else {
                    Timber.w("⚠️ Could not get power levels content for room ${room.roomId}")
                    false
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ Error checking admin status for user $userId in room ${room.roomId}")
                false
            }
        }
        
        /**
         * طلب التحدث مع تجاوز إداري (داخل نفس الغرفة)
         * @param room الغرفة 
         * @param userId المستخدم المراد منحه الإذن
         * @return Boolean - true إذا تم منح الإذن، false إذا تم الرفض
         */
        fun requestAdminOverride(room: org.matrix.android.sdk.api.session.room.Room, userId: String): Boolean {
            val roomId = room.roomId
            
            // 🛡️ فحص الصلاحيات الإدارية
            if (!isUserAdmin(room, userId)) {
                Timber.w("🚫 ADMIN OVERRIDE DENIED: $userId is not an admin in room $roomId")
                return false
            }
            
            // 🔍 فحص المتحدث الحالي في نفس الغرفة
            val currentSpeaker = currentSpeakerInRoom[roomId]
            
            if (currentSpeaker == null) {
                // لا يوجد متحدث - منح الإذن مباشرة
                currentSpeakerInRoom[roomId] = userId
                speakerStartTime[roomId] = System.currentTimeMillis()
                Timber.d("🎤 ADMIN FLOOR GRANTED to $userId in room $roomId (no current speaker)")
                return true
            }
            
            if (currentSpeaker == userId) {
                // المستخدم يتحدث بالفعل
                Timber.d("✅ ADMIN OVERRIDE: $userId is already speaking in room $roomId")
                return true
            }
            
            // 🚨 تجاوز إداري - قطع المتحدث الحالي
            Timber.w("🚨 ADMIN OVERRIDE: Admin $userId interrupting $currentSpeaker in room $roomId")
            
            // تسجيل التغيير
            val previousSpeaker = currentSpeaker
            currentSpeakerInRoom[roomId] = userId
            speakerStartTime[roomId] = System.currentTimeMillis()
            
            // 📊 تحديث الإحصائيات
            adminOverrideCount++
            
            // 📢 إشعار بالقطع الإداري مع تفاصيل واضحة
            Timber.i("📢 ADMIN INTERRUPT #$adminOverrideCount: $previousSpeaker was interrupted by admin $userId in room $roomId")
            onPriorityOverrideCallback?.invoke(roomId, roomId) // نفس الغرفة
            
            Timber.d("🎤 ADMIN OVERRIDE GRANTED to $userId in room $roomId")
            return true
        }

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
         * طلب التحدث مع مراعاة الأولويات
         * @param requestingRoomId غرفة المرسل
         * @param userId المستخدم الذي يريد التحدث
         * @return Boolean - true إذا تم منح الإذن، false إذا تم الرفض
         */
        fun requestSpeakingFloorWithPriority(requestingRoomId: String, userId: String): Boolean {
            val requestingPriority = getRoomPriority(requestingRoomId)
            
            // 🔍 البحث عن أي متحدث نشط في أي غرفة
            val activeSpeakers = currentSpeakerInRoom.filter { it.value != null }
            
            if (activeSpeakers.isEmpty()) {
                // لا يوجد متحدث نشط - منح الإذن مباشرة
                currentSpeakerInRoom[requestingRoomId] = userId
                speakerStartTime[requestingRoomId] = System.currentTimeMillis()
                Timber.d("🎤 FLOOR GRANTED to $userId in room $requestingRoomId (no active speakers)")
                return true
            }
            
            // 🎯 فحص الأولويات مقابل المتحدثين النشطين
            var canOverride = true
            var highestActivePriority = -1
            var activeRoomWithHighestPriority = ""
            
            activeSpeakers.forEach { (activeRoomId, /*activeSpeaker*/) ->
                val activePriority = getRoomPriority(activeRoomId)
                if (activePriority > highestActivePriority) {
                    highestActivePriority = activePriority
                    activeRoomWithHighestPriority = activeRoomId
                }
                
                if (activePriority >= requestingPriority) {
                    canOverride = false
                }
            }
            
            return if (canOverride) {
                // 🚨 قطع المتحدث الحالي ومنح الإذن للأولوية الأعلى
                activeSpeakers.forEach { (activeRoomId, activeSpeaker) ->
                    Timber.w("🚨 PRIORITY OVERRIDE: Stopping $activeSpeaker in room $activeRoomId (priority ${getRoomPriority(activeRoomId)}) for $userId in room $requestingRoomId (priority $requestingPriority)")
                    currentSpeakerInRoom[activeRoomId] = null
                    speakerStartTime.remove(activeRoomId)
                    
                    // 📢 إشعار بالقطع بسبب الأولوية
                    onPriorityOverrideCallback?.invoke(activeRoomId, requestingRoomId)
                }
                
                currentSpeakerInRoom[requestingRoomId] = userId
                speakerStartTime[requestingRoomId] = System.currentTimeMillis()
                Timber.d("🎤 PRIORITY FLOOR GRANTED to $userId in room $requestingRoomId (priority $requestingPriority)")
                true
            } else {
                Timber.w("🚫 PRIORITY FLOOR DENIED to $userId in room $requestingRoomId (priority $requestingPriority) - higher priority room $activeRoomWithHighestPriority (priority $highestActivePriority) is active")
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

    fun publishIpToRoom(roomId: String, ip: String) {
        val room = session.getRoom(roomId) ?: return
        val content = mapOf("ip" to ip)

        scope.launch {
            try {
                room.stateService().sendStateEvent(
                        eventType = "im.ptt.ip",
                        stateKey = session.myUserId,
                        body = content
                )
                Timber.d("✅ IP published to room $roomId: $ip for user: ${session.myUserId}")
            } catch (e: Exception) {
                Timber.e(e, "❌ Failed to publish IP to room $roomId")
            }
        }
    }

    fun getSenderIpForRoom(): String? {
        val room = session.getRoom(roomId) ?: return null
        val stateEvents = room.stateService().getStateEvents(
                eventTypes = setOf("im.ptt.ip"),
                stateKey = QueryStringValue.IsNotEmpty
        )
        val ipEvent = stateEvents.firstOrNull { it.senderId != myUserId }
        return ipEvent?.content?.get("ip") as? String
    }

    private fun startReceiverService(speakerId: String) {
        Timber.d("🎯 Attempting to start receiver service for speaker: $speakerId")

        val senderIp = getSenderIpForUser(speakerId)
        if (senderIp.isNullOrEmpty()) {
            Timber.w("❌ Cannot start receiver - sender IP is null for user: $speakerId")
            return
        }

        val intent = Intent(context, PttTcpReceiverService::class.java).apply {
            putExtra("roomId", roomId)
            putExtra("myUserId", myUserId)
            putExtra("speakerId", speakerId)
            putExtra("senderIp", senderIp)
        }
        Timber.d("Starting receiver service for $speakerId")


        try {
            context.startService(intent)
            Timber.d("Receiver service started")
        } catch (e: Exception) {
            Timber.e("Failed to start receiver service: ${e.message}")
        }
    }

    private fun stopReceiverService() {
        Timber.d("🛑 Stopping PttReceiverService for room=$roomId")
        val intent = Intent(context, PttTcpReceiverService::class.java).apply {
            putExtra("roomId", roomId)
        }
        try {
            context.stopService(intent)
            Timber.d("✅ PttReceiverService stopped successfully")
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to stop PttReceiverService")
        }
    }

    private fun getSenderIpForUser(userId: String): String? {
        val room = session.getRoom(roomId) ?: return null
        
        return try {
            // إضافة logging مفصل
            Timber.d("🔍 Looking for IP of user: $userId in room: $roomId")
            
            // البحث عن جميع IP events في الغرفة
            val allIpEvents = room.stateService().getStateEvents(
                    eventTypes = setOf("im.ptt.ip"),
                    stateKey = QueryStringValue.IsNotEmpty
            )
            
            Timber.d("📋 Found ${allIpEvents.size} IP events in room:")
            allIpEvents.forEach { event ->
                val ip = event.content?.get("ip")
                val timestamp = event.content?.get("timestamp")
                Timber.d("  - User: ${event.stateKey} → IP: $ip (${timestamp})")
            }
            
            val event = room.stateService().getStateEvent(
                    eventType = "im.ptt.ip",
                    stateKey = QueryStringValue.Equals(userId)
            )
            
            val ip = event?.content?.get("ip") as? String
            val timestamp = event?.content?.get("timestamp")
            
            if (ip != null) {
                Timber.d("🎯 IP for $userId: $ip (timestamp: $timestamp)")
                
                // التحقق من عمر IP (تحذير إذا كان قديماً)
                timestamp?.let { ts ->
                    val age = System.currentTimeMillis() - (ts as? Number)?.toLong()!!
                    if (age > 300000) { // 5 دقائق
                        Timber.w("⚠️ IP for $userId is ${age / 1000}s old - may be stale")
                    }
                }
            } else {
                Timber.w("❌ No IP found for $userId")
            }
            
            ip
        } catch (e: Exception) {
            Timber.e(e, "💥 Error getting IP for user: $userId")
            null
        }
    }
}
