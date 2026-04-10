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
import androidx.navigation.fragment.findNavController
import androidx.viewbinding.ViewBinding
import com.example.gnssandopticalflowapp.MainViewModel
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.common.checkIfFragmentAttached
import com.example.gnssandopticalflowapp.screen.dialog.LoadingDialog

abstract class BaseFragment<T : ViewBinding>(private val bindingInflater: (LayoutInflater, ViewGroup?, Boolean) -> T) :
    Fragment() {

    val TAG = javaClass.name
    private val screenName =
        this::class.simpleName?.replace("Fragment", "")?.replace(Regex("([a-z])([A-Z])"), "$1_$2")
            ?.lowercase()?.plus("_screen") ?: "unknown_screen"

    protected lateinit var binding: T
        private set
    private val navController by lazy { findNavController() }
    val mainViewModel: MainViewModel by activityViewModels()

    var screenPlayTime: Long = 0L


    protected open val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            onBack()
        }
    }

    //    private val launcher =
//        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
//            val allowList = mutableListOf<String>()
//            it.forEach { (k, v) ->
//                if (!v) {
//                    if (!shouldShowRequestPermissionRationale(k)) {
//                        permissionListener?.onNeverAskAgain(k)
//                        return@registerForActivityResult
//                    }
//                } else {
//                    allowList.add(k)
//                }
//            }
//            if (allowList.isNotEmpty() && allowList.size == it.size) {
//                permissionListener?.onAllow()
//            } else {
//                permissionListener?.onDenied()
//            }
//        }
    private var permissionListener: IPermissionListener? = null
    private var lastRequestedPerms: Set<String> = emptySet()

    private val storagePerms = setOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        Manifest.permission.READ_EXTERNAL_STORAGE
    )

    private val launcher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val critical = lastRequestedPerms.filterNot { it in storagePerms }.toSet()
            val deniedCritical = critical.filter { results[it] != true }

            if (deniedCritical.isEmpty()) {
                permissionListener?.onAllow()
                return@registerForActivityResult
            }

            val neverAsk = deniedCritical.firstOrNull { !shouldShowRequestPermissionRationale(it) }
            if (neverAsk != null) {
                permissionListener?.onNeverAskAgain(neverAsk)
            } else {
                permissionListener?.onDenied()
            }
        }

    private val navOptions =
        NavOptions.Builder().setEnterAnim(R.anim.enter_from_right).setExitAnim(R.anim.exit_to_left)
            .setPopEnterAnim(R.anim.enter_from_left).setPopExitAnim(R.anim.exit_to_right).build()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View? {
        binding = bindingInflater.invoke(inflater, container, false)
        activity?.onBackPressedDispatcher?.addCallback(viewLifecycleOwner, backPressedCallback)
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

    private var loadingDialog: LoadingDialog? = null

    protected fun showLoadingDialog(message: String, onCancel: (() -> Unit)? = null) {
        if (loadingDialog == null || !loadingDialog!!.isAdded) {
            loadingDialog = LoadingDialog().apply {
                this.cancelCallback = {
                    onCancel?.invoke()
                    dismissLoadingDialog()
                }
            }
            loadingDialog?.show(childFragmentManager, "LoadingDialog")
        }
        loadingDialog?.setMessage(message)
    }

    protected fun dismissLoadingDialog() {
        if (loadingDialog?.isAdded == true) {
            loadingDialog?.dismissAllowingStateLoss()
        }
        loadingDialog = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dismissLoadingDialog()
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
        try {
            return NavOptions.Builder().apply {
                val currentDestination = findNavController().currentDestination?.id
                if (inclusive && currentDestination != null) {
                    setPopUpTo(currentDestination, true)
                }
                setEnterAnim(R.anim.enter_from_right)
                setExitAnim(R.anim.exit_to_left)
                setPopEnterAnim(R.anim.enter_from_left)
                setPopExitAnim(R.anim.exit_to_right)
            }.build()
        } catch (e: Exception) {
            Log.e("NavigationError", "Navigation failed: $e")
            return navOptions
        }
    }

    /**
     * Back về màn trước đó và không show ads
     */
    protected fun navigateUpScreen() {
        checkIfFragmentAttached {
            navController.navigateUp()
        }
    }

    /**
     * xin cấp quyền
     * @param permissions danh sách xin quyền
     * @param listener kết quả xử lý
     */
    fun doRequestPermission(
        permissions: Array<String>, listener: IPermissionListener,
    ) {
        permissionListener = listener
        launcher.launch(permissions)
    }

    /**
     * Mở Setting của thiết bị
     */
    fun openAppSettings() {
        try {
            if (context == null) return
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context?.packageName, null)
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    interface IPermissionListener {
        fun onAllow()
        fun onDenied() {}
        fun onNeverAskAgain(permission: String) {}
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }
}