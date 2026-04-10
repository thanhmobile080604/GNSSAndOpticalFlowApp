package com.example.gnssandopticalflowapp.screen.dialog

import android.content.Intent
import android.provider.Settings
import androidx.fragment.app.FragmentActivity
import com.example.gnssandopticalflowapp.base.BaseDialogFragment
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.DialogNoLocationBinding

class NoLocationDialog :
    BaseDialogFragment<DialogNoLocationBinding>(DialogNoLocationBinding::inflate) {

    override fun DialogNoLocationBinding.initView() = Unit

    override fun DialogNoLocationBinding.initListener() {
        tvOpenSetting.setSingleClick {
            openLocationSetting()
        }
    }

    override fun initObserver() = Unit

    private fun openLocationSetting() {
        val context = context ?: return

        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        runCatching {
            startActivity(intent)
            dismissAllowingStateLoss()
        }.onFailure {
            val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                startActivity(fallbackIntent)
                dismissAllowingStateLoss()
            }
        }
    }

    companion object {
        private const val TAG = "NoLocationDialog"

        fun show(activity: FragmentActivity) {
            val fm = activity.supportFragmentManager
            if (fm.findFragmentByTag(TAG) == null) {
                NoLocationDialog().show(fm, TAG)
            }
        }

        fun dismiss(activity: FragmentActivity) {
            val fm = activity.supportFragmentManager
            val fragment = fm.findFragmentByTag(TAG)
            if (fragment is NoLocationDialog) {
                fragment.dismissAllowingStateLoss()
            }
        }
    }
}