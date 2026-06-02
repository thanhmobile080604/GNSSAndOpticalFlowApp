package com.example.gnssandopticalflowapp.screen.fragment

import android.annotation.SuppressLint
import android.graphics.Color
import androidx.viewpager2.widget.ViewPager2
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.adapter.HomePagerAdapter
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.hide
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.common.show
import com.example.gnssandopticalflowapp.databinding.FragmentHomeBinding

class HomeFragment : BaseFragment<FragmentHomeBinding>(FragmentHomeBinding::inflate) {

    private lateinit var pagerAdapter: HomePagerAdapter

    private enum class Mode {
        MAP_2D,
        MAP_3D,
        HOME_OPTICAL
    }

    private var currentMode: Mode = Mode.MAP_2D
        set(value) {
            if (field == value) return

            field = value
            binding.applyMode(value)
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun FragmentHomeBinding.initView() {
        setupViewPager()
        setupLiquidPurple()

        applyMode(currentMode)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun FragmentHomeBinding.initListener() {
        earthButton.setSingleClick {
            viewPager.setCurrentItem(PAGE_MAP, true)
        }

        opticalFlowButton.setSingleClick {
            viewPager.setCurrentItem(PAGE_OPTICAL, true)
        }
    }

    override fun initObserver() = with(binding) {
        mainViewModel.isGnss3DMode.observe(viewLifecycleOwner) {
            updateMapModeIfCurrentPageIsMap()
        }
    }

    private fun FragmentHomeBinding.setupViewPager() {
        pagerAdapter = HomePagerAdapter(this@HomeFragment)

        viewPager.adapter = pagerAdapter
        viewPager.isUserInputEnabled = false
        viewPager.offscreenPageLimit = pagerAdapter.itemCount
        viewPager.registerOnPageChangeCallback(pageChangeCallback)

        view.bind(viewPager)
        view.setElasticEnabled(true)
    }

    private fun FragmentHomeBinding.setupLiquidPurple() {
        liquidPurpleLeft.bind(viewPager)
        liquidPurpleRight.bind(viewPager)

        liquidPurpleLeft.setElasticEnabled(true)
        liquidPurpleRight.setElasticEnabled(true)

        applyPurpleTint()
    }

    private fun FragmentHomeBinding.applyPurpleTint() {
        liquidPurpleLeft.setTintColorRed(PURPLE_RED)
        liquidPurpleLeft.setTintColorGreen(PURPLE_GREEN)
        liquidPurpleLeft.setTintColorBlue(PURPLE_BLUE)
        liquidPurpleLeft.setTintAlpha(PURPLE_ALPHA)

        liquidPurpleRight.setTintColorRed(PURPLE_RED)
        liquidPurpleRight.setTintColorGreen(PURPLE_GREEN)
        liquidPurpleRight.setTintColorBlue(PURPLE_BLUE)
        liquidPurpleRight.setTintAlpha(PURPLE_ALPHA)
    }

    private fun updateMapModeIfCurrentPageIsMap() {
        if (binding.viewPager.currentItem == PAGE_MAP) {
            currentMode = getMapMode()
        }
    }

    private fun getMapMode(): Mode {
        return if (mainViewModel.isGnss3DMode.value == true) {
            Mode.MAP_3D
        } else {
            Mode.MAP_2D
        }
    }

    private fun FragmentHomeBinding.applyMode(mode: Mode) {
        when (mode) {
            Mode.MAP_2D -> applyMap2DMode()
            Mode.MAP_3D -> applyMap3DMode()
            Mode.HOME_OPTICAL -> applyOpticalMode()
        }
    }

    private fun FragmentHomeBinding.applyMap2DMode() {
        liquidPurpleLeft.show()
        liquidPurpleRight.hide()

        earthButton.setColorFilter(Color.BLACK)
        opticalFlowButton.setColorFilter(Color.BLACK)
    }

    private fun FragmentHomeBinding.applyMap3DMode() {
        liquidPurpleLeft.hide()
        liquidPurpleRight.hide()

        earthButton.setColorFilter(Color.WHITE)
        opticalFlowButton.setColorFilter(Color.WHITE)

        view.setBackgroundResource(R.drawable.bg_blue_gradient_40_left)
    }

    private fun FragmentHomeBinding.applyOpticalMode() {
        liquidPurpleLeft.hide()
        liquidPurpleRight.show()

        earthButton.setColorFilter(Color.WHITE)
        opticalFlowButton.setColorFilter(Color.WHITE)
    }

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            super.onPageSelected(position)

            mainViewModel.currentTab.value = position

            currentMode = when (position) {
                PAGE_MAP -> getMapMode()
                PAGE_OPTICAL -> Mode.HOME_OPTICAL
                else -> Mode.MAP_2D
            }
        }
    }

    override fun onBack() = Unit

    override fun onDestroyView() {
        binding.viewPager.unregisterOnPageChangeCallback(pageChangeCallback)
        super.onDestroyView()
    }

    companion object {
        private const val PAGE_MAP = 0
        private const val PAGE_OPTICAL = 1

        private const val PURPLE_RED = 0.482f
        private const val PURPLE_GREEN = 0.361f
        private const val PURPLE_BLUE = 1f
        private const val PURPLE_ALPHA = 0.45f
    }
}