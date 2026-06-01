package com.example.gnssandopticalflowapp.gnss

import com.example.gnssandopticalflowapp.model.RouteInfo
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL

class MapRouteRepository {
    fun fetchRoute(origin: GeoPoint, destination: GeoPoint): RouteInfo? {
        val url = URL(
            "https://router.project-osrm.org/route/v1/driving/" +
                "${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}" +
                "?overview=full&geometries=geojson"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "GNSSAndOpticalFlowApp/1.0")
        }

        return connection.useTextResponse { body ->
            val root = JSONObject(body)
            val routes = root.optJSONArray("routes") ?: return@useTextResponse null
            if (routes.length() == 0) return@useTextResponse null

            val firstRoute = routes.getJSONObject(0)
            val geometry = firstRoute.getJSONObject("geometry")
            val coordinates = geometry.getJSONArray("coordinates")
            val points = buildList {
                for (index in 0 until coordinates.length()) {
                    val coordinate = coordinates.getJSONArray(index)
                    add(GeoPoint(coordinate.getDouble(1), coordinate.getDouble(0)))
                }
            }
            RouteInfo(
                points = points,
                distanceMeters = firstRoute.optDouble("distance", 0.0),
                durationSeconds = firstRoute.optDouble("duration", 0.0)
            )
        }
    }

    private inline fun <T> HttpURLConnection.useTextResponse(block: (String) -> T): T {
        return try {
            val stream = if (responseCode in 200..299) inputStream else errorStream
            val body = stream.bufferedReader().use { it.readText() }
            block(body)
        } finally {
            disconnect()
        }
    }
}
