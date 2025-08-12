/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import kotlinx.coroutines.delay
import im.vector.app.features.home.room.detail.composer.PttMatrixSyncHandler
import androidx.recyclerview.widget.ConcatAdapter.Config.StableIdMode
import androidx.recyclerview.widget.LinearLayoutManager
import com.airbnb.epoxy.OnModelBuildFinishedListener
import com.airbnb.mvrx.fragmentViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.epoxy.LayoutManagerStateRestorer
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.platform.StateView
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.resources.UserPreferencesProvider
import im.vector.app.core.utils.FirstItemUpdatedObserver
import im.vector.app.core.utils.toast
import im.vector.app.databinding.FragmentRoomListBinding
import im.vector.app.features.analytics.plan.ViewRoom
import im.vector.app.features.home.room.detail.composer.PttManager
import im.vector.app.features.home.room.detail.composer.PttTcpReceiverService
import im.vector.app.features.home.room.list.RoomListAnimator
import im.vector.app.features.home.room.list.RoomListListener
import im.vector.app.features.home.room.list.RoomSummaryItem
import im.vector.app.features.home.room.list.actions.RoomListQuickActionsBottomSheet
import im.vector.app.features.home.room.list.actions.RoomListQuickActionsSharedAction
import im.vector.app.features.home.room.list.actions.RoomListQuickActionsSharedActionViewModel
import im.vector.app.features.home.room.list.home.header.HomeRoomFilter
import im.vector.app.features.home.room.list.home.header.HomeRoomsHeadersController
import im.vector.app.features.home.room.list.home.invites.InvitesActivity
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.SpaceChildInfo
import org.matrix.android.sdk.api.session.room.model.tag.RoomTag
import org.matrix.android.sdk.api.session.room.notification.RoomNotificationState
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class HomeRoomListFragment :
        VectorBaseFragment<FragmentRoomListBinding>(),
        RoomListListener {

    @Inject lateinit var userPreferencesProvider: UserPreferencesProvider
    @Inject lateinit var headersController: HomeRoomsHeadersController
    @Inject lateinit var roomsController: HomeFilteredRoomsController

    private val roomListViewModel: HomeRoomListViewModel by fragmentViewModel()
    private lateinit var sharedQuickActionsViewModel: RoomListQuickActionsSharedActionViewModel
    private var concatAdapter = ConcatAdapter(
            ConcatAdapter.Config.Builder()
                    .setIsolateViewTypes(true)
                    .setStableIdMode(StableIdMode.ISOLATED_STABLE_IDS).build(),
            emptyList()
    )
    private lateinit var firstItemObserver: FirstItemUpdatedObserver
    private var modelBuildListener: OnModelBuildFinishedListener? = null

    private lateinit var stateRestorer: LayoutManagerStateRestorer

    private val pttManager: PttManager? by lazy {
        activeSessionHolder.getSafeActiveSession()?.let {
            PttManager(requireContext(), it)
        }
    }
    @Inject lateinit var activeSessionHolder: ActiveSessionHolder

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private var permissionGrantedCallback: ((Boolean) -> Unit)? = null

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentRoomListBinding {
        return FragmentRoomListBinding.inflate(inflater, container, false)
    }

    override fun onStartPtt(roomId: String) {
        Timber.d("🎙️ Start PTT streaming for room: $roomId")
        
        // ✅ POLICE RADIO PROTOCOL: Check if channel is busy
        val session = activeSessionHolder.getActiveSession()
        val (isBusy, currentSpeaker) = PttMatrixSyncHandler.isChannelBusy(roomId, session.myUserId)
        if (isBusy && currentSpeaker != null) {
            context?.toast("🚫 Channel busy - $currentSpeaker is speaking")
            Timber.w("🚫 POLICE RADIO: Channel busy - $currentSpeaker is speaking")
            return
        }
        
        try {
            // Stop any existing receiver service immediately
            val stopIntent = Intent(requireContext(), PttTcpReceiverService::class.java).apply {
                putExtra("roomId", roomId)
            }
            requireContext().stopService(stopIntent)
            
            // ✅ COORDINATE PTT START: Send Matrix event first, then wait before audio
            sendPttStatus(roomId, "talking")
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Wait for Matrix event to propagate
                    delay(500)
                    pttManager?.startStreamingCoordinated(roomId)
                } catch (e: Exception) {
                    Timber.e(e, "❌ Failed to start PTT for room: $roomId")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error starting PTT for room: $roomId")
        }
    }

    override fun onStopPtt(roomId: String) {
        Timber.d("🛑 Stop PTT streaming for room: $roomId")
        // ✅ POLICE RADIO PROTOCOL: Release speaking floor and send Matrix idle status
        val session = activeSessionHolder.getActiveSession()
        PttMatrixSyncHandler.releaseSpeakingFloor(roomId, session.myUserId)
        
        pttManager?.stopStreaming()
        sendPttStatus(roomId, "idle")
    }

    private fun sendPttStatus(roomId: String, status: String) {
        val session = activeSessionHolder.getActiveSession()

        val content = mapOf(
                "status" to status,
                "userId" to session.myUserId
        )

        val room = session.getRoom(roomId) ?: return

        // Use viewLifecycleOwner.lifecycleScope to ensure proper lifecycle management
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO + SupervisorJob()) {
            try {
                room.stateService().sendStateEvent("ptt.status", session.myUserId, content)
            } catch (e: Exception) {
                // Handle permission errors gracefully - don't crash the app
                when {
                    e.message?.contains("M_FORBIDDEN") == true -> {
                        Timber.w("⚠️ User doesn't have permission to send PTT status: ${e.message}")
                    }
                    e.message?.contains("user_level") == true -> {
                        Timber.w("⚠️ User level too low for PTT status: ${e.message}")
                    }
                    else -> {
                        Timber.e(e, "❌ Failed to send ptt.status: ${e.message}")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions[Manifest.permission.RECORD_AUDIO] == true
            permissionGrantedCallback?.invoke(granted)
            permissionGrantedCallback = null
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        views.stateView.contentView = views.roomListView
        views.stateView.state = StateView.State.Loading
        setupObservers()
        setupRecyclerView()
        
        // ✅ Set up global timeout callback for wave animation
        PttManager.setGlobalTimeoutCallback { roomId ->
            requireActivity().runOnUiThread {
                Timber.d("⏰ Global PTT timeout occurred for room: $roomId")
                handlePttTimeout(roomId)
            }
        }
    }

    override fun requestVoicePermission(context: Context, callback: (Boolean) -> Unit) {
        permissionGrantedCallback = callback
        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
    }

    override fun checkPttPermissionAndStart(roomId: String, callback: (Boolean) -> Unit) {
        Timber.d("🔍 Checking PTT permission for room: $roomId")

        val session = activeSessionHolder.getActiveSession()
        val room = session.getRoom(roomId) ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val stateKey = QueryStringValue.Equals("", QueryStringValue.Case.SENSITIVE)
                val powerLevelsEvent = room.stateService().getStateEvent("m.room.power_levels", stateKey)
                val powerLevels = powerLevelsEvent?.content?.toModel<PowerLevelsContent>()

                val userLevel = powerLevels?.users?.get(session.myUserId) ?: powerLevels?.usersDefault ?: 0
                val requiredLevel = powerLevels?.events?.get("ptt.status") ?: powerLevels?.stateDefault ?: 50

                Timber.d("🔍 User level: $userLevel, Required level: $requiredLevel")

                withContext(Dispatchers.Main) {
                    if (userLevel >= requiredLevel) {
                        Timber.d("✅ User has PTT permission")
                        callback(true) // ✅ يملك الصلاحية
                    } else {
                        Timber.w("❌ User doesn't have PTT permission")
                        Toast.makeText(context, "You don't have permission to send voice in this room", Toast.LENGTH_LONG).show()
                        callback(false) // ❌ لا يملك الصلاحية
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ Error checking PTT permission")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error checking permissions", Toast.LENGTH_SHORT).show()
                    callback(false)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()

        // Local rooms should not exist anymore when the room list is shown
        roomListViewModel.handle(HomeRoomListAction.DeleteAllLocalRoom)
    }

    private fun setupObservers() {
        sharedQuickActionsViewModel = activityViewModelProvider[RoomListQuickActionsSharedActionViewModel::class.java]
        sharedQuickActionsViewModel
                .stream()
                .onEach(::handleQuickActions)
                .launchIn(viewLifecycleOwner.lifecycleScope)

        roomListViewModel.observeViewEvents {
            when (it) {
                is HomeRoomListViewEvents.Loading -> showLoading(it.message)
                is HomeRoomListViewEvents.Failure -> showFailure(it.throwable)
                is HomeRoomListViewEvents.SelectRoom -> handleSelectRoom(it, it.isInviteAlreadyAccepted)
                is HomeRoomListViewEvents.Done -> Unit
            }
        }
    }

    private fun handleQuickActions(quickAction: RoomListQuickActionsSharedAction) {
        when (quickAction) {
            is RoomListQuickActionsSharedAction.NotificationsAllNoisy -> {
                roomListViewModel.handle(HomeRoomListAction.ChangeRoomNotificationState(quickAction.roomId, RoomNotificationState.ALL_MESSAGES_NOISY))
            }
            is RoomListQuickActionsSharedAction.NotificationsAll -> {
                roomListViewModel.handle(HomeRoomListAction.ChangeRoomNotificationState(quickAction.roomId, RoomNotificationState.ALL_MESSAGES))
            }
            is RoomListQuickActionsSharedAction.NotificationsMentionsOnly -> {
                roomListViewModel.handle(HomeRoomListAction.ChangeRoomNotificationState(quickAction.roomId, RoomNotificationState.MENTIONS_ONLY))
            }
            is RoomListQuickActionsSharedAction.NotificationsMute -> {
                roomListViewModel.handle(HomeRoomListAction.ChangeRoomNotificationState(quickAction.roomId, RoomNotificationState.MUTE))
            }
            is RoomListQuickActionsSharedAction.Settings -> {
                navigator.openRoomProfile(requireActivity(), quickAction.roomId)
            }
            is RoomListQuickActionsSharedAction.Favorite -> {
                roomListViewModel.handle(HomeRoomListAction.ToggleTag(quickAction.roomId, RoomTag.ROOM_TAG_FAVOURITE))
            }
            is RoomListQuickActionsSharedAction.LowPriority -> {
                roomListViewModel.handle(HomeRoomListAction.ToggleTag(quickAction.roomId, RoomTag.ROOM_TAG_LOW_PRIORITY))
            }
            is RoomListQuickActionsSharedAction.Leave -> {
                promptLeaveRoom(quickAction.roomId)
            }
        }
    }

    private fun setupRecyclerView() {
        views.stateView.state = StateView.State.Content
        val layoutManager = LinearLayoutManager(requireContext())
        firstItemObserver = FirstItemUpdatedObserver(layoutManager) {
            layoutManager.scrollToPosition(0)
        }
        stateRestorer = LayoutManagerStateRestorer(layoutManager).register()
        views.roomListView.layoutManager = layoutManager
        views.roomListView.itemAnimator = RoomListAnimator()
        layoutManager.recycleChildrenOnDetach = true

        modelBuildListener = OnModelBuildFinishedListener { it.dispatchTo(stateRestorer) }

        roomListViewModel.onEach(HomeRoomListViewState::headersData) {
            headersController.submitData(it)
        }
        roomListViewModel.filteredPagedRoomSummariesLive.livePagedList.observe(viewLifecycleOwner) { roomsList ->
            roomsController.submitRoomsList(roomsList)
        }

        roomListViewModel.filteredPagedRoomSummariesLive.liveBoundaries.observe(viewLifecycleOwner) {
            roomsController.boundaryChange(it)
        }

        roomListViewModel.onEach(HomeRoomListViewState::emptyState) { emptyState ->
            roomsController.submitEmptyStateData(emptyState)
        }

        setUpAdapters()

        views.roomListView.adapter = concatAdapter

        concatAdapter.registerAdapterDataObserver(firstItemObserver)
    }

    override fun invalidate() = Unit

    private fun setUpAdapters() {
        val headersAdapter = headersController.also { controller ->
            controller.invitesClickListener = ::onInvitesCounterClicked
            controller.onFilterChangedListener = ::onRoomFilterChanged
            controller.recentsRoomListener = this
        }.adapter

        val roomsAdapter = roomsController
                .also { controller ->
                    controller.listener = this
                }.adapter

        concatAdapter.addAdapter(headersAdapter)
        concatAdapter.addAdapter(roomsAdapter)
    }

    private fun promptLeaveRoom(roomId: String) {
        val isPublicRoom = roomListViewModel.isPublicRoom(roomId)
        val message = buildString {
            append(getString(CommonStrings.room_participants_leave_prompt_msg))
            if (!isPublicRoom) {
                append("\n\n")
                append(getString(CommonStrings.room_participants_leave_private_warning))
            }
        }
        MaterialAlertDialogBuilder(
                requireContext(),
                if (isPublicRoom) 0 else im.vector.lib.ui.styles.R.style.ThemeOverlay_Vector_MaterialAlertDialog_Destructive
        )
                .setTitle(CommonStrings.room_participants_leave_prompt_title)
                .setMessage(message)
                .setPositiveButton(CommonStrings.action_leave) { _, _ ->
                    roomListViewModel.handle(HomeRoomListAction.LeaveRoom(roomId))
                }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }

    private fun onInvitesCounterClicked() {
        startActivity(Intent(activity, InvitesActivity::class.java))
    }

    private fun onRoomFilterChanged(filter: HomeRoomFilter) {
        roomListViewModel.handle(HomeRoomListAction.ChangeRoomFilter(filter))
    }

    private fun handleSelectRoom(event: HomeRoomListViewEvents.SelectRoom, isInviteAlreadyAccepted: Boolean) {
        navigator.openRoom(
                context = requireActivity(),
                roomId = event.roomSummary.roomId,
                isInviteAlreadyAccepted = isInviteAlreadyAccepted,
                trigger = ViewRoom.Trigger.RoomList
        )
    }

    override fun onDestroyView() {
        views.roomListView.cleanup()

        headersController.recentsRoomListener = null
        headersController.invitesClickListener = null
        headersController.onFilterChangedListener = null

        roomsController.listener = null

        concatAdapter.unregisterAdapterDataObserver(firstItemObserver)

        // ✅ Clear global timeout callback
        PttManager.clearGlobalTimeoutCallback()
        
        super.onDestroyView()
    }

    // region RoomListListener

    override fun onRoomClicked(room: RoomSummary) {
        roomListViewModel.handle(HomeRoomListAction.SelectRoom(room))
    }

    override fun onRoomLongClicked(room: RoomSummary): Boolean {
        userPreferencesProvider.neverShowLongClickOnRoomHelpAgain()
        RoomListQuickActionsBottomSheet
                .newInstance(room.roomId)
                .show(childFragmentManager, "ROOM_LIST_QUICK_ACTIONS")
        return true
    }

    override fun onRejectRoomInvitation(room: RoomSummary) = Unit

    override fun onAcceptRoomInvitation(room: RoomSummary) = Unit

    override fun onJoinSuggestedRoom(room: SpaceChildInfo) = Unit

    override fun onSuggestedRoomClicked(room: SpaceChildInfo) = Unit

    override fun onPttTimeout(roomId: String) {
        Timber.d("⏰ PTT timeout for room: $roomId")
        // ✅ POLICE RADIO PROTOCOL: Release speaking floor and send Matrix idle status
        val session = activeSessionHolder.getActiveSession()
        PttMatrixSyncHandler.releaseSpeakingFloor(roomId, session.myUserId)
        
        pttManager?.stopStreaming()
        sendPttStatus(roomId, "idle")
    }

    private fun handlePttTimeout(roomId: String) {
        Timber.d("⏰ Handling PTT timeout for room: $roomId")
        // ✅ Stop wave animation using the static method
        RoomSummaryItem.stopWaveAnimationForRoom(roomId)
        // ✅ Also handle the timeout through the existing mechanism
        onPttTimeout(roomId)
    }

    // endregion
}
