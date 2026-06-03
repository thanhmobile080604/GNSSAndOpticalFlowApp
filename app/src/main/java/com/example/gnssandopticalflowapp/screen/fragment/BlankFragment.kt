package com.example.gnssandopticalflowapp.screen.fragment

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.example.gnssandopticalflowapp.BuildConfig
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentBlankBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

class BlankFragment : BaseFragment<FragmentBlankBinding>(FragmentBlankBinding::inflate) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.MINUTES)
        .build()

    private val uploadLogBuilder = StringBuilder()
    private val uploadLogLock = Any()
    private var mqttClient: MqttAsyncClient? = null
    private var mqttConnectInProgress = false
    private var publishFakeLocationAfterConnect = false

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { videoUri ->
        appendLog("Picker callback uri=$videoUri")

        if (videoUri == null) {
            appendLog("Video selection cancelled")
            return@registerForActivityResult
        }

        uploadSelectedVideo(videoUri)
    }

    override fun FragmentBlankBinding.initView() {
        replyFromServer.movementMethod = ScrollingMovementMethod()
        btnSend.text = "Send"
        btnSendLocation.text = "Send Location"
        resetLog(
            """
            Ready to upload video
            GNSS API: ${BuildConfig.GNSS_API_BASE_URL}
            Device ID: ${BuildConfig.GNSS_DEVICE_ID}
            """.trimIndent()
        )
    }

    override fun FragmentBlankBinding.initListener() {
        btnSend.setSingleClick {
            resetLog("Send clicked")
            appendLog("Opening video picker with MIME filter video/*")
            videoPickerLauncher.launch("video/*")
        }

        btnSendLocation.setSingleClick {
            resetLog("Send fake MQTT location clicked")
            publishFakeCoordinatesViaMqtt()
        }
    }

    override fun initObserver() = Unit

    private fun uploadSelectedVideo(videoUri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            setUploadInProgress(true)
            appendLog("Upload coroutine started")

            try {
                val contentResolver = requireContext().contentResolver

                appendLog("Preparing selected video...")

                val selectedVideo = withContext(Dispatchers.IO) {
                    resolveSelectedVideo(contentResolver, videoUri)
                }

                appendLog(
                    """
                    Requesting upload URL...
                    File: ${selectedVideo.displayName}
                    Size: ${formatBytes(selectedVideo.sizeBytes)}
                    Extension: ${selectedVideo.extension}
                    """.trimIndent()
                )

                val uploadUrlResponse = withContext(Dispatchers.IO) {
                    requestUploadUrl(selectedVideo)
                }

                appendLog(
                    """
                    Upload URL received
                    s3Key: ${uploadUrlResponse.s3Key}
                    mimeType: ${uploadUrlResponse.mimeType}
                    uploadUrl: ${uploadUrlResponse.uploadUrl}
                    """.trimIndent()
                )

                appendLog("Uploading video to SeaweedFS...")

                withContext(Dispatchers.IO) {
                    uploadToSeaweedFs(
                        contentResolver = contentResolver,
                        video = selectedVideo,
                        uploadUrlResponse = uploadUrlResponse
                    ) { uploadedBytes, totalBytes ->
                        renderUploadProgress(uploadedBytes, totalBytes)
                    }
                }

                appendLog("Confirming upload...")

                val confirmResponse = withContext(Dispatchers.IO) {
                    confirmUpload(
                        s3Key = uploadUrlResponse.s3Key,
                        snapshotId = selectedVideo.snapshotId
                    )
                }

                appendLog(
                    """
                    Upload success

                    s3Key:
                    ${uploadUrlResponse.s3Key}

                    snapshotId:
                    ${selectedVideo.snapshotId}

                    Backend response:
                    ${confirmResponse.ifBlank { "(empty response)" }}
                    """.trimIndent()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Upload video failed", e)
                appendLog("Upload failed: ${e.message ?: "Unknown error"}")
            } finally {
                appendLog("Upload coroutine finished")
                setUploadInProgress(false)
            }
        }
    }

    private fun requestUploadUrl(video: SelectedVideo): UploadUrlResponse {
        val payload = JSONObject()
            .put("deviceId", deviceId())
            .put("fileExtension", video.extension)
            .put("filename", video.filename)
            .toString()

        appendLog(
            """
            Backend request-upload-url
            URL: ${apiUrl("/api/media-logs/request-upload-url")}
            Payload: $payload
            """.trimIndent()
        )

        val request = gnssBackendRequestBuilder("/api/media-logs/request-upload-url")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()

            appendLog(
                """
                Backend request-upload-url response
                HTTP ${response.code}
                Body: ${body.ifBlank { "(empty)" }}
                """.trimIndent()
            )

            if (!response.isSuccessful) {
                throw IOException("Request upload URL failed: HTTP ${response.code} ${body.take(300)}")
            }

            val json = JSONObject(body)

            val uploadUrl = json.optString("uploadUrl")
                .takeIf { it.isNotBlank() }
                ?: throw IOException("Missing uploadUrl")

            val s3Key = json.optString("s3Key")
                .takeIf { it.isNotBlank() }
                ?: throw IOException("Missing s3Key")

            val mimeType = json.optString("mimeType")
                .takeIf { it.isNotBlank() }
                ?: throw IOException("Missing mimeType")

            return UploadUrlResponse(
                uploadUrl = uploadUrl,
                s3Key = s3Key,
                mimeType = mimeType
            )
        }
    }

    private fun uploadToSeaweedFs(
        contentResolver: ContentResolver,
        video: SelectedVideo,
        uploadUrlResponse: UploadUrlResponse,
        onProgress: (uploadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        val requestBody = ContentUriRequestBody(
            contentResolver = contentResolver,
            uri = video.uri,
            mimeType = uploadUrlResponse.mimeType,
            contentLength = video.sizeBytes,
            onProgress = onProgress
        )

        appendLog(
            """
            SeaweedFS PUT request
            URL: ${uploadUrlResponse.uploadUrl}
            Content-Type: ${uploadUrlResponse.mimeType}
            Content-Length: ${formatBytes(video.sizeBytes)}
            Source URI: ${video.uri}
            """.trimIndent()
        )

        val request = Request.Builder()
            .url(uploadUrlResponse.uploadUrl)
            .put(requestBody)
            .header("Content-Type", uploadUrlResponse.mimeType)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()

            appendLog(
                """
                SeaweedFS PUT response
                HTTP ${response.code}
                Body: ${body.ifBlank { "(empty)" }}
                """.trimIndent()
            )

            if (response.code !in SUCCESS_UPLOAD_CODES) {
                throw IOException("SeaweedFS upload failed: HTTP ${response.code} ${body.take(300)}")
            }
        }
    }

    private fun confirmUpload(
        s3Key: String,
        snapshotId: String
    ): String {
        val payload = JSONObject()
            .put("deviceId", deviceId())
            .put("s3Key", s3Key)
            .put("mediaType", "video")
            .put("snapshotId", snapshotId)
            .toString()

        appendLog(
            """
            Backend confirm-upload
            URL: ${apiUrl("/api/media-logs/confirm-upload")}
            Payload: $payload
            """.trimIndent()
        )

        val request = gnssBackendRequestBuilder("/api/media-logs/confirm-upload")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()

            appendLog(
                """
                Backend confirm-upload response
                HTTP ${response.code}
                Body: ${body.ifBlank { "(empty)" }}
                """.trimIndent()
            )

            if (!response.isSuccessful) {
                throw IOException("Confirm upload failed: HTTP ${response.code} ${body.take(300)}")
            }

            return body
        }
    }

    private fun publishFakeCoordinatesViaMqtt() {
        val connectedClient = mqttClient?.takeIf { it.isConnected }
        if (connectedClient != null) {
            appendLog("MQTT already connected")
            publishFakeCoordinates(connectedClient)
            return
        }

        publishFakeLocationAfterConnect = true

        if (mqttConnectInProgress) {
            appendLog("MQTT connect already in progress; location publish queued")
            return
        }

        connectMqtt()
    }

    private fun connectMqtt() {
        val serverUri = buildMqttServerUri()
        val clientId = "android-location-${mqttDeviceId()}-${System.currentTimeMillis()}"

        appendLog(
            """
            MQTT connecting
            Server: $serverUri
            ClientId: $clientId
            Username: ${BuildConfig.MQTT_USERNAME}
            Coordinates topic: ${BuildConfig.MQTT_TOPIC_COORDINATES}
            """.trimIndent()
        )

        try {
            val client = MqttAsyncClient(
                serverUri,
                clientId,
                MemoryPersistence()
            )

            mqttClient = client
            mqttConnectInProgress = true

            client.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    appendLog("MQTT ${if (reconnect) "reconnected" else "connected"} callback server=$serverURI")
                }

                override fun connectionLost(cause: Throwable?) {
                    appendLog("MQTT connection lost: ${cause?.message ?: "unknown"}")
                    Log.e(TAG, "MQTT connection lost", cause)
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = message?.payload?.toString(Charsets.UTF_8).orEmpty()
                    appendLog(
                        """
                        MQTT message arrived
                        Topic: $topic
                        Payload: $payload
                        """.trimIndent()
                    )
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    appendLog("MQTT delivery complete")
                }
            })

            val options = MqttConnectOptions().apply {
                userName = BuildConfig.MQTT_USERNAME
                password = BuildConfig.MQTT_PASSWORD.toCharArray()
                isCleanSession = true
                isAutomaticReconnect = true
                connectionTimeout = 10
                keepAliveInterval = 30
            }

            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    mqttConnectInProgress = false
                    appendLog("MQTT connect success")

                    if (publishFakeLocationAfterConnect) {
                        publishFakeLocationAfterConnect = false
                        publishFakeCoordinates(client)
                    }
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    mqttConnectInProgress = false
                    publishFakeLocationAfterConnect = false
                    appendLog("MQTT connect failed: ${exception?.message ?: "unknown"}")
                    Log.e(TAG, "MQTT connect failed", exception)
                }
            })
        } catch (e: Exception) {
            mqttConnectInProgress = false
            publishFakeLocationAfterConnect = false
            appendLog("MQTT init error: ${e.message ?: "unknown"}")
            Log.e(TAG, "MQTT init error", e)
        }
    }

    private fun publishFakeCoordinates(client: MqttAsyncClient) {
        if (!client.isConnected) {
            appendLog("MQTT not connected; reconnecting before publish")
            publishFakeLocationAfterConnect = true
            connectMqtt()
            return
        }

        val topic = BuildConfig.MQTT_TOPIC_COORDINATES
        val payload = JSONObject()
            .put("lng", 102.6958)
            .put("lat", 18.7769)
            .put("speed", 70.5)
            .put("heading", 270)
            .put("timestamp", nowUtcIso())
            .toString()

        appendLog(
            """
            MQTT publish fake coordinates
            Topic: $topic
            QoS: $MQTT_QOS
            Payload: $payload
            """.trimIndent()
        )

        val message = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
            qos = MQTT_QOS
            isRetained = false
        }

        client.publish(topic, message, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                appendLog("MQTT fake coordinates publish success")
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                appendLog("MQTT fake coordinates publish failed: ${exception?.message ?: "unknown"}")
                Log.e(TAG, "MQTT fake coordinates publish failed", exception)
            }
        })
    }

    private fun resolveSelectedVideo(
        contentResolver: ContentResolver,
        videoUri: Uri
    ): SelectedVideo {
        val mimeType = contentResolver.getType(videoUri)
        val displayName = resolveDisplayName(contentResolver, videoUri) ?: "selected-video.mp4"
        val extension = resolveFileExtension(displayName, mimeType)

        appendLog(
            """
            Selected video metadata
            URI: $videoUri
            displayName: $displayName
            mimeType: ${mimeType ?: "(unknown)"}
            extension: $extension
            """.trimIndent()
        )

        if (extension !in SUPPORTED_VIDEO_EXTENSIONS) {
            throw IOException("Unsupported video extension: $extension")
        }

        val snapshotId = "video-${UUID.randomUUID()}"
        val sizeBytes = resolveSizeBytes(contentResolver, videoUri)

        appendLog(
            """
            Generated upload metadata
            filename: $snapshotId
            snapshotId: $snapshotId
            sizeBytes: $sizeBytes
            size: ${formatBytes(sizeBytes)}
            """.trimIndent()
        )

        return SelectedVideo(
            uri = videoUri,
            displayName = displayName,
            extension = extension,
            filename = snapshotId,
            snapshotId = snapshotId,
            sizeBytes = sizeBytes
        )
    }

    private fun resolveDisplayName(
        contentResolver: ContentResolver,
        uri: Uri
    ): String? {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index)
            }
        }

        return null
    }

    private fun resolveSizeBytes(
        contentResolver: ContentResolver,
        uri: Uri
    ): Long {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)

            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                return cursor.getLong(index)
            }
        }

        return UNKNOWN_CONTENT_LENGTH
    }

    private fun resolveFileExtension(
        displayName: String,
        mimeType: String?
    ): String {
        val extensionFromMime = mimeType
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?.lowercase(Locale.US)

        if (!extensionFromMime.isNullOrBlank()) {
            return extensionFromMime
        }

        val extensionFromName = displayName
            .substringAfterLast('.', "")
            .lowercase(Locale.US)

        return extensionFromName.ifBlank { "mp4" }
    }

    private fun apiUrl(path: String): String {
        val baseUrl = BuildConfig.GNSS_API_BASE_URL.trim().trimEnd('/')

        require(baseUrl.isNotBlank()) {
            "Missing gnssApiBaseUrl in local.properties"
        }

        return "$baseUrl/${path.trimStart('/')}"
    }

    private fun gnssBackendRequestBuilder(path: String): Request.Builder {
        val username = BuildConfig.MQTT_USERNAME.trim()
        val password = BuildConfig.MQTT_PASSWORD

        require(username.isNotBlank()) {
            "Missing mqttUsername in local.properties"
        }
        require(password.isNotBlank()) {
            "Missing mqttPassword in local.properties"
        }

        appendLog(
            """
            Backend HTTP Basic auth attached
            Username: $username
            Password: ${maskSecret(password)}
            """.trimIndent()
        )

        return Request.Builder()
            .url(apiUrl(path))
            .header("Authorization", Credentials.basic(username, password))
    }

    private fun maskSecret(value: String): String {
        if (value.length <= 8) return "****"
        return "${value.take(4)}...${value.takeLast(4)}"
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

    private fun nowUtcIso(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }

    private fun renderUploadProgress(
        uploadedBytes: Long,
        totalBytes: Long
    ) {
        if (totalBytes > 0) {
            appendLog(
                """
                Uploading video...
                ${formatBytes(uploadedBytes)} / ${formatBytes(totalBytes)}
                """.trimIndent()
            )
        } else {
            appendLog(
                """
                Uploading video...
                Uploaded: ${formatBytes(uploadedBytes)}
                Total size: unknown
                """.trimIndent()
            )
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "unknown"
        if (bytes < 1024L) return "$bytes B"

        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)

        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)

        return String.format(Locale.US, "%.1f GB", mb / 1024.0)
    }

    private fun setUploadInProgress(isUploading: Boolean) {
        binding.btnSend.isEnabled = !isUploading
        binding.btnSend.text = if (isUploading) "Uploading..." else "Send"
        Log.d(TAG, "setUploadInProgress isUploading=$isUploading")
    }

    private fun resetLog(text: String) {
        synchronized(uploadLogLock) {
            uploadLogBuilder.clear()
        }
        appendLog(text)
    }

    private fun appendLog(text: String) {
        val message = text.trim()
        Log.d(TAG, message)
        val fullLog = synchronized(uploadLogLock) {
            uploadLogBuilder
                .append(currentLogTime())
                .append(" ")
                .append(message)
                .append("\n\n")
            uploadLogBuilder.toString().trimEnd()
        }

        activity?.runOnUiThread {
            if (view != null) {
                binding.replyFromServer.text = fullLog
                binding.replyFromServer.post {
                    val scrollAmount = binding.replyFromServer.layout?.let { layout ->
                        layout.getLineTop(binding.replyFromServer.lineCount) - binding.replyFromServer.height
                    } ?: 0

                    if (scrollAmount > 0) {
                        binding.replyFromServer.scrollTo(0, scrollAmount)
                    }
                }
            }
        }
    }

    private fun currentLogTime(): String {
        return String.format(Locale.US, "%tT", System.currentTimeMillis())
    }

    override fun onDestroyView() {
        disconnectMqtt()
        super.onDestroyView()
    }

    private fun disconnectMqtt() {
        try {
            mqttClient?.let { client ->
                if (client.isConnected) {
                    client.disconnect()
                    appendLog("MQTT disconnected")
                }
                client.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MQTT disconnect error", e)
        } finally {
            mqttClient = null
            mqttConnectInProgress = false
            publishFakeLocationAfterConnect = false
        }
    }

    private class ContentUriRequestBody(
        private val contentResolver: ContentResolver,
        private val uri: Uri,
        mimeType: String,
        private val contentLength: Long,
        private val onProgress: (uploadedBytes: Long, totalBytes: Long) -> Unit
    ) : RequestBody() {

        private val mediaType = mimeType.toMediaType()

        override fun contentType(): MediaType = mediaType

        override fun contentLength(): Long = contentLength

        override fun writeTo(sink: BufferedSink) {
            val inputStream = contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot open selected video")

            inputStream.use { stream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var uploadedBytes = 0L
                var lastProgressUpdateMs = 0L

                while (true) {
                    val bytesRead = stream.read(buffer)
                    if (bytesRead == -1) break

                    sink.write(buffer, 0, bytesRead)
                    uploadedBytes += bytesRead

                    val nowMs = System.currentTimeMillis()
                    val shouldUpdateProgress =
                        nowMs - lastProgressUpdateMs >= PROGRESS_UPDATE_INTERVAL_MS ||
                            uploadedBytes == contentLength

                    if (shouldUpdateProgress) {
                        lastProgressUpdateMs = nowMs
                        onProgress(uploadedBytes, contentLength)
                    }
                }
            }
        }
    }

    private data class SelectedVideo(
        val uri: Uri,
        val displayName: String,
        val extension: String,
        val filename: String,
        val snapshotId: String,
        val sizeBytes: Long
    )

    private data class UploadUrlResponse(
        val uploadUrl: String,
        val s3Key: String,
        val mimeType: String
    )

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val SUPPORTED_VIDEO_EXTENSIONS = setOf("mp4", "avi", "mkv")
        private val SUCCESS_UPLOAD_CODES = setOf(200, 201, 204)
        private const val UNKNOWN_CONTENT_LENGTH = -1L
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
        private const val MQTT_QOS = 1
    }
}
