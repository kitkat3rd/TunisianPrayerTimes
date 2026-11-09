package com.tunisianprayertimes

internal fun formatArabicMinutes(value: Int, standalone: Boolean = false): String {
    val count = value.coerceAtLeast(0)
    return when (count) {
        0 -> "0 دقيقة"
        1 -> "دقيقة واحدة"
        2 -> if (standalone) "دقيقتان" else "دقيقتين"
        else -> if (count % 100 in 3..10) "$count دقائق" else "$count دقيقة"
    }
}

internal fun formatArabicHours(value: Int): String = when (value) {
    1 -> "ساعة"
    2 -> "ساعتين"
    in 3..10 -> "$value ساعات"
    else -> "$value ساعة"
}

/** Whole hours, then the minutes left: «20 دقيقة», «ساعة», «ساعتين و15 دقيقة». */
internal fun formatArabicDuration(totalMinutes: Int): String {
    val count = totalMinutes.coerceAtLeast(0)
    val hours = count / 60
    val minutes = count % 60
    return when {
        hours == 0 -> formatArabicMinutes(minutes)
        minutes == 0 -> formatArabicHours(hours)
        else -> "${formatArabicHours(hours)} و${formatArabicMinutes(minutes)}"
    }
}

/** The duration after «بـ»: joined to a word («بساعة و30 دقيقة»), apart before a digit («بـ 20 دقيقة»). */
internal fun formatArabicByDuration(totalMinutes: Int): String {
    val duration = formatArabicDuration(totalMinutes)
    return if (duration.first().isDigit()) "بـ $duration" else "ب$duration"
}
