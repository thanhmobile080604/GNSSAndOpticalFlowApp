package com.example.gnssandopticalflowapp.screen.fragment

import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.adapter.VideoListAdapter
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentVideoListBinding
import com.example.gnssandopticalflowapp.util.VideoStorageUtil

class VideoListFragment : BaseFragment<FragmentVideoListBinding>(FragmentVideoListBinding::inflate) {
    
    private lateinit var adapter: VideoListAdapter

    override fun FragmentVideoListBinding.initView() {
        adapter = VideoListAdapter { video ->
            // Update UI if needed when item selected
        }
        
        rcvAllPhoto.layoutManager = GridLayoutManager(requireContext(), 3)
        rcvAllPhoto.adapter = adapter
        
        loadVideos()
    }

    private fun loadVideos() {
        val videos = VideoStorageUtil.getVideos(requireContext())
        adapter.setData(videos)
    }

    override fun FragmentVideoListBinding.initListener() {
        ivBack.setSingleClick {
            onBack()
        }

        ivVideoCheck.setSingleClick {
            val selectedVideo = adapter.getSelectedVideo()
            if (selectedVideo != null) {
                mainViewModel.selectedVideoPath.value = selectedVideo.path
                navigateTo(R.id.videoOpticalFlowFragment)
            } else {
                Toast.makeText(requireContext(), "Please select a video first", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun initObserver() {
    }
}
