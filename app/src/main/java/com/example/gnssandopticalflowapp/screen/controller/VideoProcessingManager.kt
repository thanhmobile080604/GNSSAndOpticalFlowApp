package com.example.gnssandopticalflowapp.screen.controller

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.NavHostFragment
import com.example.gnssandopticalflowapp.MainViewModel
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.ActivityMainBinding
import com.example.gnssandopticalflowapp.video.VideoProcessingBus
import com.example.gnssandopticalflowapp.video.VideoProcessingForegroundService
import com.example.gnssandopticalflowapp.video.VideoProcessingProgressText
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Single class that manages both the video processing overlay UI and
 * the LiveData/Bus observation + navigation logic.
 */
class VideoProcessingManager(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val viewModel: MainViewModel
) {
    // Overlay state
    private var isVideoProcessingVisible = false
    private var isVideoProcessingCollapsed = false
    private var isVideoProcessingTransitioning = false
    private var isProcessedVideoReady = false
    private var pendingProcessedVideoPath: String? = null
    private var currentVideoProcessingMessage = DEFAULT_PROCESSING_MESSAGE
    private var activeVideoProcessingAnimation: AnimatorSet? = null
    private var bubblePositionInitialized = false
    private var bubbleDownRawX = 0f
    private var bubbleDownRawY = 0f
    private var bubbleStartX = 0f
    private var bubbleStartY = 0f
    private var bubbleMoved = false
    private var bubbleTouchSlop = 0
    private var isAttached = false

    private val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (isVideoProcessingCollapsed) {
            clampBubbleInsideParent()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        if (isAttached) return
        isAttached = true
        bubbleTouchSlop = ViewConfiguration.get(binding.root.context).scaledTouchSlop

        // Setup UI handlers
        binding.btnCancel.setSingleClick {
            val processedVideoPath = pendingProcessedVideoPath
            if (isProcessedVideoReady && !processedVideoPath.isNullOrBlank()) {
                watchProcessedVideo(processedVideoPath)
            } else {
                cancelVideoProcessing()
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

        binding.main.addOnLayoutChangeListener(layoutChangeListener)

        // Observe LiveData / Bus
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.videoProcessingMessage.observe(activity) { message ->
                        if (message.isNullOrBlank()) {
                            if (viewModel.processedVideoPathToOpen.value.isNullOrBlank() &&
                                VideoProcessingBus.processedVideoPathToOpen.value.isNullOrBlank()
                            ) {
                                hide()
                            }
                        } else {
                            clearPendingProcessedVideo()
                            showProcessing(message)
                        }
                    }
                }

                launch {
                    VideoProcessingBus.processingMessage.observe(activity) { message ->
                        if (message.isNullOrBlank()) {
                            if (viewModel.processedVideoPathToOpen.value.isNullOrBlank() &&
                                VideoProcessingBus.processedVideoPathToOpen.value.isNullOrBlank()
                            ) {
                                hide()
                            }
                        } else {
                            clearPendingProcessedVideo()
                            showProcessing(message)
                        }
                    }
                }
            }
        }

        viewModel.processedVideoPathToOpen.observe(activity) { path ->
            if (path.isNullOrBlank()) return@observe
            showProcessedVideoReady(path)
        }

        VideoProcessingBus.processedVideoPathToOpen.observe(activity) { path ->
            if (path.isNullOrBlank()) return@observe
            showProcessedVideoReady(path)
        }

        VideoProcessingBus.videoLibraryUpdated.observe(activity) {
            viewModel.videoLibraryUpdated.value = it
        }
    }

    fun onConfigurationChanged() {
        binding.main.post {
            if (isVideoProcessingCollapsed) {
                clampBubbleInsideParent()
                snapBubbleToNearestEdge()
            }
        }
    }

    fun dispose() {
        cancelActiveVideoProcessingAnimation()
        binding.processingBubble.animate().cancel()
        binding.processingBubble.setOnTouchListener(null)
        binding.processingBubble.setOnClickListener(null)
        binding.main.removeOnLayoutChangeListener(layoutChangeListener)
        isAttached = false
    }

    private fun showProcessing(message: String) {
        val fallbackPercent = if (isVideoProcessingVisible) {
            currentProgressPercent()
        } else {
            VideoProcessingProgressText.DEFAULT_PERCENT
        }
        currentVideoProcessingMessage = VideoProcessingProgressText.normalize(message, fallbackPercent)
        isProcessedVideoReady = false
        pendingProcessedVideoPath = null

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

    private fun hide(clearProcessedVideo: Boolean = false) {
        if (!clearProcessedVideo && isProcessedVideoReady) {
            return
        }

        isVideoProcessingVisible = false
        isVideoProcessingCollapsed = false
        isVideoProcessingTransitioning = false
        cancelActiveVideoProcessingAnimation()

        if (clearProcessedVideo) {
            isProcessedVideoReady = false
            pendingProcessedVideoPath = null
            currentVideoProcessingMessage = DEFAULT_PROCESSING_MESSAGE
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
                duration = OVERLAY_TRANSITION_DURATION_MS
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
                            .setDuration(BUBBLE_FADE_DURATION_MS)
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
                duration = OVERLAY_TRANSITION_DURATION_MS
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
        binding.tvLoadingMessage.text = VideoProcessingProgressText.normalize(message, currentProgressPercent())
        binding.btnCancel.text = CANCEL_TEXT
        binding.btnCancel.visibility = View.VISIBLE
        binding.btnLater.visibility = View.GONE
        binding.progressCircular.visibility = View.VISIBLE
        binding.processingDoneDot.visibility = View.GONE
    }

    private fun applyCompletedUi() {
        binding.tvLoadingMessage.text = DONE_TEXT
        binding.btnCancel.text = WATCH_TEXT
        binding.btnCancel.visibility = View.VISIBLE
        binding.btnLater.visibility = View.VISIBLE
        binding.progressCircular.visibility = View.INVISIBLE
        binding.processingDoneDot.visibility = View.VISIBLE
    }

    private fun currentProgressPercent(): Int {
        return VideoProcessingProgressText.extractPercent(currentVideoProcessingMessage)
            ?: VideoProcessingProgressText.DEFAULT_PERCENT
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
            .setDuration(BUBBLE_SNAP_DURATION_MS)
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
        return value * binding.root.resources.displayMetrics.density
    }

    private fun cancelVideoProcessing() {
        viewModel.videoUploadJob?.cancel()
        viewModel.videoProcessingMessage.value = null
        activity.startService(VideoProcessingForegroundService.cancelIntent(activity))
    }

    private fun watchProcessedVideo(path: String) {
        val navController = (activity.supportFragmentManager.findFragmentById(com.example.gnssandopticalflowapp.R.id.nav_host_fragment) as? NavHostFragment)
            ?.navController
            ?: return

        viewModel.selectedVideoPath.value = path
        clearVideoProcessingOverlayState()

        val navigateToVideo = {
            if (navController.currentDestination?.id == com.example.gnssandopticalflowapp.R.id.videoOpticalFlowFragment) {
                navController.popBackStack()
            }
            navController.navigate(com.example.gnssandopticalflowapp.R.id.videoOpticalFlowFragment)
            viewModel.processedVideoPathToOpen.value = null
        }

        runCatching {
            if (activity.supportFragmentManager.isStateSaved) {
                binding.root.post {
                    runCatching { navigateToVideo() }
                        .onFailure { error -> Log.e("NavigationError", "Open processed video failed: $error") }
                }
            } else {
                navigateToVideo()
            }
        }.onFailure { error -> Log.e("NavigationError", "Open processed video failed: $error") }
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
        hide(clearProcessedVideo = true)
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

    private companion object {
        const val DEFAULT_PROCESSING_MESSAGE = "Processing: 0%"
        const val CANCEL_TEXT = "Cancel"
        const val DONE_TEXT = "Done!"
        const val WATCH_TEXT = "Watch"
        const val OVERLAY_TRANSITION_DURATION_MS = 260L
        const val BUBBLE_FADE_DURATION_MS = 160L
        const val BUBBLE_SNAP_DURATION_MS = 180L
    }
}
