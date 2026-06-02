package com.example.gnssandopticalflowapp.function.video.server

import com.example.gnssandopticalflowapp.BuildConfig
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object OpticalFlowServerClient {
    val api: OpticalFlowServerApi by lazy {
        Retrofit.Builder()
            .baseUrl(serverBaseUrl())
            .client(okHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpticalFlowServerApi::class.java)
    }

    private fun serverBaseUrl(): String {
        val baseUrl = BuildConfig.OPTICAL_FLOW_SERVER_BASE_URL.trim().trimEnd('/')
        require(baseUrl.startsWith("https://")) {
            "OPTICAL_FLOW_SERVER_BASE_URL must be a Cloudflare HTTPS URL."
        }
        return "$baseUrl/"
    }

    private fun okHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .writeTimeout(WRITE_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .build()
    }

    private const val CONNECT_TIMEOUT_SECONDS = 30L
    private const val READ_TIMEOUT_MINUTES = 30L
    private const val WRITE_TIMEOUT_MINUTES = 10L
}
