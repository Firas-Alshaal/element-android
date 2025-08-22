package im.vector.app.features.notifications

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.android.sdk.api.session.Session
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.UUID

object PushGatewayApiProvider {

    private const val BASE_URL = "http://10.100.10.162:5000/"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
                .build()
    }

    val api: PushGatewayApi by lazy {
        Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(PushGatewayApi::class.java)
    }
}

suspend fun uploadVoiceFileToSynapse(
        session: Session, // 🔴 pass session here
        fileUri: Uri,
        mimeType: String,
        fileName: String
): UploadResponse = withContext(Dispatchers.IO) {
    val accessToken = session.sessionParams.credentials.accessToken
    val token = "Bearer $accessToken"
//    val serverUrl = "http://10.100.10.162:8008"
    val serverUrl = "https://eoc.atlascrisis.com/Matrix"
    val uploadUrl = "$serverUrl/_matrix/media/v3/upload?filename=$fileName"

    val file = File(fileUri.path ?: throw IllegalArgumentException("Invalid URI"))

    val client = OkHttpClient()
    val body = file.asRequestBody(mimeType.toMediaType())
    val request = Request.Builder()
            .url(uploadUrl)
            .addHeader("Authorization", token)
            .addHeader("Content-Type", mimeType)
            .post(body)
            .build()

    val response = client.newCall(request).execute()
    if (!response.isSuccessful) {
        throw IOException("Upload failed: ${response.code} - ${response.message}")
    }

    val responseBody = response.body?.string() ?: throw IOException("Empty upload response")
    val json = JSONObject(responseBody)
    val contentUri = json.getString("content_uri")

     UploadResponse(contentUri)
}

data class UploadResponse(val contentUri: String)

fun fetchMediaHttpUrl(mediaId: String, callback: (String?) -> Unit) {
    val url = "http://10.100.10.162:8585/link/$mediaId"
    val client = OkHttpClient()

    val request = Request.Builder()
            .url(url)
            .get()
            .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Timber.e("❌ Failed to fetch HTTP URL: ${e.message}")
            callback(null)
        }

        override fun onResponse(call: Call, response: Response) {
            if (response.isSuccessful) {
                response.body?.string()?.let { body ->
                    try {
                        val json = JSONObject(body)
                        val link = json.optString("link")
                        if (link.isNotBlank()) {
                            Timber.d("✅ Got HTTP link: $link")
                            callback(link)
                        } else {
                            callback(null)
                        }
                    } catch (e: Exception) {
                        Timber.e("❌ JSON parsing error: ${e.message}")
                        callback(null)
                    }
                } ?: callback(null)
            } else {
                Timber.e("❌ HTTP error: ${response.code}")
                callback(null)
            }
        }
    })
}


fun sendPttNotificationViaHttp(audioUrl: String, roomId: String, receiverPushKey: String, session: Session) {
    val jsonMediaType = "application/json".toMediaType()

    val payload = JSONObject().apply {
        put("notification", JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("room_id", roomId)
            put("type", "push_to_talk")
            put("sender", session.myUserId)
            put("sender_display_name", "PushToTalk Sender")
            put("room_name", "PushToTalk Room")
//            put("room_alias", "#pushtotalk:10.100.10.162")
            put("room_alias", "#pushtotalk:eoc.atlascrisis.com")
            put("prio", "high")
            put("content", JSONObject().apply {
                put("msgtype", "m.audio")
                put("body", "Push-to-talk message")
                put("audio_url", audioUrl) // optional for display
            })
            put("counts", JSONObject().apply {
                put("unread", 1)
                put("missed_calls", 0)
            })
            put("devices", JSONArray().apply {
                put(JSONObject().apply {
                    put("app_id", "im.vector.app")
                    put("pushkey", receiverPushKey)
                    put("pushkey_ts", System.currentTimeMillis())
                    put("data", JSONObject().apply {
                        put("audio_url", audioUrl) // ✅ delivered to onMessageReceived
                        put("content_body", "Voice message received")
                    })
                    put("tweaks", JSONObject().apply {
                        put("sound", "default")
                    })
                })
            })
        })
    }

    val request = Request.Builder()
            .url("http://10.100.10.162:5000/_matrix/push/v1/notify")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

    println("📤 Sending payload:\n${payload.toString(2)}")


    OkHttpClient().newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Timber.e(e, "🔴 Failed to send PTT push notification")
        }

        override fun onResponse(call: Call, response: Response) {
            Timber.d("✅ Sent PTT push notification with audio URL. Status: ${response.code}")
        }
    })
}

