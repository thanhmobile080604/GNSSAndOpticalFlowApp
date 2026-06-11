package com.example.gnssandopticalflowapp.function.video.options

import com.example.gnssandopticalflowapp.model.VideoProcessOptions
import org.json.JSONArray
import org.json.JSONObject

internal object VideoProcessOptionsCodec {
    fun encode(options: VideoProcessOptions): String {
        val root = JSONObject()
            .put("isMoving", options.isMoving)
            .put("useFarneback", options.useFarneback)
            .put("sensitivity", options.sensitivity)
            .put("useFarnebackHeatmap", options.useFarnebackHeatmap)
            .put("useAi", options.useAi)
            .put("processingMode", options.processingMode.name)

        options.roi?.let { normalizedRoi ->
            val pathPoints = JSONArray()
            normalizedRoi.pathPoints.forEach { point ->
                pathPoints.put(
                    JSONObject()
                        .put("x", point.x.toDouble())
                        .put("y", point.y.toDouble())
                )
            }

            root.put(
                "roi",
                JSONObject()
                    .put("left", normalizedRoi.left.toDouble())
                    .put("top", normalizedRoi.top.toDouble())
                    .put("right", normalizedRoi.right.toDouble())
                    .put("bottom", normalizedRoi.bottom.toDouble())
                    .put("viewAspectRatio", normalizedRoi.viewAspectRatio.toDouble())
                    .put("selectedPositionMs", normalizedRoi.selectedPositionMs)
                    .put("pathPoints", pathPoints)
            )
        }

        return root.toString()
    }

    fun decode(json: String): VideoProcessOptions? {
        return runCatching {
            val root = JSONObject(json)
            val roi = root.optJSONObject("roi")?.let { roiJson ->
                val pathPointsJson = roiJson.optJSONArray("pathPoints")
                val pathPoints = if (pathPointsJson == null) {
                    emptyList()
                } else {
                    List(pathPointsJson.length()) { index ->
                        val pointJson = pathPointsJson.getJSONObject(index)
                        VideoProcessOptions.NormalizedPoint(
                            x = pointJson.getDouble("x").toFloat(),
                            y = pointJson.getDouble("y").toFloat()
                        )
                    }
                }

                VideoProcessOptions.NormalizedRoi(
                    left = roiJson.getDouble("left").toFloat(),
                    top = roiJson.getDouble("top").toFloat(),
                    right = roiJson.getDouble("right").toFloat(),
                    bottom = roiJson.getDouble("bottom").toFloat(),
                    viewAspectRatio = roiJson.getDouble("viewAspectRatio").toFloat(),
                    selectedPositionMs = roiJson.optLong("selectedPositionMs", 0L),
                    pathPoints = pathPoints
                )
            }

            val processingMode = runCatching {
                VideoProcessOptions.ProcessingMode.valueOf(
                    root.optString(
                        "processingMode",
                        VideoProcessOptions.ProcessingMode.OFFLINE.name
                    )
                )
            }.getOrDefault(VideoProcessOptions.ProcessingMode.OFFLINE)

            VideoProcessOptions(
                isMoving = root.getBoolean("isMoving"),
                useFarneback = root.getBoolean("useFarneback"),
                sensitivity = root.getInt("sensitivity"),
                useFarnebackHeatmap = root.optBoolean("useFarnebackHeatmap", false),
                useAi = root.optBoolean("useAi", false),
                roi = roi,
                processingMode = processingMode
            )
        }.getOrNull()
    }
}
