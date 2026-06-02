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


    enum class MODE {
        MAP_2D, MAP_3D, HOME_OPTICAL
    }

    private var currentMode: MODE? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun FragmentHomeBinding.initView() {
        pagerAdapter = HomePagerAdapter(this@HomeFragment)
        viewPager.adapter = pagerAdapter
        viewPager.isUserInputEnabled = false
        viewPager.offscreenPageLimit = pagerAdapter.itemCount
        viewPager.registerOnPageChangeCallback(pageChangeCallback)
        view.bind(viewPager)
        view.setElasticEnabled(true)

        liquidPurpleLeft.bind(viewPager)
        liquidPurpleRight.bind(viewPager)
        liquidPurpleLeft.setElasticEnabled(true)
        liquidPurpleRight.setElasticEnabled(true)
        liquidPurpleLeft.setTintColorRed(0.482f)
        liquidPurpleLeft.setTintColorGreen(0.361f)
        liquidPurpleLeft.setTintColorBlue(1f)
        liquidPurpleLeft.setTintAlpha(0.45f)
        liquidPurpleRight.setTintColorRed(0.482f)
        liquidPurpleRight.setTintColorGreen(0.361f)
        liquidPurpleRight.setTintColorBlue(1f)
        liquidPurpleRight.setTintAlpha(0.45f)
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
        mainViewModel.isGnss3DMode.observe(viewLifecycleOwner) {
            syncMode()
        }
    }

    private fun setMode(mode: MODE) {
        if (currentMode == mode) return

        currentMode = mode
        binding.applyMode(mode)
    }

    private fun syncMode() {
        val mode = when (binding.viewPager.currentItem) {
            0 -> {
                if (mainViewModel.isGnss3DMode.value == true) {
                    MODE.MAP_3D
                } else {
                    MODE.MAP_2D
                }
            }

            1 -> MODE.HOME_OPTICAL

            else -> MODE.MAP_2D
        }

        setMode(mode)
    }

    private fun FragmentHomeBinding.applyMode(mode: MODE) {
        when (mode) {
            MODE.MAP_2D -> {
                liquidPurpleLeft.show()
                liquidPurpleRight.hide()
                earthButton.setColorFilter(Color.BLACK)
                opticalFlowButton.setColorFilter(Color.BLACK)
            }

            MODE.MAP_3D -> {
                liquidPurpleLeft.hide()
                liquidPurpleRight.hide()
                earthButton.setColorFilter(Color.WHITE)
                opticalFlowButton.setColorFilter(Color.WHITE)
                view.setBackgroundResource(R.drawable.bg_blue_gradient_40_left)
            }

            MODE.HOME_OPTICAL -> {
                liquidPurpleLeft.hide()
                liquidPurpleRight.show()
                earthButton.setColorFilter(Color.WHITE)
                opticalFlowButton.setColorFilter(Color.WHITE)
            }
        }
    }

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            super.onPageSelected(position)

            mainViewModel.currentTab.value = position
            syncMode()
        }
    }

    override fun onBack() = Unit

    override fun onDestroyView() {
        binding.viewPager.unregisterOnPageChangeCallback(pageChangeCallback)
        super.onDestroyView()
    }
}
