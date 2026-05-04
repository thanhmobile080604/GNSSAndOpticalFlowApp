package com.example.gnssandopticalflowapp.screen.fragment

import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.adapter.VideoListAdapter
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.hide
import com.example.gnssandopticalflowapp.common.safeContext
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.common.show
import com.example.gnssandopticalflowapp.databinding.FragmentVideoListBinding
import com.example.gnssandopticalflowapp.util.ShareHelper
import com.example.gnssandopticalflowapp.util.VideoStorageUtil
import java.io.File

class VideoListFragment : BaseFragment<FragmentVideoListBinding>(FragmentVideoListBinding::inflate) {
    
    private lateinit var adapter: VideoListAdapter

    private enum class Mode {
        NORMAL, EDIT
    }

    private var currentMode = Mode.NORMAL

    override fun FragmentVideoListBinding.initView() {
        adapter = VideoListAdapter {
            updateToolbarState()
        }
        rcvAllPhoto.layoutManager = GridLayoutManager(safeContext(), 3)
        rcvAllPhoto.adapter = adapter
        loadVideos()
        updateToolbarState()
    }

    private fun loadVideos() {
        val videos = VideoStorageUtil.getVideos(safeContext())
        adapter.setData(videos)
    }

    override fun FragmentVideoListBinding.initListener() {
        ivBack.setSingleClick {
            if (currentMode == Mode.EDIT) {
                exitEditMode()
            } else {
                onBack()
            }
        }

        ivVideoCheck.setSingleClick {
            val selectedVideo = adapter.getSelectedVideo()
            if (selectedVideo != null) {
                mainViewModel.selectedVideoPath.value = selectedVideo.path
                navigateTo(R.id.videoOpticalFlowFragment)
            }
        }

        tvEdit.setSingleClick {
            enterEditMode()
        }

        tvCancel.setSingleClick {
            exitEditMode()
        }

        ivTrash.setSingleClick {
            deleteSelectedVideos()
        }

        ivShare.setSingleClick {
            shareSelectedVideos()
        }
    }

    private fun enterEditMode() {
        currentMode = Mode.EDIT
        adapter.setEditMode(true)
        updateToolbarState()
    }

    private fun exitEditMode() {
        currentMode = Mode.NORMAL
        adapter.setEditMode(false)
        updateToolbarState()
    }

    private fun deleteSelectedVideos() {
        val selectedVideos = adapter.getSelectedVideos()
        if (selectedVideos.isEmpty()) {
            showToast("Select videos to delete")
            return
        }

        val deletedCount = VideoStorageUtil.deleteVideos(safeContext(), selectedVideos)
        adapter.removeVideos(selectedVideos)
        showToast("Deleted $deletedCount video(s)")

        if (adapter.itemCount == 0) {
            exitEditMode()
        } else {
            updateToolbarState()
        }
    }

    private fun shareSelectedVideos() {
        val selectedVideos = adapter.getSelectedVideos()
        if (selectedVideos.isEmpty()) {
            showToast("Select videos to share")
            return
        }

        val shared = ShareHelper(safeContext()).shareFiles(selectedVideos.map { File(it.path) })
        if (!shared) {
            showToast("No available videos to share")
        }
    }

    private fun updateToolbarState() {
        when (currentMode) {
            Mode.NORMAL -> updateNormalToolbar()
            Mode.EDIT -> updateEditToolbar()
        }
    }

    private fun updateNormalToolbar() = with(binding) {
        val hasSelectedVideo = adapter.getSelectedVideo() != null

        ivBack.show()
        tvCancel.hide()
        ivTrash.hide()
        ivShare.hide()

        if (hasSelectedVideo) {
            tvEdit.hide()
            ivVideoCheck.show()
        } else {
            ivVideoCheck.hide()
            tvEdit.show()
        }
    }

    private fun updateEditToolbar() = with(binding) {
        val hasSelection = adapter.getSelectedVideos().isNotEmpty()
        val actionAlpha = if (hasSelection) 1f else 0.45f

        ivBack.hide()
        tvCancel.show()
        tvEdit.hide()
        ivVideoCheck.hide()
        ivTrash.show()
        ivShare.show()
        ivTrash.alpha = actionAlpha
        ivShare.alpha = actionAlpha
    }

    private fun showToast(message: String) {
        Toast.makeText(safeContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun initObserver() = Unit
}
