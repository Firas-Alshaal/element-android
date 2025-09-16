package im.vector.app.features.home.room.detail.composer

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.asFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.RoomSummaryQueryParams
import org.matrix.android.sdk.api.session.room.getStateEvent
import org.matrix.android.sdk.api.session.room.model.Membership
import timber.log.Timber
import java.net.Inet4Address
import java.net.NetworkInterface

object GlobalPttManagerHolder {
    var manager: GlobalPttManager? = null

    fun ensureStarted(context: Context, session: Session) {

        if (!session.isOpenable) {
            Timber.w("⛔ Session not openable yet. Skipping GlobalPttManager start.")
            return
        }


        if (manager == null) {
            Timber.d("🚀 INITIALIZING GlobalPttManager for user: ${session.myUserId}")
            manager = GlobalPttManager(context, session)
            Handler(Looper.getMainLooper()).post {
                manager?.startMonitoringRooms()
            }
        } else {
            Timber.d("✅ GlobalPttManager already running for user: ${session.myUserId}")
        }
    }
}

class GlobalPttManager(
        private val context: Context,
        private val session: Session
) {

    private val startedRooms = mutableSetOf<String>()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun startMonitoringRooms() {
        session.roomService()
                .getRoomSummariesLive(RoomSummaryQueryParams.Builder().apply {
                    memberships = listOf(Membership.JOIN)
                }.build())
                .asFlow()
                .onEach { roomSummaries ->

                    val currentRoomIds = roomSummaries.map { it.roomId }.toSet()
                    val newRooms = currentRoomIds - startedRooms

                    Timber.d("🔍 Found ${newRooms.size} new rooms to monitor")
                    newRooms.forEach { roomId ->
                        Timber.d("🏠 Setting up PTT monitoring for room: $roomId")

                        val room = session.getRoom(roomId) ?: return@forEach // 🛡️ حماية من null

                        val pttHandler = PttMatrixSyncHandler(
                                context = context,
                                session = session,
                                myUserId = session.myUserId,
                                roomId = roomId
                        )
                        // ✅ المفتاح: بدء الاستماع لأحداث Matrix
                        pttHandler.startListening()
                        Timber.d("✅ PttMatrixSyncHandler started listening for room: $roomId")

                        val myIp = getLocalIpAddress()
                        Timber.d("🌐 Local IP detected: $myIp")
                        if (myIp == "unknown") {
                            Timber.w("⚠️ IP غير معروف، لن يتم نشره")
                            return@forEach
                        }

                        val powerLevelsEvent = room.getStateEvent(
                                "m.room.power_levels",
                                QueryStringValue.Equals("") // ✅ هذا هو الشكل الصحيح
                        )
                        val powerLevelsContent = powerLevelsEvent?.content
                        val usersMap = powerLevelsContent?.get("users") as? Map<*, *>
                        val userLevel = (usersMap?.get(session.myUserId) as? Number)?.toInt() ?: 0

                        val eventsMap = powerLevelsContent?.get("events") as? Map<*, *>
                        val requiredLevel = (eventsMap?.get("im.ptt.ip") as? Number)?.toInt() ?: 50

                        Timber.d("🔐 Permission check for ${session.myUserId}: userLevel=$userLevel, requiredLevel=$requiredLevel")
                        Timber.d("📊 PowerLevels users: ${usersMap?.keys}")
                        Timber.d("📊 PowerLevels events: ${eventsMap?.keys}")

                        if (userLevel >= requiredLevel) {
                            Timber.d("✅ Publishing IP to room $roomId")
                            pttHandler.publishIpToRoom(roomId, myIp)
                        } else {
                            Timber.w("🚫 لا صلاحية لنشر IP إلى $roomId (userLevel=$userLevel < required=$requiredLevel)")
                        }

                        val legacyPreStartIntent = Intent(context, PttTcpReceiverService::class.java).apply {
                            putExtra("roomId", roomId)
                            putExtra("myUserId", session.myUserId)
                            putExtra("senderIp", myIp)
                            putExtra("speakerId", "PRESTART")
                        }
                        try {
                            context.startService(legacyPreStartIntent)
                            Timber.d("🚀 Enhanced PTT Receiver pre-started for room $roomId")
                        } catch (e: Exception) {

                        }

                        startedRooms.add(roomId)
                    }

                    val removedRooms = startedRooms - currentRoomIds
                    removedRooms.forEach { roomId ->
                        val stopIntent = Intent(context, PttTcpReceiverService::class.java).apply {
                            putExtra("roomId", roomId)
                        }
                        context.stopService(stopIntent)
                        startedRooms.remove(roomId)
                        Timber.d("🛑 تم إيقاف الاستماع للغرفة $roomId")
                    }

                }
                .launchIn(scope)
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress
                        if (!ip.isNullOrEmpty() && ip.startsWith("10.")) { // فقط من الشبكة المحلية
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ فشل في الحصول على عنوان IP المحلي")
        }
        return "unknown"
    }
}
