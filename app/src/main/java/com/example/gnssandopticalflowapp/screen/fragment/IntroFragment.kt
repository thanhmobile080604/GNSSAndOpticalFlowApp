package com.example.gnssandopticalflowapp.screen.fragment

import android.graphics.Color
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.CompositePageTransformer
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.adapter.IntroImageAdapter
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.dp
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentIntroBinding
import kotlin.math.abs

class IntroFragment : BaseFragment<FragmentIntroBinding>(FragmentIntroBinding::inflate) {

    private val introImages = listOf(
        R.drawable.img_map,
        R.drawable.img_gnss,
        R.drawable.img_optical_flow
    )

    private val introPageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            binding.introDots.setSelectedDot(position)
        }
    }

    override fun FragmentIntroBinding.initView() {
        mainViewModel.isResolvingDeviceSettings.value = true
        setupIntroCarousel()
        introDots.setSelectedDot(0)
    }

    private fun FragmentIntroBinding.setupIntroCarousel() {
        introImagePager.adapter = IntroImageAdapter(introImages)
        introImagePager.offscreenPageLimit = 3
        introImagePager.clipToPadding = false
        introImagePager.clipChildren = false
        introImagePager.getChildAt(0)?.let { child ->
            child.overScrollMode = View.OVER_SCROLL_NEVER
            if (child is RecyclerView) {
                child.clipToPadding = false
                child.clipChildren = false
                child.setPadding(68.dp, 0, 68.dp, 0)
            }
        }
        introImagePager.setPageTransformer(
            CompositePageTransformer().apply {
                addTransformer(MarginPageTransformer(10.dp))
                addTransformer { page, position ->
                    val distance = abs(position).coerceAtMost(1f)
                    val scale = 0.82f + (1f - distance) * 0.18f
                    page.scaleX = scale
                    page.scaleY = scale
                    page.alpha = 0.72f + (1f - distance) * 0.28f
                    page.translationZ = (1f - distance) * 8f
                }
            }
        )
        introDots.setDotCount(introImages.size)
        introDots.setColors(
            selectedColor = Color.WHITE,
            unselectedColor = Color.argb(90, 255, 255, 255)
        )
        introImagePager.registerOnPageChangeCallback(introPageChangeCallback)
        introImagePager.setCurrentItem(0, false)
    }

    override fun FragmentIntroBinding.initListener() {
        btnStartIntro.setSingleClick {
            navigateTo(R.id.homeFragment, inclusive = true)
        }
    }

    override fun initObserver() = Unit

    override fun onBack() = Unit

    override fun onDestroyView() {
        binding.introImagePager.unregisterOnPageChangeCallback(introPageChangeCallback)
        super.onDestroyView()
    }
}
