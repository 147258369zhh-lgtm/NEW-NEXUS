package io.github.mangi.eta.ui.app

import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.temporal.ChronoUnit

internal object ConversationTimeLabels {
    fun label(
        timestampMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault(),
        use24HourClock: Boolean = true,
        yesterdayLabel: String = "Yesterday",
        recentLabel: String = "Recent",
    ): String = Formatter(nowMillis, locale, timeZone, use24HourClock, yesterdayLabel, recentLabel)
        .label(timestampMillis)

    /** One immutable date context per list projection; formatters stay local to its thread. */
    class Formatter(
        private val nowMillis: Long,
        private val locale: Locale,
        private val timeZone: TimeZone,
        private val use24HourClock: Boolean,
        private val yesterdayLabel: String,
        private val recentLabel: String,
    ) {
        private val zoneId = timeZone.toZoneId()
        private val nowDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        private val patterns = mutableMapOf<String, SimpleDateFormat>()
        private val fullDate by lazy { DateFormat.getDateInstance(DateFormat.MEDIUM, locale).also { it.timeZone = timeZone } }

        fun label(timestampMillis: Long): String {
            if (timestampMillis <= 0L) return recentLabel
            val targetDate = Instant.ofEpochMilli(timestampMillis).atZone(zoneId).toLocalDate()
            val dayDelta = ChronoUnit.DAYS.between(targetDate, nowDate).toInt()

            return when {
                dayDelta <= 0 -> format(if (use24HourClock) "HH:mm" else "h:mm a", timestampMillis)
                dayDelta == 1 -> yesterdayLabel
                dayDelta in 2..6 -> format("EEE", timestampMillis)
                targetDate.year == nowDate.year -> format(sameYearPattern(locale), timestampMillis)
                else -> fullDate.format(Date(timestampMillis))
            }
        }

        private fun format(pattern: String, millis: Long): String = patterns.getOrPut(pattern) {
            SimpleDateFormat(pattern, locale).also { it.timeZone = timeZone }
        }.format(Date(millis))
    }

    private fun sameYearPattern(locale: Locale): String = when (locale.language) {
        Locale.CHINESE.language -> "M月d日"
        Locale.JAPANESE.language -> "M月d日"
        Locale.KOREAN.language -> "M월 d일"
        else -> "MMM d"
    }
}
