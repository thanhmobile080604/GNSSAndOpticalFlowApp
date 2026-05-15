package com.example.gnssandopticalflowapp.util.liquidglass

import android.content.Context
import android.content.res.Resources
import android.graphics.Rect
import android.os.Build
import android.util.TypedValue
import android.view.WindowManager

object Utils {
    fun getDeviceWidthPx(context: Context): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowManager = context.getSystemService(WindowManager::class.java)
            val bounds: Rect? = windowManager?.currentWindowMetrics?.bounds
            if (bounds != null) return bounds.width()
        }

        return context.resources.displayMetrics.widthPixels
    }

    fun dp2px(resources: Resources, dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        )
    }
}
