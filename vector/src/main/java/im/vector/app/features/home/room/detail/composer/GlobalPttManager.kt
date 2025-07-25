import android.content.Context
import android.content.Intent
import androidx.lifecycle.asFlow
import im.vector.app.features.home.room.detail.composer.PttMatrixSyncHandler
import im.vector.app.features.home.room.detail.composer.PttReceiverService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.RoomSummaryQueryParams

class GlobalPttManager(
        private val context: Context,
        private val session: Session
) {

    private val startedRooms = mutableSetOf<String>()

    fun startMonitoringRooms() {
        session.roomService()
                .getRoomSummariesLive(RoomSummaryQueryParams.Builder().build())
                .asFlow()
                .onEach { roomSummaries ->
                    withContext(Dispatchers.Main) {
                        val currentRoomIds = roomSummaries.map { it.roomId }.toSet()
                        val newRooms = currentRoomIds - startedRooms

                        newRooms.forEach { roomId ->
                            val pttHandler = PttMatrixSyncHandler(
                                    context = context,
                                    session = session,
                                    myUserId = session.myUserId,
                                    roomId = roomId
                            )
                            pttHandler.startListening()
                            
                            // ✅ POLICE RADIO: Pre-start receiver service for INSTANT readiness
                            val preStartIntent = Intent(context, PttReceiverService::class.java).apply {
                                putExtra("roomId", roomId)
                                putExtra("myUserId", session.myUserId)
                                putExtra("speakerId", "PRESTART") // Special flag for pre-start
                            }
                            context.startService(preStartIntent)
                            
                            startedRooms.add(roomId)
                        }

                        val removedRooms = startedRooms - currentRoomIds
                        removedRooms.forEach { roomId ->
                            val stopIntent = Intent(context, PttReceiverService::class.java).apply {
                                putExtra("roomId", roomId)
                            }
                            context.stopService(stopIntent)
                            startedRooms.remove(roomId)
                        }
                    }
                }
                .flowOn(Dispatchers.Default) // حتى لا تبطئ الواجهة
                .launchIn(CoroutineScope(Dispatchers.Main + SupervisorJob()))
    }
}
