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
    private val onVideoSelected: (VideoInfo?) -> Unit
) : RecyclerView.Adapter<VideoListAdapter.VideoViewHolder>() {

    private var videos: List<VideoInfo> = emptyList()
    private var selectedPosition: Int = -1

    fun setData(newVideos: List<VideoInfo>) {
        videos = newVideos
        selectedPosition = -1
        notifyDataSetChanged()
    }

    fun getSelectedVideo(): VideoInfo? {
        return if (selectedPosition in videos.indices) videos[selectedPosition] else null
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
        holder.bind(video, position == selectedPosition)

        holder.itemView.setOnClickListener {
            val previousSelected = selectedPosition

            if (selectedPosition == position) {
                selectedPosition = -1
            } else {
                selectedPosition = position
            }

            if (previousSelected != -1) {
                notifyItemChanged(previousSelected)
            }
            if (selectedPosition != -1) {
                notifyItemChanged(selectedPosition)
            }

            onVideoSelected(getSelectedVideo())
        }
    }

    override fun getItemCount(): Int = videos.size

    class VideoViewHolder(
        private val binding: ItemVideoThumbBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(video: VideoInfo, isSelected: Boolean) {
            Glide.with(binding.thumbGallery.context)
                .load(video.path)
                .centerCrop()
                .into(binding.thumbGallery)

            binding.blackOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.check.visibility = if (isSelected) View.VISIBLE else View.GONE

            val simpleDateFormat = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault())
            val formattedDate = simpleDateFormat.format(Date(video.timestamp))

            binding.root.findViewById<TextView>(R.id.tvTitle)?.text = formattedDate
        }
    }
}
