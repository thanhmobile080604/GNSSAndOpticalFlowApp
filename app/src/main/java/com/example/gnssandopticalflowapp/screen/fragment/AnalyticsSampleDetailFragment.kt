package com.example.gnssandopticalflowapp.screen.fragment

import android.content.pm.ActivityInfo
import com.example.gnssandopticalflowapp.base.BaseFragment
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
    private var forcedLandscape = false

    override fun FragmentAnalyticsSampleDetailBinding.initView() {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        forcedLandscape = true

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
    }

    override fun FragmentAnalyticsSampleDetailBinding.initListener() {
        ivBack.setSingleClick {
            onBack()
        }
    }

    override fun initObserver() = Unit

    override fun onBack() {
        restorePortrait()
        super.onBack()
    }

    override fun onDestroyView() {
        restorePortrait()
        super.onDestroyView()
    }

    private fun restorePortrait() {
        if (!forcedLandscape) return
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        forcedLandscape = false
    }

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
        tvKltProcessValue.text = "${formatTwo(sample.kltProcessMs)} ms"
        tvFarnebackProcessValue.text = "${formatTwo(sample.farnebackProcessMs)} ms"
        tvKltActiveValue.text = "${sample.kltActiveVectorCount}/${sample.kltFeatureCount}"
        tvFarnebackActiveValue.text =
            "${sample.farnebackActiveVectorCount}/${sample.farnebackSampleCount}"
        tvKltVectorValue.text = formatVector(sample.kltAvgDx, sample.kltAvgDy)
        tvFarnebackVectorValue.text = formatVector(sample.farnebackAvgDx, sample.farnebackAvgDy)
        tvKltConfidenceValue.text = "${formatOne(sample.kltConfidence)}%"
        tvFarnebackConfidenceValue.text = "${formatOne(sample.farnebackConfidence)}%"
    }

    private fun metricTitle(metric: AnalyticsChartView.Metric): String {
        return when (metric) {
            AnalyticsChartView.Metric.FPS -> "FPS"
            AnalyticsChartView.Metric.PROCESS_MS -> "Process Time"
            AnalyticsChartView.Metric.TRACKS -> "Tracks / Active Vectors"
        }
    }

    private fun metricUnit(metric: AnalyticsChartView.Metric): String {
        return when (metric) {
            AnalyticsChartView.Metric.FPS -> "fps"
            AnalyticsChartView.Metric.PROCESS_MS -> "ms"
            AnalyticsChartView.Metric.TRACKS -> ""
        }
    }

    private fun formatMetricValue(sample: AnalyticsSample, klt: Boolean): String {
        return when (metric) {
            AnalyticsChartView.Metric.FPS ->
                "${formatOne(if (klt) sample.kltFps else sample.farnebackFps)} fps"
            AnalyticsChartView.Metric.PROCESS_MS ->
                "${formatTwo(if (klt) sample.kltProcessMs else sample.farnebackProcessMs)} ms"
            AnalyticsChartView.Metric.TRACKS -> {
                if (klt) sample.kltFeatureCount.toString() else sample.farnebackActiveVectorCount.toString()
            }
        }
    }

    private fun formatVector(dx: Double, dy: Double): String {
        return "${formatTwo(dx)}, ${formatTwo(dy)}"
    }

    private fun formatOne(value: Double): String = String.format(Locale.US, "%.1f", value)
    private fun formatTwo(value: Double): String = String.format(Locale.US, "%.2f", value)
}
