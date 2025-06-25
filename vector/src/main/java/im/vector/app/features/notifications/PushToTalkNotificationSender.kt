/*
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.notifications

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Response
import timber.log.Timber
import java.lang.Exception
import java.util.UUID
import javax.inject.Inject

class PushToTalkNotificationSender @Inject constructor() {

    suspend fun getPushKeys(accessToken: String): List<String> = withContext(Dispatchers.IO) {
        try {
            // Because our interface now has "suspend fun getPushers(...): Response<ResponseBody>"
            val response: Response<ResponseBody> =
                    MatrixPushersApiProvider.api.getPushers("Bearer $accessToken")

            if (response.isSuccessful) {
                val bodyStr = response.body()?.string().orEmpty()
                if (bodyStr.isBlank()) {
                    Timber.tag("PushToTalk").e("getPushers: empty body")
                    return@withContext emptyList()
                }

                // Parse JSON array under “pushers”
                val rootJson = JSONObject(bodyStr)
                val pushersArray: JSONArray = rootJson.optJSONArray("pushers") ?: JSONArray()

                // Collect all non‐blank "pushkey" fields
                List(pushersArray.length()) { i ->
                    pushersArray.optJSONObject(i)?.optString("pushkey")
                }
                        .filterNotNull()
                        .filter { it.isNotBlank() }
            } else {
                Timber.tag("PushToTalk")
                        .e("getPushKeys failed: HTTP ${response.code()} – ${response.errorBody()?.string()}")
                emptyList()
            }
        } catch (e: Exception) {
            Timber.tag("PushToTalk").e("Exception in getPushKeys: ${e.localizedMessage}")
            emptyList()
        }
    }

    suspend fun sendPushToTalkMessage(
            accessToken: String,
            targetPushKey: String,
            audioUrl: String,
            roomId: String,
            senderName: String,
            senderId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {

            val notificationObj = com.google.gson.JsonObject().apply {
                addProperty("id", UUID.randomUUID().toString())
                addProperty("room_id", roomId)
                addProperty("type", "push_to_talk")
                addProperty("sender", senderId)
                addProperty("sender_display_name", senderName)
                addProperty("room_name", "PushToTalk Room")
                addProperty("room_alias", "#pushtotalk:$roomId")
                addProperty("prio", "high")

                // content { "msgtype": "m.audio", "body": ..., "audio_url": ... }
                val contentObj = com.google.gson.JsonObject().apply {
                    addProperty("msgtype", "m.audio")
                    addProperty("body", "Push-to-talk message")
                    addProperty("audio_url", audioUrl)
                }
                add("content", contentObj)

                // counts { "unread": 1, "missed_calls": 0 }
                val countsObj = com.google.gson.JsonObject().apply {
                    addProperty("unread", 1)
                    addProperty("missed_calls", 0)
                }
                add("counts", countsObj)

                // devices: [ { "app_id": "...", "pushkey": "...", "pushkey_ts": 123, "data": {...}, "tweaks": {...} } ]
                val deviceEntry = com.google.gson.JsonObject().apply {
                    addProperty("app_id", "im.vector.app")
                    addProperty("pushkey", targetPushKey)
                    addProperty("pushkey_ts", System.currentTimeMillis())

                    // data { "content_body": "Voice message received", "audio_url": ... }
                    val dataObj = com.google.gson.JsonObject().apply {
                        addProperty("content_body", "Voice message received")
                        addProperty("audio_url", audioUrl)
                    }
                    add("data", dataObj)

                    // tweaks { "sound": "default" }
                    val tweaksObj = com.google.gson.JsonObject().apply {
                        addProperty("sound", "default")
                    }
                    add("tweaks", tweaksObj)
                }
                val devicesArray = com.google.gson.JsonArray().apply {
                    add(deviceEntry)
                }
                add("devices", devicesArray)
            }

            // wrap it under { "notification": { … } }
            val payloadObj = com.google.gson.JsonObject().apply {
                add("notification", notificationObj)
            }

            val response: Response<ResponseBody> =
                    PushGatewayApiProvider.api.sendFcmNotification(
                            authHeader = "Bearer $accessToken",
                            payload = payloadObj
                    )

//            val response: Response<ResponseBody> =
//                    PushGatewayApiProvider.api.sendFcmNotification(
//                            "Bearer $accessToken",
//                            payload,
//                    )
            if (response.isSuccessful) {
                Timber.tag("PushToTalk").d("✅ PTT push sent to $targetPushKey")
                true
            } else {
                Timber.tag("PushToTalk").e("❌ Push failed: ${response.code()} ${response.errorBody()?.string()}")
                false
            }
        } catch (e: Exception) {
            Timber.tag("PushToTalk").e("🔥 Exception: ${e.message}")
            false
        }
    }
}
