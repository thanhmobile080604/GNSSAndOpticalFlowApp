package com.example.gnssandopticalflowapp.screen.fragment

import androidx.recyclerview.widget.GridLayoutManager
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.adapter.VideoListAdapter
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.hide
import com.example.gnssandopticalflowapp.common.safeContext
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.common.show
import com.example.gnssandopticalflowapp.databinding.FragmentVideoListBinding
import com.example.gnssandopticalflowapp.util.VideoStorageUtil

class VideoListFragment : BaseFragment<FragmentVideoListBinding>(FragmentVideoListBinding::inflate) {
    
    private lateinit var adapter: VideoListAdapter

    override fun FragmentVideoListBinding.initView() {
        adapter = VideoListAdapter { video ->
            val selectedVideo = adapter.getSelectedVideo()
            if(selectedVideo != null) ivVideoCheck.show()
            else ivVideoCheck.hide()
        }
        rcvAllPhoto.layoutManager = GridLayoutManager(safeContext(), 3)
        rcvAllPhoto.adapter = adapter
        loadVideos()
    }

    private fun loadVideos() {
        val videos = VideoStorageUtil.getVideos(safeContext())
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
            }
        }
    }

    override fun initObserver() {
    }
}
