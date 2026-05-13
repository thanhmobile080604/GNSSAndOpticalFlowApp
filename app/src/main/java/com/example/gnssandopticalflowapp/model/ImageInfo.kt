package com.example.gnssandopticalflowapp.model

data class ImageInfo(
    override val path: String,
    override val timestamp: Long
) : MediaInfo()
