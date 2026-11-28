package com.tunisianprayertimes.access

import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

/** Run one inspection after a foreground frame and cancel it if the user leaves. */
internal suspend fun Lifecycle.inspectAccessWhenResumed(inspect: suspend () -> Unit) {
    repeatOnLifecycle(Lifecycle.State.RESUMED) {
        withFrameNanos { }
        inspect()
    }
}
