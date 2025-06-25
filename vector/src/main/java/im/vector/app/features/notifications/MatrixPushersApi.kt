package im.vector.app.features.notifications

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface MatrixPushersApi {

    @GET("_matrix/client/r0/pushers")
    suspend fun getPushers(
            @Header("Authorization") authHeader: String
    ): Response<ResponseBody>
}

interface PushGatewayApi {
    @POST("_matrix/push/v1/notify")
    suspend fun sendFcmNotification(
            @Header("Authorization") authHeader: String,
            @Body payload: com.google.gson.JsonObject
    ): Response<ResponseBody>
}
