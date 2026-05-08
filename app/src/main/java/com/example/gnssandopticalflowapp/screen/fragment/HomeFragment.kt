package com.example.gnssandopticalflowapp.screen.fragment

import android.annotation.SuppressLint
import androidx.core.content.ContextCompat
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

    override fun initObserver() = Unit

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            super.onPageSelected(position)
            mainViewModel.currentTab.value = position
            updateTabState()
        }
    }

    private fun updateTabState() = with(binding) {
        view.background = ContextCompat.getDrawable(root.context, R.drawable.bg_bottom_nav_glass)
    }

    override fun onBack() = Unit

    override fun onDestroyView() {
        binding.viewPager.unregisterOnPageChangeCallback(pageChangeCallback)
        super.onDestroyView()
    }
}
