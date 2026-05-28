package com.example.gnssandopticalflowapp

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.util.Log
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.NavHostFragment
import com.example.gnssandopticalflowapp.common.AndroidLocationObserver
import com.example.gnssandopticalflowapp.databinding.ActivityMainBinding
import com.example.gnssandopticalflowapp.screen.controller.VideoProcessingManager
import com.example.gnssandopticalflowapp.screen.dialog.NoGPSDialog
import com.example.gnssandopticalflowapp.screen.dialog.NoLocationDialog
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
    private lateinit var videoProcessingManager: VideoProcessingManager

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
        videoProcessingManager = VideoProcessingManager(this, binding, viewModel).also { it.attach() }

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
        askNotificationPermission()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::binding.isInitialized) {
            ViewCompat.requestApplyInsets(binding.main)
        }
        if (::videoProcessingManager.isInitialized) {
            videoProcessingManager.onConfigurationChanged()
        }
    }

    override fun onDestroy() {
        if (::videoProcessingManager.isInitialized) {
            videoProcessingManager.dispose()
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

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->

            if (isGranted) {
                // Được cấp quyền
            } else {
                // Bị từ chối
            }
        }

    private fun askNotificationPermission() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {

                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {

                    // Đã có quyền
                }

                else -> {
                    requestPermissionLauncher.launch(
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                }
            }
        }
    }
}
