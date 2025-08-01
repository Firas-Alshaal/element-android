/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.location.live.map

import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.features.location.LocationData
import im.vector.app.features.home.room.detail.timeline.helper.LocationPinProvider
import im.vector.app.core.resources.DrawableProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.content.RoomMemberLocationContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.util.toMatrixItem
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.query.QueryStringValue
import im.vector.app.R
import org.matrix.android.sdk.api.session.room.members.roomMemberQueryParams
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

class GetRoomMembersLocationUseCase @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder,
        private val locationPinProvider: LocationPinProvider,
        private val drawableProvider: DrawableProvider
) {
    // Cache for pin drawables to avoid recreating them
    private val pinCache = ConcurrentHashMap<String, android.graphics.drawable.Drawable>()

    fun execute(roomId: String): Flow<List<UserLiveLocationViewState>> {
        val session = activeSessionHolder.getSafeActiveSession() ?: return flowOf(emptyList())
        
        return try {
            // Get all room members
            val room = session.roomService().getRoom(roomId) ?: return flowOf(emptyList())
            val roomMembers = room.membershipService().getRoomMembers(
                    queryParams = roomMemberQueryParams { 
                        memberships = listOf(Membership.JOIN)
                    }
            )
            
            // Get location state events for all members
            val memberLocations = roomMembers.mapNotNull { roomMember ->
                val stateEvent = room.stateService().getStateEvent(
                        EventType.STATE_ROOM_MEMBER_LOCATION,
                        QueryStringValue.Equals(roomMember.userId)
                )
                
                val locationContent = stateEvent?.content?.toModel<RoomMemberLocationContent>()
                
                // Extract location to local variable to avoid smart cast issues
                val location = locationContent?.location
                if (location != null) {
                    val userMatrixItem = roomMember.toMatrixItem()
                    val userId = roomMember.userId
                    
                    // Parse string values back to double
                    val latitude = location.latitude.toDoubleOrNull()
                    val longitude = location.longitude.toDoubleOrNull()
                    
                    if (latitude != null && longitude != null) {
                    
                    // Use cached pin or create new one  
                    val pinDrawable = pinCache[userId] ?: run {
                        // Use simple drawable for now - locationPinProvider.create needs callback
                        val drawable = drawableProvider.getDrawable(R.drawable.ic_location_user)
                                ?: drawableProvider.getDrawable(R.drawable.ic_location_pin)!!
                        pinCache[userId] = drawable
                        drawable
                    }
                    
                        UserLiveLocationViewState(
                                matrixItem = userMatrixItem,
                                pinDrawable = pinDrawable,
                                locationData = LocationData(
                                        latitude = latitude,
                                        longitude = longitude,
                                        uncertainty = location.uncertainty?.toDoubleOrNull()
                                ),
                                endOfLiveTimestampMillis = null,
                                locationTimestampMillis = locationContent.timestamp ?: System.currentTimeMillis(),
                                showStopSharingButton = false
                        )
                    } else null
                } else null
            }
            
            flowOf(memberLocations)
        } catch (exception: Exception) {
            flowOf(emptyList())
        }
    }
} 
 