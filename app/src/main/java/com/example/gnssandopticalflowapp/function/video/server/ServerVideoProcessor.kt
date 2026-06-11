package com.example.gnssandopticalflowapp.function.video.server

import android.content.Context
import android.util.Log
import com.example.gnssandopticalflowapp.common.AndroidConnectivityObserver
import com.example.gnssandopticalflowapp.function.video.state.VideoProcessingProgressText
import com.example.gnssandopticalflowapp.model.VideoProcessOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import retrofit2.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

internal class ServerVideoProcessor(
    context: Context,
    private val callbacks: Callbacks,
    private val api: OpticalFlowServerApi = OpticalFlowServerClient.api
) {
    private val connectivityObserver = AndroidConnectivityObserver(context.applicationContext)

    @Volatile
    private var currentServerJobId: String? = null

    @Volatile
    private var currentServerUploadId: String? = null

    interface Callbacks {
        fun isActive(): Boolean
        fun postStatus(status: String)
        fun postPercent(percent: Int)
        fun postServerJobId(serverJobId: String)
    }

    suspend fun process(
        sourceFile: File,
        outputFile: File,
        options: VideoProcessOptions
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            callbacks.postStatus("Uploading to server...")
            val serverJobId = createServerVideoJob(sourceFile, options) ?: return@withContext false
            currentServerJobId = serverJobId
            callbacks.postStatus("Processing on server...")
            return@withContext waitForServerVideoJob(serverJobId, outputFile)
        } catch (e: CancellationException) {
            cancelCurrentWork()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Server connection failed: ${e.message}", e)
            return@withContext false
        } finally {
            currentServerUploadId = null
            currentServerJobId = null
        }
    }

    suspend fun cancelCurrentWork() {
        cancelCurrentServerUpload()
        cancelCurrentServerJob()
    }

    private suspend fun createServerVideoJob(
        sourceFile: File,
        options: VideoProcessOptions
    ): String? {
        if (sourceFile.length() > SERVER_CHUNK_UPLOAD_THRESHOLD_BYTES) {
            return createChunkedServerVideoJob(sourceFile, options)
        }

        while (callbacks.isActive()) {
            Log.d(
                TAG,
                "Creating server video job mode=${if (options.useFarnebackHeatmap) "HEATMAP" else "VECTORS"} " +
                    "algorithm=${serverAlgorithmName(options)} isMoving=${options.isMoving}"
            )
            val response = runServerNetworkRequest("Server job upload") {
                val videoBody = sourceFile.asRequestBody("video/mp4".toMediaType())
                val videoPart = MultipartBody.Part.createFormData("file", sourceFile.name, videoBody)
                api.createProcessVideoJob(
                    file = videoPart,
                    fields = serverMultipartFields(options)
                )
            }

            if (response.isSuccessful) {
                return response.body()?.jobId?.takeIf { it.isNotBlank() }?.also { serverJobId ->
                    currentServerJobId = serverJobId
                    callbacks.postServerJobId(serverJobId)
                }
            }

            if (response.code() == HTTP_TOO_MANY_REQUESTS) {
                Log.w(TAG, "Server queue is full; waiting before retrying job upload")
                callbacks.postStatus("Server queue is full; waiting...")
                delay(SERVER_QUEUE_RETRY_DELAY_MS)
                continue
            }

            Log.e(TAG, "Server job create error: ${response.code()} ${response.errorBodyText()}")
            return null
        }
        throw CancellationException("Video processing stopped")
    }

    private suspend fun createChunkedServerVideoJob(
        sourceFile: File,
        options: VideoProcessOptions
    ): String? {
        val fileSize = sourceFile.length()
        if (fileSize <= 0L) return null
        val chunkSize = SERVER_UPLOAD_CHUNK_BYTES.coerceAtMost(fileSize)
        val totalChunks = ((fileSize + chunkSize - 1L) / chunkSize).toInt()
        callbacks.postStatus("Preparing chunk upload...")

        while (callbacks.isActive()) {
            val createResponse = runServerNetworkRequest("Server chunk upload session") {
                api.createProcessVideoUpload(
                    fileName = sourceFile.name,
                    fileSize = fileSize,
                    chunkSize = chunkSize,
                    totalChunks = totalChunks
                )
            }

            if (!createResponse.isSuccessful) {
                if (createResponse.code() == HTTP_TOO_MANY_REQUESTS) {
                    Log.w(TAG, "Server upload slots are full; waiting before retrying chunk upload")
                    callbacks.postStatus("Server upload slots are full; waiting...")
                    delay(SERVER_QUEUE_RETRY_DELAY_MS)
                    continue
                }
                Log.e(TAG, "Server upload create error: ${createResponse.code()} ${createResponse.errorBodyText()}")
                return null
            }

            val uploadId = createResponse.body()?.uploadId?.takeIf { it.isNotBlank() }
            if (uploadId == null) {
                Log.e(TAG, "Server upload create response missing upload_id")
                return null
            }
            currentServerUploadId = uploadId

            try {
                uploadVideoChunks(uploadId, sourceFile, chunkSize, totalChunks)
                val serverJobId = completeChunkedServerVideoUpload(uploadId, options) ?: return null
                currentServerUploadId = null
                return serverJobId
            } catch (e: CancellationException) {
                cancelCurrentServerUpload()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Chunked server upload failed: ${e.message}", e)
                cancelCurrentServerUpload()
                return null
            }
        }
        throw CancellationException("Video processing stopped")
    }

    private suspend fun uploadVideoChunks(
        uploadId: String,
        sourceFile: File,
        chunkSize: Long,
        totalChunks: Int
    ) {
        for (chunkIndex in 0 until totalChunks) {
            if (!callbacks.isActive()) {
                throw CancellationException("Video processing stopped")
            }

            val offset = chunkIndex * chunkSize
            val byteCount = minOf(chunkSize, sourceFile.length() - offset)
            callbacks.postStatus("Uploading chunk ${chunkIndex + 1}/$totalChunks...")
            val response = runServerNetworkRequest("Server chunk upload") {
                val chunkBody = FileChunkRequestBody(sourceFile, offset, byteCount)
                val chunkPart = MultipartBody.Part.createFormData(
                    "chunk",
                    "${sourceFile.name}.part${chunkIndex + 1}",
                    chunkBody
                )
                api.uploadProcessVideoChunk(
                    uploadId = uploadId,
                    chunk = chunkPart,
                    fields = mapOf(
                        "chunk_index" to textPart(chunkIndex.toString()),
                        "total_chunks" to textPart(totalChunks.toString())
                    )
                )
            }

            if (!response.isSuccessful) {
                throw IOException("Server chunk upload error: ${response.code()} ${response.errorBodyText()}")
            }
            val uploadedPercent = ((chunkIndex + 1) * SERVER_UPLOAD_PROGRESS_PERCENT / totalChunks)
                .coerceIn(1, SERVER_UPLOAD_PROGRESS_PERCENT)
            callbacks.postPercent(uploadedPercent)
        }
    }

    private suspend fun completeChunkedServerVideoUpload(
        uploadId: String,
        options: VideoProcessOptions
    ): String? {
        while (callbacks.isActive()) {
            callbacks.postStatus("Finalizing upload...")
            val response = runServerNetworkRequest("Server chunk upload complete") {
                api.completeProcessVideoUpload(
                    uploadId = uploadId,
                    fields = serverFormFields(options)
                )
            }

            if (response.isSuccessful) {
                return response.body()?.jobId?.takeIf { it.isNotBlank() }?.also { serverJobId ->
                    currentServerJobId = serverJobId
                    callbacks.postServerJobId(serverJobId)
                }
            }

            if (response.code() == HTTP_TOO_MANY_REQUESTS) {
                Log.w(TAG, "Server queue is full; waiting before finalizing upload")
                callbacks.postStatus("Server queue is full; waiting...")
                delay(SERVER_QUEUE_RETRY_DELAY_MS)
                continue
            }

            Log.e(TAG, "Server upload complete error: ${response.code()} ${response.errorBodyText()}")
            return null
        }
        throw CancellationException("Video processing stopped")
    }

    private suspend fun waitForServerVideoJob(serverJobId: String, outputFile: File): Boolean {
        var transientStatusFailures = 0
        while (callbacks.isActive()) {
            val statusPayload = fetchServerVideoJob(serverJobId)
            if (statusPayload == null) {
                transientStatusFailures++
                if (transientStatusFailures > MAX_TRANSIENT_STATUS_FAILURES) {
                    Log.e(TAG, "Server job status unavailable too many times: $serverJobId")
                    return false
                }
                callbacks.postStatus("Server is busy; waiting...")
                delay(SERVER_BUSY_RETRY_DELAY_MS)
                continue
            }
            transientStatusFailures = 0
            when (val status = statusPayload.status) {
                "queued" -> {
                    callbacks.postStatus("Queued on server...")
                    delay(SERVER_POLL_INTERVAL_MS)
                }
                "processing" -> {
                    statusPayload.progress?.let { callbacks.postPercent(it) }
                    delay(SERVER_POLL_INTERVAL_MS)
                }
                "cancelling" -> {
                    callbacks.postStatus("Cancelling server job...")
                    delay(SERVER_POLL_INTERVAL_MS)
                }
                "cancelled" -> {
                    Log.d(TAG, "Server job cancelled: $serverJobId")
                    return false
                }
                "completed" -> {
                    callbacks.postStatus("Downloading processed video...")
                    return downloadServerVideoJobResult(serverJobId, outputFile)
                }
                "failed" -> {
                    Log.e(TAG, "Server job failed: ${statusPayload.error.orEmpty()}")
                    return false
                }
                else -> {
                    Log.e(TAG, "Unknown server job status: $status")
                    return false
                }
            }
        }
        return false
    }

    private suspend fun fetchServerVideoJob(serverJobId: String): ServerVideoJobResponse? {
        return runServerNetworkRequest("Server job status") {
            val response = api.getProcessVideoJob(serverJobId)
            if (response.isSuccessful) {
                response.body()
            } else if (response.code() in TRANSIENT_SERVER_STATUS_CODES) {
                Log.w(TAG, "Transient server job status error: ${response.code()} ${response.errorBodyText()}")
                null
            } else {
                Log.e(TAG, "Server job status error: ${response.code()} ${response.errorBodyText()}")
                ServerVideoJobResponse(jobId = serverJobId, status = "failed", error = "Server status error ${response.code()}")
            }
        }
    }

    private suspend fun cancelCurrentServerJob() {
        val serverJobId = currentServerJobId?.takeIf { it.isNotBlank() } ?: return
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                withTimeout(SERVER_CANCEL_TIMEOUT_MS) {
                    val response = api.cancelProcessVideoJob(serverJobId)
                    if (response.isSuccessful) {
                        Log.d(TAG, "Server job cancel requested: $serverJobId")
                    } else {
                        Log.w(TAG, "Server job cancel failed: $serverJobId ${response.code()} ${response.errorBodyText()}")
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "Server job cancel request failed: $serverJobId ${error.message}")
            }
        }
    }

    private suspend fun cancelCurrentServerUpload() {
        val uploadId = currentServerUploadId?.takeIf { it.isNotBlank() } ?: return
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                withTimeout(SERVER_CANCEL_TIMEOUT_MS) {
                    val response = api.cancelProcessVideoUpload(uploadId)
                    if (response.isSuccessful) {
                        Log.d(TAG, "Server upload cancelled: $uploadId")
                    } else {
                        Log.w(TAG, "Server upload cancel failed: $uploadId ${response.code()} ${response.errorBodyText()}")
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "Server upload cancel request failed: $uploadId ${error.message}")
            }
        }
    }

    private suspend fun downloadServerVideoJobResult(serverJobId: String, outputFile: File): Boolean {
        val resultInfo = fetchServerVideoResultInfo(serverJobId)
        if (resultInfo?.fileSize != null && resultInfo.totalChunks != null && resultInfo.totalChunks > 0) {
            return downloadServerVideoJobResultChunks(serverJobId, outputFile, resultInfo)
        }

        return runServerNetworkRequest("Server result download") {
            outputFile.delete()
            val response = api.downloadProcessVideoJobResult(serverJobId)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                FileOutputStream(outputFile).use { fos ->
                    body.byteStream().use { it.copyTo(fos) }
                }
                outputFile.length() > 100
            } else {
                Log.e(TAG, "Server result download error: ${response.code()} ${response.errorBodyText()}")
                false
            }
        }
    }

    private suspend fun fetchServerVideoResultInfo(serverJobId: String): ServerVideoResultInfoResponse? {
        return runServerNetworkRequest("Server result info") {
            val response = api.getProcessVideoJobResultInfo(serverJobId)
            if (response.isSuccessful) {
                response.body()
            } else {
                Log.w(TAG, "Server result info unavailable: ${response.code()} ${response.errorBodyText()}")
                null
            }
        }
    }

    private suspend fun downloadServerVideoJobResultChunks(
        serverJobId: String,
        outputFile: File,
        resultInfo: ServerVideoResultInfoResponse
    ): Boolean {
        val expectedSize = resultInfo.fileSize?.takeIf { it > 0L } ?: return false
        val totalChunks = resultInfo.totalChunks?.takeIf { it > 0 } ?: return false
        outputFile.delete()

        return try {
            FileOutputStream(outputFile).use { output ->
                for (chunkIndex in 0 until totalChunks) {
                    if (!callbacks.isActive()) {
                        throw CancellationException("Video processing stopped")
                    }

                    callbacks.postStatus("Downloading chunk ${chunkIndex + 1}/$totalChunks...")
                    val response = runServerNetworkRequest("Server result chunk download") {
                        api.downloadProcessVideoJobResultChunk(serverJobId, chunkIndex)
                    }
                    val body = response.body()
                    if (!response.isSuccessful || body == null) {
                        Log.e(TAG, "Server result chunk error: ${response.code()} ${response.errorBodyText()}")
                        outputFile.delete()
                        return false
                    }
                    body.byteStream().use { input ->
                        input.copyTo(output)
                    }
                    val downloadPercent = SERVER_DOWNLOAD_PROGRESS_START_PERCENT +
                        ((chunkIndex + 1) * SERVER_DOWNLOAD_PROGRESS_RANGE_PERCENT / totalChunks)
                    callbacks.postPercent(
                        downloadPercent.coerceIn(
                            SERVER_DOWNLOAD_PROGRESS_START_PERCENT,
                            VideoProcessingProgressText.COMPLETE_PERCENT
                        )
                    )
                }
            }

            val valid = outputFile.length() == expectedSize && outputFile.length() > 100
            if (valid) {
                cleanupServerVideoResult(serverJobId)
            } else {
                Log.e(TAG, "Server result size mismatch: ${outputFile.length()} != $expectedSize")
                outputFile.delete()
            }
            valid
        } catch (e: CancellationException) {
            outputFile.delete()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Server result chunk download failed: ${e.message}", e)
            outputFile.delete()
            false
        }
    }

    private suspend fun cleanupServerVideoResult(serverJobId: String) {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                withTimeout(SERVER_CANCEL_TIMEOUT_MS) {
                    val response = api.cleanupProcessVideoJobResult(serverJobId)
                    if (response.isSuccessful) {
                        Log.d(TAG, "Server result cleanup requested: $serverJobId")
                    } else {
                        Log.w(TAG, "Server result cleanup failed: $serverJobId ${response.code()} ${response.errorBodyText()}")
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "Server result cleanup request failed: $serverJobId ${error.message}")
            }
        }
    }

    private suspend fun <T> runServerNetworkRequest(
        operationName: String,
        block: suspend () -> T
    ): T {
        var retryDelayMs = NETWORK_RETRY_DELAY_MS
        while (callbacks.isActive()) {
            waitForInternetConnection()
            try {
                return block()
            } catch (e: IOException) {
                if (!callbacks.isActive()) {
                    throw CancellationException("Video processing stopped")
                }

                val internetAvailable = isInternetConnected()
                Log.w(
                    TAG,
                    "$operationName interrupted by network error: ${e.message}; " +
                        "internetAvailable=$internetAvailable"
                )

                if (internetAvailable) {
                    callbacks.postStatus("$operationName interrupted; retrying...")
                    delay(retryDelayMs)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_NETWORK_RETRY_DELAY_MS)
                } else {
                    retryDelayMs = NETWORK_RETRY_DELAY_MS
                    waitForInternetConnection()
                }
            }
        }
        throw CancellationException("Video processing stopped")
    }

    private suspend fun waitForInternetConnection() {
        if (isInternetConnected()) return
        callbacks.postStatus("Waiting for internet connection...")
        connectivityObserver.isConnected.first { isConnected ->
            !callbacks.isActive() || isConnected
        }
        if (!callbacks.isActive()) {
            throw CancellationException("Video processing stopped")
        }
        callbacks.postStatus("Network restored. Resuming...")
    }

    private suspend fun isInternetConnected(): Boolean {
        return connectivityObserver.isConnected.first()
    }

    private fun serverMultipartFields(options: VideoProcessOptions): Map<String, RequestBody> {
        return serverFormFields(options).mapValues { (_, value) -> textPart(value) }
    }

    private fun serverFormFields(options: VideoProcessOptions): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        fields["mode"] = if (options.useFarnebackHeatmap) "HEATMAP" else "VECTORS"
        fields["processing_mode"] = options.processingMode.name
        fields["algorithm"] = serverAlgorithmName(options)
        fields["sensitivity"] = options.sensitivity.toString()
        fields["is_moving"] = options.isMoving.toString()
        fields["isMoving"] = options.isMoving.toString()
        fields["roi_enabled"] = (options.roi != null).toString()

        options.roi?.let { roi ->
            fields["roi_left"] = roi.left.toString()
            fields["roi_top"] = roi.top.toString()
            fields["roi_right"] = roi.right.toString()
            fields["roi_bottom"] = roi.bottom.toString()
            fields["roi_view_aspect_ratio"] = roi.viewAspectRatio.toString()
            fields["roi_selected_position_ms"] = roi.selectedPositionMs.toString()
            fields["roi_path_points"] =
                roi.pathPoints.joinToString(separator = ";") { point ->
                    "${point.x},${point.y}"
                }
        }
        return fields
    }

    private fun textPart(value: String): RequestBody {
        return value.toRequestBody("text/plain".toMediaType())
    }

    private fun Response<*>.errorBodyText(): String {
        return try {
            errorBody()?.string().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun serverAlgorithmName(options: VideoProcessOptions): String {
        return when {
            options.useAi -> "AI"
            options.useFarneback -> "FARNEBACK"
            else -> "KLT"
        }
    }

    private class FileChunkRequestBody(
        private val file: File,
        private val offset: Long,
        private val byteCount: Long
    ) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()

        override fun contentLength() = byteCount

        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var remaining = byteCount
                while (remaining > 0L) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read == -1) break
                    sink.write(buffer, 0, read)
                    remaining -= read.toLong()
                }
            }
        }
    }

    private companion object {
        private const val TAG = "SERVER-VIDEO"
        private const val SERVER_POLL_INTERVAL_MS = 2_000L
        private const val SERVER_BUSY_RETRY_DELAY_MS = 8_000L
        private const val MAX_TRANSIENT_STATUS_FAILURES = 45
        private const val SERVER_CANCEL_TIMEOUT_MS = 5_000L
        private const val SERVER_QUEUE_RETRY_DELAY_MS = 5_000L
        private const val NETWORK_RETRY_DELAY_MS = 3_000L
        private const val MAX_NETWORK_RETRY_DELAY_MS = 30_000L
        private const val SERVER_CHUNK_UPLOAD_THRESHOLD_BYTES = 80L * 1024L * 1024L
        private const val SERVER_UPLOAD_CHUNK_BYTES = 32L * 1024L * 1024L
        private const val SERVER_UPLOAD_PROGRESS_PERCENT = 20
        private const val SERVER_DOWNLOAD_PROGRESS_START_PERCENT = 90
        private const val SERVER_DOWNLOAD_PROGRESS_RANGE_PERCENT = 10
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private val TRANSIENT_SERVER_STATUS_CODES = setOf(408, 429, 500, 502, 503, 504)
    }
}
