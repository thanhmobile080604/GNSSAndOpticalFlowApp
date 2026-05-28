package com.example.gnssandopticalflowapp.screen.fragment

import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

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
        val loadDecision = evaluateProcessingLoad(options.processingMode)
        if (loadDecision.isBlocked) {
            showProcessingLimitToast(loadDecision)
            return
        }

        if (loadDecision.shouldWarn) {
            showHeavyLoadWarning(loadDecision) {
                startVideoProcessing(uri, options)
            }
            return
        }

        startVideoProcessing(uri, options)
    }

    private fun startVideoProcessing(uri: Uri, options: VideoProcessOptions) {
        val appContext = safeContext().applicationContext
        val localJobId = VideoProcessingWorker.newJobId()
        VideoProcessingBus.postQueued(localJobId, options.processingMode)

        val uploadJob = mainViewModel.videoProcessingScope.launch(Dispatchers.IO) {
            var sourceFile: File? = null
            try {
                val cacheDir = appContext.cacheDir
                val videosDir = File(cacheDir, "videos")
                if (!videosDir.exists()) videosDir.mkdirs()
                
                val selectedSourceFile = File(videosDir, "temp_source_${localJobId}_${System.currentTimeMillis()}.mp4")
                sourceFile = selectedSourceFile
                val inputStream = appContext.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open selected video")
                inputStream.use { input ->
                    FileOutputStream(selectedSourceFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (!isActive) throw CancellationException()

                VideoProcessingBus.postProcessing(
                    localJobId,
                    options.processingMode,
                    VideoProcessingProgressText.format(VideoProcessingProgressText.DEFAULT_PERCENT)
                )
                VideoProcessingWorker.enqueue(
                    context = appContext,
                    sourcePath = selectedSourceFile.absolutePath,
                    options = options,
                    jobId = localJobId
                )
                
            } catch (_: CancellationException) {
                sourceFile?.delete()
                VideoProcessingBus.postCancelled(localJobId)
                withContext(Dispatchers.Main) {
                    dismissUploadLoading()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                sourceFile?.delete()
                VideoProcessingBus.postFailed(localJobId, "Processing failed")
                withContext(Dispatchers.Main) {
                    dismissUploadLoading()
                }
            }
        }
        mainViewModel.trackVideoUploadJob(localJobId, uploadJob)
    }

    private fun evaluateProcessingLoad(mode: VideoProcessOptions.ProcessingMode): ProcessingLoadDecision {
        val modeCount = VideoProcessingBus.activeJobCount(mode)
        val totalCount = VideoProcessingBus.activeJobCount()
        val hardLimit = when (mode) {
            VideoProcessOptions.ProcessingMode.OFFLINE -> ON_DEVICE_HARD_LIMIT
            VideoProcessOptions.ProcessingMode.ONLINE -> ONLINE_HARD_LIMIT
        }
        val warnAt = when (mode) {
            VideoProcessOptions.ProcessingMode.OFFLINE -> ON_DEVICE_WARN_AT
            VideoProcessOptions.ProcessingMode.ONLINE -> ONLINE_WARN_AT
        }

        return ProcessingLoadDecision(
            mode = mode,
            modeCount = modeCount,
            totalCount = totalCount,
            modeHardLimit = hardLimit,
            globalHardLimit = GLOBAL_HARD_LIMIT,
            shouldWarn = modeCount >= warnAt || totalCount >= GLOBAL_WARN_AT,
            isBlocked = modeCount >= hardLimit || totalCount >= GLOBAL_HARD_LIMIT
        )
    }

    private fun showProcessingLimitToast(loadDecision: ProcessingLoadDecision) {
        val modeLabel = when (loadDecision.mode) {
            VideoProcessOptions.ProcessingMode.OFFLINE -> "on-device"
            VideoProcessOptions.ProcessingMode.ONLINE -> "server"
        }
        Toast.makeText(
            safeContext(),
            "Too many $modeLabel jobs (${loadDecision.modeCount}/${loadDecision.modeHardLimit}) or total jobs (${loadDecision.totalCount}/${loadDecision.globalHardLimit}).",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showHeavyLoadWarning(
        loadDecision: ProcessingLoadDecision,
        onContinue: () -> Unit
    ) {
        val modeLabel = when (loadDecision.mode) {
            VideoProcessOptions.ProcessingMode.OFFLINE -> "on-device"
            VideoProcessOptions.ProcessingMode.ONLINE -> "server"
        }
        AlertDialog.Builder(safeContext())
            .setTitle("Heavy processing load")
            .setMessage(
                "There are ${loadDecision.modeCount} $modeLabel job(s) and " +
                    "${loadDecision.totalCount} total job(s) already running. Continue anyway?"
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ -> onContinue() }
            .show()
    }

    private fun showProcessingToast() {
        Toast.makeText(
            safeContext(),
            "Đang loading, chờ xử lý xong rồi thử lại",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun dismissUploadLoading() {
        mainViewModel.videoProcessingMessage.value = null
    }


    override fun onDestroyView() {
        super.onDestroyView()
    }

    override fun initObserver() {
    }

    private data class ProcessingLoadDecision(
        val mode: VideoProcessOptions.ProcessingMode,
        val modeCount: Int,
        val totalCount: Int,
        val modeHardLimit: Int,
        val globalHardLimit: Int,
        val shouldWarn: Boolean,
        val isBlocked: Boolean
    )

    private companion object {
        const val ON_DEVICE_WARN_AT = 1
        const val ON_DEVICE_HARD_LIMIT = 2
        const val ONLINE_WARN_AT = 3
        const val ONLINE_HARD_LIMIT = 5
        const val GLOBAL_WARN_AT = 4
        const val GLOBAL_HARD_LIMIT = 6
    }
}
