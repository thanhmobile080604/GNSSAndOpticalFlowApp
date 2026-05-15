package com.example.gnssandopticalflowapp.util.liquidglass.impl

import android.graphics.Canvas

interface Impl {
    fun onSizeChanged(w: Int, h: Int)
    fun onPreDraw()
    fun draw(canvas: Canvas)
    fun dispose() = Unit
}
