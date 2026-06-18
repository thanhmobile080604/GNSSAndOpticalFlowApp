package com.example.gnssandopticalflowapp.screen.fragment

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources.getDrawable
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.dp
import com.example.gnssandopticalflowapp.common.safeContext
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentRouteReplayBinding
import com.example.gnssandopticalflowapp.model.RouteLatLng
import com.example.gnssandopticalflowapp.model.RouteSession
import com.example.gnssandopticalflowapp.util.RouteStorageUtil
import org.osmdroid.config.Configuration as OsmConfiguration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RouteReplayFragment :
    BaseFragment<FragmentRouteReplayBinding>(FragmentRouteReplayBinding::inflate) {

    override fun FragmentRouteReplayBinding.initView() {
        val id = mainViewModel.selectedRouteSessionId.value
        val session = id?.let { RouteStorageUtil.getSession(safeContext(), it) }
        if (session == null) {
            root.post {
                Toast.makeText(safeContext(), "Route session not found", Toast.LENGTH_SHORT).show()
                onBack()
            }
            return
        }

        tvTitle.text = buildTitle(session)
        setupMap()
        drawSession(session)
    }

    override fun FragmentRouteReplayBinding.initListener() {
        ivBack.setSingleClick { onBack() }
    }

    override fun initObserver() = Unit

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    private fun setupMap() {
        val ctx = safeContext()
        OsmConfiguration.getInstance().userAgentValue = ctx.packageName
        OsmConfiguration.getInstance().load(
            ctx.applicationContext,
            ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )
        with(binding.mapView) {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true) // pinch zoom + pan
            overlays.add(RotationGestureOverlay(this).apply { isEnabled = true }) // two-finger rotate
            controller.setZoom(18.0)
        }
    }

    private fun drawSession(session: RouteSession) {
        // Bottom-to-top draw order: planned route, then BLACK gnss path, then RED optical path, then pins.
        drawRouteLine(session.routePoints.toGeoPoints())
        drawPathSegments(session.gnssTravelSegments, color = Color.BLACK, strokeWidth = 8f)
        drawPathSegments(
            session.opticalAssistSegments,
            color = Color.rgb(255, 40, 60),
            strokeWidth = 8f
        )
        drawMarkers(session.weakPoints.toGeoPoints(), R.drawable.ic_weak, "GNSS Lost")
        drawMarkers(session.strongPoints.toGeoPoints(), R.drawable.ic_strong, "GNSS Restored")
        drawTargetMarker(session.destination.toGeoPoint(), session.destinationName)

        frameToBounds(session)
    }

    private fun drawRouteLine(points: List<GeoPoint>) {
        if (points.size < 2) return
        val line = Polyline(binding.mapView).apply {
            infoWindow = null // no tap dialog on the path itself — only pins/destination respond
            outlinePaint.color = Color.rgb(123, 92, 255)
            outlinePaint.strokeWidth = 9f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            setPoints(points)
        }
        binding.mapView.overlays.add(0, line)
    }

    private fun drawPathSegments(
        segments: List<List<RouteLatLng>>,
        color: Int,
        strokeWidth: Float
    ) {
        segments.filter { it.size >= 2 }.forEach { segment ->
            val line = Polyline(binding.mapView).apply {
                infoWindow = null // no tap dialog on the path itself — only pins/destination respond
                outlinePaint.color = color
                outlinePaint.alpha = 255
                outlinePaint.strokeWidth = strokeWidth
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                setPoints(segment.toGeoPoints())
            }
            binding.mapView.overlays.add(line)
        }
    }

    private fun drawMarkers(points: List<GeoPoint>, iconRes: Int, titleText: String) {
        val icon = buildMarkerIcon(iconRes, 24)
        points.forEach { point ->
            val marker = Marker(binding.mapView).apply {
                setAnchor(0.2f, 0.85f)
                this.icon = icon
                title = titleText
                position = point
            }
            binding.mapView.overlays.add(marker)
        }
    }

    private fun drawTargetMarker(point: GeoPoint, name: String) {
        val marker = Marker(binding.mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = buildMarkerIcon(R.drawable.ic_target_location, 46)
            title = name.ifBlank { "Destination" }
            position = point
        }
        binding.mapView.overlays.add(marker)
    }

    private fun frameToBounds(session: RouteSession) {
        val all = ArrayList<GeoPoint>()
        all.addAll(session.routePoints.toGeoPoints())
        session.gnssTravelSegments.forEach { all.addAll(it.toGeoPoints()) }
        session.opticalAssistSegments.forEach { all.addAll(it.toGeoPoints()) }
        all.addAll(session.weakPoints.toGeoPoints())
        all.addAll(session.strongPoints.toGeoPoints())
        all.add(session.destination.toGeoPoint())
        all.add(session.start.toGeoPoint())
        if (all.isEmpty()) return

        binding.mapView.post {
            if (!isAdded || view == null) return@post
            if (all.size == 1) {
                binding.mapView.controller.setZoom(18.0)
                binding.mapView.controller.setCenter(all.first())
            } else {
                runCatching {
                    binding.mapView.zoomToBoundingBox(
                        BoundingBox.fromGeoPoints(all).increaseByScale(1.35f),
                        false,
                        48
                    )
                }
            }
            binding.mapView.invalidate()
        }
    }

    private fun buildMarkerIcon(drawableRes: Int, sizeDp: Int): Drawable? = context?.let { ctx ->
        getDrawable(ctx, drawableRes)?.let { drawable ->
            val sizePx = sizeDp.dp
            val bitmap = createBitmap(sizePx, sizePx)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap.toDrawable(ctx.resources)
        }
    }

    /** 2 lines: short destination, then start->end time range below it. */
    private fun buildTitle(session: RouteSession): String {
        val name = session.destinationName.substringBefore(",").trim().ifBlank { "Route" }
        val start = titleFormat.format(Date(session.startedAtMs))
        val end = titleFormat.format(Date(session.startedAtMs + session.durationMs))
        return "$name\n$start -> $end"
    }

    private fun RouteLatLng.toGeoPoint() = GeoPoint(lat, lon)
    private fun List<RouteLatLng>.toGeoPoints() = map { it.toGeoPoint() }

    private companion object {
        val titleFormat = SimpleDateFormat("H:mm dd/MM/yyyy", Locale.getDefault())
    }
}
