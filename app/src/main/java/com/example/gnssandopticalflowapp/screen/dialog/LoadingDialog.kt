package com.example.gnssandopticalflowapp.screen.dialog

import com.example.gnssandopticalflowapp.base.BaseDialogFragment
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.DialogLoadingBinding

class LoadingDialog() :
    BaseDialogFragment<DialogLoadingBinding>(DialogLoadingBinding::inflate) {

    var cancelCallback: (() -> Unit)? = null

    override fun DialogLoadingBinding.initView() = Unit

    override fun DialogLoadingBinding.initListener() {
        btnCancel.setSingleClick {
            cancelCallback?.invoke()
        }
    }

    override fun initObserver() = Unit
}