package com.example.gnssandopticalflowapp.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

@Entity(tableName = "videos")
data class VideoEntity(
    @PrimaryKey val path: String,
    val timestamp: Long,
    val durationMs: Long
)

@Entity(tableName = "images")
data class ImageEntity(
    @PrimaryKey val path: String,
    val timestamp: Long
)

@Dao
interface MediaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertVideo(video: VideoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertVideos(videos: List<VideoEntity>)

    @Query("SELECT * FROM videos ORDER BY timestamp DESC")
    fun getVideos(): List<VideoEntity>

    @Query("SELECT * FROM videos WHERE path = :path")
    fun getVideo(path: String): VideoEntity?

    @Query("DELETE FROM videos")
    fun clearVideos()

    @Query("DELETE FROM videos WHERE path IN (:paths)")
    fun deleteVideosByPath(paths: List<String>)

    @Transaction
    fun replaceVideos(videos: List<VideoEntity>) {
        clearVideos()
        upsertVideos(videos)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertImage(image: ImageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertImages(images: List<ImageEntity>)

    @Query("SELECT * FROM images ORDER BY timestamp DESC")
    fun getImages(): List<ImageEntity>

    @Query("SELECT * FROM images WHERE path = :path")
    fun getImage(path: String): ImageEntity?

    @Query("DELETE FROM images")
    fun clearImages()

    @Query("DELETE FROM images WHERE path IN (:paths)")
    fun deleteImagesByPath(paths: List<String>)

    @Transaction
    fun replaceImages(images: List<ImageEntity>) {
        clearImages()
        upsertImages(images)
    }
}
