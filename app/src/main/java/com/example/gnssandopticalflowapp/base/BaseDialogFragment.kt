package com.example.gnssandopticalflowapp.base

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.annotation.IdRes
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.viewbinding.ViewBinding
import com.example.gnssandopticalflowapp.MainViewModel
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.common.checkIfFragmentAttached

abstract class BaseDialogFragment<DialogBinding : ViewBinding>(
    private val bindingInflater: (LayoutInflater, ViewGroup?, Boolean) -> DialogBinding
) : DialogFragment() {

    protected lateinit var binding: DialogBinding
    protected val navController by lazy { findNavController() }
    protected val mainViewModel by activityViewModels<MainViewModel>()

    protected open val isFullscreen: Boolean = true

    protected open val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            onBackPressed()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            isCancelable = false
        }
    }

    override fun getTheme(): Int {
        return if (isFullscreen) R.style.DialogFullScreen else R.style.DialogModal
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
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

    override fun onStart() {
        super.onStart()
        setupDialogWindow()
    }

    override fun onResume() {
        super.onResume()
        setupDialogWindow()
    }

    private fun setupDialogWindow() {
        val window = dialog?.window ?: return

        window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        window.setWindowAnimations(R.style.FadeTransition)

        window.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        window.decorView.setPadding(0, 0, 0, 0)

        if (isFullscreen) {
            setupEdgeToEdgeSystemBars(window)
        }
    }

    private fun setupEdgeToEdgeSystemBars(window: Window) {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        val controller = WindowInsetsControllerCompat(window, window.decorView)

        controller.isAppearanceLightStatusBars = false

        controller.isAppearanceLightNavigationBars = false

        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    }

    private val navOptions = NavOptions.Builder()
        .setEnterAnim(R.anim.enter_from_right)
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
        backPressedCallback.remove()
        super.onDestroyView()
    }

    open fun onBackPressed() {
        checkIfFragmentAttached {
            navController.navigateUp()
        }
    }
}