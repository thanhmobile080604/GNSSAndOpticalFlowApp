package com.example.gnssandopticalflowapp

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.util.Log
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.NavHostFragment
import com.example.gnssandopticalflowapp.common.AndroidLocationObserver
import com.example.gnssandopticalflowapp.databinding.ActivityMainBinding
import com.example.gnssandopticalflowapp.screen.controller.VideoProcessingOverlayController
import com.example.gnssandopticalflowapp.screen.dialog.NoGPSDialog
import com.example.gnssandopticalflowapp.screen.dialog.NoLocationDialog
import com.example.gnssandopticalflowapp.video.VideoProcessingBus
import com.example.gnssandopticalflowapp.video.VideoProcessingForegroundService
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import org.orekit.data.DataContext
import org.orekit.data.DirectoryCrawler
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        init {
            if (OpenCVLoader.initDebug()) {
                Log.d("OpenCV", "OpenCV library found inside package. Using it!")
            } else {
                Log.e("OpenCV", "OpenCV library not found!")
            }
        }
    }

    private lateinit var binding: ActivityMainBinding

    private val locationObserver: AndroidLocationObserver by lazy {
        AndroidLocationObserver(applicationContext)
    }

    private val viewModel: MainViewModel by viewModels()
    private lateinit var videoProcessingOverlay: VideoProcessingOverlayController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        allowLayoutInDisplayCutout()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupRootWindowInsets()

        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
        )

        viewModel.seedFakeLocationIfNeeded()
        setupOrekit()
        setupVideoProcessingOverlay()
        observeVideoProcessingOverlay()
        observeProcessedVideoReady()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Collect permissions status
                launch {
                    locationObserver.isLocationPermitted.collect { isPermitted ->
                        if (!viewModel.isResolvingDeviceSettings.value) {
                            if (isPermitted) {
                                NoLocationDialog.dismiss(this@MainActivity)
                            } else {
                                NoLocationDialog.show(this@MainActivity)
                            }
                        }
                    }
                }

                // Collect GPS status
                launch {
                    locationObserver.isGpsEnabled.collect { isGpsEnabled ->
                        if (!viewModel.isResolvingDeviceSettings.value) {
                            if (isGpsEnabled) {
                                NoGPSDialog.dismiss(this@MainActivity)
                            } else {
                                NoGPSDialog.show(this@MainActivity)
                            }
                        }
                    }
                }

                // Monitor suppression flag cleanup
                launch {
                    viewModel.isResolvingDeviceSettings.collect { isResolving ->
                        if (!isResolving) {
                            // When resolution ends, force a check of current states
                            locationObserver.refreshPermissionState()
                            locationObserver.refreshGpsState()
                            
                            // Use a small delay to ensure states are updated
                            kotlinx.coroutines.delay(100)
                            
                            // Check current states using the new helper methods
                            val isPermitted = locationObserver.getCurrentPermissionState()
                            val isGpsOn = locationObserver.getCurrentGpsState()
                            
                            if (isPermitted) {
                                NoLocationDialog.dismiss(this@MainActivity)
                            } else {
                                NoLocationDialog.show(this@MainActivity)
                            }

                            if (isGpsOn) {
                                NoGPSDialog.dismiss(this@MainActivity)
                            } else {
                                NoGPSDialog.show(this@MainActivity)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::binding.isInitialized) {
            ViewCompat.requestApplyInsets(binding.main)
        }
        if (::videoProcessingOverlay.isInitialized) {
            videoProcessingOverlay.onConfigurationChanged()
        }
    }

    override fun onDestroy() {
        if (::videoProcessingOverlay.isInitialized) {
            videoProcessingOverlay.dispose()
        }
        super.onDestroy()
    }

    private fun setupOrekit() {
        try {
            val orekitDir = File(cacheDir, "orekit-data")
            if (!orekitDir.exists()) {
                orekitDir.mkdirs()
            }
            val targetFile = File(orekitDir, "tai-utc.dat")
            if (!targetFile.exists()) {
                assets.open("tai-utc.dat").use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            val manager = DataContext.getDefault().dataProvidersManager
            manager.addProvider(DirectoryCrawler(orekitDir))
            Log.d("Orekit", "Orekit data loaded successfully")
        } catch (e: Exception) {
            Log.e("Orekit", "Failed to load Orekit data", e)
        }
    }

    private fun allowLayoutInDisplayCutout() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun setupRootWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }
    }

    private fun setupVideoProcessingOverlay() {
        videoProcessingOverlay = VideoProcessingOverlayController(
            binding = binding,
            onCancelProcessing = ::cancelVideoProcessing,
            onWatchProcessedVideo = ::watchProcessedVideo,
            onDismissProcessedVideo = ::dismissProcessedVideoReady
        ).also { it.attach() }
    }

    private fun observeVideoProcessingOverlay() {
        viewModel.videoProcessingMessage.observe(this) { message ->
            if (message.isNullOrBlank()) {
                videoProcessingOverlay.hide()
            } else {
                clearPendingProcessedVideo()
                videoProcessingOverlay.showProcessing(message)
            }
        }
        VideoProcessingBus.processingMessage.observe(this) { message ->
            if (message.isNullOrBlank()) {
                videoProcessingOverlay.hide()
            } else {
                clearPendingProcessedVideo()
                videoProcessingOverlay.showProcessing(message)
            }
        }
    }

    private fun observeProcessedVideoReady() {
        viewModel.processedVideoPathToOpen.observe(this) { path ->
            if (path.isNullOrBlank()) return@observe

            videoProcessingOverlay.showProcessedVideoReady(path)
        }
        VideoProcessingBus.processedVideoPathToOpen.observe(this) { path ->
            if (path.isNullOrBlank()) return@observe

            videoProcessingOverlay.showProcessedVideoReady(path)
        }
        VideoProcessingBus.videoLibraryUpdated.observe(this) {
            viewModel.videoLibraryUpdated.value = it
        }
    }

    private fun cancelVideoProcessing() {
        viewModel.videoUploadJob?.cancel()
        viewModel.videoProcessingMessage.value = null
        startService(VideoProcessingForegroundService.cancelIntent(this))
    }

    private fun watchProcessedVideo(path: String) {
        val navController = (supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment)
            ?.navController
            ?: return

        viewModel.selectedVideoPath.value = path
        clearVideoProcessingOverlayState()

        val navigateToVideo = {
            if (navController.currentDestination?.id == R.id.videoOpticalFlowFragment) {
                navController.popBackStack()
            }
            navController.navigate(R.id.videoOpticalFlowFragment)
            viewModel.processedVideoPathToOpen.value = null
        }

        runCatching {
            if (supportFragmentManager.isStateSaved) {
                binding.root.post {
                    runCatching { navigateToVideo() }
                        .onFailure { error ->
                            Log.e("NavigationError", "Open processed video failed: $error")
                        }
                }
            } else {
                navigateToVideo()
            }
        }.onFailure { error ->
            Log.e("NavigationError", "Open processed video failed: $error")
        }
    }

    private fun dismissProcessedVideoReady() {
        clearVideoProcessingOverlayState()
        viewModel.processedVideoPathToOpen.value = null
        VideoProcessingBus.processedVideoPathToOpen.value = null
    }

    private fun clearPendingProcessedVideo() {
        if (viewModel.processedVideoPathToOpen.value != null) {
            viewModel.processedVideoPathToOpen.value = null
        }
        if (VideoProcessingBus.processedVideoPathToOpen.value != null) {
            VideoProcessingBus.processedVideoPathToOpen.value = null
        }
    }

    private fun clearVideoProcessingOverlayState() {
        videoProcessingOverlay.hide(clearProcessedVideo = true)
        if (viewModel.videoProcessingMessage.value != null) {
            viewModel.videoProcessingMessage.value = null
        }
        if (VideoProcessingBus.processingMessage.value != null) {
            VideoProcessingBus.processingMessage.value = null
        }
        if (VideoProcessingBus.processedVideoPathToOpen.value != null) {
            VideoProcessingBus.processedVideoPathToOpen.value = null
        }
    }
}
