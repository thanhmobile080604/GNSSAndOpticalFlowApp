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
import com.example.gnssandopticalflowapp.databinding.FragmentStorageListBinding
import com.example.gnssandopticalflowapp.model.ImageInfo
import com.example.gnssandopticalflowapp.model.MediaInfo
import com.example.gnssandopticalflowapp.model.RouteSessionInfo
import com.example.gnssandopticalflowapp.model.RouteSessionSummary
import com.example.gnssandopticalflowapp.model.VideoInfo
import com.example.gnssandopticalflowapp.util.MediaStorageUtil
import com.example.gnssandopticalflowapp.util.RouteStorageUtil
import com.example.gnssandopticalflowapp.util.ShareHelper
import java.io.File

class StorageListFragment : BaseFragment<FragmentStorageListBinding>(FragmentStorageListBinding::inflate) {

    private lateinit var adapter: MediaListAdapter

    private enum class Mode {
        NORMAL, EDIT
    }

    private var currentMode = Mode.NORMAL

    override fun FragmentStorageListBinding.initView() {
        adapter = MediaListAdapter {
            updateToolbarState()
        }
        rcvAllPhoto.layoutManager = GridLayoutManager(safeContext(), 3)
        rcvAllPhoto.adapter = adapter
        (rcvAllPhoto.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        loadItems()
        updateToolbarState()
    }

    /** Media (videos + images) AND saved live-routing sessions, one mixed grid sorted newest-first. */
    private fun loadItems() {
        val items = buildList {
            addAll(MediaStorageUtil.getMedia(safeContext()))
            addAll(RouteStorageUtil.getSessionSummaries(safeContext()).map { it.toMediaInfo() })
        }.sortedByDescending { it.timestamp }

        adapter.setData(items)
        if (items.isEmpty()) binding.tvEmpty.show() else binding.tvEmpty.hide()
    }

    override fun FragmentStorageListBinding.initListener() {
        ivBack.setSingleClick {
            if (currentMode == Mode.EDIT) {
                exitEditMode()
            } else {
                onBack()
            }
        }

        ivVideoCheck.setSingleClick {
            when (val selected = adapter.getSelectedMedia()) {
                is RouteSessionInfo -> openRoute(selected)
                is ImageInfo -> openImageDetail(selected)
                is VideoInfo -> {
                    if (!isValidVideo(selected.path)) {
                        showToast("Video is invalid")
                        return@setSingleClick
                    }

                    mainViewModel.selectedVideoPath.value = selected.path
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
            deleteSelectedItems()
        }

        ivShare.setSingleClick {
            shareSelectedItems()
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

    private fun deleteSelectedItems() {
        val selected = adapter.getSelectedMediaItems()
        if (selected.isEmpty()) {
            showToast("Select files to delete")
            return
        }

        val routes = selected.filterIsInstance<RouteSessionInfo>()
        val media = selected.filterNot { it is RouteSessionInfo }

        var deletedCount = 0
        if (media.isNotEmpty()) deletedCount += MediaStorageUtil.deleteMedia(safeContext(), media)
        if (routes.isNotEmpty()) {
            deletedCount += RouteStorageUtil.deleteSessions(safeContext(), routes.map { it.toSummary() })
        }
        adapter.removeMedia(selected)
        showToast("Deleted $deletedCount item(s)")

        if (adapter.itemCount == 0) {
            exitEditMode()
            binding.tvEmpty.show()
        } else {
            updateToolbarState()
        }
    }

    private fun shareSelectedItems() {
        // Route sessions have no shareable file — share only the real media files.
        val media = adapter.getSelectedMediaItems().filterNot { it is RouteSessionInfo }
        if (media.isEmpty()) {
            showToast("Select files to share")
            return
        }

        val shared = ShareHelper.shareFiles(safeContext(), media.map { File(it.path) })
        if (!shared) {
            showToast("No available files to share")
        }
    }

    private fun openRoute(route: RouteSessionInfo) {
        mainViewModel.selectedRouteSessionId.value = route.path
        navigateTo(R.id.routeReplayFragment)
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
        val hasSelectedItem = adapter.getSelectedMedia() != null

        ivBack.show()
        tvCancel.hide()
        ivTrash.hide()
        ivShare.hide()

        if (hasSelectedItem) {
            tvEdit.hide()
            ivVideoCheck.show()
        } else {
            ivVideoCheck.hide()
            tvEdit.show()
        }
    }

    private fun updateEditToolbar() = with(binding) {
        val selected = adapter.getSelectedMediaItems()
        val hasSelection = selected.isNotEmpty()
        val canShare = selected.any { it !is RouteSessionInfo }

        ivBack.hide()
        tvCancel.show()
        tvEdit.hide()
        ivVideoCheck.hide()
        ivTrash.show()
        ivShare.show()
        ivTrash.alpha = if (hasSelection) 1f else 0.45f
        ivShare.alpha = if (canShare) 1f else 0.45f
    }

    private fun showToast(message: String) {
        Toast.makeText(safeContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun RouteSessionSummary.toMediaInfo(): RouteSessionInfo {
        return RouteSessionInfo(
            path = id,
            timestamp = startedAtMs,
            destinationName = destinationName,
            durationMs = durationMs,
            outagePointCount = outagePointCount
        )
    }

    private fun RouteSessionInfo.toSummary(): RouteSessionSummary {
        return RouteSessionSummary(
            id = path,
            startedAtMs = timestamp,
            durationMs = durationMs,
            destinationName = destinationName,
            outagePointCount = outagePointCount,
            gnssPointCount = 0
        )
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
            loadItems()
            updateToolbarState()
        }
        mainViewModel.routeLibraryUpdated.observe(viewLifecycleOwner) {
            loadItems()
            updateToolbarState()
        }
    }
}
