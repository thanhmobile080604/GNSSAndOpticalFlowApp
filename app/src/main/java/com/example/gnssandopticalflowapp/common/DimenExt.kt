package com.example.gnssandopticalflowapp.common

import android.content.res.Resources
import android.util.TypedValue

object DimenExt {
    // Chuyển đổi từ DP sang pixel
    fun getDp(number: Number): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            number.toFloat(),
            Resources.getSystem().displayMetrics
        ).toInt()
    }

    // Chuyển đổi từ SP sang pixel
    fun getSp(number: Number): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            number.toFloat(),
            Resources.getSystem().displayMetrics
        ).toInt()
    }

    // Trả về giá trị DP dưới dạng float
    fun getDpf(number: Number): Float {
        return getDp(number).toFloat()
    }

    // Trả về giá trị SP dưới dạng float
    fun getSpf(number: Number): Float {
        return getSp(number).toFloat()
    }
}