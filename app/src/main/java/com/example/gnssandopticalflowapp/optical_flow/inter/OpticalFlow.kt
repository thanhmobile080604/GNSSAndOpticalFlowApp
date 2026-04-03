package com.example.gnssandopticalflowapp.optical_flow.inter

import com.example.gnssandopticalflowapp.model.OFOutput
import org.opencv.core.Mat

interface OpticalFlow {
    fun run(newFrame: Mat): OFOutput?
    fun resetMotionVector()
    fun updateFeatures()
    fun setSensitivity(value: Int)
}
