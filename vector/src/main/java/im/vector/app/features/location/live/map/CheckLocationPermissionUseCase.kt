package im.vector.app.features.location.live.map

import im.vector.app.core.di.ActiveSessionHolder
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.powerlevels.PowerLevelsHelper
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.events.model.toModel
import javax.inject.Inject

class CheckLocationPermissionUseCase @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder
) {

    suspend fun execute(roomId: String, userId: String): Boolean {
        return try {
            val session = activeSessionHolder.getSafeActiveSession() ?: return false
            val room = session.roomService().getRoom(roomId) ?: return false
            
            // Check if user has permission to send state events
            val powerLevelsContent = room.stateService().getStateEvent(EventType.STATE_ROOM_POWER_LEVELS, QueryStringValue.IsEmpty)
                    ?.content?.toModel<PowerLevelsContent>()
            
            val powerLevelsHelper = PowerLevelsHelper(powerLevelsContent ?: return false)
            powerLevelsHelper.isUserAllowedToSend(userId, true, EventType.STATE_ROOM_MEMBER_LOCATION)
        } catch (exception: Exception) {
            false
        }
    }
} 