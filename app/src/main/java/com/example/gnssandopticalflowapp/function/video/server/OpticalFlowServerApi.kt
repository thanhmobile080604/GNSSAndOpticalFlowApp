package com.example.gnssandopticalflowapp.function.video.server

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
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

    @FormUrlEncoded
    @POST("process-video/uploads")
    suspend fun createProcessVideoUpload(
        @Field("file_name") fileName: String,
        @Field("file_size") fileSize: Long,
        @Field("chunk_size") chunkSize: Long,
        @Field("total_chunks") totalChunks: Int
    ): Response<ServerVideoUploadResponse>

    @Multipart
    @POST("process-video/uploads/{uploadId}/chunks")
    suspend fun uploadProcessVideoChunk(
        @Path("uploadId") uploadId: String,
        @Part chunk: MultipartBody.Part,
        @PartMap fields: Map<String, @JvmSuppressWildcards RequestBody>
    ): Response<ServerVideoUploadResponse>

    @FormUrlEncoded
    @POST("process-video/uploads/{uploadId}/complete")
    suspend fun completeProcessVideoUpload(
        @Path("uploadId") uploadId: String,
        @FieldMap fields: Map<String, @JvmSuppressWildcards String>
    ): Response<ServerVideoJobResponse>

    @DELETE("process-video/uploads/{uploadId}")
    suspend fun cancelProcessVideoUpload(
        @Path("uploadId") uploadId: String
    ): Response<ServerVideoUploadResponse>

    @GET("process-video/jobs/{jobId}")
    suspend fun getProcessVideoJob(
        @Path("jobId") jobId: String
    ): Response<ServerVideoJobResponse>

    @POST("process-video/jobs/{jobId}/cancel")
    suspend fun cancelProcessVideoJob(
        @Path("jobId") jobId: String
    ): Response<ServerVideoJobResponse>

    @Streaming
    @GET("process-video/jobs/{jobId}/result")
    suspend fun downloadProcessVideoJobResult(
        @Path("jobId") jobId: String
    ): Response<ResponseBody>

    @GET("process-video/jobs/{jobId}/result/info")
    suspend fun getProcessVideoJobResultInfo(
        @Path("jobId") jobId: String
    ): Response<ServerVideoResultInfoResponse>

    @Streaming
    @GET("process-video/jobs/{jobId}/result/chunks/{chunkIndex}")
    suspend fun downloadProcessVideoJobResultChunk(
        @Path("jobId") jobId: String,
        @Path("chunkIndex") chunkIndex: Int
    ): Response<ResponseBody>

    @DELETE("process-video/jobs/{jobId}/result")
    suspend fun cleanupProcessVideoJobResult(
        @Path("jobId") jobId: String
    ): Response<ServerVideoJobResponse>
}

data class ServerVideoJobResponse(
    @SerializedName("job_id")
    val jobId: String? = null,
    val status: String? = null,
    val progress: Int? = null,
    val error: String? = null
)

data class ServerVideoUploadResponse(
    @SerializedName("upload_id")
    val uploadId: String? = null,
    val status: String? = null,
    @SerializedName("file_size")
    val fileSize: Long? = null,
    @SerializedName("chunk_size")
    val chunkSize: Long? = null,
    @SerializedName("received_chunks")
    val receivedChunks: Int? = null,
    @SerializedName("total_chunks")
    val totalChunks: Int? = null,
    val detail: String? = null
)

data class ServerVideoResultInfoResponse(
    @SerializedName("job_id")
    val jobId: String? = null,
    @SerializedName("file_name")
    val fileName: String? = null,
    @SerializedName("file_size")
    val fileSize: Long? = null,
    @SerializedName("chunk_size")
    val chunkSize: Long? = null,
    @SerializedName("total_chunks")
    val totalChunks: Int? = null
)
