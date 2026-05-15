package com.example.gnssandopticalflowapp.util.liquidglass

class Config {
    var CORNER_RADIUS_PX: Float = 40f
    var ECCENTRIC_FACTOR: Float = 0f
    var REFRACTION_HEIGHT: Float = 20f
    var REFRACTION_OFFSET: Float = -70f
    var CONTRAST: Float = 0f
    var WHITE_POINT: Float = 0f
    var CHROMA_MULTIPLIER: Float = 1f
    var BLUR_RADIUS: Float = 0.01f
    var DISPERSION: Float = 0.5f
    var DEPTH_EFFECT: Float = 0f
    var TINT_COLOR_RED: Float = 1f
    var TINT_COLOR_GREEN: Float = 1f
    var TINT_COLOR_BLUE: Float = 1f
    var TINT_ALPHA: Float = 0f
    var WIDTH: Float = 0f
    var HEIGHT: Float = 0f

    fun configure(overrides: Overrides): Config = apply {
        overrides.applyTo(this)
    }

    class Overrides {
        private var noFilter = false
        private var cornerRadiusPx: Float? = null
        private var eccentricFactor: Float? = null
        private var refractionHeight: Float? = null
        private var refractionOffset: Float? = null
        private var contrast: Float? = null
        private var whitePoint: Float? = null
        private var chromaMultiplier: Float? = null
        private var blurRadius: Float? = null
        private var dispersion: Float? = null
        private var depthEffect: Float? = null
        private var tintColorRed: Float? = null
        private var tintColorGreen: Float? = null
        private var tintColorBlue: Float? = null
        private var tintAlpha: Float? = null
        private var width: Float? = null
        private var height: Float? = null

        fun noFilter(): Overrides = apply {
            noFilter = true
        }

        fun cornerRadius(value: Float): Overrides = apply {
            cornerRadiusPx = value
        }

        fun eccentricFactor(value: Float): Overrides = apply {
            eccentricFactor = value
        }

        fun refractionHeight(value: Float): Overrides = apply {
            refractionHeight = value
        }

        fun refractionOffset(value: Float): Overrides = apply {
            refractionOffset = value
        }

        fun contrast(value: Float): Overrides = apply {
            contrast = value
        }

        fun whitePoint(value: Float): Overrides = apply {
            whitePoint = value
        }

        fun chromaMultiplier(value: Float): Overrides = apply {
            chromaMultiplier = value
        }

        fun blurRadius(value: Float): Overrides = apply {
            blurRadius = value
        }

        fun dispersion(value: Float): Overrides = apply {
            dispersion = value
        }

        fun depthEffect(value: Float): Overrides = apply {
            depthEffect = value
        }

        fun tintColorRed(value: Float): Overrides = apply {
            tintColorRed = value
        }

        fun tintColorGreen(value: Float): Overrides = apply {
            tintColorGreen = value
        }

        fun tintColorBlue(value: Float): Overrides = apply {
            tintColorBlue = value
        }

        fun tintAlpha(value: Float): Overrides = apply {
            tintAlpha = value
        }

        fun size(width: Int, height: Int): Overrides = apply {
            this.width = width.toFloat()
            this.height = height.toFloat()
        }

        internal fun applyTo(config: Config) {
            if (noFilter) {
                config.CONTRAST = 0f
                config.WHITE_POINT = 0f
                config.CHROMA_MULTIPLIER = 1f
            }
            cornerRadiusPx?.let { config.CORNER_RADIUS_PX = it }
            eccentricFactor?.let { config.ECCENTRIC_FACTOR = it }
            refractionHeight?.let { config.REFRACTION_HEIGHT = it }
            refractionOffset?.let { config.REFRACTION_OFFSET = it }
            contrast?.let { config.CONTRAST = it }
            whitePoint?.let { config.WHITE_POINT = it }
            chromaMultiplier?.let { config.CHROMA_MULTIPLIER = it }
            blurRadius?.let { config.BLUR_RADIUS = it }
            dispersion?.let { config.DISPERSION = it }
            depthEffect?.let { config.DEPTH_EFFECT = it }
            tintColorRed?.let { config.TINT_COLOR_RED = it }
            tintColorGreen?.let { config.TINT_COLOR_GREEN = it }
            tintColorBlue?.let { config.TINT_COLOR_BLUE = it }
            tintAlpha?.let { config.TINT_ALPHA = it }
            width?.let { config.WIDTH = it }
            height?.let { config.HEIGHT = it }
        }
    }
}
