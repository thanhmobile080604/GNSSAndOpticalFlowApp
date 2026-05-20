package com.example.gnssandopticalflowapp.optical_flow.interfaces

interface FrameProgressAwareOpticalFlow {
    fun updateFrameProgress(frameNumber: Long, totalFrames: Long)
}
