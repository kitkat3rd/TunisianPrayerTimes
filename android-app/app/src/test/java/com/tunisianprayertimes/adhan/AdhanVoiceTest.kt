package com.tunisianprayertimes.adhan

import com.tunisianprayertimes.Prayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Fajr recording says «الصلاة خير من النوم»: it must never play at another prayer, and Fajr must have one. */
class AdhanVoiceTest {
    @Test
    fun fajrOffersOnlyItsOwnRecordingsAndTheOtherPrayersNeverDo() {
        assertTrue(AdhanSettings.voices(Prayer.FAJR).isNotEmpty())
        assertTrue(AdhanSettings.voices(Prayer.FAJR).all { it.forFajr })
        for (prayer in AdhanSettings.prayers - Prayer.FAJR) {
            assertTrue("$prayer offers a Fajr recording", AdhanSettings.voices(prayer).none { it.forFajr })
        }
    }

    @Test
    fun everyRecordingIsOfferedByOneGroupAndEachDefaultIsItsGroupsFirst() {
        assertEquals(
            AdhanVoice.entries.toSet(),
            (AdhanSettings.voices(Prayer.FAJR) + AdhanSettings.voices(Prayer.DHUHR)).toSet(),
        )
        assertEquals(AdhanVoice.FAJR_ALI_MULLA, AdhanSettings.defaultVoice(Prayer.FAJR))
        assertEquals(AdhanVoice.ADHAN_ISLAM_SOBHI, AdhanSettings.defaultVoice(Prayer.MAGHRIB))
    }
}
