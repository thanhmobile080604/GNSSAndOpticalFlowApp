package com.example.gnssandopticalflowapp.screen.dialog

import androidx.fragment.app.FragmentManager
import com.example.gnssandopticalflowapp.base.BaseDialogFragment
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.DialogVideoProcessOptionsBinding
import com.example.gnssandopticalflowapp.model.VideoProcessOptions

class VideoProcessOptionsDialog :
    BaseDialogFragment<DialogVideoProcessOptionsBinding>(DialogVideoProcessOptionsBinding::inflate) {

    var onApplyOptions: ((VideoProcessOptions) -> Unit)? = null

    override fun DialogVideoProcessOptionsBinding.initView() {
        isCancelable = true
        switchMoving.isChecked = false
        switchAlgorithm.isChecked = false
        updateMovingValue(isMoving = false)
        updateAlgorithmValue(useFarneback = false)
    }

    override fun DialogVideoProcessOptionsBinding.initListener() {
        switchMoving.setOnCheckedChangeListener { _, isChecked ->
            updateMovingValue(isMoving = isChecked)
        }

        switchAlgorithm.setOnCheckedChangeListener { _, isChecked ->
            updateAlgorithmValue(useFarneback = isChecked)
        }

        tvCancel.setSingleClick {
            dismissAllowingStateLoss()
        }

        tvApply.setSingleClick {
            val options = VideoProcessOptions(
                isMoving = switchMoving.isChecked,
                useFarneback = switchAlgorithm.isChecked
            )
            onApplyOptions?.invoke(options)
            dismissAllowingStateLoss()
        }
    }

    override fun initObserver() = Unit

    override fun onBackPressed() {
        dismissAllowingStateLoss()
    }

    override fun onDestroyView() {
        onApplyOptions = null
        super.onDestroyView()
    }

    private fun updateMovingValue(isMoving: Boolean) {
        binding.tvMovingValue.text = if (isMoving) "Moving" else "Not Moving"
    }

    private fun updateAlgorithmValue(useFarneback: Boolean) {
        binding.tvAlgorithmValue.text = if (useFarneback) "Farneback" else "KLT"
    }

    companion object {
        private const val TAG = "VideoProcessOptionsDialog"

        fun show(
            fragmentManager: FragmentManager,
            onApplyOptions: (VideoProcessOptions) -> Unit
        ) {
            if (fragmentManager.findFragmentByTag(TAG) != null) return

            VideoProcessOptionsDialog().apply {
                this.onApplyOptions = onApplyOptions
            }.show(fragmentManager, TAG)
        }
    }
}
