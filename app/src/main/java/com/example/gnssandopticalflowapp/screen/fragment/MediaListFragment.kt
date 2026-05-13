package com.example.gnssandopticalflowapp.screen.fragment

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.adapter.MediaListAdapter
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.hide
import com.example.gnssandopticalflowapp.common.safeContext
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.common.show
import com.example.gnssandopticalflowapp.databinding.FragmentVideoListBinding
import com.example.gnssandopticalflowapp.model.ImageInfo
import com.example.gnssandopticalflowapp.model.VideoInfo
import com.example.gnssandopticalflowapp.util.ShareHelper
import com.example.gnssandopticalflowapp.util.MediaStorageUtil
import java.io.File

class MediaListFragment : BaseFragment<FragmentVideoListBinding>(FragmentVideoListBinding::inflate) {
    
    private lateinit var adapter: MediaListAdapter

    private enum class Mode {
        NORMAL, EDIT
    }

    private var currentMode = Mode.NORMAL

    override fun FragmentVideoListBinding.initView() {
        adapter = MediaListAdapter {
            updateToolbarState()
        }
        rcvAllPhoto.layoutManager = GridLayoutManager(safeContext(), 3)
        rcvAllPhoto.adapter = adapter
        loadMedia()
        updateToolbarState()
    }

    private fun loadMedia() {
        val media = MediaStorageUtil.getMedia(safeContext())
        adapter.setData(media)
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
            when (val selectedMedia = adapter.getSelectedMedia()) {
                is ImageInfo -> openImageDetail(selectedMedia)
                is VideoInfo -> {
                    if (!isValidVideo(selectedMedia.path)) {
                        showToast("Video is invalid")
                        return@setSingleClick
                    }

                    mainViewModel.selectedVideoPath.value = selectedMedia.path
                    navigateTo(R.id.videoOpticalFlowFragment)
                }
                null -> Unit
            }
        }

        tvEdit.setSingleClick {
            enterEditMode()
        }

        tvCancel.setSingleClick {
            exitEditMode()
        }

        ivTrash.setSingleClick {
            deleteSelectedMedia()
        }

        ivShare.setSingleClick {
            shareSelectedMedia()
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

    private fun deleteSelectedMedia() {
        val selectedMedia = adapter.getSelectedMediaItems()
        if (selectedMedia.isEmpty()) {
            showToast("Select files to delete")
            return
        }

        val deletedCount = MediaStorageUtil.deleteMedia(safeContext(), selectedMedia)
        adapter.removeMedia(selectedMedia)
        showToast("Deleted $deletedCount item(s)")

        if (adapter.itemCount == 0) {
            exitEditMode()
        } else {
            updateToolbarState()
        }
    }

    private fun shareSelectedMedia() {
        val selectedMedia = adapter.getSelectedMediaItems()
        if (selectedMedia.isEmpty()) {
            showToast("Select files to share")
            return
        }

        val shared = ShareHelper.shareFiles(safeContext(), selectedMedia.map { File(it.path) })
        if (!shared) {
            showToast("No available files to share")
        }
    }

    private fun openImageDetail(image: ImageInfo) {
        if (!isValidImage(image.path)) {
            showToast("Image is invalid")
            return
        }

        mainViewModel.selectedImagePath.value = image.path
        navigateTo(R.id.imageDetailFragment)
    }

    private fun updateToolbarState() {
        when (currentMode) {
            Mode.NORMAL -> updateNormalToolbar()
            Mode.EDIT -> updateEditToolbar()
        }
    }

    private fun updateNormalToolbar() = with(binding) {
        val hasSelectedVideo = adapter.getSelectedMedia() != null

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
        val hasSelection = adapter.getSelectedMediaItems().isNotEmpty()
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

    private fun isValidVideo(path: String): Boolean {
        val file = File(path)
        if (!file.exists() || !file.canRead() || file.length() <= 100L) return false

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)

            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?: 0
            hasVideo && durationMs > 0L && width > 0 && height > 0
        } catch (_: Exception) {
            false
        } finally {
            retriever.release()
        }
    }

    private fun isValidImage(path: String): Boolean {
        val file = File(path)
        if (!file.exists() || !file.canRead() || file.length() <= 0L) return false

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    override fun initObserver() {
        mainViewModel.videoLibraryUpdated.observe(viewLifecycleOwner) {
            loadMedia()
            updateToolbarState()
        }
    }
}
