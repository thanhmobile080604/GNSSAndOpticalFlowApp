package com.example.gnssandopticalflowapp.screen.fragment

import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.safeContext
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentHomeOpticalFlowBinding
import com.example.gnssandopticalflowapp.model.VideoProcessOptions
import com.example.gnssandopticalflowapp.screen.dialog.VideoProcessOptionsDialog
import com.example.gnssandopticalflowapp.video.VideoProcessingBus
import com.example.gnssandopticalflowapp.video.VideoProcessingProgressText
import com.example.gnssandopticalflowapp.video.VideoProcessingWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class HomeOpticalFlowFragment : BaseFragment<FragmentHomeOpticalFlowBinding>(FragmentHomeOpticalFlowBinding::inflate) {

    private val videoPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            showVideoProcessOptions(uri)
        }
    }

    override fun FragmentHomeOpticalFlowBinding.initView() {
    }

    override fun FragmentHomeOpticalFlowBinding.initListener() {
        btnFunc1.setSingleClick {
            navigateTo(R.id.cameraOpticalFlowFragment)
        }

        btnFunc2.setSingleClick {
            if (isProcessingVideo()) {
                showProcessingToast()
                return@setSingleClick
            }
            videoPickerLauncher.launch("video/*")
        }

        btnFunc3.setSingleClick {
            navigateTo(R.id.videoListFragment)
        }

        btnFunc4.setSingleClick {
            navigateTo(R.id.analyticsListFragment)
        }
    }

    private fun showVideoProcessOptions(uri: Uri) {
        VideoProcessOptionsDialog.show(childFragmentManager, uri) { options ->
            handleVideoSelection(uri, options)
        }
    }

    private fun handleVideoSelection(uri: Uri, options: VideoProcessOptions) {
        if (isProcessingVideo()) {
            showProcessingToast()
            return
        }

        val appContext = safeContext().applicationContext
        showUploadLoading()

        val uploadJob = mainViewModel.videoProcessingScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = appContext.cacheDir
                val videosDir = File(cacheDir, "videos")
                if (!videosDir.exists()) videosDir.mkdirs()
                
                val sourceFile = File(videosDir, "temp_source_${System.currentTimeMillis()}.mp4")
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(sourceFile).use { output ->
                        input.copyTo(output)
                    }
                }
                
                VideoProcessingBus.postProcessing(
                    VideoProcessingProgressText.format(VideoProcessingProgressText.DEFAULT_PERCENT)
                )
                VideoProcessingWorker.enqueue(
                    context = appContext,
                    sourcePath = sourceFile.absolutePath,
                    options = options
                )
                
            } catch (_: CancellationException) {
                VideoProcessingBus.postIdle()
                withContext(Dispatchers.Main) {
                    dismissUploadLoading()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                VideoProcessingBus.postIdle()
                withContext(Dispatchers.Main) {
                    dismissUploadLoading()
                }
            }
        }
        mainViewModel.videoUploadJob = uploadJob
        uploadJob.invokeOnCompletion {
            if (mainViewModel.videoUploadJob === uploadJob) {
                mainViewModel.videoUploadJob = null
            }
        }
    }

    private fun isProcessingVideo(): Boolean {
        return mainViewModel.videoUploadJob?.isActive == true || VideoProcessingBus.isProcessing
    }

    private fun showProcessingToast() {
        Toast.makeText(
            safeContext(),
            "Đang loading, chờ xử lý xong rồi thử lại",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showUploadLoading() {
        mainViewModel.videoProcessingMessage.value =
            VideoProcessingProgressText.format(VideoProcessingProgressText.DEFAULT_PERCENT)
    }

    private fun dismissUploadLoading() {
        mainViewModel.videoProcessingMessage.value = null
    }


    override fun onDestroyView() {
        super.onDestroyView()
    }

    override fun initObserver() {
    }
}
