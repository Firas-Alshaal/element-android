package im.vector.app.features.notifications

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object MatrixPushersApiProvider {

    private const val BASE_URL = "https://eoc.atlascrisis.com/Matrix"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
                .build()
    }

    val api: MatrixPushersApi by lazy {
        Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(MatrixPushersApi::class.java)
    }
}
