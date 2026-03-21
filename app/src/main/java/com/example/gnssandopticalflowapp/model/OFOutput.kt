package com.example.opticalflowapp.model

import org.opencv.core.Mat
import org.opencv.core.Point

class OFOutput {
    @JvmField
    var of_frame: Mat? = null
    @JvmField
    var position: Point? = null
}
