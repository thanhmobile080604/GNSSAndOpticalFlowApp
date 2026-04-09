package com.example.gnssandopticalflowapp.screen.fragment

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.example.gnssandopticalflowapp.MainViewModel
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentHomeOpticalFlowBinding
import com.example.gnssandopticalflowapp.util.VideoStorageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.media.MediaMetadataRetriever
import android.widget.TextView
import com.example.gnssandopticalflowapp.optical_flow.classes.KLT
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.videoio.VideoWriter
import java.io.File
import java.io.FileOutputStream

class HomeOpticalFlowFragment : BaseFragment<FragmentHomeOpticalFlowBinding>(FragmentHomeOpticalFlowBinding::inflate) {
    private var copyJob: Job? = null
    private var loadingDialog: AlertDialog? = null

    private val videoPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            handleVideoSelection(uri)
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

    private fun handleVideoSelection(uri: Uri) {
        showLoadingDialog("Copying video...")
        copyJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = requireContext().cacheDir
                val videosDir = File(cacheDir, "videos")
                if (!videosDir.exists()) videosDir.mkdirs()
                
                val sourceFile = File(videosDir, "temp_source_${System.currentTimeMillis()}.mp4")
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(sourceFile).use { output ->
                        input.copyTo(output)
                    }
                }
                
                processVideoOffline(sourceFile)
                
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    loadingDialog?.dismiss()
                }
            }
        }
    }

    private suspend fun processVideoOffline(sourceFile: File) {
        withContext(Dispatchers.Main) {
            loadingDialog?.findViewById<TextView>(R.id.tvLoadingMessage)?.text = "Processing Optical Flow..."
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(sourceFile.absolutePath)
        } catch (e: Exception) {
            retriever.release()
            throw e
        }

        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val durationMs = durationStr?.toLong() ?: 0L
        
        val firstFrame = retriever.getFrameAtTime(0)
        if (firstFrame == null) {
            retriever.release()
            return
        }
        
        val width = firstFrame.width
        val height = firstFrame.height
        
        val outputFile = File(sourceFile.parentFile, "processed_${System.currentTimeMillis()}.avi")
        val fourcc = VideoWriter.fourcc('M', 'J', 'P', 'G')
        val videoWriter = VideoWriter(outputFile.absolutePath, fourcc, 30.0, Size(width.toDouble(), height.toDouble()), true)
        
        if (!videoWriter.isOpened) {
            retriever.release()
            return
        }

        val klt = KLT(null)
        val frameDurationUs = 1000000L / 30 // 30 FPS
        var currentTimeUs = 0L

        try {
            while (currentTimeUs < durationMs * 1000) {
                if (!copyJob!!.isActive) break

                val bitmap = retriever.getFrameAtTime(currentTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bitmap != null) {
                    val rgbaMat = Mat()
                    Utils.bitmapToMat(bitmap, rgbaMat)
                    
                    val output = klt.run(rgbaMat)
                    val outFrame = output.of_frame ?: rgbaMat
                    
                    val bgrFrame = Mat()
                    Imgproc.cvtColor(outFrame, bgrFrame, Imgproc.COLOR_RGBA2BGR)
                    videoWriter.write(bgrFrame)
                    
                    rgbaMat.release()
                    bgrFrame.release()
                }
                
                currentTimeUs += frameDurationUs
                
                // Update progress occasionally
                if (currentTimeUs % (frameDurationUs * 10) == 0L) {
                    val progress = (currentTimeUs / 1000).toFloat() / durationMs * 100
                    withContext(Dispatchers.Main) {
                        loadingDialog?.findViewById<TextView>(R.id.tvLoadingMessage)?.text = "Processing: ${progress.toInt()}%"
                    }
                }
            }
        } finally {
            videoWriter.release()
            retriever.release()
            sourceFile.delete() // Clean up temp source
        }

        if (copyJob!!.isActive) {
            withContext(Dispatchers.Main) {
                loadingDialog?.dismiss()
                VideoStorageUtil.addVideo(requireContext(), outputFile.absolutePath)
                mainViewModel.selectedVideoPath.value = outputFile.absolutePath
                navigateTo(R.id.videoOpticalFlowFragment)
            }
        } else {
            outputFile.delete() // Clean up partial if cancelled
        }
    }

    private fun showLoadingDialog(message: String) {
        if (loadingDialog == null || !loadingDialog!!.isShowing) {
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_loading, null)
            loadingDialog = AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .setCancelable(false)
                .create()
                
            loadingDialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            
            dialogView.findViewById<Button>(R.id.btnCancel).setOnClickListener {
                copyJob?.cancel()
                loadingDialog?.dismiss()
            }
        }
        loadingDialog?.findViewById<TextView>(R.id.tvLoadingMessage)?.text = message
        loadingDialog?.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        copyJob?.cancel()
        loadingDialog?.dismiss()
    }

    override fun initObserver() {
    }
}
