package com.example.gnssandopticalflowapp.function.gnss

import android.content.Context
import androidx.core.content.edit
import com.example.gnssandopticalflowapp.model.SearchPlace
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MapPlaceRepository(context: Context) {
    private val appContext = context.applicationContext

    fun searchPlaces(query: String): List<SearchPlace> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = URL(
            "https://nominatim.openstreetmap.org/search?format=json&addressdetails=0&limit=12&q=$encodedQuery"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "GNSSAndOpticalFlowApp/1.0")
        }

        return connection.useTextResponse { body ->
            val results = JSONArray(body)
            buildList {
                for (index in 0 until results.length()) {
                    val item = results.getJSONObject(index)
                    val name = item.optString("display_name").takeIf { it.isNotBlank() } ?: continue
                    val latitude = item.optString("lat").toDoubleOrNull() ?: continue
                    val longitude = item.optString("lon").toDoubleOrNull() ?: continue
                    add(SearchPlace(name = name, latitude = latitude, longitude = longitude))
                }
            }
        }
    }

    fun reverseGeocode(latitude: Double, longitude: Double): String? {
        val url = URL(
            "https://nominatim.openstreetmap.org/reverse?format=json&lat=$latitude&lon=$longitude&zoom=18&addressdetails=0"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "GNSSAndOpticalFlowApp/1.0")
        }

        return try {
            connection.useTextResponse { body ->
                val result = JSONObject(body)
                result.optString("display_name").takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getRecentSearches(): List<SearchPlace> {
        val historyJson = prefs.getString(HISTORY_KEY, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(historyJson)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        SearchPlace(
                            name = item.getString("name"),
                            latitude = item.getDouble("lat"),
                            longitude = item.getDouble("lon")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveRecentSearch(place: SearchPlace) {
        val updated = getRecentSearches().toMutableList().apply {
            removeAll { it.name == place.name }
            add(0, place)
            if (size > MAX_RECENT_SEARCHES) {
                subList(MAX_RECENT_SEARCHES, size).clear()
            }
        }

        val history = JSONArray()
        updated.forEach { recentPlace ->
            history.put(
                JSONObject().apply {
                    put("name", recentPlace.name)
                    put("lat", recentPlace.latitude)
                    put("lon", recentPlace.longitude)
                }
            )
        }

        prefs.edit { putString(HISTORY_KEY, history.toString()) }
    }

    private val prefs
        get() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private inline fun <T> HttpURLConnection.useTextResponse(block: (String) -> T): T {
        return try {
            val stream = if (responseCode in 200..299) inputStream else errorStream
            val body = stream.bufferedReader().use { it.readText() }
            block(body)
        } finally {
            disconnect()
        }
    }

    private companion object {
        const val PREFS_NAME = "recent_searches"
        const val HISTORY_KEY = "history"
        const val MAX_RECENT_SEARCHES = 3
    }
}
