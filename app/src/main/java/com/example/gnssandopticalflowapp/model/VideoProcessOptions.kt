package com.example.gnssandopticalflowapp.model

data class VideoProcessOptions(
    val isMoving: Boolean,
    val useFarneback: Boolean,
    val sensitivity: Int
)
