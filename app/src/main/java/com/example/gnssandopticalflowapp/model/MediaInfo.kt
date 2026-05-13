package com.example.gnssandopticalflowapp.model

sealed class MediaInfo {
    abstract val path: String
    abstract val timestamp: Long
}
