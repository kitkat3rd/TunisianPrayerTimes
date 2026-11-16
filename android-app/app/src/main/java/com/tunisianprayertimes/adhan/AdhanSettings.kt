package com.tunisianprayertimes.adhan

import android.content.Context
import android.content.SharedPreferences
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.R
import java.time.LocalDate

/**
 * Which prayers have their adhan played when their time comes, and in which voice. Each prayer is set on its own and
 * starts off: the app silences phones at prayer, so no sound comes until the user asks for one. Jumu'a has its own
 * switch, apart from Dhuhr, because whoever prays it is usually already in the mosque at its adhan.
 */
object AdhanSettings {
    private const val PREFS = "adhan_settings"
    private const val KEY_WARN_BEFORE = "warn_before"
    private const val KEY_SKIPPED = "skipped_occurrences"

    /** The prayers of the settings, in the day's order. On a Friday Jumu'a stands in Dhuhr's place (A18). */
    val prayers: List<Prayer> = listOf(Prayer.FAJR, Prayer.DHUHR, Prayer.JOMOAA, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA)

    /** The voices [prayer] offers: Fajr's say «الصلاة خير من النوم», the other prayers' do not ([AdhanVoice]). */
    fun voices(prayer: Prayer): List<AdhanVoice> = AdhanVoice.entries.filter { it.forFajr == (prayer == Prayer.FAJR) }

    fun defaultVoice(prayer: Prayer): AdhanVoice = voices(prayer).first()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context, prayer: Prayer): Boolean =
        prayer in prayers && prefs(context).getBoolean(enabledKey(prayer), false)

    fun enabledPrayers(context: Context): List<Prayer> = prayers.filter { isEnabled(context, it) }

    fun setEnabled(context: Context, prayer: Prayer, enabled: Boolean) {
        require(prayer in prayers) { "No adhan setting for $prayer" }
        prefs(context).edit().putBoolean(enabledKey(prayer), enabled).apply()
        AdhanScheduler.refreshLater(context)
    }

    /** Updates every prayer together, including Jumu'a, without changing any saved voice. */
    fun setAllEnabled(context: Context, enabled: Boolean) {
        val editor = prefs(context).edit()
        prayers.forEach { editor.putBoolean(enabledKey(it), enabled) }
        editor.apply()
        AdhanScheduler.refreshLater(context)
    }

    /** The saved voice of [prayer], or its first one when none is saved or the saved one is not for it. */
    fun voice(context: Context, prayer: Prayer): AdhanVoice =
        prefs(context).getString(voiceKey(prayer), null)
            ?.let { name -> runCatching { AdhanVoice.valueOf(name) }.getOrNull() }
            ?.takeIf { it in voices(prayer) }
            ?: defaultVoice(prayer)

    fun setVoice(context: Context, prayer: Prayer, voice: AdhanVoice) {
        require(voice in voices(prayer)) { "$voice is not an adhan of $prayer" }
        prefs(context).edit().putString(voiceKey(prayer), voice.name).apply()
    }

    fun voiceTitle(context: Context, voice: AdhanVoice): String = context.getString(voice.titleResId)

    /**
     * A countdown shortly before each enabled adhan, even when it will stay quiet. The warning vibrates only when
     * the adhan will sound, so it can be cancelled first (owner's idea, 2026-11-15). On unless turned off.
     */
    fun warnsBefore(context: Context): Boolean = prefs(context).getBoolean(KEY_WARN_BEFORE, true)

    fun setWarnsBefore(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_WARN_BEFORE, on).apply()
        AdhanScheduler.refreshLater(context)
    }

    /** The user cancelled this one adhan from its warning; the prayer's switch stays on. */
    fun skip(context: Context, prayer: Prayer, prayerDay: LocalDate) {
        val kept = skipped(context).filter { key ->
            // Older cancellations are of no use any more.
            key.substringAfter('@').toLongOrNull()?.let { it >= prayerDay.toEpochDay() - 2 } == true
        }
        prefs(context).edit().putStringSet(KEY_SKIPPED, (kept + skipKey(prayer, prayerDay)).toSet()).apply()
    }

    fun isSkipped(context: Context, prayer: Prayer, prayerDay: LocalDate): Boolean = skipKey(prayer, prayerDay) in skipped(context)

    private fun skipped(context: Context): Set<String> = prefs(context).getStringSet(KEY_SKIPPED, null).orEmpty()

    /** The warning listens here, so «إلغاء هذا الأذان» in its notification ends it at once. */
    fun registerListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)

    private fun skipKey(prayer: Prayer, prayerDay: LocalDate) = "${prayer.name}@${prayerDay.toEpochDay()}"

    /** The line under «الأذان» in the prayer tab's settings: which prayers have it. */
    fun summary(context: Context): String {
        val on = enabledPrayers(context)
        return when (on.size) {
            0 -> context.getString(R.string.adhan_summary_off)
            prayers.size -> context.getString(R.string.adhan_summary_all)
            else -> on.joinToString("، ") { prayerName(context, it) }
        }
    }

    fun prayerName(context: Context, prayer: Prayer): String = context.getString(
        when (prayer) {
            Prayer.FAJR -> R.string.prayer_fajr
            Prayer.DHUHR -> R.string.prayer_dhuhr
            Prayer.ASR -> R.string.prayer_asr
            Prayer.MAGHRIB -> R.string.prayer_maghrib
            Prayer.ISHA -> R.string.prayer_isha
            Prayer.JOMOAA -> R.string.prayer_jomoaa
            Prayer.AID_FITR -> R.string.prayer_aid_fitr
            Prayer.AID_ADHA -> R.string.prayer_aid_adha
        },
    )

    private fun enabledKey(prayer: Prayer) = "enabled_${prayer.name}"
    private fun voiceKey(prayer: Prayer) = "voice_${prayer.name}"
}
