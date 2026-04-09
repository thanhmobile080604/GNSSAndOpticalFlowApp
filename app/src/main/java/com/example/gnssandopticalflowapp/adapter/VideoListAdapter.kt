package com.example.gnssandopticalflowapp.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.databinding.ItemVideoThumbBinding
import com.example.gnssandopticalflowapp.model.VideoInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoListAdapter(
    private val onVideoSelected: (VideoInfo) -> Unit
) : RecyclerView.Adapter<VideoListAdapter.VideoViewHolder>() {

    private var videos: List<VideoInfo> = emptyList()
    private var selectedPosition: Int = -1

    fun setData(newVideos: List<VideoInfo>) {
        videos = newVideos
        notifyDataSetChanged()
    }

    fun getSelectedVideo(): VideoInfo? {
        return if (selectedPosition != -1) videos[selectedPosition] else null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = ItemVideoThumbBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val video = videos[position]
        holder.bind(video, position == selectedPosition)
        
        holder.itemView.setOnClickListener {
            val previousSelected = selectedPosition
            selectedPosition = position
            notifyItemChanged(previousSelected)
            notifyItemChanged(selectedPosition)
            onVideoSelected(video)
        }
    }

    override fun getItemCount(): Int = videos.size

    class VideoViewHolder(private val binding: ItemVideoThumbBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(video: VideoInfo, isSelected: Boolean) {
            // Load thumbnail using Glide
            Glide.with(binding.thumbGallery.context)
                .load(video.path)
                .centerCrop()
                .into(binding.thumbGallery)

            // Show/hide selection overlay
            binding.blackOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.check.visibility = if (isSelected) View.VISIBLE else View.GONE
            
            // Format timestamp
            val sdf = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault())
            val dateStr = sdf.format(Date(video.timestamp))
            
            // Assuming we added tvTitle to item_video_thumb.xml
            val tvTitle = binding.root.findViewById<TextView>(R.id.tvTitle)
            tvTitle?.text = dateStr
        }
    }
}
