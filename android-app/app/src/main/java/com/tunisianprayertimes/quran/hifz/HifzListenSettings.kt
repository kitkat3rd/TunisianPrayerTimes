package com.tunisianprayertimes.quran.hifz

import android.content.Context

/**
 * How «الاستماع إلى المواضع» plays, kept from one listening to the next as a music app keeps its repeat: each point
 * [times] times in a row, the list again from its first point after its last ([endless]), a silence after each
 * recitation to say it ([pause]), the points already held too ([mastered]).
 */
internal data class HifzListenSettings(
    val times: Int = 3,
    val endless: Boolean = true,
    val pause: Boolean = false,
    val mastered: Boolean = false,
) {
    val isValid: Boolean get() = times in HifzListenTimes

    companion object {
        /** One point heard once, as the review's «استمع» plays it. */
        val Once = HifzListenSettings(times = 1, endless = false)
        private const val PREFERENCES = "hifz_listen"

        fun read(context: Context): HifzListenSettings {
            val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val defaults = HifzListenSettings()
            return HifzListenSettings(
                times = prefs.getInt("times", defaults.times),
                endless = prefs.getBoolean("endless", defaults.endless),
                pause = prefs.getBoolean("pause", defaults.pause),
                mastered = prefs.getBoolean("mastered", defaults.mastered),
            ).takeIf { it.isValid } ?: defaults
        }

        fun write(context: Context, value: HifzListenSettings) {
            if (!value.isValid) return
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putInt("times", value.times).putBoolean("endless", value.endless)
                .putBoolean("pause", value.pause).putBoolean("mastered", value.mastered).apply()
        }
    }
}

/** The counts offered for each point: «مرة واحدة · مرتين · 3 مرات · 5 مرات». */
internal val HifzListenTimes = listOf(1, 2, 3, 5)

/**
 * The points «الاستماع إلى المواضع» recites from [points]: those not yet held, or all of them with
 * [HifzListenSettings.mastered]; all when none is held or all are, there being nothing to choose then.
 */
internal fun List<HifzPoint>.toHear(settings: HifzListenSettings): List<HifzPoint> {
    val learning = filterNot { it.mastered }
    return if (settings.mastered || learning.isEmpty()) this else learning
}
