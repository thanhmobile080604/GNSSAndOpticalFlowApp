package com.example.gnssandopticalflowapp.video

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.PartMap
import retrofit2.http.Path
import retrofit2.http.Streaming

interface OpticalFlowServerApi {
    @Multipart
    @POST("process-video/jobs")
    suspend fun createProcessVideoJob(
        @Part file: MultipartBody.Part,
        @PartMap fields: Map<String, @JvmSuppressWildcards RequestBody>
    ): Response<ServerVideoJobResponse>

    @GET("process-video/jobs/{jobId}")
    suspend fun getProcessVideoJob(
        @Path("jobId") jobId: String
    ): Response<ServerVideoJobResponse>

    @Streaming
    @GET("process-video/jobs/{jobId}/result")
    suspend fun downloadProcessVideoJobResult(
        @Path("jobId") jobId: String
    ): Response<ResponseBody>
}

data class ServerVideoJobResponse(
    @SerializedName("job_id")
    val jobId: String? = null,
    val status: String? = null,
    val progress: Int? = null,
    val error: String? = null
)
