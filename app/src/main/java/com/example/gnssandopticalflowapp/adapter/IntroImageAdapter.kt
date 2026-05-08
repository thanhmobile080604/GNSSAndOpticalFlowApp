package com.example.gnssandopticalflowapp.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.recyclerview.widget.RecyclerView
import com.example.gnssandopticalflowapp.databinding.ItemIntroBinding

class IntroImageAdapter(
    @DrawableRes private val imageResIds: List<Int>
) : RecyclerView.Adapter<IntroImageAdapter.IntroImageViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IntroImageViewHolder {
        val binding = ItemIntroBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return IntroImageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: IntroImageViewHolder, position: Int) {
        holder.bind(imageResIds[position])
    }

    override fun getItemCount(): Int = imageResIds.size

    class IntroImageViewHolder(
        private val binding: ItemIntroBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(@DrawableRes imageResId: Int) {
            binding.ivIntroImage.setImageResource(imageResId)
        }
    }
}
