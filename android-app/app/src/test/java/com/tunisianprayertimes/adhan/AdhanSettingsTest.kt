package com.tunisianprayertimes.adhan

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.R
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = Application::class)
class AdhanSettingsTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("adhan_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun allPrayersStartOffAndBulkEnableIncludesFriday() {
        assertTrue(AdhanSettings.enabledPrayers(context).isEmpty())

        AdhanSettings.setAllEnabled(context, true)

        assertEquals(AdhanSettings.prayers, AdhanSettings.enabledPrayers(context))
        assertTrue(AdhanSettings.isEnabled(context, Prayer.JOMOAA))
        assertFalse(AdhanSettings.isEnabled(context, Prayer.AID_FITR))
        assertEquals(context.getString(R.string.adhan_summary_all), AdhanSettings.summary(context))
    }

    @Test
    fun bulkDisableClearsAMixedSelectionAndKeepsSavedVoices() {
        AdhanSettings.setVoice(context, Prayer.FAJR, AdhanVoice.FAJR_ABDULMAJEED_SURAYHI)
        AdhanSettings.setVoice(context, Prayer.DHUHR, AdhanVoice.ADHAN_NASSER_AL_QATAMI)
        AdhanSettings.setEnabled(context, Prayer.FAJR, true)
        AdhanSettings.setEnabled(context, Prayer.JOMOAA, true)

        AdhanSettings.setAllEnabled(context, false)

        assertTrue(AdhanSettings.enabledPrayers(context).isEmpty())
        assertEquals(context.getString(R.string.adhan_summary_off), AdhanSettings.summary(context))

        AdhanSettings.setAllEnabled(context, true)

        assertEquals(AdhanVoice.FAJR_ABDULMAJEED_SURAYHI, AdhanSettings.voice(context, Prayer.FAJR))
        assertEquals(AdhanVoice.ADHAN_NASSER_AL_QATAMI, AdhanSettings.voice(context, Prayer.DHUHR))
    }

    @Test
    fun individualPrayersCanStillBeChangedAfterBulkEnable() {
        AdhanSettings.setAllEnabled(context, true)

        AdhanSettings.setEnabled(context, Prayer.DHUHR, false)

        assertEquals(AdhanSettings.prayers - Prayer.DHUHR, AdhanSettings.enabledPrayers(context))
        assertTrue(AdhanSettings.isEnabled(context, Prayer.JOMOAA))
    }

    @Test
    fun theWarningIsOnUntilTurnedOff() {
        assertTrue(AdhanSettings.warnsBefore(context))
        AdhanSettings.setWarnsBefore(context, false)
        assertFalse(AdhanSettings.warnsBefore(context))
    }

    @Test
    fun cancellingFromTheWarningSkipsOnlyThatAdhanAndForgetsOldOnes() {
        val day = LocalDate.of(2026, 11, 15)
        AdhanSettings.setEnabled(context, Prayer.DHUHR, true)

        AdhanSettings.skip(context, Prayer.DHUHR, day)

        assertTrue(AdhanSettings.isSkipped(context, Prayer.DHUHR, day))
        assertFalse(AdhanSettings.isSkipped(context, Prayer.DHUHR, day.plusDays(1)))
        assertFalse(AdhanSettings.isSkipped(context, Prayer.ASR, day))
        assertTrue(AdhanSettings.isEnabled(context, Prayer.DHUHR))

        AdhanSettings.skip(context, Prayer.ASR, day.plusDays(5))

        assertFalse(AdhanSettings.isSkipped(context, Prayer.DHUHR, day))
        assertTrue(AdhanSettings.isSkipped(context, Prayer.ASR, day.plusDays(5)))
    }

    @Test
    fun everyRecordingLabelIsJustTheMuezzinsName() {
        val names = mapOf(
            AdhanVoice.ADHAN_ISLAM_SOBHI to "إسلام صبحي",
            AdhanVoice.ADHAN_OMAR_HISHAM_AL_ARABI to "عمر هشام العربي",
            AdhanVoice.ADHAN_ABDULLAH_AL_ZAILI to "عبد الله الزائلي",
            AdhanVoice.ADHAN_MADINAH_MARWAN_QASSAS to "محمد مروان قصاص",
            AdhanVoice.ADHAN_NASSER_AL_QATAMI to "ناصر القطامي",
            AdhanVoice.FAJR_ALI_MULLA to "علي أحمد ملا",
            AdhanVoice.FAJR_MARWAN_QASSAS to "محمد مروان قصاص",
            AdhanVoice.FAJR_ABDULMAJEED_SURAYHI to "عبد المجيد السريحي",
        )
        assertEquals(AdhanVoice.entries.toSet(), names.keys)
        names.forEach { (voice, name) -> assertEquals(name, AdhanSettings.voiceTitle(context, voice)) }
    }
}
