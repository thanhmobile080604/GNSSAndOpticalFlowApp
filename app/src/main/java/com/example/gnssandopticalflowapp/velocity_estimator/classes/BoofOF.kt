package com.example.gnssandopticalflowapp.velocity_estimator.classes

import com.example.gnssandopticalflowapp.model.OFOutput
import com.example.gnssandopticalflowapp.velocity_estimator.inter.OpticalFlow
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
