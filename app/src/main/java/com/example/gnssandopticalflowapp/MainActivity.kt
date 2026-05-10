package com.example.gnssandopticalflowapp

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.res.Configuration
import android.os.Bundle
import android.os.StrictMode
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
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
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.ActivityMainBinding
import com.example.gnssandopticalflowapp.screen.dialog.NoGPSDialog
import com.example.gnssandopticalflowapp.screen.dialog.NoLocationDialog
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import org.orekit.data.DataContext
import org.orekit.data.DirectoryCrawler
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

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
    private var isVideoProcessingVisible = false
    private var isVideoProcessingCollapsed = false
    private var isVideoProcessingTransitioning = false
    private var isProcessedVideoReady = false
    private var pendingProcessedVideoPath: String? = null
    private var currentVideoProcessingMessage = "Processing..."
    private var activeVideoProcessingAnimation: AnimatorSet? = null
    private var bubblePositionInitialized = false
    private var bubbleDownRawX = 0f
    private var bubbleDownRawY = 0f
    private var bubbleStartX = 0f
    private var bubbleStartY = 0f
    private var bubbleMoved = false
    private var bubbleTouchSlop = 0

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
        binding.main.post {
            if (isVideoProcessingCollapsed) {
                clampBubbleInsideParent()
                snapBubbleToNearestEdge()
            }
        }
    }

    override fun onDestroy() {
        cancelActiveVideoProcessingAnimation()
        if (::binding.isInitialized) {
            binding.processingBubble.animate().cancel()
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

    private fun setupVideoProcessingOverlay() {
        bubbleTouchSlop = ViewConfiguration.get(this).scaledTouchSlop

        binding.btnCancel.setSingleClick {
            if (isProcessedVideoReady) {
                watchProcessedVideo()
            } else {
                viewModel.videoUploadJob?.cancel()
                viewModel.videoProcessingMessage.value = null
            }
        }

        binding.btnLater.setSingleClick {
            dismissProcessedVideoReady()
        }

        binding.ivClose.setSingleClick {
            collapseVideoProcessingOverlay()
        }

        binding.processingBubble.setOnClickListener {
            if (isVideoProcessingCollapsed) {
                expandVideoProcessingOverlay()
            }
        }

        binding.processingBubble.setOnTouchListener { view, event ->
            if (!isVideoProcessingCollapsed) return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    view.animate().cancel()
                    bubbleDownRawX = event.rawX
                    bubbleDownRawY = event.rawY
                    bubbleStartX = view.x
                    bubbleStartY = view.y
                    bubbleMoved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - bubbleDownRawX
                    val dy = event.rawY - bubbleDownRawY

                    if (!bubbleMoved && (abs(dx) > bubbleTouchSlop || abs(dy) > bubbleTouchSlop)) {
                        bubbleMoved = true
                    }

                    if (bubbleMoved) {
                        view.x = clampBubbleX(bubbleStartX + dx)
                        view.y = clampBubbleY(bubbleStartY + dy)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    if (bubbleMoved) {
                        snapBubbleToNearestEdge()
                    } else {
                        view.performClick()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    if (bubbleMoved) snapBubbleToNearestEdge()
                    true
                }

                else -> false
            }
        }

        binding.main.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (isVideoProcessingCollapsed) {
                clampBubbleInsideParent()
            }
        }
    }

    private fun observeVideoProcessingOverlay() {
        viewModel.videoProcessingMessage.observe(this) { message ->
            if (message.isNullOrBlank()) {
                hideVideoProcessingOverlay()
            } else {
                showVideoProcessingOverlay(message)
            }
        }
    }

    private fun observeProcessedVideoReady() {
        viewModel.processedVideoPathToOpen.observe(this) { path ->
            if (path.isNullOrBlank()) return@observe

            showProcessedVideoReady(path)
        }
    }

    private fun watchProcessedVideo() {
        val path = pendingProcessedVideoPath ?: viewModel.processedVideoPathToOpen.value ?: return
        val navController = (supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment)
            ?.navController
            ?: return

        viewModel.selectedVideoPath.value = path
        hideVideoProcessingOverlay(clearProcessedVideo = true)

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
        hideVideoProcessingOverlay(clearProcessedVideo = true)
        viewModel.processedVideoPathToOpen.value = null
    }

    private fun showVideoProcessingOverlay(message: String) {
        currentVideoProcessingMessage = message
        isProcessedVideoReady = false
        pendingProcessedVideoPath = null
        if (viewModel.processedVideoPathToOpen.value != null) {
            viewModel.processedVideoPathToOpen.value = null
        }

        if (!isVideoProcessingVisible) {
            isVideoProcessingVisible = true
            isVideoProcessingCollapsed = false
            cancelActiveVideoProcessingAnimation()
            binding.processingBubble.animate().cancel()
            binding.processingBubble.visibility = View.INVISIBLE
            showLoadingViews()
        } else if (!isVideoProcessingCollapsed && !isVideoProcessingTransitioning) {
            showLoadingViews()
        } else {
            binding.processingDoneDot.visibility = View.GONE
        }
    }

    private fun showProcessedVideoReady(path: String) {
        val keepTopTabOpen = isVideoProcessingVisible &&
            !isVideoProcessingCollapsed &&
            !isVideoProcessingTransitioning

        pendingProcessedVideoPath = path
        isProcessedVideoReady = true
        isVideoProcessingVisible = true
        isVideoProcessingTransitioning = false
        cancelActiveVideoProcessingAnimation()

        if (keepTopTabOpen) {
            isVideoProcessingCollapsed = false
            binding.processingBubble.animate().cancel()
            resetAnimatedView(binding.processingBubble)
            binding.processingBubble.visibility = View.INVISIBLE
            showLoadingViews()
            return
        }

        isVideoProcessingCollapsed = true
        applyCompletedUi()

        loadingViews().forEach { view ->
            resetAnimatedView(view)
            view.visibility = View.GONE
        }

        binding.main.post {
            ensureBubblePosition()
            binding.processingBubble.animate().cancel()
            resetAnimatedView(binding.processingBubble)
            binding.processingDoneDot.visibility = View.VISIBLE
            binding.processingBubble.visibility = View.VISIBLE
            snapBubbleToNearestEdge()
        }
    }

    private fun hideVideoProcessingOverlay(clearProcessedVideo: Boolean = false) {
        isVideoProcessingVisible = false
        isVideoProcessingCollapsed = false
        isVideoProcessingTransitioning = false
        cancelActiveVideoProcessingAnimation()

        if (clearProcessedVideo) {
            isProcessedVideoReady = false
            pendingProcessedVideoPath = null
            if (viewModel.videoProcessingMessage.value != null) {
                viewModel.videoProcessingMessage.value = null
            }
        }

        loadingViews().forEach { view ->
            resetAnimatedView(view)
            view.visibility = View.GONE
        }

        binding.processingBubble.animate().cancel()
        resetAnimatedView(binding.processingBubble)
        binding.processingDoneDot.visibility = View.GONE
        binding.processingBubble.visibility = View.INVISIBLE
    }

    private fun collapseVideoProcessingOverlay() {
        if (!isVideoProcessingVisible || isVideoProcessingCollapsed) return

        binding.main.post {
            if (!isVideoProcessingVisible || isVideoProcessingCollapsed) return@post

            ensureBubblePosition()
            cancelActiveVideoProcessingAnimation()
            isVideoProcessingCollapsed = true
            isVideoProcessingTransitioning = true
            binding.processingBubble.animate().cancel()
            binding.processingBubble.visibility = View.INVISIBLE

            val dx = bubbleCenterX() - loadingCardCenterX()
            val dy = bubbleCenterY() - loadingCardCenterY()
            val animators = loadingViews().flatMap { view ->
                listOf(
                    ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, dx),
                    ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, dy),
                    ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 0.18f),
                    ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 0.18f),
                    ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f)
                )
            }

            activeVideoProcessingAnimation = AnimatorSet().apply {
                playTogether(animators)
                duration = 260L
                interpolator = AccelerateDecelerateInterpolator()
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        loadingViews().forEach { view ->
                            resetAnimatedView(view)
                            view.visibility = View.GONE
                        }

                        binding.processingDoneDot.visibility =
                            if (isProcessedVideoReady) View.VISIBLE else View.GONE
                        binding.processingBubble.alpha = 0f
                        binding.processingBubble.scaleX = 0.75f
                        binding.processingBubble.scaleY = 0.75f
                        binding.processingBubble.visibility = View.VISIBLE
                        binding.processingBubble.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(160L)
                            .setInterpolator(DecelerateInterpolator())
                            .start()

                        isVideoProcessingTransitioning = false
                        activeVideoProcessingAnimation = null
                    }
                })
                start()
            }
        }
    }

    private fun expandVideoProcessingOverlay() {
        if (!isVideoProcessingVisible || !isVideoProcessingCollapsed) return

        binding.main.post {
            if (!isVideoProcessingVisible || !isVideoProcessingCollapsed) return@post

            cancelActiveVideoProcessingAnimation()
            isVideoProcessingCollapsed = false
            isVideoProcessingTransitioning = true

            val dx = bubbleCenterX() - loadingCardCenterX()
            val dy = bubbleCenterY() - loadingCardCenterY()

            binding.processingBubble.animate().cancel()
            binding.processingBubble.visibility = View.INVISIBLE

            loadingViews().forEach { view ->
                view.visibility = View.VISIBLE
                view.translationX = dx
                view.translationY = dy
                view.scaleX = 0.18f
                view.scaleY = 0.18f
                view.alpha = 0f
            }
            applyCurrentOverlayMode()

            val animators = loadingViews().flatMap { view ->
                listOf(
                    ObjectAnimator.ofFloat(view, View.TRANSLATION_X, dx, 0f),
                    ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, dy, 0f),
                    ObjectAnimator.ofFloat(view, View.SCALE_X, 0.18f, 1f),
                    ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.18f, 1f),
                    ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f)
                )
            }

            activeVideoProcessingAnimation = AnimatorSet().apply {
                playTogether(animators)
                duration = 260L
                interpolator = AccelerateDecelerateInterpolator()
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        loadingViews().forEach(::resetAnimatedView)
                        isVideoProcessingTransitioning = false
                        activeVideoProcessingAnimation = null
                    }
                })
                start()
            }
        }
    }

    private fun showLoadingViews() {
        loadingViews().forEach { view ->
            resetAnimatedView(view)
            view.visibility = View.VISIBLE
        }
        applyCurrentOverlayMode()
    }

    private fun loadingViews(): List<View> {
        return listOf(
            binding.loadingCard,
            binding.progressCircular,
            binding.tvLoadingMessage,
            binding.actionGroup,
            binding.ivClose
        )
    }

    private fun applyCurrentOverlayMode() {
        if (isProcessedVideoReady) {
            applyCompletedUi()
        } else {
            applyProcessingUi(currentVideoProcessingMessage)
        }
    }

    private fun applyProcessingUi(message: String) {
        binding.tvLoadingMessage.text = message
        binding.btnCancel.text = "Cancel"
        binding.btnCancel.visibility = View.VISIBLE
        binding.btnLater.visibility = View.GONE
        binding.progressCircular.visibility = View.VISIBLE
        binding.processingDoneDot.visibility = View.GONE
    }

    private fun applyCompletedUi() {
        binding.tvLoadingMessage.text = "Done!"
        binding.btnCancel.text = "Watch"
        binding.btnCancel.visibility = View.VISIBLE
        binding.btnLater.visibility = View.VISIBLE
        binding.progressCircular.visibility = View.INVISIBLE
        binding.processingDoneDot.visibility = View.VISIBLE
    }

    private fun resetAnimatedView(view: View) {
        view.translationX = 0f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.alpha = 1f
    }

    private fun cancelActiveVideoProcessingAnimation() {
        activeVideoProcessingAnimation?.removeAllListeners()
        activeVideoProcessingAnimation?.cancel()
        activeVideoProcessingAnimation = null
    }

    private fun ensureBubblePosition() {
        if (bubblePositionInitialized && binding.processingBubble.width > 0) {
            clampBubbleInsideParent()
            return
        }

        val parent = binding.main
        val bubble = binding.processingBubble
        if (parent.width <= 0 || bubble.width <= 0) return

        bubble.x = clampBubbleX(parent.width - parent.paddingRight - bubble.width - dp(16))
        bubble.y = clampBubbleY(dp(104))
        bubblePositionInitialized = true
    }

    private fun clampBubbleInsideParent() {
        binding.processingBubble.x = clampBubbleX(binding.processingBubble.x)
        binding.processingBubble.y = clampBubbleY(binding.processingBubble.y)
    }

    private fun snapBubbleToNearestEdge() {
        val parent = binding.main
        val bubble = binding.processingBubble
        if (parent.width <= 0 || bubble.width <= 0) return

        val targetX = if (bubble.x + bubble.width / 2f < parent.width / 2f) {
            minBubbleX()
        } else {
            maxBubbleX()
        }

        bubble.animate()
            .x(targetX)
            .y(clampBubbleY(bubble.y))
            .setDuration(180L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun clampBubbleX(x: Float): Float {
        val min = minBubbleX()
        val max = maxBubbleX().coerceAtLeast(min)
        return x.coerceIn(min, max)
    }

    private fun clampBubbleY(y: Float): Float {
        val min = dp(24)
        val max = (binding.main.height - binding.main.paddingBottom - binding.processingBubble.height - dp(24))
            .coerceAtLeast(min)
        return y.coerceIn(min, max)
    }

    private fun minBubbleX(): Float {
        return binding.main.paddingLeft + dp(12)
    }

    private fun maxBubbleX(): Float {
        return binding.main.width - binding.main.paddingRight - binding.processingBubble.width - dp(12)
    }

    private fun loadingCardCenterX(): Float {
        return binding.loadingCard.x + binding.loadingCard.width / 2f
    }

    private fun loadingCardCenterY(): Float {
        return binding.loadingCard.y + binding.loadingCard.height / 2f
    }

    private fun bubbleCenterX(): Float {
        return binding.processingBubble.x + binding.processingBubble.width / 2f
    }

    private fun bubbleCenterY(): Float {
        return binding.processingBubble.y + binding.processingBubble.height / 2f
    }

    private fun dp(value: Int): Float {
        return value * resources.displayMetrics.density
    }
}
