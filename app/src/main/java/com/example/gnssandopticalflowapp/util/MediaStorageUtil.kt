package com.example.gnssandopticalflowapp.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import androidx.core.content.edit
import com.example.gnssandopticalflowapp.model.ImageInfo
import com.example.gnssandopticalflowapp.model.MediaInfo
import com.example.gnssandopticalflowapp.model.VideoInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MediaStorageUtil {
    private const val PREFS_NAME = "video_storage_prefs"
    private const val KEY_LEGACY_MEDIA_LIST = "video_list"
    private const val KEY_VIDEO_LIST = "video_list_v2"
    private const val KEY_IMAGE_LIST = "image_list_v2"
    private const val KEY_SPLIT_MIGRATED = "split_media_migrated"
    private const val TYPE_VIDEO = "VIDEO"
    private const val TYPE_IMAGE = "IMAGE"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getVideos(context: Context): List<VideoInfo> {
        migrateLegacyMediaIfNeeded(context)
        return readVideos(context)
    }

    fun getImages(context: Context): List<ImageInfo> {
        migrateLegacyMediaIfNeeded(context)
        return readImages(context)
    }

    fun getMedia(context: Context): List<MediaInfo> {
        migrateLegacyMediaIfNeeded(context)
        return mutableListOf<MediaInfo>().apply {
            addAll(readVideos(context))
            addAll(readImages(context))
        }.sortedByDescending { it.timestamp }
    }

    fun getVideo(context: Context, path: String): VideoInfo? {
        return getVideos(context).firstOrNull { it.path == path }
    }

    fun getImage(context: Context, path: String): ImageInfo? {
        return getImages(context).firstOrNull { it.path == path }
    }

    @Synchronized
    fun setVideos(context: Context, videos: List<VideoInfo>) {
        saveVideos(context, videos.sortedByDescending { it.timestamp })
    }

    @Synchronized
    fun setImages(context: Context, images: List<ImageInfo>) {
        saveImages(context, images.sortedByDescending { it.timestamp })
    }

    @Synchronized
    fun setVideo(context: Context, video: VideoInfo) {
        val videos = readVideos(context).toMutableList()
        val existingIndex = videos.indexOfFirst { it.path == video.path }
        if (existingIndex >= 0) {
            videos[existingIndex] = video
        } else {
            videos.add(0, video)
        }
        saveVideos(context, videos.sortedByDescending { it.timestamp })
    }

    @Synchronized
    fun setImage(context: Context, image: ImageInfo) {
        val images = readImages(context).toMutableList()
        val existingIndex = images.indexOfFirst { it.path == image.path }
        if (existingIndex >= 0) {
            images[existingIndex] = image
        } else {
            images.add(0, image)
        }
        saveImages(context, images.sortedByDescending { it.timestamp })
    }

    @Synchronized
    fun addVideo(context: Context, videoPath: String, durationMs: Long = 0L) {
        setVideo(context, VideoInfo(videoPath, System.currentTimeMillis(), durationMs))
    }

    @Synchronized
    fun addImage(context: Context, imagePath: String) {
        setImage(context, ImageInfo(imagePath, System.currentTimeMillis()))
    }

    @Synchronized
    fun deleteVideos(context: Context, videosToDelete: List<VideoInfo>): Int {
        if (videosToDelete.isEmpty()) return 0
        val pathsToDelete = videosToDelete.mapTo(mutableSetOf()) { it.path }
        val currentVideos = getVideos(context)
        val remainingVideos = currentVideos.filterNot { it.path in pathsToDelete }
        setVideos(context, remainingVideos)
        deleteFiles(pathsToDelete)
        return currentVideos.size - remainingVideos.size
    }

    @Synchronized
    fun deleteImages(context: Context, imagesToDelete: List<ImageInfo>): Int {
        if (imagesToDelete.isEmpty()) return 0
        val pathsToDelete = imagesToDelete.mapTo(mutableSetOf()) { it.path }
        val currentImages = getImages(context)
        val remainingImages = currentImages.filterNot { it.path in pathsToDelete }
        setImages(context, remainingImages)
        deleteFiles(pathsToDelete)
        return currentImages.size - remainingImages.size
    }

    @Synchronized
    fun deleteMedia(context: Context, mediaToDelete: List<MediaInfo>): Int {
        if (mediaToDelete.isEmpty()) return 0
        migrateLegacyMediaIfNeeded(context)

        val pathsToDelete = mediaToDelete.mapTo(mutableSetOf()) { it.path }
        val currentVideos = readVideos(context)
        val currentImages = readImages(context)
        val remainingVideos = currentVideos.filterNot { it.path in pathsToDelete }
        val remainingImages = currentImages.filterNot { it.path in pathsToDelete }
        saveVideos(context, remainingVideos)
        saveImages(context, remainingImages)
        deleteFiles(pathsToDelete)

        return currentVideos.size + currentImages.size - remainingVideos.size - remainingImages.size
    }

    fun createVideoFile(context: Context, prefix: String = "recorded"): File {
        val dir = ensureMediaDir(context, Environment.DIRECTORY_MOVIES, "videos")
        return File(dir, "${prefix}_${timestampForFile()}.mp4")
    }

    fun createImageFile(context: Context, prefix: String = "photo"): File {
        val dir = ensureMediaDir(context, Environment.DIRECTORY_PICTURES, "images")
        return File(dir, "${prefix}_${timestampForFile()}.jpg")
    }

    private fun readVideos(context: Context): List<VideoInfo> {
        return readArray(context, KEY_VIDEO_LIST).mapNotNull { obj ->
            val path = obj.optString("path")
            if (path.isBlank()) return@mapNotNull null

            VideoInfo(
                path = path,
                timestamp = obj.optLong("timestamp", 0L),
                durationMs = obj.optLong("durationMs", 0L)
            )
        }
    }

    private fun readImages(context: Context): List<ImageInfo> {
        return readArray(context, KEY_IMAGE_LIST).mapNotNull { obj ->
            val path = obj.optString("path")
            if (path.isBlank()) return@mapNotNull null

            ImageInfo(
                path = path,
                timestamp = obj.optLong("timestamp", 0L)
            )
        }
    }

    private fun readLegacyMedia(context: Context): List<MediaInfo> {
        return readArray(context, KEY_LEGACY_MEDIA_LIST).mapNotNull { obj ->
            val path = obj.optString("path")
            if (path.isBlank()) return@mapNotNull null

            val timestamp = obj.optLong("timestamp", 0L)
            val type = parseLegacyMediaType(obj.optString("mediaType", ""), path)
            val media: MediaInfo = if (type == TYPE_IMAGE) {
                ImageInfo(path = path, timestamp = timestamp)
            } else {
                VideoInfo(
                    path = path,
                    timestamp = timestamp,
                    durationMs = obj.optLong("durationMs", 0L)
                )
            }
            media
        }
    }

    private fun readArray(context: Context, key: String): List<JSONObject> {
        val jsonString = getPrefs(context).getString(key, null) ?: return emptyList()
        val list = mutableListOf<JSONObject>()
        try {
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                list.add(array.getJSONObject(i))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun saveVideos(context: Context, videos: List<VideoInfo>) {
        saveArray(context, KEY_VIDEO_LIST, videos.map { video ->
            JSONObject().apply {
                put("path", video.path)
                put("timestamp", video.timestamp)
                put("mediaType", TYPE_VIDEO)
                put("durationMs", video.durationMs)
            }
        })
    }

    private fun saveImages(context: Context, images: List<ImageInfo>) {
        saveArray(context, KEY_IMAGE_LIST, images.map { image ->
            JSONObject().apply {
                put("path", image.path)
                put("timestamp", image.timestamp)
                put("mediaType", TYPE_IMAGE)
            }
        })
    }

    private fun saveArray(context: Context, key: String, objects: List<JSONObject>) {
        val array = JSONArray()
        objects.forEach { obj -> array.put(obj) }
        getPrefs(context).edit { putString(key, array.toString()) }
    }

    private fun migrateLegacyMediaIfNeeded(context: Context) {
        val prefs = getPrefs(context)
        if (prefs.getBoolean(KEY_SPLIT_MIGRATED, false)) return

        val legacyMedia = readLegacyMedia(context)
        if (legacyMedia.isNotEmpty()) {
            val videos = mergeVideos(readVideos(context) + legacyMedia.filterIsInstance<VideoInfo>())
            val images = mergeImages(readImages(context) + legacyMedia.filterIsInstance<ImageInfo>())
            saveVideos(context, videos)
            saveImages(context, images)
        }

        prefs.edit { putBoolean(KEY_SPLIT_MIGRATED, true) }
    }

    private fun mergeVideos(videos: List<VideoInfo>): List<VideoInfo> {
        return videos
            .groupBy { it.path }
            .mapNotNull { (_, items) -> items.maxByOrNull { it.timestamp } }
            .sortedByDescending { it.timestamp }
    }

    private fun mergeImages(images: List<ImageInfo>): List<ImageInfo> {
        return images
            .groupBy { it.path }
            .mapNotNull { (_, items) -> items.maxByOrNull { it.timestamp } }
            .sortedByDescending { it.timestamp }
    }

    private fun deleteFiles(pathsToDelete: Set<String>) {
        pathsToDelete.forEach { path ->
            runCatching {
                val file = File(path)
                if (file.exists()) file.delete()
            }
        }
    }

    private fun ensureMediaDir(context: Context, type: String, child: String): File {
        val root = context.getExternalFilesDir(type) ?: File(context.filesDir, type)
        return File(root, child).apply {
            if (!exists()) mkdirs()
        }
    }

    private fun timestampForFile(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    }

    private fun parseLegacyMediaType(value: String, path: String): String {
        if (value.equals(TYPE_IMAGE, ignoreCase = true)) return TYPE_IMAGE
        if (value.equals(TYPE_VIDEO, ignoreCase = true)) return TYPE_VIDEO

        val extension = path.substringAfterLast('.', "").lowercase(Locale.US)
        return when (extension) {
            "jpg", "jpeg", "png", "webp" -> TYPE_IMAGE
            else -> TYPE_VIDEO
        }
    }
}
