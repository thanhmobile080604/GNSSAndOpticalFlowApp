package com.example.opticalflowapp.velocity_estimator.classes

import com.example.opticalflowapp.model.OFOutput
import com.example.opticalflowapp.velocity_estimator.inter.OpticalFlow
import org.opencv.core.Mat

class BoofOF : OpticalFlow {
    override fun run(new_frame: Mat): OFOutput? {
        return null
    }

    public override fun reset_motion_vector() {
    }

    public override fun UpdateFeatures() {
    }

    public override fun set_sensitivity(value: Int) {
    }
}
