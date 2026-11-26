package com.tunisianprayertimes.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class PrayerHeroTime(
    val phoneClock: String,
    val placeClock: String?,
    val phoneDate: LocalDate?,
    val placeDate: LocalDate?,
    val tomorrow: Boolean,
)

private val heroClockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

/** Use the offsets at the actual adhan, including when a clock changes between now and the prayer. */
internal fun prayerHeroTime(at: Instant, now: Instant, phoneZone: ZoneId, placeZone: ZoneId): PrayerHeroTime {
    val phoneTime = at.atZone(phoneZone)
    val placeTime = at.atZone(placeZone)
    val phoneToday = now.atZone(phoneZone).toLocalDate()
    val differentOffsets = phoneTime.offset != placeTime.offset
    val showDates = differentOffsets && (
        phoneTime.toLocalDate() != placeTime.toLocalDate() || phoneToday != now.atZone(placeZone).toLocalDate()
    )
    return PrayerHeroTime(
        phoneClock = phoneTime.format(heroClockFormat),
        placeClock = placeTime.format(heroClockFormat).takeIf { differentOffsets },
        phoneDate = phoneTime.toLocalDate().takeIf { showDates },
        placeDate = placeTime.toLocalDate().takeIf { showDates },
        tomorrow = !showDates && phoneTime.toLocalDate() == phoneToday.plusDays(1),
    )
}
