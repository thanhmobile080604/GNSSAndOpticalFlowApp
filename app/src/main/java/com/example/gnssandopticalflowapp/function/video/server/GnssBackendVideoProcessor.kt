package com.example.gnssandopticalflowapp.function.video.server

import android.content.Context
import android.util.Log
import com.example.gnssandopticalflowapp.BuildConfig
import com.example.gnssandopticalflowapp.model.VideoProcessOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal class GnssBackendVideoProcessor(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.MINUTES)
        .build()

    private var mqttClient: MqttAsyncClient? = null

    @Volatile
    private var isCancelled = false

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
            callbacks.postStatus("Requesting upload URL...")
            val uploadUrlInfo = requestUploadUrl(sourceFile)
            
            callbacks.postStatus("Uploading to GNSS S3...")
            uploadToSeaweedFs(sourceFile, uploadUrlInfo)
            
            callbacks.postStatus("Confirming upload...")
            val mediaLogId = confirmUpload(uploadUrlInfo.s3Key)
            callbacks.postServerJobId(mediaLogId)

            callbacks.postStatus("Connecting to MQTT for updates...")
            connectMqtt()

//            callbacks.postStatus("Triggering AI analysis...")
//            requestAnalysis(mediaLogId, options)

            callbacks.postStatus("Waiting for AI processing via MQTT...")
            val processedS3Key = waitForMqttResult(mediaLogId)

            callbacks.postStatus("Downloading processed video...")
            val streamUrl = getStreamUrl(mediaLogId)
            
            return@withContext downloadVideo(streamUrl, outputFile)
        } catch (e: CancellationException) {
            isCancelled = true
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "GNSS Backend processing failed: ${e.message}", e)
            return@withContext false
        } finally {
            disconnectMqtt()
        }
    }

    suspend fun cancelCurrentWork() {
        isCancelled = true
        disconnectMqtt()
    }

    private fun requestUploadUrl(file: File): UploadUrlResponse {
        val payload = JSONObject()
            .put("deviceId", deviceId())
            .put("fileExtension", "mp4")
            .put("filename", file.name)
            .toString()

        val request = gnssBackendRequestBuilder("/api/media-logs/request-upload-url")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Request upload URL failed: HTTP ${response.code} $body")
            }
            val json = JSONObject(body)
            return UploadUrlResponse(
                uploadUrl = json.getString("uploadUrl"),
                s3Key = json.getString("s3Key"),
                mimeType = json.getString("mimeType")
            )
        }
    }

    private fun uploadToSeaweedFs(file: File, uploadUrlResponse: UploadUrlResponse) {
        val requestBody = object : RequestBody() {
            override fun contentType() = uploadUrlResponse.mimeType.toMediaType()
            override fun contentLength() = file.length()
            override fun writeTo(sink: BufferedSink) {
                RandomAccessFile(file, "r").use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var uploadedBytes = 0L
                    var lastProgressUpdateMs = 0L
                    val totalBytes = file.length()
                    
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        sink.write(buffer, 0, bytesRead)
                        uploadedBytes += bytesRead
                        
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastProgressUpdateMs >= PROGRESS_UPDATE_INTERVAL_MS || uploadedBytes == totalBytes) {
                            lastProgressUpdateMs = nowMs
                            val percent = (uploadedBytes * 50 / totalBytes).toInt()
                            callbacks.postPercent(percent.coerceIn(1, 50))
                        }
                    }
                }
            }
        }

        val request = Request.Builder()
            .url(uploadUrlResponse.uploadUrl)
            .put(requestBody)
            .header("Content-Type", uploadUrlResponse.mimeType)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("SeaweedFS upload failed: HTTP ${response.code}")
            }
        }
    }

    private fun confirmUpload(s3Key: String): String {
        val payload = JSONObject()
            .put("deviceId", deviceId())
            .put("s3Key", s3Key)
            .put("mediaType", "video")
            .put("snapshotId", "video-${UUID.randomUUID()}")
            .toString()

        val request = gnssBackendRequestBuilder("/api/media-logs/confirm-upload")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Confirm upload failed: HTTP ${response.code} $body")
            }
            val json = JSONObject(body)
            return json.getString("id")
        }
    }
    private fun requestAnalysis(mediaLogId: String, options: VideoProcessOptions) {
        val payload = JSONObject()
            .put("mode", if (options.useFarnebackHeatmap) "HEATMAP" else "VECTORS")
            .put("isMoving", options.isMoving)
            .toString()

        val request = gnssBackendRequestBuilder("/api/media-logs/$mediaLogId/analyze")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw IOException("Analyze request failed: HTTP ${response.code} $body")
            }
        }
    }

    private suspend fun connectMqtt(): Unit = suspendCancellableCoroutine { continuation ->
        val serverUri = buildMqttServerUri()
        val clientId = "android-worker-${mqttDeviceId()}-${System.currentTimeMillis()}"
        
        try {
            val client = MqttAsyncClient(serverUri, clientId, MemoryPersistence())
            mqttClient = client

            val options = MqttConnectOptions().apply {
                userName = BuildConfig.MQTT_USERNAME
                password = BuildConfig.MQTT_PASSWORD.toCharArray()
                isCleanSession = true
                isAutomaticReconnect = true
                connectionTimeout = 10
                keepAliveInterval = 30
            }

            client.connect(options, null, object : org.eclipse.paho.client.mqttv3.IMqttActionListener {
                override fun onSuccess(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?) {
                    val topic = "gnss/${deviceId()}/media/result"
                    client.subscribe(topic, 1, null, object : org.eclipse.paho.client.mqttv3.IMqttActionListener {
                        override fun onSuccess(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?) {
                            continuation.resume(Unit)
                        }
                        override fun onFailure(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?, exception: Throwable?) {
                            continuation.resumeWithException(exception ?: Exception("Subscribe failed"))
                        }
                    })
                }

                override fun onFailure(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?, exception: Throwable?) {
                    continuation.resumeWithException(exception ?: Exception("MQTT connect failed"))
                }
            })
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }

    private suspend fun waitForMqttResult(mediaLogId: String): String = suspendCancellableCoroutine { continuation ->
        var resumed = false
        val client = mqttClient ?: return@suspendCancellableCoroutine continuation.resumeWithException(Exception("MQTT client not initialized"))
        
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {}
            override fun connectionLost(cause: Throwable?) {}
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                val payloadStr = message?.payload?.toString(Charsets.UTF_8) ?: return
                try {
                    val payload = JSONObject(payloadStr)
                    if (payload.optString("jobId") == mediaLogId) {
                        val status = payload.optString("status")
                        if (status == "completed") {
                            if (!resumed) {
                                resumed = true
                                continuation.resume(payload.optString("outputS3Key"))
                            }
                        } else if (status == "failed" || status == "cancelled") {
                            if (!resumed) {
                                resumed = true
                                continuation.resumeWithException(Exception("AI processing $status: ${payload.optString("error")}"))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse MQTT message", e)
                }
            }
        })
    }

    private fun getStreamUrl(mediaLogId: String): String {
        val request = gnssBackendRequestBuilder("/api/media-logs/$mediaLogId/stream?type=processed")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Get stream URL failed: HTTP ${response.code} $body")
            }
            return JSONObject(body).getString("url")
        }
    }

    private fun downloadVideo(streamUrl: String, outputFile: File): Boolean {
        val request = Request.Builder().url(streamUrl).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Download failed: HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Empty response body")
            val totalBytes = body.contentLength()
            
            outputFile.delete()
            FileOutputStream(outputFile).use { fos ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloadedBytes = 0L
                    var lastProgressUpdateMs = 0L
                    
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        fos.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastProgressUpdateMs >= PROGRESS_UPDATE_INTERVAL_MS || downloadedBytes == totalBytes) {
                            lastProgressUpdateMs = nowMs
                            if (totalBytes > 0) {
                                val percent = 50 + (downloadedBytes * 50 / totalBytes).toInt()
                                callbacks.postPercent(percent.coerceIn(50, 100))
                            }
                        }
                    }
                }
            }
            return outputFile.length() > 100
        }
    }

    private fun disconnectMqtt() {
        try {
            mqttClient?.let { client ->
                if (client.isConnected) client.disconnect()
                client.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MQTT disconnect error", e)
        } finally {
            mqttClient = null
        }
    }

    private fun apiUrl(path: String): String {
        val baseUrl = BuildConfig.GNSS_API_BASE_URL.trim().trimEnd('/')
        require(baseUrl.isNotBlank()) { "Missing gnssApiBaseUrl in local.properties" }
        return "$baseUrl/${path.trimStart('/')}"
    }

    private fun gnssBackendRequestBuilder(path: String): Request.Builder {
        val username = BuildConfig.MQTT_USERNAME.trim()
        val password = BuildConfig.MQTT_PASSWORD
        require(username.isNotBlank()) { "Missing mqttUsername in local.properties" }
        require(password.isNotBlank()) { "Missing mqttPassword in local.properties" }
        return Request.Builder()
            .url(apiUrl(path))
            .header("Authorization", Credentials.basic(username, password))
    }

    private fun deviceId(): String {
        return BuildConfig.GNSS_DEVICE_ID.trim().ifBlank {
            throw IllegalStateException("Missing gnssDeviceId in local.properties")
        }
    }

    private fun mqttDeviceId(): String {
        return BuildConfig.MQTT_DEVICE_ID.trim().ifBlank { deviceId() }
    }

    private fun buildMqttServerUri(): String {
        val protocol = when (BuildConfig.MQTT_PROTOCOL.lowercase(Locale.US)) {
            "mqtt", "tcp" -> "tcp"
            "mqtts", "ssl" -> "ssl"
            else -> BuildConfig.MQTT_PROTOCOL
        }
        return "$protocol://${BuildConfig.MQTT_HOST}:${BuildConfig.MQTT_PORT}"
    }

    private data class UploadUrlResponse(
        val uploadUrl: String,
        val s3Key: String,
        val mimeType: String
    )

    private companion object {
        const val TAG = "GnssBackendProcessor"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val PROGRESS_UPDATE_INTERVAL_MS = 500L
    }
}
