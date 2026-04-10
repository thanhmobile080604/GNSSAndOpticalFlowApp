package com.example.gnssandopticalflowapp.screen.dialog

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.fragment.app.FragmentActivity
import com.example.gnssandopticalflowapp.base.BaseDialogFragment
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.DialogNoLocationBinding

class NoLocationDialog :
    BaseDialogFragment<DialogNoLocationBinding>(DialogNoLocationBinding::inflate) {

    override fun DialogNoLocationBinding.initView() {
        isCancelable = true
    }

    override fun DialogNoLocationBinding.initListener() {
        tvOpenSetting.setSingleClick {
            openAppPermissionSetting()
        }
    }

    override fun initObserver() = Unit

    private fun openAppPermissionSetting() {
        val fragmentContext = context ?: return

        val appSettingIntent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", fragmentContext.packageName, null)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        runCatching {
            fragmentContext.startActivity(appSettingIntent)
            dismissAllowingStateLoss()
        }.onFailure {
            runCatching {
                fragmentContext.startActivity(fallbackIntent)
                dismissAllowingStateLoss()
            }
        }
    }

    companion object {
        private const val TAG = "NoLocationDialog"

        fun show(activity: FragmentActivity) {
            val fragmentManager = activity.supportFragmentManager
            if (fragmentManager.findFragmentByTag(TAG) == null) {
                NoLocationDialog().show(fragmentManager, TAG)
            }
        }

        fun dismiss(activity: FragmentActivity) {
            val fragmentManager = activity.supportFragmentManager
            val dialog = fragmentManager.findFragmentByTag(TAG) as? NoLocationDialog
            dialog?.dismissAllowingStateLoss()
        }
    }
}