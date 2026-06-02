package com.example.gnssandopticalflowapp.function.optical_flow.interfaces

interface FrameProgressAwareOpticalFlow {
    fun updateFrameProgress(frameNumber: Long, totalFrames: Long)
}
