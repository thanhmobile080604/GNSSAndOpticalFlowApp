package com.example.gnssandopticalflowapp.screen

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.view.View
import androidx.viewpager2.widget.ViewPager2
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.adapter.HomePagerAdapter
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentHomeBinding

class HomeFragment : BaseFragment<FragmentHomeBinding>(FragmentHomeBinding::inflate) {

    private lateinit var pagerAdapter: HomePagerAdapter
    private var indicatorBaseX = 0f
    private var currentTabPosition = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun FragmentHomeBinding.initView() {
        pagerAdapter = HomePagerAdapter(this@HomeFragment)
        viewPager.adapter = pagerAdapter
        viewPager.offscreenPageLimit = pagerAdapter.itemCount
        viewPager.registerOnPageChangeCallback(pageChangeCallback)
        view.post {
            indicatorBaseX = view.x
        }
        updateTabState(0)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun FragmentHomeBinding.initListener() {
        earthButton.setSingleClick {
            viewPager.setCurrentItem(0, true)
        }
        opticalFlowButton.setSingleClick {
            viewPager.setCurrentItem(1, true)
        }
    }

    override fun initObserver() {
        mainViewModel.isNetworkAvailable.observe(viewLifecycleOwner) { isConnected ->
            binding.loadingOverlay.visibility =
                if (isConnected) View.GONE else View.VISIBLE
        }
    }

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            super.onPageSelected(position)
            updateTabState(position)
        }
    }

    private fun updateTabState(targetPosition: Int) = with(binding) {
        val targetView = if (targetPosition == 0) earthButton else opticalFlowButton
        val movingToRight = targetPosition > currentTabPosition

        view.post {
            view.setBackgroundResource(
                if (movingToRight) {
                    R.drawable.bg_blue_gradient_40_right
                } else {
                    R.drawable.bg_blue_gradient_40_left
                }
            )

            val targetCenterX = targetView.x + targetView.width / 2f
            val targetTranslationX = (targetCenterX - view.width / 2f) - indicatorBaseX

            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(
                        view,
                        View.TRANSLATION_X,
                        view.translationX,
                        targetTranslationX
                    ),
                    ObjectAnimator.ofFloat(
                        view,
                        View.SCALE_X,
                        1f, 1.08f, 1f
                    ),
                    ObjectAnimator.ofFloat(
                        view,
                        View.ALPHA,
                        0.88f, 1f, 0.9f
                    )
                )
                duration = 320
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    override fun onBack() {

    }


    override fun onDestroyView() {
        binding.viewPager.unregisterOnPageChangeCallback(pageChangeCallback)
        super.onDestroyView()
    }
}
