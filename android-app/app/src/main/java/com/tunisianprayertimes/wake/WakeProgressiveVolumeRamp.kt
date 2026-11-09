package com.tunisianprayertimes.wake

internal object WakeProgressiveVolumeRamp {
    const val DURATION_MILLIS = 120_000L
    const val FALLBACK_STEP_INTERVAL_MILLIS = 100L

    const val START_RINGTONE_VOLUME = 0.05f
    const val VOLUME_INCREASE_STEPS = 10
    private const val END_RINGTONE_VOLUME = 1f

    /** Start at 5%, then hold 10%, 20%, ... 100% for one configured interval each. */
    fun ringtoneVolumeAfterStep(step: Int): Float =
        if (step <= 0) START_RINGTONE_VOLUME else step.coerceAtMost(VOLUME_INCREASE_STEPS) / 10f

    fun ringtoneVolumeAt(elapsedMillis: Long, durationMillis: Long = DURATION_MILLIS): Float {
        val boundedDuration = durationMillis.coerceAtLeast(1L)
        val boundedElapsed = elapsedMillis.coerceIn(0L, boundedDuration)
        val fraction = boundedElapsed / boundedDuration.toFloat()
        return START_RINGTONE_VOLUME + ((END_RINGTONE_VOLUME - START_RINGTONE_VOLUME) * fraction)
    }
}
