package com.example.gnssandopticalflowapp.screen.fragment

import android.widget.Toast
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.safeContext
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentAnalyticsViewBinding
import com.example.gnssandopticalflowapp.model.AnalyticsSample
import com.example.gnssandopticalflowapp.model.AnalyticsSession
import com.example.gnssandopticalflowapp.util.AnalyticsStorageUtil
import com.example.gnssandopticalflowapp.view.AnalyticsChartView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AnalyticsViewFragment :
    BaseFragment<FragmentAnalyticsViewBinding>(FragmentAnalyticsViewBinding::inflate) {

    private var session: AnalyticsSession? = null

    override fun FragmentAnalyticsViewBinding.initView() {
        val sessionId = mainViewModel.selectedAnalyticsSessionId.value
        session = sessionId?.let { AnalyticsStorageUtil.getSession(safeContext(), it) }

        val currentSession = session
        if (currentSession == null) {
            root.post {
                Toast.makeText(safeContext(), "Analysis session not found", Toast.LENGTH_SHORT).show()
                onBack()
            }
            return
        }

        bindSession(currentSession)
    }

    override fun FragmentAnalyticsViewBinding.initListener() {
        ivBack.setSingleClick {
            onBack()
        }

    }

    override fun initObserver() = Unit

    private fun bindSession(session: AnalyticsSession) = with(binding) {
        tvSessionTime.text = displayDateFormat.format(Date(session.startedAtMs))
        tvDurationValue.text = formatDuration(session.durationMs)
        tvSamplesValue.text = session.samples.size.toString()
        tvKltFpsValue.text = "${session.samples.averageOf { it.kltFps }.formatOne()} fps"
        tvFarnebackFpsValue.text = "${session.samples.averageOf { it.farnebackFps }.formatOne()} fps"
        tvKltConfidenceValue.text = "${session.samples.averageOf { it.kltConfidence }.formatOne()}%"
        tvFarnebackConfidenceValue.text = "${session.samples.averageOf { it.farnebackConfidence }.formatOne()}%"

        chartFps.setData("FPS", "fps", AnalyticsChartView.Metric.FPS, session.samples)
        chartConfidence.setData("Confidence", "%", AnalyticsChartView.Metric.CONFIDENCE, session.samples)
        chartMagnitude.setData("Average Vector", "px", AnalyticsChartView.Metric.MAGNITUDE, session.samples)
        chartProcess.setData("Process Time", "ms", AnalyticsChartView.Metric.PROCESS_MS, session.samples)
        chartTracks.setData("Tracks / Active Vectors", "", AnalyticsChartView.Metric.TRACKS, session.samples)
        chartFlowX.setData("Horizontal Flow", "px", AnalyticsChartView.Metric.FLOW_X, session.samples)

        chartFps.setOnSampleSelectedListener { openChartDetail(AnalyticsChartView.Metric.FPS, it) }
        chartConfidence.setOnSampleSelectedListener { openChartDetail(AnalyticsChartView.Metric.CONFIDENCE, it) }
        chartMagnitude.setOnSampleSelectedListener { openChartDetail(AnalyticsChartView.Metric.MAGNITUDE, it) }
        chartProcess.setOnSampleSelectedListener { openChartDetail(AnalyticsChartView.Metric.PROCESS_MS, it) }
        chartTracks.setOnSampleSelectedListener { openChartDetail(AnalyticsChartView.Metric.TRACKS, it) }
        chartFlowX.setOnSampleSelectedListener { openChartDetail(AnalyticsChartView.Metric.FLOW_X, it) }
    }

    private fun openChartDetail(metric: AnalyticsChartView.Metric, index: Int) {
        val currentSession = session ?: return
        if (index !in currentSession.samples.indices) return

        mainViewModel.selectedAnalyticsSessionId.value = currentSession.id
        mainViewModel.selectedAnalyticsSampleIndex.value = index
        mainViewModel.selectedAnalyticsMetric.value = metric.name
        navigateTo(com.example.gnssandopticalflowapp.R.id.analyticsSampleDetailFragment)
    }

    private fun List<AnalyticsSample>.averageOf(selector: (AnalyticsSample) -> Double): Double {
        if (isEmpty()) return 0.0
        return map(selector).filter { it.isFinite() }.average().takeIf { it.isFinite() } ?: 0.0
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun Double.formatOne(): String = String.format(Locale.US, "%.1f", this)

    private companion object {
        val displayDateFormat = SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault())
    }
}
