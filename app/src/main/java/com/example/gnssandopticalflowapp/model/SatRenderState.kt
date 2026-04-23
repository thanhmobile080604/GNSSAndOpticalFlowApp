package com.example.gnssandopticalflowapp.model

data class SatRenderState(
    var rX: Float,
    var rY: Float,
    var rZ: Float,
    var tX: Float,
    var tY: Float,
    var tZ: Float,
    var info: SatelliteInfo
)