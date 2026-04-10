package com.example.gnssandopticalflowapp

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
import com.example.gnssandopticalflowapp.base.AndroidConnectivityObserver
import com.example.gnssandopticalflowapp.base.AndroidLocationObserver
import com.example.gnssandopticalflowapp.databinding.ActivityMainBinding
import com.example.gnssandopticalflowapp.screen.dialog.NoGPSDialog
import com.example.gnssandopticalflowapp.screen.dialog.NoLocationDialog
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader

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
    private val network: AndroidConnectivityObserver by lazy {
        AndroidConnectivityObserver(this)
    }

    private val locationObserver: AndroidLocationObserver by lazy {
        AndroidLocationObserver(applicationContext)
    }

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            insets
        }

        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
        )

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
}
