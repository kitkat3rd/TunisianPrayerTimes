package com.tunisianprayertimes.adhan

import android.content.Context
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.R

/**
 * Which prayers have their adhan played when their time comes, and in which voice. Each prayer is set on its own and
 * starts off: the app silences phones at prayer, so no sound comes until the user asks for one. Jumu'a has its own
 * switch, apart from Dhuhr, because whoever prays it is usually already in the mosque at its adhan.
 */
object AdhanSettings {
    private const val PREFS = "adhan_settings"

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
