package com.example.gnssandopticalflowapp.screen.fragment

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.safeContext
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentHomeOpticalFlowBinding
import com.example.gnssandopticalflowapp.model.VideoProcessOptions
import com.example.gnssandopticalflowapp.optical_flow.classes.Farneback
import com.example.gnssandopticalflowapp.optical_flow.classes.KLT
import com.example.gnssandopticalflowapp.optical_flow.inter.OpticalFlow
import com.example.gnssandopticalflowapp.screen.dialog.VideoProcessOptionsDialog
import com.example.gnssandopticalflowapp.util.VideoStorageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.io.File
import java.io.FileOutputStream

class HomeOpticalFlowFragment : BaseFragment<FragmentHomeOpticalFlowBinding>(FragmentHomeOpticalFlowBinding::inflate) {
    private var copyJob: Job? = null

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
    }

    private fun showVideoProcessOptions(uri: Uri) {
        VideoProcessOptionsDialog.show(childFragmentManager) { options ->
            handleVideoSelection(uri, options)
        }
    }

    private fun handleVideoSelection(uri: Uri, options: VideoProcessOptions) {
        showLoadingDialog("Copying video...") {
            copyJob?.cancel()
        }
        
        copyJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = safeContext().cacheDir
                val videosDir = File(cacheDir, "videos")
                if (!videosDir.exists()) videosDir.mkdirs()
                
                val sourceFile = File(videosDir, "temp_source_${System.currentTimeMillis()}.mp4")
                safeContext().contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(sourceFile).use { output ->
                        input.copyTo(output)
                    }
                }
                
                processVideoOffline(sourceFile, options)
                
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    dismissLoadingDialog()
                }
            }
        }
    }

    private suspend fun processVideoOffline(sourceFile: File, options: VideoProcessOptions) {
        withContext(Dispatchers.Main) {
            showLoadingDialog("Processing Optical Flow...")
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(sourceFile.absolutePath)
        } catch (e: Exception) {
            Log.e("VIDEO-PROCESS", "Failed to set data source: ${e.message}")
            retriever.release()
            throw e
        }

        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val durationMs = durationStr?.toLong() ?: 0L
        
        val firstFrame = retriever.getFrameAtTime(0)
        if (firstFrame == null) {
            Log.e("VIDEO-PROCESS", "Failed to get first frame")
            retriever.release()
            return
        }
        
        val width = firstFrame.width
        val height = firstFrame.height
        
        val outputFile = File(sourceFile.parentFile, "processed_${System.currentTimeMillis()}.mp4")
        val encoder = com.example.gnssandopticalflowapp.util.VideoEncoder(outputFile.absolutePath, width, height)
        
        try {
            encoder.start()
        } catch (e: Exception) {
            Log.e("VIDEO-PROCESS", "Encoder failed to start: ${e.message}")
            retriever.release()
            return
        }

        val opticalFlow = createOpticalFlow(options)
        val frameDurationUs = 1000000L / 30 // 30 FPS
        var currentTimeUs = 0L
        var framesProcessed = 0

        try {
            while (currentTimeUs < durationMs * 1000) {
                if (copyJob?.isActive != true) break

                val originalBitmap = retriever.getFrameAtTime(currentTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                if (originalBitmap != null) {
                    val bitmap = originalBitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    
                    val rgbaMat = Mat()
                    Utils.bitmapToMat(bitmap, rgbaMat)
                    
                    if (!rgbaMat.empty()) {
                        val output = opticalFlow.run(rgbaMat)
                        val outFrame = output?.ofFrame ?: rgbaMat
                        
                        encoder.encodeFrame(outFrame)
                        framesProcessed++
                    }
                    rgbaMat.release()
                    bitmap.recycle()
                    originalBitmap.recycle()
                }
                
                currentTimeUs += frameDurationUs
                
                if (currentTimeUs % (frameDurationUs * 10) <= frameDurationUs) {
                    val progress = ((currentTimeUs / 1000).toFloat() / durationMs * 100).toInt()
                    withContext(Dispatchers.Main) {
                        showLoadingDialog("Processing: $progress%")
                    }
                }
            }
        } finally {
            encoder.release()
            retriever.release()
            sourceFile.delete() 
        }

        if (copyJob?.isActive == true) {
            // Scan file to ensure it's ready
            android.media.MediaScannerConnection.scanFile(safeContext(), arrayOf(outputFile.absolutePath), null) { _, _ -> }
            
            withContext(Dispatchers.Main) {
                dismissLoadingDialog()
                VideoStorageUtil.addVideo(safeContext(), outputFile.absolutePath)
                
                // delay slightly
                kotlinx.coroutines.delay(500)
                
                mainViewModel.selectedVideoPath.value = outputFile.absolutePath
                navigateTo(R.id.videoOpticalFlowFragment)
            }
        } else {
            Log.d("VIDEO-PROCESS", "Processing cancelled.")
            outputFile.delete() 
        }
    }

    private fun createOpticalFlow(options: VideoProcessOptions): OpticalFlow {
        val opticalFlow: OpticalFlow = if (options.useFarneback) {
            Farneback()
        } else {
            KLT(null)
        }

        return opticalFlow.apply {
            setSensitivity(50)
            setMovingMode(options.isMoving)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        copyJob?.cancel()
    }

    override fun initObserver() {
    }
}
