package com.example.gnssandopticalflowapp.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.ItemTopBarNotificationBinding
import com.example.gnssandopticalflowapp.video.VideoProcessingJobState
import com.example.gnssandopticalflowapp.video.VideoProcessingProgressText
import kotlin.math.abs

class TopBarNotificationAdapter(
    private val onPrimaryAction: (NotificationItem) -> Unit,
    private val onLaterAction: (NotificationItem) -> Unit,
    private val onCollapseAction: (NotificationItem) -> Unit
) : RecyclerView.Adapter<TopBarNotificationAdapter.TopBarNotificationViewHolder>() {

    private var items: List<NotificationItem> = emptyList()

    fun submitItems(
        newItems: List<NotificationItem>,
        currentPosition: Int,
        refreshVisibleItems: Boolean
    ): Boolean {
        val keysChanged = items.map { it.key } != newItems.map { it.key }
        val contentChanged = items != newItems
        items = newItems
        if (keysChanged) {
            notifyDataSetChanged()
        } else if (contentChanged && refreshVisibleItems) {
            refreshAround(currentPosition)
        }
        return keysChanged
    }

    fun refreshAround(position: Int) {
        if (items.isEmpty()) return
        val maxPosition = getItemCount() - 1
        (position - 1..position + 1)
            .filter { it in 0..maxPosition }
            .forEach { positionToRefresh -> notifyItemChanged(positionToRefresh) }
    }

    fun getRealItemCount(): Int = items.size

    fun itemAt(position: Int): NotificationItem? {
        if (items.isEmpty()) return null
        return items[realPosition(position)]
    }

    fun initialPositionForKey(key: String?): Int {
        val realIndex = realIndexOfKey(key).takeIf { it >= 0 } ?: 0
        if (items.size <= 1) return realIndex

        val middle = Int.MAX_VALUE / 2
        return middle - (middle % items.size) + realIndex
    }

    fun nearestPositionForKey(key: String?, currentPosition: Int): Int {
        val realIndex = realIndexOfKey(key).takeIf { it >= 0 } ?: 0
        if (items.size <= 1) return realIndex

        val base = currentPosition - realPosition(currentPosition)
        return listOf(
            base + realIndex,
            base + realIndex + items.size,
            base + realIndex - items.size
        )
            .filter { it >= 0 }
            .minByOrNull { abs(it - currentPosition) }
            ?: initialPositionForKey(key)
    }

    private fun realIndexOfKey(key: String?): Int {
        if (key.isNullOrBlank()) return -1
        return items.indexOfFirst { it.key == key }
    }

    private fun realPosition(position: Int): Int {
        if (items.isEmpty()) return 0
        return ((position % items.size) + items.size) % items.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TopBarNotificationViewHolder {
        val binding = ItemTopBarNotificationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        binding.root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        return TopBarNotificationViewHolder(
            binding = binding,
            onPrimaryAction = onPrimaryAction,
            onLaterAction = onLaterAction,
            onCollapseAction = onCollapseAction
        )
    }

    override fun onBindViewHolder(holder: TopBarNotificationViewHolder, position: Int) {
        itemAt(position)?.let(holder::bind)
    }

    override fun getItemCount(): Int {
        if (items.isEmpty()) return 0
        if (items.size == 1) return 1
        return Int.MAX_VALUE
    }

    data class NotificationItem(
        val key: String,
        val jobId: String?,
        val title: String?,
        val message: String,
        val percent: Int,
        val status: VideoProcessingJobState.Status,
        val outputPath: String?
    ) {
        val isReady: Boolean
            get() = status == VideoProcessingJobState.Status.COMPLETED && !outputPath.isNullOrBlank()

        val isTerminal: Boolean
            get() = status == VideoProcessingJobState.Status.COMPLETED ||
                status == VideoProcessingJobState.Status.FAILED ||
                status == VideoProcessingJobState.Status.CANCELLED
    }

    class TopBarNotificationViewHolder(
        private val binding: ItemTopBarNotificationBinding,
        private val onPrimaryAction: (NotificationItem) -> Unit,
        private val onLaterAction: (NotificationItem) -> Unit,
        private val onCollapseAction: (NotificationItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: NotificationItem) = with(binding) {
            tvProcessingJobId.text = item.title.orEmpty()
            tvProcessingJobId.visibility = if (item.title.isNullOrBlank()) View.GONE else View.VISIBLE

            when (item.status) {
                VideoProcessingJobState.Status.QUEUED,
                VideoProcessingJobState.Status.PROCESSING -> bindProcessing(item)
                VideoProcessingJobState.Status.COMPLETED -> {
                    if (item.isReady) {
                        bindCompleted(item)
                    } else {
                        bindTerminal(item, "Done")
                    }
                }
                VideoProcessingJobState.Status.FAILED -> bindTerminal(item, "Failed")
                VideoProcessingJobState.Status.CANCELLED -> bindTerminal(item, "Cancelled")
            }

            btnCancel.setSingleClick { onPrimaryAction(item) }
            btnLater.setSingleClick { onLaterAction(item) }
            ivClose.setSingleClick { onCollapseAction(item) }
        }

        private fun bindProcessing(item: NotificationItem) = with(binding) {
            tvLoadingMessage.text = VideoProcessingProgressText.normalize(item.message, item.percent)
            btnCancel.text = CANCEL_TEXT
            btnCancel.visibility = View.VISIBLE
            btnLater.visibility = View.GONE
            progressCircular.visibility = View.VISIBLE
        }

        private fun bindCompleted(item: NotificationItem) = with(binding) {
            tvLoadingMessage.text = DONE_TEXT
            btnCancel.text = WATCH_TEXT
            btnCancel.visibility = View.VISIBLE
            btnLater.visibility = View.VISIBLE
            progressCircular.visibility = View.INVISIBLE
        }

        private fun bindTerminal(item: NotificationItem, fallbackMessage: String) = with(binding) {
            tvLoadingMessage.text = item.message.ifBlank { fallbackMessage }
            btnCancel.text = DISMISS_TEXT
            btnCancel.visibility = View.VISIBLE
            btnLater.visibility = View.GONE
            progressCircular.visibility = View.INVISIBLE
        }

        private companion object {
            const val CANCEL_TEXT = "Cancel"
            const val DISMISS_TEXT = "Dismiss"
            const val DONE_TEXT = "Done!"
            const val WATCH_TEXT = "Watch"
        }
    }
}
