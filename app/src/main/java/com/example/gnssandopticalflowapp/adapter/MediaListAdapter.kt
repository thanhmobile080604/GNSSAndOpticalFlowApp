package com.example.gnssandopticalflowapp.adapter

import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.databinding.ItemVideoThumbBinding
import com.example.gnssandopticalflowapp.model.ImageInfo
import com.example.gnssandopticalflowapp.model.MediaInfo
import com.example.gnssandopticalflowapp.model.VideoInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MediaListAdapter(
    private val onSelectionChanged: (List<MediaInfo>) -> Unit
) : RecyclerView.Adapter<MediaListAdapter.MediaViewHolder>() {

    private var mediaItems: MutableList<MediaInfo> = mutableListOf()
    private var selectedPosition: Int = -1
    private val selectedPaths = mutableSetOf<String>()
    private var editMode: Boolean = false

    fun setData(newMediaItems: List<MediaInfo>) {
        val selectedPath = getSelectedMedia()?.path
        val editSelectedPaths = selectedPaths.toSet()

        mediaItems = newMediaItems.toMutableList()
        if (editMode) {
            selectedPaths.clear()
            selectedPaths.addAll(editSelectedPaths.intersect(mediaItems.map { it.path }.toSet()))
        } else {
            selectedPosition = mediaItems.indexOfFirst { it.path == selectedPath }
        }

        notifyDataSetChanged()
        onSelectionChanged(getSelectedMediaItems())
    }

    fun getSelectedMedia(): MediaInfo? {
        return if (!editMode && selectedPosition in mediaItems.indices) mediaItems[selectedPosition] else null
    }

    fun getSelectedMediaItems(): List<MediaInfo> {
        return if (editMode) {
            mediaItems.filter { it.path in selectedPaths }
        } else {
            getSelectedMedia()?.let(::listOf).orEmpty()
        }
    }

    fun setEditMode(enabled: Boolean) {
        if (editMode == enabled) return

        editMode = enabled
        clearSelection(notify = false)
        notifyDataSetChanged()
        onSelectionChanged(emptyList())
    }

    fun clearSelection(notify: Boolean = true) {
        selectedPosition = -1
        selectedPaths.clear()
        if (notify) {
            notifyDataSetChanged()
            onSelectionChanged(emptyList())
        }
    }

    fun removeMedia(mediaToRemove: List<MediaInfo>) {
        val pathsToRemove = mediaToRemove.mapTo(mutableSetOf()) { it.path }
        if (pathsToRemove.isEmpty()) return

        mediaItems = mediaItems.filterNot { it.path in pathsToRemove }.toMutableList()
        clearSelection(notify = false)
        notifyDataSetChanged()
        onSelectionChanged(emptyList())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val binding = ItemVideoThumbBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MediaViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        val media = mediaItems[position]
        val isSelected = if (editMode) {
            media.path in selectedPaths
        } else {
            position == selectedPosition
        }
        holder.bind(media, isSelected)

        holder.itemView.setOnClickListener {
            if (editMode) {
                if (!selectedPaths.add(media.path)) {
                    selectedPaths.remove(media.path)
                }
                notifyItemChanged(position)
                onSelectionChanged(getSelectedMediaItems())
            } else {
                val previousSelected = selectedPosition

                selectedPosition = if (selectedPosition == position) {
                    -1
                } else {
                    position
                }

                if (previousSelected != -1) {
                    notifyItemChanged(previousSelected)
                }
                if (selectedPosition != -1) {
                    notifyItemChanged(selectedPosition)
                }
                onSelectionChanged(getSelectedMediaItems())
            }
        }
    }

    override fun getItemCount(): Int = mediaItems.size

    class MediaViewHolder(
        private val binding: ItemVideoThumbBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(media: MediaInfo, isSelected: Boolean) {
            val requestOptions = RequestOptions()
                .centerCrop()
                .placeholder(R.drawable.ic_video_placeholder)
                .error(R.drawable.ic_video_error)

            val glideRequest = Glide.with(binding.thumbGallery.context)
                .load(media.path)

            if (media is VideoInfo) {
                glideRequest.apply(requestOptions.frame(1_000_000)).into(binding.thumbGallery)
            } else {
                glideRequest.apply(requestOptions).into(binding.thumbGallery)
            }

            binding.blackOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.check.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.tvMediaBadge.text = when (media) {
                is ImageInfo -> "PHOTO"
                is VideoInfo -> formatDuration(resolveVideoDurationMs(media))
            }

            val simpleDateFormat = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault())
            binding.tvTitle.text = simpleDateFormat.format(Date(media.timestamp))
        }

        private fun resolveVideoDurationMs(video: VideoInfo): Long {
            if (video.durationMs > 0L) return video.durationMs
            durationCache[video.path]?.let { return it }

            val durationMs = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(video.path)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?: 0L
                } finally {
                    retriever.release()
                }
            }.getOrDefault(0L)

            durationCache[video.path] = durationMs
            return durationMs
        }

        private fun formatDuration(durationMs: Long): String {
            val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0L) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%d:%02d", minutes, seconds)
            }
        }

        private companion object {
            val durationCache = mutableMapOf<String, Long>()
        }
    }
}
