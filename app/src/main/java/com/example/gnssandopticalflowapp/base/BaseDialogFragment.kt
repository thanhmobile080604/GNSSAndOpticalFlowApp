package com.example.gnssandopticalflowapp.base

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.annotation.IdRes
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.viewbinding.ViewBinding
import com.example.gnssandopticalflowapp.MainViewModel
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.common.checkIfFragmentAttached


abstract class BaseDialogFragment<DialogBinding : ViewBinding>(private val bindingInflater: (LayoutInflater, ViewGroup?, Boolean) -> DialogBinding) :
    DialogFragment() {

    protected lateinit var binding: DialogBinding
    protected val navController by lazy { findNavController() }
    protected val mainViewModel by activityViewModels<MainViewModel>()
    protected open val isFullscreen: Boolean = true

    protected open val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            onBackPressed()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = bindingInflater.invoke(inflater, container, false)
        activity?.onBackPressedDispatcher?.addCallback(viewLifecycleOwner, backPressedCallback)
        binding.initView()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.initListener()
        initObserver()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        isCancelable = false
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setWindowAnimations(R.style.FadeTransition)
        return dialog
    }

    override fun getTheme(): Int {
        return if (isFullscreen) R.style.DialogFullScreen else R.style.DialogModal
    }

    private val navOptions = NavOptions.Builder().setEnterAnim(R.anim.enter_from_right)
        .setExitAnim(R.anim.exit_to_left)
        .setPopEnterAnim(R.anim.enter_from_left)
        .setPopExitAnim(R.anim.exit_to_right)
        .build()

    protected fun navigateTo(@IdRes resId: Int) {
        checkIfFragmentAttached {
            findNavController().navigate(resId, null, navOptions)
        }
    }

    abstract fun DialogBinding.initView()

    abstract fun DialogBinding.initListener()

    abstract fun initObserver()


    override fun onDestroyView() {
        super.onDestroyView()
        backPressedCallback.remove()
    }

    open fun onBackPressed() {
        checkIfFragmentAttached {
            navController.navigateUp()
        }
    }

    override fun onResume() {
        super.onResume()
    }
}