package com.example.gnssandopticalflowapp.screen.dialog

import android.content.Intent
import android.provider.Settings
import androidx.fragment.app.FragmentActivity
import com.example.gnssandopticalflowapp.base.BaseDialogFragment
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.DialogNoGpsBinding

class NoGPSDialog :
    BaseDialogFragment<DialogNoGpsBinding>(DialogNoGpsBinding::inflate) {

    override fun DialogNoGpsBinding.initView() {
        isCancelable = true
    }

    override fun DialogNoGpsBinding.initListener() {
        tvOpenSetting.setSingleClick {
            openGpsSetting()
        }
    }

    override fun initObserver() = Unit

    private fun openGpsSetting() {
        val fragmentContext = context ?: return

        val gpsIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        runCatching {
            fragmentContext.startActivity(gpsIntent)
            dismissAllowingStateLoss()
        }.onFailure {
            runCatching {
                fragmentContext.startActivity(fallbackIntent)
                dismissAllowingStateLoss()
            }
        }
    }

    companion object {
        private const val TAG = "NoGPSDialog"

        fun show(activity: FragmentActivity) {
            val fragmentManager = activity.supportFragmentManager
            if (fragmentManager.findFragmentByTag(TAG) == null) {
                NoGPSDialog().show(fragmentManager, TAG)
            }
        }

        fun dismiss(activity: FragmentActivity) {
            val fragmentManager = activity.supportFragmentManager
            val dialog = fragmentManager.findFragmentByTag(TAG) as? NoGPSDialog
            dialog?.dismissAllowingStateLoss()
        }
    }
}