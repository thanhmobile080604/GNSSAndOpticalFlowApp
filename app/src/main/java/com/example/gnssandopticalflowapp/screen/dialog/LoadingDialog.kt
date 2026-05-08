package com.example.gnssandopticalflowapp.screen.dialog

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.WindowManager
import com.example.gnssandopticalflowapp.base.BaseDialogFragment
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.DialogLoadingBinding

class LoadingDialog() :
    BaseDialogFragment<DialogLoadingBinding>(DialogLoadingBinding::inflate) {

    var cancelCallback: (() -> Unit)? = null

    private var initialMessage: String? = null

    override fun DialogLoadingBinding.initView() {
        initialMessage?.let { tvLoadingMessage.text = it }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.TOP)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            decorView.setPadding(0, 0, 0, 0)
        }
    }

    fun setMessage(message: String) {
        if (::binding.isInitialized) {
            binding.tvLoadingMessage.text = message
        } else {
            initialMessage = message
        }
    }

    override fun DialogLoadingBinding.initListener() {
        btnCancel.setSingleClick {
            cancelCallback?.invoke()
        }
    }

    override fun initObserver() = Unit
}
