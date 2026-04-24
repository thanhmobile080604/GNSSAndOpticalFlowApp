package com.example.gnssandopticalflowapp.util

import android.content.Context
import android.content.SharedPreferences
import com.example.gnssandopticalflowapp.model.VideoInfo
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.edit

object VideoStorageUtil {
    private const val PREFS_NAME = "video_storage_prefs"
    private const val KEY_VIDEO_LIST = "video_list"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getVideos(context: Context): List<VideoInfo> {
        val prefs = getPrefs(context)
        val jsonString = prefs.getString(KEY_VIDEO_LIST, null) ?: return emptyList()
        val list = mutableListOf<VideoInfo>()
        try {
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(VideoInfo(obj.getString("path"), obj.getLong("timestamp")))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun addVideo(context: Context, videoPath: String) {
        val videos = getVideos(context).toMutableList()
        if (videos.none { it.path == videoPath }) {
            videos.add(0, VideoInfo(videoPath, System.currentTimeMillis()))
            saveVideos(context, videos)
        }
    }

    private fun saveVideos(context: Context, videos: List<VideoInfo>) {
        val prefs = getPrefs(context)
        val array = JSONArray()
        for (video in videos) {
            val obj = JSONObject()
            obj.put("path", video.path)
            obj.put("timestamp", video.timestamp)
            array.put(obj)
        }
        prefs.edit { putString(KEY_VIDEO_LIST, array.toString()) }
    }
}
