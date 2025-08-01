/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.location.live.map

import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.features.location.LocationData
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.content.RoomMemberLocationContent
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.room.powerlevels.PowerLevelsHelper
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.events.model.toModel
import timber.log.Timber
import javax.inject.Inject

class SaveRoomMemberLocationUseCase @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder
) {

    suspend fun execute(
            roomId: String,
            userId: String,
            locationData: LocationData,
            @Suppress("UNUSED_PARAMETER") description: String? = null
    ): Result<Unit> {
        return try {
            val session = activeSessionHolder.getSafeActiveSession() 
                    ?: return Result.failure(IllegalStateException("No active session"))
            
            val room = session.roomService().getRoom(roomId)
                    ?: return Result.failure(IllegalStateException("Room not found"))
            
            // Check if user has permission to send state events
            val powerLevelsContent = room.stateService().getStateEvent(EventType.STATE_ROOM_POWER_LEVELS, QueryStringValue.IsEmpty)
                    ?.content?.toModel<PowerLevelsContent>()
            
            val powerLevelsHelper = PowerLevelsHelper(powerLevelsContent ?: return Result.failure(IllegalStateException("No power levels found")))
            val canSendStateEvent = powerLevelsHelper.isUserAllowedToSend(userId, true, EventType.STATE_ROOM_MEMBER_LOCATION)
            
            if (!canSendStateEvent) {
                Timber.w("User $userId does not have permission to send state events")
                return Result.failure(SecurityException("Insufficient permissions to save location data"))
            }
            
            val locationContent = RoomMemberLocationContent(
                    uri = "geo:${locationData.latitude},${locationData.longitude}",
                    location = RoomMemberLocationContent.LocationInfo(
                            latitude = String.format("%.6f", locationData.latitude),
                            longitude = String.format("%.6f", locationData.longitude),
                            uncertainty = locationData.uncertainty?.let { String.format("%.2f", it) }
                    ),
                    timestamp = System.currentTimeMillis()
            )
            
            room.stateService().sendStateEvent(
                    eventType = EventType.STATE_ROOM_MEMBER_LOCATION,
                    stateKey = userId,
                    body = locationContent.toContent()
            )
            
            Timber.d("Successfully saved location for user $userId in room $roomId")
            Result.success(Unit)
        } catch (exception: Exception) {
            Timber.e(exception, "Failed to save member location")
            Result.failure(exception)
        }
    }
} 
