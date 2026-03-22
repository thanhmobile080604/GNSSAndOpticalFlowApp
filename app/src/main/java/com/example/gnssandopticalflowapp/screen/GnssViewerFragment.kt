package com.example.gnssandopticalflowapp.screen

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Toast
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.databinding.FragmentGnssViewerBinding
import com.example.gnssandopticalflowapp.gnss.EarthRenderer

class GnssViewerFragment :
    BaseFragment<FragmentGnssViewerBinding>(FragmentGnssViewerBinding::inflate) {
    private var rendererSet = false
    private lateinit var earthRenderer: EarthRenderer
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    override fun FragmentGnssViewerBinding.initView() {
        initOpenGLES()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun FragmentGnssViewerBinding.initListener() {
        if (!rendererSet) return

        scaleGestureDetector = ScaleGestureDetector(
            requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    earthRenderer.scaleFactor *= detector.scaleFactor
                    earthRenderer.scaleFactor = earthRenderer.scaleFactor.coerceIn(0.2f, 3.0f)
                    return true
                }
            })

        var previousX = 0f
        var previousY = 0f

        myGLSurfaceView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)

            if (event.pointerCount == 1) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        previousX = event.x
                        previousY = event.y
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - previousX
                        val dy = event.y - previousY

                        earthRenderer.theta += dx * 0.5f
                        earthRenderer.phi -= dy * 0.5f

                        // Giới hạn phi để tránh lật camera
                        earthRenderer.phi = earthRenderer.phi.coerceIn(-89f, 89f)

                        previousX = event.x
                        previousY = event.y
                    }
                }
            }
            true
        }
    }

    override fun initObserver() {
    }

    private fun initOpenGLES() {
        val activityManager =
            requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val configurationInfo = activityManager.deviceConfigurationInfo
        val supportsEs32 = configurationInfo.reqGlEsVersion >= 0x30002

        if (supportsEs32) {
            earthRenderer = EarthRenderer(requireContext())
            binding.myGLSurfaceView.setEGLContextClientVersion(3)
            binding.myGLSurfaceView.setRenderer(earthRenderer)
            rendererSet = true
        } else {
            Toast.makeText(
                requireContext(),
                "This device doesn't support OpenGL ES 3.2",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (rendererSet) {
            binding.myGLSurfaceView.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (rendererSet) {
            binding.myGLSurfaceView.onPause()
        }
    }
}
