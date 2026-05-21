package com.example.gnssandopticalflowapp.video

object VideoProcessingProgressText {
    const val DEFAULT_PERCENT = 0
    const val COMPLETE_PERCENT = 100

    private val percentRegex = Regex("""(\d{1,3})\s*%""")

    fun format(percent: Int): String {
        return "Processing: ${percent.coerceIn(DEFAULT_PERCENT, COMPLETE_PERCENT)}%"
    }

    fun normalize(message: String?, fallbackPercent: Int = DEFAULT_PERCENT): String {
        return format(extractPercent(message) ?: fallbackPercent)
    }

    fun extractPercent(message: String?): Int? {
        return percentRegex.find(message.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.coerceIn(DEFAULT_PERCENT, COMPLETE_PERCENT)
    }
}
