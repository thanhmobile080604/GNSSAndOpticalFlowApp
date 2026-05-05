package com.example.gnssandopticalflowapp.common

import android.content.Context
import android.content.res.Resources
import android.os.SystemClock
import android.view.View
import androidx.fragment.app.Fragment


fun Fragment.checkIfFragmentAttached(operation: Context.() -> Unit) {
    if (isAdded && context != null) {
        operation(requireContext())
    }
}

fun Fragment.safeContext(): Context {
    return if (isAdded && context != null) {
        requireContext()
    } else {
        throw IllegalStateException("Fragment not attached to context")
    }
}

val Int.dp: Int
    get() = (this * Resources.getSystem().displayMetrics.density + 0.5f).toInt()

fun View.setSingleClick(
    clickSpendTime: Long = 500L,
    execution: () -> Unit
) {
    setOnClickListener(object : View.OnClickListener {
        var lastClickTime: Long = 0
        override fun onClick(p0: View?) {
            if (SystemClock.elapsedRealtime() - lastClickTime < clickSpendTime) {
                return
            }
            lastClickTime = SystemClock.elapsedRealtime()
            execution.invoke()
        }
    })
}

fun View?.show() {
    this?.visibility = View.VISIBLE
}

fun View?.hide() {
    this?.visibility = View.GONE
}



