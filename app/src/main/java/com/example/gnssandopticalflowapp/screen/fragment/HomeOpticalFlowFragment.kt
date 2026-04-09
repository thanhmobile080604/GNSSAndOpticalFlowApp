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
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import com.example.gnssandopticalflowapp.optical_flow.classes.KLT
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.videoio.VideoWriter
import java.io.File
import java.io.FileOutputStream
import androidx.core.graphics.drawable.toDrawable
import org.opencv.core.Point
import org.opencv.core.Scalar

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
            Log.e("VIDEO-PROCESS", "Failed to set data source: ${e.message}")
            retriever.release()
            throw e
        }

        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val durationMs = durationStr?.toLong() ?: 0L
        Log.d("VIDEO-PROCESS", "Duration: $durationMs ms")
        
        val firstFrame = retriever.getFrameAtTime(0)
        if (firstFrame == null) {
            Log.e("VIDEO-PROCESS", "Failed to get first frame")
            retriever.release()
            return
        }
        
        val width = firstFrame.width
        val height = firstFrame.height
        Log.d("VIDEO-PROCESS", "Frame size: ${width}x${height}")
        
        val outputFile = File(sourceFile.parentFile, "processed_${System.currentTimeMillis()}.mp4")
        val encoder = com.example.gnssandopticalflowapp.util.VideoEncoder(outputFile.absolutePath, width, height)
        
        try {
            encoder.start()
            Log.d("VIDEO-PROCESS", "VideoEncoder started for ${outputFile.name}")
        } catch (e: Exception) {
            Log.e("VIDEO-PROCESS", "Encoder failed to start: ${e.message}")
            retriever.release()
            return
        }

        val klt = KLT(null)
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
                        if (framesProcessed % 30 == 0) {
                            val mean = org.opencv.core.Core.mean(rgbaMat)
                            Log.d("VIDEO-PROCESS", "Frame $framesProcessed, mean: $mean")
                        }

                        val output = klt.run(rgbaMat)
                        val outFrame = output.of_frame ?: rgbaMat
                        
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
                        loadingDialog?.findViewById<TextView>(R.id.tvLoadingMessage)?.text = "Processing: $progress%"
                    }
                }
            }
        } finally {
            encoder.release()
            retriever.release()
            sourceFile.delete() 
            Log.d("VIDEO-PROCESS", "Processing finished. Frames: $framesProcessed. File size: ${outputFile.length()} bytes")
        }

        if (copyJob?.isActive == true) {
            // Scan file to ensure it's ready
            android.media.MediaScannerConnection.scanFile(requireContext(), arrayOf(outputFile.absolutePath), null) { _, _ -> }
            
            withContext(Dispatchers.Main) {
                loadingDialog?.dismiss()
                VideoStorageUtil.addVideo(requireContext(), outputFile.absolutePath)
                
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

    private fun showLoadingDialog(message: String) {
        if (loadingDialog == null || !loadingDialog!!.isShowing) {
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_loading, null)
            loadingDialog = AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .setCancelable(false)
                .create()
                
            loadingDialog?.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            
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
