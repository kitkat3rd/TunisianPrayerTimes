package com.tunisianprayertimes.mosque

import android.content.Context
import com.tunisianprayertimes.Prayer

internal enum class AutomaticSilencePause { ANDROID_AUTO, AWAKE_CHECK }

/** Build variants supply an optional observer for audio writes and Android rule feedback. */
internal interface SilenceSoundEvents {
    fun requested(context: Context, owner: SilenceOwner, prayer: Prayer? = null)
    fun restored(context: Context, owner: SilenceOwner)
    fun wakeTakeover(context: Context)
    fun legacyPause(context: Context, reason: AutomaticSilencePause)
    fun manualWriteFailed(context: Context)
    fun rulePaused(context: Context, owner: SilenceOwner, reason: AutomaticSilencePause)
    fun clearRulePause(owner: SilenceOwner)
    fun ruleWritten(context: Context, owner: SilenceOwner, desired: Boolean)
    fun ruleWriteFailed(context: Context, owner: SilenceOwner, desired: Boolean, failure: Exception)
    fun ruleStatus(context: Context, owner: SilenceOwner, status: Int)
}
