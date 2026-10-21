package com.tempmail.otp.data

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
object ApiClient {

    private const val BASE_URL = "https://mailboxtemp.com/"

    val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS) // Keep WebSocket alive
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    val api: MailboxTempApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(MailboxTempApi::class.java)
    }

    val webSocketClient: MailboxWebSocketClient by lazy {
        MailboxWebSocketClient(okHttpClient)
    }

    @Volatile
    private var monitorInstance: InboxMonitor? = null

    /**
     * Process-wide socket owner shared by the ViewModel and the foreground service.
     * First caller wins; the Application context is safe to retain.
     */
    fun monitor(context: Context): InboxMonitor =
        monitorInstance ?: synchronized(this) {
            monitorInstance ?: InboxMonitor(
                context.applicationContext,
                api,
                webSocketClient
            ).also { monitorInstance = it }
        }
}
