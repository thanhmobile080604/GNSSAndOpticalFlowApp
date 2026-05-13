package com.example.gnssandopticalflowapp.base

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import androidx.viewbinding.ViewBinding
import com.example.gnssandopticalflowapp.MainViewModel
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.common.checkIfFragmentAttached

abstract class BaseFragment<T : ViewBinding>(private val bindingInflater: (LayoutInflater, ViewGroup?, Boolean) -> T) :
    Fragment() {

    val TAG = javaClass.name

    protected lateinit var binding: T
        private set
    val mainViewModel: MainViewModel by activityViewModels()


    protected open val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            onBack()
        }
    }

    private val navOptions =
        NavOptions.Builder()
            .setEnterAnim(R.anim.fade_in)
            .setExitAnim(R.anim.fade_out)
            .setPopEnterAnim(R.anim.fade_in)
            .setPopExitAnim(R.anim.fade_out)
            .build()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View? {
        binding = bindingInflater.invoke(inflater, container, false)
        if (parentFragment is NavHostFragment) {
            activity?.onBackPressedDispatcher?.addCallback(viewLifecycleOwner, backPressedCallback)
        }
        binding.initView()
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.initListener()
        initObserver()
    }

    abstract fun T.initView()

    abstract fun T.initListener()

    abstract fun initObserver()

    /**
     * Back về màn trước đó có show ad
     */
    open fun onBack() {
        checkIfFragmentAttached {
            findNavController().navigateUp()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        backPressedCallback.remove()
    }

    protected fun navigateTo(
        id: Int, inclusive: Boolean = false, noAds: Boolean = false, complete: () -> Unit = {},
    ) {
        val action: () -> Unit = {
            complete.invoke()
            try {
                val navOptions = buildNavOptions(inclusive)
                findNavController().navigate(id, null, navOptions)
            } catch (e: Exception) {
                Log.e("NavigationError", "Navigation failed: $e")
                findNavController().navigate(id, null, navOptions)
            }
        }


        checkIfFragmentAttached { action() }
    }

    private fun buildNavOptions(inclusive: Boolean): NavOptions {
        return try {
            NavOptions.Builder().apply {
                val currentDestination = findNavController().currentDestination?.id

                if (inclusive && currentDestination != null) {
                    setPopUpTo(currentDestination, true)
                }

                setEnterAnim(R.anim.fade_in)
                setExitAnim(R.anim.fade_out)
                setPopEnterAnim(R.anim.fade_in)
                setPopExitAnim(R.anim.fade_out)
            }.build()
        } catch (e: Exception) {
            Log.e("NavigationError", "Navigation failed: $e")
            navOptions
        }
    }
}
