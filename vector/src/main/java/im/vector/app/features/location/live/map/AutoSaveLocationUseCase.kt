/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.location.live.map

import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.utils.PERMISSIONS_FOR_FOREGROUND_LOCATION_SHARING
import im.vector.app.features.location.LocationData
import im.vector.app.features.location.LocationTracker
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class AutoSaveLocationUseCase @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder,
        private val saveRoomMemberLocationUseCase: SaveRoomMemberLocationUseCase,
        private val locationTracker: LocationTracker
) {
    
    suspend fun execute(roomId: String, hasLocationPermission: Boolean): Result<Unit> {
        return try {
            val session = activeSessionHolder.getSafeActiveSession() 
                    ?: return Result.failure(IllegalStateException("No active session"))
            
            if (!hasLocationPermission) {
                return Result.failure(IllegalStateException("No location permission"))
            }
            
            // بدء تتبع الموقع
            locationTracker.start()
            locationTracker.requestLastKnownLocation()
            
            // انتظار الموقع الأول
            val locationData = locationTracker.locations.first()
            
            // حفظ الموقع
            saveRoomMemberLocationUseCase.execute(
                    roomId = roomId,
                    userId = session.myUserId,
                    locationData = locationData,
            )
            
            Result.success(Unit)
        } catch (exception: Exception) {
            Result.failure(exception)
        }
    }
} 
