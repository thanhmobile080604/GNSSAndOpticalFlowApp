package com.example.opticalflowapp.velocity_estimator.inter

import com.example.opticalflowapp.model.OFOutput
import org.opencv.core.Mat

interface OpticalFlow {
    fun run(new_frame: Mat): OFOutput?
    fun reset_motion_vector()
    fun UpdateFeatures()
    fun set_sensitivity(value: Int)
}
