package com.example.gnssandopticalflowapp.util

import android.content.Context
import android.os.Environment
import com.example.gnssandopticalflowapp.data.AppDatabase
import com.example.gnssandopticalflowapp.data.ImageEntity
import com.example.gnssandopticalflowapp.model.ImageInfo
import com.example.gnssandopticalflowapp.model.MediaInfo
import com.example.gnssandopticalflowapp.data.VideoEntity
import com.example.gnssandopticalflowapp.model.VideoInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lưu trữ thư viện ảnh và video kết quả bằng Room. Metadata của từng tệp
 * (đường dẫn, thời điểm, thời lượng) được lưu trong các bảng videos và images;
 * tệp media thực tế vẫn nằm trong bộ nhớ riêng của ứng dụng.
 */
object MediaStorageUtil {

    private fun mediaDao(context: Context) = AppDatabase.get(context).mediaDao()

    fun getVideos(context: Context): List<VideoInfo> {
        return mediaDao(context).getVideos().map { it.toModel() }
    }

    fun getImages(context: Context): List<ImageInfo> {
        return mediaDao(context).getImages().map { it.toModel() }
    }

    fun getMedia(context: Context): List<MediaInfo> {
        return mutableListOf<MediaInfo>().apply {
            addAll(getVideos(context))
            addAll(getImages(context))
        }.sortedByDescending { it.timestamp }
    }

    fun getVideo(context: Context, path: String): VideoInfo? {
        return mediaDao(context).getVideo(path)?.toModel()
    }

    fun getImage(context: Context, path: String): ImageInfo? {
        return mediaDao(context).getImage(path)?.toModel()
    }

    @Synchronized
    fun setVideos(context: Context, videos: List<VideoInfo>) {
        mediaDao(context).replaceVideos(videos.map { it.toEntity() })
    }

    @Synchronized
    fun setImages(context: Context, images: List<ImageInfo>) {
        mediaDao(context).replaceImages(images.map { it.toEntity() })
    }

    @Synchronized
    fun setVideo(context: Context, video: VideoInfo) {
        mediaDao(context).upsertVideo(video.toEntity())
    }

    @Synchronized
    fun setImage(context: Context, image: ImageInfo) {
        mediaDao(context).upsertImage(image.toEntity())
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
        val dao = mediaDao(context)
        val existing = dao.getVideos().mapTo(mutableSetOf()) { it.path }
        val pathsToDelete = videosToDelete.map { it.path }.filter { it in existing }
        if (pathsToDelete.isEmpty()) return 0
        dao.deleteVideosByPath(pathsToDelete)
        deleteFiles(pathsToDelete.toSet())
        return pathsToDelete.size
    }

    @Synchronized
    fun deleteImages(context: Context, imagesToDelete: List<ImageInfo>): Int {
        if (imagesToDelete.isEmpty()) return 0
        val dao = mediaDao(context)
        val existing = dao.getImages().mapTo(mutableSetOf()) { it.path }
        val pathsToDelete = imagesToDelete.map { it.path }.filter { it in existing }
        if (pathsToDelete.isEmpty()) return 0
        dao.deleteImagesByPath(pathsToDelete)
        deleteFiles(pathsToDelete.toSet())
        return pathsToDelete.size
    }

    @Synchronized
    fun deleteMedia(context: Context, mediaToDelete: List<MediaInfo>): Int {
        if (mediaToDelete.isEmpty()) return 0
        val videos = mediaToDelete.filterIsInstance<VideoInfo>()
        val images = mediaToDelete.filterIsInstance<ImageInfo>()
        return deleteVideos(context, videos) + deleteImages(context, images)
    }

    fun createVideoFile(context: Context, prefix: String = "recorded"): File {
        val dir = ensureMediaDir(context, Environment.DIRECTORY_MOVIES, "videos")
        return File(dir, "${prefix}_${timestampForFile()}.mp4")
    }

    fun createImageFile(context: Context, prefix: String = "photo"): File {
        val dir = ensureMediaDir(context, Environment.DIRECTORY_PICTURES, "images")
        return File(dir, "${prefix}_${timestampForFile()}.jpg")
    }

    private fun VideoEntity.toModel(): VideoInfo = VideoInfo(path, timestamp, durationMs)

    private fun ImageEntity.toModel(): ImageInfo = ImageInfo(path, timestamp)

    private fun VideoInfo.toEntity(): VideoEntity = VideoEntity(path, timestamp, durationMs)

    private fun ImageInfo.toEntity(): ImageEntity = ImageEntity(path, timestamp)

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
}
