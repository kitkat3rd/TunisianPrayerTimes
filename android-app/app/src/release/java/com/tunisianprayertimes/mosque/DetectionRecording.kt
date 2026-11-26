@file:Suppress("UNUSED_PARAMETER", "NOTHING_TO_INLINE")

package com.tunisianprayertimes.mosque

import android.content.Context
import com.tunisianprayertimes.mosque.presence.AdaptiveObservation
import com.tunisianprayertimes.mosque.presence.PresenceFix

internal object DetectionRecording {
    inline fun awaitingReturn(token: String) = false
    inline fun acquiring(c: Context, token: String, engine: AdaptiveObservation, periods: MosquePeriods? = null,
        recheck: Boolean = false, prayerStart: Boolean = false, fix: PresenceFix? = null) = Unit
    inline fun settled(c: Context, token: String, engine: AdaptiveObservation) = Unit
    inline fun foreground(c: Context, token: String) = Unit
    inline fun serviceGone(c: Context, token: String?) = Unit
    inline fun stopped(c: Context) = Unit
}
