package com.example.gnssandopticalflowapp.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.gnssandopticalflowapp.databinding.ItemAnalyticsSessionBinding
import com.example.gnssandopticalflowapp.model.AnalyticsSessionSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AnalyticsSessionAdapter(
    private val onOpenSession: (AnalyticsSessionSummary) -> Unit
) : RecyclerView.Adapter<AnalyticsSessionAdapter.AnalyticsSessionViewHolder>() {

    private var sessions: List<AnalyticsSessionSummary> = emptyList()

    fun setData(items: List<AnalyticsSessionSummary>) {
        sessions = items
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AnalyticsSessionViewHolder {
        val binding = ItemAnalyticsSessionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return AnalyticsSessionViewHolder(binding, onOpenSession)
    }

    override fun onBindViewHolder(holder: AnalyticsSessionViewHolder, position: Int) {
        holder.bind(sessions[position])
    }

    override fun getItemCount(): Int = sessions.size

    class AnalyticsSessionViewHolder(
        private val binding: ItemAnalyticsSessionBinding,
        private val onOpenSession: (AnalyticsSessionSummary) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(session: AnalyticsSessionSummary) = with(binding) {
            tvTitle.text = dateFormat.format(Date(session.startedAtMs))
            tvDuration.text = formatDuration(session.durationMs)
            tvSamples.text = "${session.sampleCount} samples"
            tvKltValue.text = "${session.avgKltFps.formatOne()} fps"
            tvFarnebackValue.text = "${session.avgFarnebackFps.formatOne()} fps"
            tvConfidence.text = "Confidence ${session.avgKltConfidence.formatOne()}% / ${session.avgFarnebackConfidence.formatOne()}%"
            root.setOnClickListener { onOpenSession(session) }
        }

        private fun formatDuration(durationMs: Long): String {
            val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            return String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }

        private fun Double.formatOne(): String = String.format(Locale.US, "%.1f", this)

        private companion object {
            val dateFormat = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault())
        }
    }
}
