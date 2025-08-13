/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.groupmap

import com.airbnb.mvrx.Async
import com.airbnb.mvrx.MavericksState
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.platform.VectorViewModelAction
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.platform.VectorViewEvents
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import kotlinx.coroutines.launch
import im.vector.app.features.roomprofile.RoomProfileArgs
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.api.session.room.members.roomMemberQueryParams
import timber.log.Timber

data class GroupMapViewState(
        val roomId: String,
        val isLoading: Boolean = false,
        val isMapLoaded: Boolean = false,
        val error: String? = null,
        val members: Async<List<RoomMemberSummary>> = Uninitialized
) : MavericksState {

    constructor(args: RoomProfileArgs) : this(roomId = args.roomId)
}

sealed class GroupMapAction : VectorViewModelAction {
    object LoadMap : GroupMapAction()
    object LoadMembers : GroupMapAction()
}

sealed class GroupMapViewEvents : VectorViewEvents {
    object MapLoaded : GroupMapViewEvents()
    data class MapLoadError(val message: String) : GroupMapViewEvents()
    data class MembersLoaded(val members: List<RoomMemberSummary>) : GroupMapViewEvents()
}

class GroupMapViewModel @AssistedInject constructor(
        @Assisted initialState: GroupMapViewState,
        private val session: Session,
        private val vtpkMapProvider: VtpkMapProvider
) : VectorViewModel<GroupMapViewState, GroupMapAction, GroupMapViewEvents>(initialState) {

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<GroupMapViewModel, GroupMapViewState> {
        override fun create(initialState: GroupMapViewState): GroupMapViewModel
    }

    companion object : MavericksViewModelFactory<GroupMapViewModel, GroupMapViewState> by hiltMavericksViewModelFactory()

    private val room = session.getRoom(initialState.roomId)

    override fun handle(action: GroupMapAction) {
        when (action) {
            is GroupMapAction.LoadMap -> handleLoadMap()
            is GroupMapAction.LoadMembers -> handleLoadMembers()
        }
    }
    
    fun getVtpkMapPath(): String? {
        return vtpkMapProvider.getDefaultMapPath()
    }
    
    fun getVtpkProvider(): VtpkMapProvider {
        return vtpkMapProvider
    }

    private fun handleLoadMap() {
        setState { copy(isLoading = true, error = null) }
        
        viewModelScope.launch {
            try {
                // Check if VTPK files are available
                if (!vtpkMapProvider.areVtpkFilesAvailable()) {
                    throw Exception("VTPK map files not available. Please ensure map files are in assets folder.")
                }
                
                // Get the default map path (UAE map)
                val mapPath = vtpkMapProvider.getDefaultMapPath()
                if (mapPath == null) {
                    throw Exception("Failed to get map file path")
                }
                
                Timber.d("Loading map from: $mapPath")
                
                // VTPK map will be loaded by the Fragment using ArcGIS Runtime
                // This is just for state management
                setState { 
                    copy(
                        isLoading = false, 
                        isMapLoaded = true,
                        error = null
                    ) 
                }
                
                _viewEvents.post(GroupMapViewEvents.MapLoaded)
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to load map")
                setState { 
                    copy(
                        isLoading = false, 
                        isMapLoaded = false,
                        error = e.message ?: "Unknown error"
                    ) 
                }
                _viewEvents.post(GroupMapViewEvents.MapLoadError(e.message ?: "Unknown error"))
            }
        }
    }

    private fun handleLoadMembers() {
        if (room == null) {
            setState { copy(error = "Room not found") }
            return
        }

        viewModelScope.launch {
            try {
                val members = room.membershipService().getRoomMembers(roomMemberQueryParams { })
                setState { copy(members = Success(members)) }
                _viewEvents.post(GroupMapViewEvents.MembersLoaded(members))
                Timber.d("Loaded ${members.size} members")
            } catch (e: Exception) {
                Timber.e(e, "Failed to load members")
                setState { copy(error = "Failed to load members: ${e.message}") }
            }
        }
    }
}
