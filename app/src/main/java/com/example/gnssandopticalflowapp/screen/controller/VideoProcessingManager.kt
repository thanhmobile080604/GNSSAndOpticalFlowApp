package com.example.gnssandopticalflowapp.screen.controller

import android.annotation.SuppressLint
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.gnssandopticalflowapp.MainViewModel
import com.example.gnssandopticalflowapp.adapter.TopBarNotificationAdapter
import com.example.gnssandopticalflowapp.adapter.TopBarNotificationAdapter.NotificationItem
import com.example.gnssandopticalflowapp.databinding.ActivityMainBinding
import com.example.gnssandopticalflowapp.model.VideoProcessOptions
import com.example.gnssandopticalflowapp.function.video.state.VideoProcessingBus
import com.example.gnssandopticalflowapp.function.video.state.VideoProcessingJobState
import com.example.gnssandopticalflowapp.function.video.worker.VideoProcessingWorker
import kotlin.math.abs
import androidx.core.view.isVisible

class VideoProcessingManager(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val viewModel: MainViewModel
) {
    private lateinit var topBarAdapter: TopBarNotificationAdapter

    private var jobs: List<VideoProcessingJobState> = emptyList()
    private var selectedJobId: String? = null
    private var isCollapsed = false
    private var isAttached = false
    private var pagerScrollState = ViewPager2.SCROLL_STATE_IDLE
    private var pendingVisibleRefresh = false
    private var pendingPagerSync = false
    private var pendingPagerSyncKeepNearby = true

    private var bubblePositionInitialized = false
    private var bubbleDownRawX = 0f
    private var bubbleDownRawY = 0f
    private var bubbleStartX = 0f
    private var bubbleStartY = 0f
    private var bubbleMoved = false
    private var bubbleTouchSlop = 0

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            topBarAdapter.itemAt(position)?.let { item ->
                selectedJobId = item.jobId
                updateBubbleDot()
            }
        }

        override fun onPageScrollStateChanged(state: Int) {
            pagerScrollState = state
            if (state == ViewPager2.SCROLL_STATE_IDLE) {
                flushPendingPagerWork()
            }
        }
    }

    private val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (isCollapsed) {
            clampBubbleInsideParent()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        if (isAttached) return
        isAttached = true
        bubbleTouchSlop = ViewConfiguration.get(binding.root.context).scaledTouchSlop

        setupTopBarViewPager()
        setupBubble()
        binding.main.addOnLayoutChangeListener(layoutChangeListener)

        VideoProcessingBus.processingJobs.observe(activity) {
            renderJobs(it.orEmpty())
        }
        VideoProcessingBus.videoLibraryUpdated.observe(activity) {
            viewModel.videoLibraryUpdated.value = it
        }
        renderJobs(VideoProcessingBus.jobsSnapshot())
    }

    fun onConfigurationChanged() {
        binding.main.post {
            if (isCollapsed) {
                clampBubbleInsideParent()
                snapBubbleToNearestEdge()
            }
        }
    }

    fun dispose() {
        binding.topBarViewPager.unregisterOnPageChangeCallback(pageChangeCallback)
        binding.topBarViewPager.adapter = null
        binding.topBarViewPager.animate().cancel()
        binding.processingBubble.animate().cancel()
        binding.processingBubble.setOnTouchListener(null)
        binding.processingBubble.setOnClickListener(null)
        binding.main.removeOnLayoutChangeListener(layoutChangeListener)
        VideoProcessingBus.processingJobs.removeObservers(activity)
        VideoProcessingBus.videoLibraryUpdated.removeObservers(activity)
        isAttached = false
    }

    private fun setupTopBarViewPager() {
        topBarAdapter = TopBarNotificationAdapter(
            onPrimaryAction = ::handlePrimaryAction,
            onLaterAction = ::handleLaterAction,
            onCollapseAction = { collapseToBubble() }
        )

        binding.topBarViewPager.apply {
            adapter = topBarAdapter
            orientation = ViewPager2.ORIENTATION_VERTICAL
            offscreenPageLimit = 1
            isUserInputEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            setPageTransformer(CenterOnlyVerticalCarouselTransformer())
            registerOnPageChangeCallback(pageChangeCallback)
            visibility = View.GONE
        }

        (binding.topBarViewPager.getChildAt(0) as? RecyclerView)?.apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            itemAnimator = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubble() {
        binding.processingBubble.setOnClickListener {
            if (jobs.isNotEmpty()) expandFromBubble()
        }

        binding.processingBubble.setOnTouchListener { view, event ->
            if (!isCollapsed) return@setOnTouchListener false

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
                    if (bubbleMoved) snapBubbleToNearestEdge() else view.performClick()
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
    }

    private fun renderJobs(newJobs: List<VideoProcessingJobState>) {
        val previousKeys = jobs.map { it.jobId }
        jobs = newJobs

        if (jobs.isEmpty()) {
            hideAll()
            return
        }

        val selectedWasRemoved = selectedJobId?.let { selected ->
            jobs.none { it.jobId == selected }
        } ?: true
        selectedJobId = selectedJobId
            ?.takeIf { selected -> jobs.any { it.jobId == selected } }
            ?: jobs.first().jobId

        val newItems = jobs.map(::toNotificationItem)
        val canRefreshVisibleItems = pagerScrollState == ViewPager2.SCROLL_STATE_IDLE &&
            !isCollapsed &&
                binding.topBarViewPager.isVisible
        val keysChanged = topBarAdapter.submitItems(
            newItems = newItems,
            currentPosition = binding.topBarViewPager.currentItem,
            refreshVisibleItems = canRefreshVisibleItems
        )
        if (!keysChanged && !canRefreshVisibleItems && !isCollapsed) {
            pendingVisibleRefresh = true
        }
        updateBubbleDot()

        if (isCollapsed) {
            if (binding.processingBubble.visibility != View.VISIBLE) {
                showBubble()
            }
            return
        }

        if (binding.topBarViewPager.visibility != View.VISIBLE) {
            showTopBar()
        } else {
            binding.processingBubble.visibility = View.INVISIBLE
        }

        if (keysChanged || selectedWasRemoved) {
            val keepNearby = previousKeys.size > 1 && topBarAdapter.getRealItemCount() > 1
            requestPagerSync(keepNearby = keepNearby)
        }
    }

    private fun toNotificationItem(job: VideoProcessingJobState): NotificationItem {
        return NotificationItem(
            key = job.jobId,
            jobId = job.jobId,
            title = jobTitle(job),
            message = job.message,
            percent = job.percent,
            status = job.status,
            outputPath = job.outputPath
        )
    }

    private fun syncPagerToSelected(keepNearby: Boolean) {
        if (topBarAdapter.getRealItemCount() == 0) return
        val target = if (keepNearby) {
            topBarAdapter.nearestPositionForKey(selectedJobId, binding.topBarViewPager.currentItem)
        } else {
            topBarAdapter.initialPositionForKey(selectedJobId)
        }
        if (binding.topBarViewPager.currentItem != target) {
            binding.topBarViewPager.setCurrentItem(target, false)
        }
    }

    private fun requestPagerSync(keepNearby: Boolean) {
        if (pagerScrollState == ViewPager2.SCROLL_STATE_IDLE) {
            syncPagerToSelected(keepNearby = keepNearby)
        } else {
            pendingPagerSync = true
            pendingPagerSyncKeepNearby = keepNearby
        }
    }

    private fun flushPendingPagerWork() {
        if (!::topBarAdapter.isInitialized || jobs.isEmpty() || isCollapsed) {
            pendingPagerSync = false
            pendingVisibleRefresh = false
            return
        }

        if (pendingPagerSync) {
            val keepNearby = pendingPagerSyncKeepNearby
            pendingPagerSync = false
            pendingPagerSyncKeepNearby = true
            syncPagerToSelected(keepNearby = keepNearby)
        }

        if (pendingVisibleRefresh) {
            pendingVisibleRefresh = false
            topBarAdapter.refreshAround(binding.topBarViewPager.currentItem)
        }
    }

    private fun showTopBar() {
        isCollapsed = false
        if (binding.topBarViewPager.visibility != View.VISIBLE) {
            binding.topBarViewPager.animate().cancel()
            resetView(binding.topBarViewPager)
            binding.topBarViewPager.visibility = View.VISIBLE
        }
        binding.processingBubble.visibility = View.INVISIBLE
    }

    private fun collapseToBubble() {
        if (jobs.isEmpty()) {
            hideAll()
            return
        }
        isCollapsed = true
        binding.topBarViewPager.visibility = View.GONE
        showBubble()
    }

    private fun expandFromBubble() {
        if (jobs.isEmpty()) {
            hideAll()
            return
        }
        showTopBar()
        syncPagerToSelected(keepNearby = true)
    }

    private fun showBubble() {
        updateBubbleDot()
        if (binding.processingBubble.visibility != View.VISIBLE) {
            binding.processingBubble.animate().cancel()
            resetView(binding.processingBubble)
            binding.processingBubble.visibility = View.VISIBLE
            binding.processingBubble.post {
                ensureBubblePosition()
                snapBubbleToNearestEdge()
            }
        }
    }

    private fun hideAll() {
        jobs = emptyList()
        selectedJobId = null
        isCollapsed = false
        if (::topBarAdapter.isInitialized) {
            topBarAdapter.submitItems(
                newItems = emptyList(),
                currentPosition = binding.topBarViewPager.currentItem,
                refreshVisibleItems = false
            )
        }
        pendingPagerSync = false
        pendingVisibleRefresh = false
        binding.topBarViewPager.animate().cancel()
        binding.topBarViewPager.visibility = View.GONE
        resetView(binding.topBarViewPager)
        binding.processingBubble.animate().cancel()
        binding.processingBubble.visibility = View.INVISIBLE
        binding.processingDoneDot.visibility = View.GONE
        resetView(binding.processingBubble)
    }

    private fun handlePrimaryAction(item: NotificationItem) {
        selectedJobId = item.jobId
        val outputPath = item.outputPath
        when {
            item.isReady && !outputPath.isNullOrBlank() -> watchProcessedVideo(item.jobId, outputPath)
            item.isTerminal -> dismissJob(item.jobId)
            else -> cancelJob(item.jobId)
        }
    }

    private fun handleLaterAction(item: NotificationItem) {
        dismissJob(item.jobId)
    }

    private fun cancelJob(jobId: String?) {
        if (jobId.isNullOrBlank()) return
        viewModel.cancelVideoUploadJob(jobId)
        viewModel.videoProcessingMessage.value = null
        viewModel.processedVideoPathToOpen.value = null
        VideoProcessingWorker.cancel(activity, jobId)
    }

    private fun dismissJob(jobId: String?) {
        if (jobId.isNullOrBlank()) return
        viewModel.processedVideoPathToOpen.value = null
        VideoProcessingWorker.dismiss(activity, jobId)
    }

    private fun watchProcessedVideo(jobId: String?, path: String) {
        val navController = (activity.supportFragmentManager.findFragmentById(com.example.gnssandopticalflowapp.R.id.nav_host_fragment) as? NavHostFragment)
            ?.navController
            ?: return

        viewModel.selectedVideoPath.value = path
        dismissJob(jobId)

        val navigateToVideo = {
            if (navController.currentDestination?.id == com.example.gnssandopticalflowapp.R.id.videoOpticalFlowFragment) {
                navController.popBackStack()
            }
            navController.navigate(com.example.gnssandopticalflowapp.R.id.videoOpticalFlowFragment)
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

    private fun updateBubbleDot() {
        binding.processingDoneDot.visibility =
            if (jobs.any { it.isReady }) View.VISIBLE else View.GONE
    }

    private fun resetView(view: View) {
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
    }

    private fun ensureBubblePosition() {
        val parent = binding.main
        val bubble = binding.processingBubble
        if (parent.width <= 0 || bubble.width <= 0) return

        if (!bubblePositionInitialized) {
            bubble.x = clampBubbleX(parent.width - parent.paddingRight - bubble.width - dp(16))
            bubble.y = clampBubbleY(dp(104))
            bubblePositionInitialized = true
        } else {
            clampBubbleInsideParent()
        }
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

    private fun minBubbleX(): Float = binding.main.paddingLeft + dp(12)

    private fun maxBubbleX(): Float {
        return binding.main.width - binding.main.paddingRight - binding.processingBubble.width - dp(12)
    }

    private fun dp(value: Int): Float {
        return value * binding.root.resources.displayMetrics.density
    }

    private fun jobTitle(job: VideoProcessingJobState): String {
        val index = jobs.indexOfFirst { it.jobId == job.jobId }
            .takeIf { it >= 0 }
            ?.plus(1)
            ?: 1
        val modeLabel = when (job.mode) {
            VideoProcessOptions.ProcessingMode.ONLINE -> "ONLINE"
            VideoProcessOptions.ProcessingMode.OFFLINE -> "DEVICE"
            null -> "VIDEO"
        }
        val serverId = job.serverJobId
            ?.take(8)
            ?.let { "  S:$it" }
            .orEmpty()
        return "#${job.jobId}  $modeLabel  $index/${jobs.size}$serverId"
    }

    private class CenterOnlyVerticalCarouselTransformer : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            val centerProgress = 1f - abs(position).coerceAtMost(1f)
            val scale = 0.9f + (centerProgress * 0.1f)

            page.alpha = centerProgress
            page.scaleX = scale
            page.scaleY = scale
            page.translationZ = centerProgress
        }
    }

    private companion object {
        const val BUBBLE_SNAP_DURATION_MS = 180L
    }
}
