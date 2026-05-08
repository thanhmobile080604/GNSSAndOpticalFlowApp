package com.example.gnssandopticalflowapp.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.databinding.ItemVideoThumbBinding
import com.example.gnssandopticalflowapp.model.VideoInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoListAdapter(
    private val onSelectionChanged: (List<VideoInfo>) -> Unit
) : RecyclerView.Adapter<VideoListAdapter.VideoViewHolder>() {

    private var videos: MutableList<VideoInfo> = mutableListOf()
    private var selectedPosition: Int = -1
    private val selectedPaths = mutableSetOf<String>()
    private var editMode: Boolean = false

    fun setData(newVideos: List<VideoInfo>) {
        val selectedPath = getSelectedVideo()?.path
        val editSelectedPaths = selectedPaths.toSet()

        videos = newVideos.toMutableList()
        if (editMode) {
            selectedPaths.clear()
            selectedPaths.addAll(editSelectedPaths.intersect(videos.map { it.path }.toSet()))
        } else {
            selectedPosition = videos.indexOfFirst { it.path == selectedPath }
        }

        notifyDataSetChanged()
        onSelectionChanged(getSelectedVideos())
    }

    fun getSelectedVideo(): VideoInfo? {
        return if (!editMode && selectedPosition in videos.indices) videos[selectedPosition] else null
    }

    fun getSelectedVideos(): List<VideoInfo> {
        return if (editMode) {
            videos.filter { it.path in selectedPaths }
        } else {
            getSelectedVideo()?.let(::listOf).orEmpty()
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

    fun removeVideos(videosToRemove: List<VideoInfo>) {
        val pathsToRemove = videosToRemove.mapTo(mutableSetOf()) { it.path }
        if (pathsToRemove.isEmpty()) return

        videos = videos.filterNot { it.path in pathsToRemove }.toMutableList()
        clearSelection(notify = false)
        notifyDataSetChanged()
        onSelectionChanged(emptyList())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = ItemVideoThumbBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val video = videos[position]
        val isSelected = if (editMode) {
            video.path in selectedPaths
        } else {
            position == selectedPosition
        }
        holder.bind(video, isSelected)

        holder.itemView.setOnClickListener {
            if (editMode) {
                if (!selectedPaths.add(video.path)) {
                    selectedPaths.remove(video.path)
                }
                notifyItemChanged(position)
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
            }
            onSelectionChanged(getSelectedVideos())
        }
    }

    override fun getItemCount(): Int = videos.size

    class VideoViewHolder(
        private val binding: ItemVideoThumbBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(video: VideoInfo, isSelected: Boolean) {
            Glide.with(binding.thumbGallery.context)
                .load(video.path)
                .apply(
                    RequestOptions()
                        .frame(1_000_000)
                        .centerCrop()
                        .placeholder(R.drawable.ic_video_placeholder)
                        .error(R.drawable.ic_video_error)
                )
                .into(binding.thumbGallery)

            binding.blackOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.check.visibility = if (isSelected) View.VISIBLE else View.GONE

            val simpleDateFormat = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault())
            val formattedDate = simpleDateFormat.format(Date(video.timestamp))

            binding.tvTitle.text = formattedDate
        }
    }
}
