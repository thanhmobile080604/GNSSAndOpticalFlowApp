package com.example.gnssandopticalflowapp.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.gnssandopticalflowapp.common.hide
import com.example.gnssandopticalflowapp.common.show
import com.example.gnssandopticalflowapp.databinding.ItemAnalyticsSessionBinding
import com.example.gnssandopticalflowapp.model.AnalyticsSessionSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AnalyticsSessionAdapter(
    private val onOpenSession: (AnalyticsSessionSummary) -> Unit,
    private val onSelectionChanged: () -> Unit = {}
) : RecyclerView.Adapter<AnalyticsSessionAdapter.AnalyticsSessionViewHolder>() {

    private var sessions: List<AnalyticsSessionSummary> = emptyList()
    private var isEditMode = false
    private val selectedIds = mutableSetOf<String>()

    @SuppressLint("NotifyDataSetChanged")
    fun setData(items: List<AnalyticsSessionSummary>) {
        sessions = items
        selectedIds.clear()
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setEditMode(edit: Boolean) {
        if (isEditMode == edit) return
        isEditMode = edit
        if (!edit) selectedIds.clear()
        notifyDataSetChanged()
    }

    fun getSelectedSessions(): List<AnalyticsSessionSummary> {
        return sessions.filter { selectedIds.contains(it.id) }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun removeSessions(sessionsToRemove: List<AnalyticsSessionSummary>) {
        val removeIds = sessionsToRemove.map { it.id }.toSet()
        sessions = sessions.filterNot { removeIds.contains(it.id) }
        selectedIds.removeAll(removeIds)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AnalyticsSessionViewHolder {
        val binding = ItemAnalyticsSessionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return AnalyticsSessionViewHolder(binding, onOpenSession, ::toggleSelection)
    }

    private fun toggleSelection(session: AnalyticsSessionSummary) {
        if (!isEditMode) return
        if (selectedIds.contains(session.id)) {
            selectedIds.remove(session.id)
        } else {
            selectedIds.add(session.id)
        }
        val index = sessions.indexOfFirst { it.id == session.id }
        if (index != -1) notifyItemChanged(index)
        onSelectionChanged()
    }

    override fun onBindViewHolder(holder: AnalyticsSessionViewHolder, position: Int) {
        val session = sessions[position]
        holder.bind(session, isEditMode, selectedIds.contains(session.id))
    }

    override fun getItemCount(): Int = sessions.size

    class AnalyticsSessionViewHolder(
        private val binding: ItemAnalyticsSessionBinding,
        private val onOpenSession: (AnalyticsSessionSummary) -> Unit,
        private val onToggleSelection: (AnalyticsSessionSummary) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(session: AnalyticsSessionSummary, isEditMode: Boolean, isSelected: Boolean) = with(binding) {
            tvTitle.text = dateFormat.format(Date(session.startedAtMs))
            tvDuration.text = formatDuration(session.durationMs)
            tvSamples.text = "${session.sampleCount} samples"
            tvKltValue.text = "${session.avgKltFps.formatOne()} fps"
            tvFarnebackValue.text = "${session.avgFarnebackFps.formatOne()} fps"
            tvConfidence.text = "Confidence ${session.avgKltConfidence.formatOne()}% / ${session.avgFarnebackConfidence.formatOne()}%"

            if(isSelected &&isEditMode) ivCheck.show()
            else ivCheck.hide()
            
            root.setOnClickListener {
                if (isEditMode) {
                    onToggleSelection(session)
                } else {
                    onOpenSession(session)
                }
            }
        }

        private fun formatDuration(durationMs: Long): String {
            val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            return String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }

        private fun Double.formatOne(): String = String.format(Locale.US, "%.1f", this)

        private companion object {
            @SuppressLint("ConstantLocale")
            val dateFormat = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault())
        }
    }
}
