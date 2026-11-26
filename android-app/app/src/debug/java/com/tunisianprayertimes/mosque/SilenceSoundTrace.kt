package com.tunisianprayertimes.mosque

import android.content.Context
import android.util.Log
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.trace.SilenceTraceStore
import com.tunisianprayertimes.wake.displayName

internal inline fun observeSilenceSound(context: Context, event: SilenceSoundEvents.() -> Unit) {
    if (SilenceTraceStore.activeId(context) == null) return
    try {
        RecordingSoundEvents.event()
    } catch (_: RuntimeException) {
        Log.w("SilenceSoundEvents", "Could not record sound event")
    }
}

private fun AutomaticSilencePause.description(context: Context) = context.getString(when (this) {
    AutomaticSilencePause.ANDROID_AUTO -> R.string.walk_context_car
    AutomaticSilencePause.AWAKE_CHECK -> R.string.walk_context_alarm
})

internal object RecordingSoundEvents : SilenceSoundEvents {
    // The coordinator delivers all rule callbacks under its serial lock.
    private val pauses = mutableMapOf<SilenceOwner, Pair<Long, AutomaticSilencePause>>()

    override fun requested(context: Context, owner: SilenceOwner, prayer: Prayer?) {
        SilenceTraceStore.append(context, "silence_requested", prayer?.displayName(context) ?: context.getString(owner.title))
    }

    override fun restored(context: Context, owner: SilenceOwner) {
        SilenceTraceStore.append(context, "sound_restore_requested", context.getString(owner.title))
    }

    override fun wakeTakeover(context: Context) {
        SilenceTraceStore.append(context, "silence_requested", "استمرار الإسكات حتى المنبّه")
    }

    override fun legacyPause(context: Context, reason: AutomaticSilencePause) {
        if (reason == AutomaticSilencePause.ANDROID_AUTO && PrefsManager.wasAutoSilenceSkippedForAndroidAuto(context)) return
        SilenceTraceStore.append(context, "silence_paused", reason.description(context))
    }

    override fun manualWriteFailed(context: Context) {
        SilenceTraceStore.append(context, "sound_write_failed", "رفض أندرويد طلب الإسكات اليدوي")
    }

    override fun rulePaused(context: Context, owner: SilenceOwner, reason: AutomaticSilencePause) {
        val session = SilenceTraceStore.activeId(context) ?: return
        val pause = session to reason
        if (pauses[owner] == pause) return
        val detail = "${context.getString(owner.title)} · ${reason.description(context)}"
        if (SilenceTraceStore.append(context, "silence_paused", detail, sessionId = session)) pauses[owner] = pause
    }

    override fun clearRulePause(owner: SilenceOwner) {
        pauses.remove(owner)
    }

    override fun ruleWritten(context: Context, owner: SilenceOwner, desired: Boolean) {
        SilenceTraceStore.append(context, if (desired) "rule_requested" else "rule_released", context.getString(owner.title))
    }

    override fun ruleWriteFailed(context: Context, owner: SilenceOwner, desired: Boolean, failure: Exception) {
        val action = if (desired) "تعذّر طلب التفعيل" else "تعذّر طلب الإيقاف"
        SilenceTraceStore.append(context, "rule_write_failed", "$action · ${context.getString(owner.title)} · ${failure.javaClass.simpleName}")
    }

    override fun ruleStatus(context: Context, owner: SilenceOwner, status: Int) {
        val change = when (status) {
            1 -> "مُمكّنة"
            2 -> "مُعطّلة"
            3 -> "محذوفة"
            4 -> "نشطة"
            5 -> "غير نشطة"
            else -> return
        }
        SilenceTraceStore.append(context, "rule_status", "${context.getString(owner.title)} · $change")
    }
}
