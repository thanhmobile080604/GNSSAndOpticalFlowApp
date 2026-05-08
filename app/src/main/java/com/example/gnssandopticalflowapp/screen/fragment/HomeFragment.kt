package com.example.gnssandopticalflowapp.screen.fragment

import android.annotation.SuppressLint
import android.graphics.Color
import androidx.viewpager2.widget.ViewPager2
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.adapter.HomePagerAdapter
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentHomeBinding

class HomeFragment : BaseFragment<FragmentHomeBinding>(FragmentHomeBinding::inflate) {

    private lateinit var pagerAdapter: HomePagerAdapter

    @SuppressLint("ClickableViewAccessibility")
    override fun FragmentHomeBinding.initView() {
        pagerAdapter = HomePagerAdapter(this@HomeFragment)
        viewPager.adapter = pagerAdapter
        viewPager.isUserInputEnabled = false
        viewPager.offscreenPageLimit = pagerAdapter.itemCount
        viewPager.registerOnPageChangeCallback(pageChangeCallback)
        updateTabState()
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
        mainViewModel.currentTab.observe(viewLifecycleOwner) {
            updateNavVisibility()
        }
    }

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            super.onPageSelected(position)
            mainViewModel.currentTab.value = position
            updateTabState()
        }
    }

    private fun updateTabState() = with(binding) {
        val selectedTab = mainViewModel.currentTab.value ?: 0
        view.setBackgroundResource(
            if (selectedTab == 0) R.drawable.bg_blue_gradient_40_left else R.drawable.bg_blue_gradient_40_right
        )
        updateTabIcons(selectedTab)
        updateNavVisibility()
    }

    private fun updateTabIcons(selectedTab: Int) = with(binding) {
        val selectedColor = Color.WHITE
        val idleColor = Color.rgb(173, 154, 223)

        earthButton.setColorFilter(if (selectedTab == 0) selectedColor else idleColor)
        opticalFlowButton.setColorFilter(if (selectedTab == 1) selectedColor else idleColor)
        earthButton.alpha = if (selectedTab == 0) 1f else 0.58f
        opticalFlowButton.alpha = if (selectedTab == 1) 1f else 0.58f
        earthButton.scaleX = if (selectedTab == 0) 1.06f else 0.94f
        earthButton.scaleY = if (selectedTab == 0) 1.06f else 0.94f
        opticalFlowButton.scaleX = if (selectedTab == 1) 1.06f else 0.94f
        opticalFlowButton.scaleY = if (selectedTab == 1) 1.06f else 0.94f
    }

    private fun updateNavVisibility() = with(binding) {
        view.visibility = android.view.View.VISIBLE
        earthButton.visibility = android.view.View.VISIBLE
        opticalFlowButton.visibility = android.view.View.VISIBLE
    }

    override fun onBack() = Unit

    override fun onDestroyView() {
        binding.viewPager.unregisterOnPageChangeCallback(pageChangeCallback)
        super.onDestroyView()
    }
}
