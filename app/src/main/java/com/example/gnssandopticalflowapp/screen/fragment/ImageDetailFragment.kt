package com.example.gnssandopticalflowapp.screen.fragment

import android.widget.Toast
import com.bumptech.glide.Glide
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.safeContext
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentImageDetailBinding
import com.example.gnssandopticalflowapp.util.MediaStorageUtil
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ImageDetailFragment : BaseFragment<FragmentImageDetailBinding>(FragmentImageDetailBinding::inflate) {

    override fun FragmentImageDetailBinding.initView() {
        val imagePath = mainViewModel.selectedImagePath.value
        if (imagePath.isNullOrBlank()) {
            root.post { onBack() }
            return
        }

        val imageFile = File(imagePath)
        if (!imageFile.exists() || !imageFile.canRead()) {
            Toast.makeText(safeContext(), "Image is unavailable", Toast.LENGTH_SHORT).show()
            root.post { onBack() }
            return
        }

        tvImageDate.text = formatImageDate(imagePath, imageFile)

        Glide.with(this@ImageDetailFragment)
            .load(imageFile)
            .placeholder(R.drawable.ic_video_placeholder)
            .error(R.drawable.ic_video_error)
            .into(zoomImageView)
    }

    override fun FragmentImageDetailBinding.initListener() {
        ivBack.setSingleClick {
            onBack()
        }
    }

    override fun initObserver() = Unit

    private fun formatImageDate(imagePath: String, imageFile: File): String {
        val storedTimestamp = MediaStorageUtil.getImage(safeContext(), imagePath)?.timestamp
        val timestamp = storedTimestamp
            ?: imageFile.lastModified().takeIf { it > 0L }
            ?: System.currentTimeMillis()

        return SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}
