package com.example.gnssandopticalflowapp.screen.dialog

import androidx.fragment.app.FragmentManager
import com.example.gnssandopticalflowapp.base.BaseDialogFragment
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.DialogErrorGnssBinding

class ErrorGNSSDialog :
    BaseDialogFragment<DialogErrorGnssBinding>(DialogErrorGnssBinding::inflate) {

    override fun DialogErrorGnssBinding.initView() {
        isCancelable = true
    }

    override fun DialogErrorGnssBinding.initListener() {
        tvOpenSetting.setSingleClick {
            dismissAllowingStateLoss()
        }
    }

    override fun initObserver() = Unit

    override fun onBackPressed() {
        dismissAllowingStateLoss()
    }

    companion object {
        private const val TAG = "ErrorGNSSDialog"

        fun show(fragmentManager: FragmentManager) {
            if (fragmentManager.findFragmentByTag(TAG) == null) {
                ErrorGNSSDialog().show(fragmentManager, TAG)
            }
        }
    }
}
