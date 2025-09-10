/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.callhistory

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.databinding.FragmentCallHistoryBinding
import im.vector.app.features.home.AvatarRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.RoomSummaryQueryParams
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.TimelineSettings
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class CallHistoryFragment : Fragment() {

    @Inject lateinit var avatarRenderer: AvatarRenderer
    @Inject lateinit var activeSessionHolder: ActiveSessionHolder

    private var _binding: FragmentCallHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var callHistoryAdapter: CallHistoryAdapter

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCallHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        loadCallHistory()
    }

    private fun setupRecyclerView() {
        callHistoryAdapter = CallHistoryAdapter(avatarRenderer)
        binding.recyclerViewCallHistory.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = callHistoryAdapter
            setHasFixedSize(true)
        }
    }

    private fun loadCallHistory() {
        Timber.w("📞 CALL HISTORY: loadCallHistory() started!")

        // Show loading indicator
        binding.loadingLayout.visibility = View.VISIBLE
        binding.recyclerViewCallHistory.visibility = View.GONE
        binding.emptyStateLayout.visibility = View.GONE

        CoroutineScope(Dispatchers.Main).launch {
            try {
                Timber.w("📞 CALL HISTORY: About to get real call history...")
                val realCallHistory = withContext(Dispatchers.IO) {
                    getRealCallHistoryFromAllRooms()
                }
                Timber.w("📞 CALL HISTORY: Got ${realCallHistory.size} call history items")

                // Hide loading indicator
                binding.loadingLayout.visibility = View.GONE

                callHistoryAdapter.updateCallHistory(realCallHistory)

                // Show appropriate content
                if (realCallHistory.isNotEmpty()) {
                    binding.recyclerViewCallHistory.visibility = View.VISIBLE
                    binding.emptyStateLayout.visibility = View.GONE
                    Timber.w("📞 CALL HISTORY: Found calls, showing list")
                } else {
                    binding.recyclerViewCallHistory.visibility = View.GONE
                    binding.emptyStateLayout.visibility = View.VISIBLE
                    Timber.w("📞 CALL HISTORY: No calls found, showing empty state")
                }
            } catch (e: Exception) {
                Timber.e(e, "📞 CALL HISTORY: Failed to load call history")

                // Hide loading indicator and show error state
                binding.loadingLayout.visibility = View.GONE
                binding.recyclerViewCallHistory.visibility = View.GONE
                binding.emptyStateLayout.visibility = View.VISIBLE

                callHistoryAdapter.updateCallHistory(emptyList())
            }
        }
    }

    private fun getRealCallHistoryFromAllRooms(): List<CallHistoryItem> {
        val oneWeekAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000
        val threeDaysAgo = System.currentTimeMillis() - 3 * 24 * 60 * 60 * 1000
        val session = activeSessionHolder.getSafeActiveSession() ?: return emptyList()
        val callHistoryList = mutableListOf<CallHistoryItem>()

        try {
            // Get all joined rooms
            val roomSummaries = session.roomService().getRoomSummaries(RoomSummaryQueryParams.Builder().build())
            val joinedRooms = roomSummaries
                    .filter { it.membership.isActive() }
                    .mapNotNull { session.getRoom(it.roomId) }

            Timber.d("=== CALL HISTORY DEBUG ====")
            Timber.d("User ID: ${session.myUserId}")
            Timber.d("Found ${joinedRooms.size} joined rooms to check for call events")

            if (joinedRooms.isEmpty()) {
                Timber.w("No joined rooms found!")
                return emptyList()
            }

            for (room in joinedRooms) {
                try {
                    val roomSummary = room.roomSummary()
                    val roomName = roomSummary?.displayName ?: "Unknown Room"
                    val avatarUrl = roomSummary?.avatarUrl
                    val isGroup = (roomSummary?.joinedMembersCount ?: 0) > 2
                    val myUserId = session.myUserId

                    Timber.w("📞 Processing room: ${roomName} (${room.roomId})")

                    // Try to get timeline events to find call events
                    val timelineService = room.timelineService()
                    val roomTimeline = try {
                        timelineService.createTimeline(null, TimelineSettings(initialSize = 50))
                    } catch (e: Exception) {
                        Timber.w(e, "📞 Could not create timeline for room ${room.roomId}")
                        null
                    }

                    val timelineEvents = mutableListOf<TimelineEvent>()

                    if (roomTimeline != null) {
                        try {
                            roomTimeline.start()
                            // Give timeline a moment to load
                            Thread.sleep(300)

                            val events = mutableListOf<TimelineEvent>()
                            var hasMore = true
                            var iteration = 0

                            while (hasMore && iteration < 10) {
                                val chunk = roomTimeline.getSnapshot()
                                events.addAll(chunk)

                                val oldestTs = chunk.minOfOrNull { it.root.originServerTs ?: Long.MAX_VALUE } ?: Long.MAX_VALUE
                                if (oldestTs < threeDaysAgo) {
                                    hasMore = false
                                } else {
                                    roomTimeline.paginate(Timeline.Direction.BACKWARDS, 10) // load 10 events each step
                                    hasMore = roomTimeline.hasMoreToLoad(Timeline.Direction.BACKWARDS)
                                }

                                iteration++
                            }

                            val callEvents = events.filter { timelineEvent ->
                                val eventType = timelineEvent.root.getClearType()
                                val ts = timelineEvent.root.originServerTs ?: 0L
                                eventType in setOf(EventType.CALL_INVITE, EventType.CALL_ANSWER, EventType.CALL_HANGUP)
                                        && ts >= threeDaysAgo
                            }

                            timelineEvents.addAll(callEvents)
                            Timber.w("📞 Room ${roomName}: Found ${callEvents.size} call events in timeline")

                            callEvents.forEach { event ->
                                Timber.w("📞   - ${event.root.getClearType()} from ${event.root.senderId} at ${event.root.originServerTs}")
                            }
                            roomTimeline.dispose()
                        } catch (e: Exception) {
                            Timber.e(e, "📞 Error processing timeline for room ${room.roomId}")
                            try {
                                roomTimeline.dispose()
                            } catch (ignored: Exception) {
                            }
                        }
                    }

                    // Also try state events as fallback
                    val stateEvents = try {
                        room.stateService().getStateEvents(
                                setOf(EventType.CALL_INVITE, EventType.CALL_ANSWER, EventType.CALL_HANGUP),
                                QueryStringValue.IsNotEmpty
                        ).filter { it.originServerTs ?: 0L >= oneWeekAgo }
                    } catch (e: Exception) {
                        Timber.w(e, "Could not get state events for room ${room.roomId}")
                        emptyList()
                    }

                    Timber.d("Room ${roomName}: Found ${stateEvents.size} call events in state")
                    stateEvents.forEach { event ->
                        Timber.d("  - State event: ${event.type} from ${event.senderId} at ${event.originServerTs}")
                    }

                    // Process timeline events (these are more likely to contain call events)
                    val allEvents = if (timelineEvents.isNotEmpty()) {
                        timelineEvents.map { it.root }
                    } else {
                        // Fallback to state events if no timeline events found
                        stateEvents
                    }

                    // Debug: Show all call events found in timeline
                    allEvents.forEach { event ->
                        Timber.w("📞 Event: ${event.getClearType()} from ${event.senderId} at ${event.originServerTs}")
                    }

                    // Simplify: treat each CALL_INVITE as a separate call
                    val inviteEvents = allEvents
                            .filter { it.getClearType() == EventType.CALL_INVITE }
                            .associateBy { it.content?.get("call_id") as? String ?: it.eventId }

                    Timber.w("📞 Room ${roomName}: Processing ${inviteEvents.size} call invitations")

                    for ((callId, inviteEvent) in inviteEvents) {

                        val relatedEvents = allEvents.filter {
                            val id = it.content?.get("call_id") as? String
                            id == callId
                        }
                        val answerEvent = relatedEvents.find { it.getClearType() == EventType.CALL_ANSWER }
                        val hangupEvent = relatedEvents.find { it.getClearType() == EventType.CALL_HANGUP }

                        Timber.w("📞 Processing invite from ${inviteEvent.senderId}, answer=${answerEvent?.senderId}, hangup=${hangupEvent?.senderId}")

                        // Process the call (inviteEvent is guaranteed to exist in this loop)
                        val isVideoCall = try {
                            inviteEvent.content?.get("offer")?.let { offer ->
                                (offer as? Map<*, *>)?.get("sdp")?.toString()?.contains("m=video") == true
                            } ?: false
                        } catch (e: Exception) {
                            false
                        }

                        val callDirection = when {
                            inviteEvent.senderId == myUserId -> CallDirection.OUTGOING
                            answerEvent == null -> CallDirection.MISSED// If no answer, it's missed
                            else -> CallDirection.INCOMING
                        }

                        val startTime = answerEvent?.originServerTs ?: inviteEvent.originServerTs ?: 0L
                        val endTime = hangupEvent?.originServerTs ?: startTime
                        val duration = if (answerEvent != null && hangupEvent != null) {
                            (endTime - startTime).coerceAtLeast(0L)
                        } else 0L


                        val callItem = CallHistoryItem(
                                callId = inviteEvent.eventId ?: "unknown",
                                roomName = roomName,
                                roomId = room.roomId,
                                callType = if (isVideoCall) CallType.VIDEO else CallType.VOICE,
                                callDirection = callDirection,
                                timestamp = inviteEvent.originServerTs ?: System.currentTimeMillis(),
                                duration = duration,
                                avatarUrl = avatarUrl,
                                isGroup = isGroup
                        )

                        callHistoryList.add(callItem)
                        Timber.w("📞 Added call: ${roomName} - ${callDirection} ${if (isVideoCall) "video" else "voice"} call from ${inviteEvent.senderId}")
                    }
                } catch (roomException: Exception) {
                    Timber.w(roomException, "Error processing room ${room.roomId}")
                    continue
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting call history from rooms")
        }

        // Sort by timestamp, most recent first
        callHistoryList.sortByDescending { it.timestamp }

        Timber.w("📞 === FINAL RESULT ===")
        Timber.w("📞 Found ${callHistoryList.size} total call events")
        if (callHistoryList.isNotEmpty()) {
            callHistoryList.forEach { call ->
                Timber.w("📞 - ${call.roomName}: ${call.callDirection} ${call.callType} call")
            }
        } else {
            Timber.w("📞 No call history found in any room!")
        }
        Timber.w("📞 ========================")
        return callHistoryList
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
