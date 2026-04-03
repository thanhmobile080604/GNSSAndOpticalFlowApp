package com.example.gnssandopticalflowapp.optical_flow.classes

import com.example.gnssandopticalflowapp.model.OFOutput
import com.example.gnssandopticalflowapp.optical_flow.inter.OpticalFlow
import org.opencv.core.Mat

class BoofOF : OpticalFlow {
    override fun run(newFrame: Mat): OFOutput? {
        return null
    }

    public override fun resetMotionVector() {
    }

    public override fun updateFeatures() {
    }

    public override fun setSensitivity(value: Int) {
    }
}
