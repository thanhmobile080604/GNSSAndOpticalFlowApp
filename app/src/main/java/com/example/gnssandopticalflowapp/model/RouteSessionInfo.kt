package com.example.gnssandopticalflowapp.model

data class RouteSessionInfo(
    override val path: String,
    override val timestamp: Long,
    val destinationName: String,
    val durationMs: Long,
    val outagePointCount: Int
) : MediaInfo()
