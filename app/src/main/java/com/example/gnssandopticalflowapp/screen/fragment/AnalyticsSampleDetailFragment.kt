package com.example.gnssandopticalflowapp.screen.fragment

import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.constraintlayout.widget.ConstraintLayout
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.dp
import com.example.gnssandopticalflowapp.common.safeContext
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentAnalyticsSampleDetailBinding
import com.example.gnssandopticalflowapp.model.AnalyticsSample
import com.example.gnssandopticalflowapp.model.AnalyticsSession
import com.example.gnssandopticalflowapp.util.AnalyticsStorageUtil
import com.example.gnssandopticalflowapp.view.AnalyticsChartView
import java.util.Locale
import kotlin.math.abs

class AnalyticsSampleDetailFragment :
    BaseFragment<FragmentAnalyticsSampleDetailBinding>(FragmentAnalyticsSampleDetailBinding::inflate) {

    private var session: AnalyticsSession? = null
    private var metric = AnalyticsChartView.Metric.FPS
    private var isLandscapeLayout = false

    override fun FragmentAnalyticsSampleDetailBinding.initView() {
        val sessionId = mainViewModel.selectedAnalyticsSessionId.value
        val initialIndex = mainViewModel.selectedAnalyticsSampleIndex.value ?: 0
        metric = mainViewModel.selectedAnalyticsMetric.value
            ?.let { runCatching { AnalyticsChartView.Metric.valueOf(it) }.getOrNull() }
            ?: AnalyticsChartView.Metric.FPS
        session = sessionId?.let { AnalyticsStorageUtil.getSession(safeContext(), it) }

        val currentSession = session
        if (currentSession == null || currentSession.samples.isEmpty()) {
            root.post { onBack() }
            return
        }

        bindChart(currentSession, initialIndex.coerceIn(0, currentSession.samples.lastIndex))
        applyChartLayout(landscape = false)
    }

    override fun FragmentAnalyticsSampleDetailBinding.initListener() {
        ivBack.setSingleClick {
            onBack()
        }

        ivFullScreen.setSingleClick {
            toggleRotation()
        }
    }

    override fun initObserver() = Unit

    private fun bindChart(session: AnalyticsSession, selectedIndex: Int) = with(binding) {
        tvTitle.text = metricTitle(metric)
        chartDetail.setData(metricTitle(metric), metricUnit(metric), metric, session.samples)
        chartDetail.setSelectedIndex(selectedIndex)
        chartDetail.setOnSampleSelectedListener { index ->
            chartDetail.setSelectedIndex(index)
            bindSelectedSample(session.samples[index])
        }
        bindSelectedSample(session.samples[selectedIndex])
    }

    private fun bindSelectedSample(sample: AnalyticsSample) = with(binding) {
        tvFrameValue.text = "#${sample.frameIndex}"
        tvTimeValue.text = "${formatTwo(sample.elapsedMs / 1000.0)}s"
        tvKltValue.text = formatMetricValue(sample, klt = true)
        tvFarnebackValue.text = formatMetricValue(sample, klt = false)
    }

    private fun toggleRotation() {
        isLandscapeLayout = !isLandscapeLayout
        applyChartLayout(landscape = isLandscapeLayout)
    }

    private fun applyChartLayout(landscape: Boolean) {
        val viewBinding = binding
        viewBinding.chartFrameViews().forEach { view ->
            view.animate().cancel()
            view.rotation = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            view.translationX = 0f
            view.translationY = 0f
        }

        if (viewBinding.root.width == 0) {
            viewBinding.root.post { applyChartLayout(landscape) }
            return
        }

        if (landscape) {
            viewBinding.applyLandscapeConstraints()
        } else {
            viewBinding.applyPortraitConstraints()
        }

        viewBinding.root.requestLayout()
        viewBinding.root.doOnNextPreDraw {
            val transform = if (landscape) {
                viewBinding.computeLandscapeTransform()
            } else {
                ChartTransform(rotation = 0f, scale = 1f, translationX = 0f, translationY = 0f)
            }
            viewBinding.animateChartFrameTransform(transform)
        }
    }

    private fun FragmentAnalyticsSampleDetailBinding.chartFrameViews(): List<View> {
        return listOf(bgGlass, chartDetail, statsPanel)
    }

    private fun FragmentAnalyticsSampleDetailBinding.applyPortraitConstraints() {
        val parentId = ConstraintLayout.LayoutParams.PARENT_ID
        val unset = ConstraintLayout.LayoutParams.UNSET

        val chartParams = chartDetail.layoutParams as ConstraintLayout.LayoutParams
        chartParams.width = 0
        chartParams.height = 0
        chartParams.dimensionRatio = "2:1"
        chartParams.matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT
        chartParams.matchConstraintPercentWidth = PORTRAIT_CHART_WIDTH_PERCENT
        chartParams.startToStart = parentId
        chartParams.startToEnd = unset
        chartParams.endToStart = unset
        chartParams.endToEnd = parentId
        chartParams.topToTop = unset
        chartParams.topToBottom = ivBack.id
        chartParams.bottomToTop = unset
        chartParams.bottomToBottom = unset
        chartParams.topMargin = PORTRAIT_TOP_MARGIN_DP.dp
        chartParams.bottomMargin = 0
        chartDetail.layoutParams = chartParams

        val statsParams = statsPanel.layoutParams as ConstraintLayout.LayoutParams
        statsParams.width = 0
        statsParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        statsParams.startToStart = chartDetail.id
        statsParams.startToEnd = unset
        statsParams.endToStart = unset
        statsParams.endToEnd = chartDetail.id
        statsParams.topToTop = unset
        statsParams.topToBottom = chartDetail.id
        statsParams.bottomToTop = unset
        statsParams.bottomToBottom = unset
        statsParams.topMargin = PORTRAIT_STATS_TOP_MARGIN_DP.dp
        statsParams.bottomMargin = 0
        statsPanel.layoutParams = statsParams

        applyGlassConstraints()
    }

    private fun FragmentAnalyticsSampleDetailBinding.applyLandscapeConstraints() {
        val parentId = ConstraintLayout.LayoutParams.PARENT_ID
        val unset = ConstraintLayout.LayoutParams.UNSET

        val chartParams = chartDetail.layoutParams as ConstraintLayout.LayoutParams
        chartParams.width = 0
        chartParams.height = 0
        chartParams.dimensionRatio = null
        chartParams.matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT
        chartParams.matchConstraintPercentWidth = LANDSCAPE_CHART_WIDTH_PERCENT
        chartParams.matchConstraintDefaultHeight = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
        chartParams.startToStart = parentId
        chartParams.startToEnd = unset
        chartParams.endToStart = unset
        chartParams.endToEnd = parentId
        chartParams.topToTop = unset
        chartParams.topToBottom = ivBack.id
        chartParams.bottomToTop = statsPanel.id
        chartParams.bottomToBottom = unset
        chartParams.topMargin = LANDSCAPE_TOP_MARGIN_DP.dp
        chartParams.bottomMargin = LANDSCAPE_PANEL_GAP_DP.dp
        chartDetail.layoutParams = chartParams

        val statsParams = statsPanel.layoutParams as ConstraintLayout.LayoutParams
        statsParams.width = 0
        statsParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        statsParams.matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT
        statsParams.matchConstraintPercentWidth = LANDSCAPE_CHART_WIDTH_PERCENT
        statsParams.startToStart = parentId
        statsParams.startToEnd = unset
        statsParams.endToStart = unset
        statsParams.endToEnd = parentId
        statsParams.topToTop = unset
        statsParams.topToBottom = unset
        statsParams.bottomToTop = unset
        statsParams.bottomToBottom = parentId
        statsParams.topMargin = 0
        statsParams.bottomMargin = LANDSCAPE_BOTTOM_PANEL_MARGIN_DP.dp
        statsPanel.layoutParams = statsParams

        applyGlassConstraints()
    }

    private fun FragmentAnalyticsSampleDetailBinding.applyGlassConstraints() {
        val glassParams = bgGlass.layoutParams as ConstraintLayout.LayoutParams
        val unset = ConstraintLayout.LayoutParams.UNSET
        val glassMargin = -GLASS_MARGIN_DP.dp
        glassParams.width = 0
        glassParams.height = 0
        glassParams.startToStart = chartDetail.id
        glassParams.startToEnd = unset
        glassParams.endToStart = unset
        glassParams.endToEnd = chartDetail.id
        glassParams.topToTop = chartDetail.id
        glassParams.topToBottom = unset
        glassParams.bottomToTop = unset
        glassParams.bottomToBottom = statsPanel.id
        glassParams.setMargins(glassMargin, glassMargin, glassMargin, glassMargin)
        bgGlass.layoutParams = glassParams
    }

    private fun FragmentAnalyticsSampleDetailBinding.animateChartFrameTransform(
        transform: ChartTransform
    ) {
        chartFrameViews().forEach { view ->
            view.pivotX = transform.pivotX - view.left
            view.pivotY = transform.pivotY - view.top
            view.animate().cancel()
            view.animate()
                .rotation(transform.rotation)
                .scaleX(transform.scale)
                .scaleY(transform.scale)
                .translationX(transform.translationX)
                .translationY(transform.translationY)
                .setDuration(260)
                .start()
        }
    }

    private fun FragmentAnalyticsSampleDetailBinding.computeLandscapeTransform(): ChartTransform {
        val bounds = chartFrameBounds()
            ?: return ChartTransform(rotation = LANDSCAPE_ROTATION, scale = 1f)
        val groupWidth = bounds.width().coerceAtLeast(1f)
        val groupHeight = bounds.height().coerceAtLeast(1f)
        val targetLeft = LANDSCAPE_SIDE_MARGIN_DP.dp.toFloat()
        val targetTop = (ivBack.bottom + LANDSCAPE_TOP_SAFE_DP.dp).toFloat()
        val targetWidth = (root.width - (LANDSCAPE_SIDE_MARGIN_DP * 2).dp)
            .coerceAtLeast(1)
            .toFloat()
        val targetHeight = (root.height - ivBack.bottom - LANDSCAPE_TOP_SAFE_DP.dp - LANDSCAPE_BOTTOM_SAFE_DP.dp)
            .coerceAtLeast(1)
            .toFloat()

        val scale = minOf(
            targetWidth / groupHeight,
            targetHeight / groupWidth,
            1f
        ).coerceAtLeast(0.1f)

        val rotatedWidth = groupHeight * scale
        val rotatedHeight = groupWidth * scale
        val pivotX = bounds.centerX()
        val pivotY = bounds.centerY()
        val translationX = targetLeft - (pivotX - rotatedWidth / 2f)
        val translationY = targetTop - (pivotY - rotatedHeight / 2f)

        return ChartTransform(
            rotation = LANDSCAPE_ROTATION,
            scale = scale,
            translationX = translationX,
            translationY = translationY,
            pivotX = pivotX,
            pivotY = pivotY
        )
    }

    private fun FragmentAnalyticsSampleDetailBinding.chartFrameBounds(): RectF? {
        val views = chartFrameViews().filter { it.width > 0 && it.height > 0 }
        if (views.isEmpty()) return null

        return RectF(
            views.minOf { it.left }.toFloat(),
            views.minOf { it.top }.toFloat(),
            views.maxOf { it.right }.toFloat(),
            views.maxOf { it.bottom }.toFloat()
        )
    }

    private fun View.doOnNextPreDraw(action: () -> Unit) {
        viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (viewTreeObserver.isAlive) {
                    viewTreeObserver.removeOnPreDrawListener(this)
                }
                action()
                return true
            }
        })
    }

    private fun metricTitle(metric: AnalyticsChartView.Metric): String {
        return when (metric) {
            AnalyticsChartView.Metric.FPS -> "FPS"
            AnalyticsChartView.Metric.CONFIDENCE -> "Confidence"
            AnalyticsChartView.Metric.MAGNITUDE -> "Average Vector"
            AnalyticsChartView.Metric.PROCESS_MS -> "Process Time"
            AnalyticsChartView.Metric.TRACKS -> "Tracks / Active Vectors"
            AnalyticsChartView.Metric.THRESHOLD -> "Motion Threshold"
        }
    }

    private fun metricUnit(metric: AnalyticsChartView.Metric): String {
        return when (metric) {
            AnalyticsChartView.Metric.FPS -> "fps"
            AnalyticsChartView.Metric.CONFIDENCE -> "%"
            AnalyticsChartView.Metric.MAGNITUDE -> "px"
            AnalyticsChartView.Metric.PROCESS_MS -> "ms"
            AnalyticsChartView.Metric.TRACKS,
            AnalyticsChartView.Metric.THRESHOLD -> ""
        }
    }

    private fun formatMetricValue(sample: AnalyticsSample, klt: Boolean): String {
        return when (metric) {
            AnalyticsChartView.Metric.FPS -> "${formatOne(if (klt) sample.kltFps else sample.farnebackFps)} fps"
            AnalyticsChartView.Metric.CONFIDENCE -> "${formatOne(if (klt) sample.kltConfidence else sample.farnebackConfidence)}%"
            AnalyticsChartView.Metric.MAGNITUDE -> formatTwo(if (klt) sample.kltAvgMagnitude else sample.farnebackAvgMagnitude)
            AnalyticsChartView.Metric.PROCESS_MS -> "${formatTwo(if (klt) sample.kltProcessMs else sample.farnebackProcessMs)} ms"
            AnalyticsChartView.Metric.TRACKS -> {
                if (klt) sample.kltFeatureCount.toString() else sample.farnebackActiveVectorCount.toString()
            }
            AnalyticsChartView.Metric.THRESHOLD -> formatTwo(if (klt) sample.kltThreshold else sample.farnebackThreshold)
        }
    }

    private fun formatDelta(sample: AnalyticsSample): String {
        return when (metric) {
            AnalyticsChartView.Metric.FPS -> "${formatOne(abs(sample.kltFps - sample.farnebackFps))} fps"
            AnalyticsChartView.Metric.CONFIDENCE -> "${formatOne(abs(sample.kltConfidence - sample.farnebackConfidence))}%"
            AnalyticsChartView.Metric.MAGNITUDE -> formatTwo(abs(sample.kltAvgMagnitude - sample.farnebackAvgMagnitude))
            AnalyticsChartView.Metric.PROCESS_MS -> "${formatTwo(abs(sample.kltProcessMs - sample.farnebackProcessMs))} ms"
            AnalyticsChartView.Metric.TRACKS -> abs(sample.kltFeatureCount - sample.farnebackActiveVectorCount).toString()
            AnalyticsChartView.Metric.THRESHOLD -> formatTwo(abs(sample.kltThreshold - sample.farnebackThreshold))
        }
    }

    private fun formatOne(value: Double): String = String.format(Locale.US, "%.1f", value)
    private fun formatTwo(value: Double): String = String.format(Locale.US, "%.2f", value)

    private data class ChartTransform(
        val rotation: Float,
        val scale: Float,
        val translationX: Float = 0f,
        val translationY: Float = 0f,
        val pivotX: Float = 0f,
        val pivotY: Float = 0f
    )

    private companion object {
        const val LANDSCAPE_ROTATION = 90f
        const val PORTRAIT_TOP_MARGIN_DP = 20
        const val PORTRAIT_STATS_TOP_MARGIN_DP = 12
        const val PORTRAIT_CHART_WIDTH_PERCENT = 0.9f
        const val LANDSCAPE_TOP_MARGIN_DP = 14
        const val LANDSCAPE_TOP_SAFE_DP = 12
        const val LANDSCAPE_BOTTOM_SAFE_DP = 18
        const val LANDSCAPE_SIDE_MARGIN_DP = 12
        const val LANDSCAPE_PANEL_GAP_DP = 8
        const val LANDSCAPE_BOTTOM_PANEL_MARGIN_DP = 16
        const val LANDSCAPE_CHART_WIDTH_PERCENT = 0.86f
        const val GLASS_MARGIN_DP = 10
    }
}
