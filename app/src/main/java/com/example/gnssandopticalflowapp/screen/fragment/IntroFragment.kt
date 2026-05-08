package com.example.gnssandopticalflowapp.screen.fragment

import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.FragmentIntroBinding

class IntroFragment : BaseFragment<FragmentIntroBinding>(FragmentIntroBinding::inflate) {

    override fun FragmentIntroBinding.initView() {
        mainViewModel.isResolvingDeviceSettings.value = true
    }

    override fun FragmentIntroBinding.initListener() {
        btnStartIntro.setSingleClick {
            navigateTo(R.id.homeFragment, inclusive = true)
        }
    }

    override fun initObserver() = Unit

    override fun onBack() = Unit
}
