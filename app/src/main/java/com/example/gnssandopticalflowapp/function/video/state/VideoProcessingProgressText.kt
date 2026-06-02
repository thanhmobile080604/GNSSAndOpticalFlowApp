package com.example.gnssandopticalflowapp.function.video.state

object VideoProcessingProgressText {
    const val DEFAULT_PERCENT = 0
    const val COMPLETE_PERCENT = 100

    private val percentRegex = Regex("""(\d{1,3})\s*%""")

    fun format(percent: Int): String {
        return "Processing: ${percent.coerceIn(DEFAULT_PERCENT, COMPLETE_PERCENT)}%"
    }

    fun normalize(message: String?, fallbackPercent: Int = DEFAULT_PERCENT): String {
        val percent = extractPercent(message) ?: fallbackPercent
        val trimmedMessage = message.orEmpty().trim()
        if (trimmedMessage.isBlank() || trimmedMessage == format(percent)) {
            return format(percent)
        }
        return if (extractPercent(trimmedMessage) != null) {
            trimmedMessage
        } else {
            "$trimmedMessage (${percent.coerceIn(DEFAULT_PERCENT, COMPLETE_PERCENT)}%)"
        }
    }

    fun extractPercent(message: String?): Int? {
        return percentRegex.find(message.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.coerceIn(DEFAULT_PERCENT, COMPLETE_PERCENT)
    }
}
