package com.example.gnssandopticalflowapp.screen.dialog

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