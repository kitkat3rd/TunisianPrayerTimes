package com.tunisianprayertimes.mosque

import android.content.Context

@Suppress("UNUSED_PARAMETER")
internal inline fun <T> traceObservationTrigger(context: Context, noinline trigger: (MosqueObservationDiagnostics.() -> Unit)?,
    reconcile: () -> T): T = reconcile()

@Suppress("UNUSED_PARAMETER", "NOTHING_TO_INLINE")
internal inline fun activeMosqueDiagnostics(context: Context): MosqueObservationDiagnostics? = null
