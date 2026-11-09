package com.tunisianprayertimes.adhan

import com.tunisianprayertimes.Prayer
import java.time.LocalDate

/**
 * Whether an adhan whose time has come sounds, kept free of Android so the rule can be tested.
 *
 * The rule, as I settled it: the adhan calls to a prayer that has not begun yet, so it sounds unless the phone was
 * already quiet when the time came. Quiet means the user's own silent, vibrate or Do Not Disturb, the app's manual
 * silence or silence until an alarm, a call, the app's prayer silence of another prayer or one that began before this
 * adhan (the user meant to be at the mosque by then), or the user being at a mosque (combined mode). The one silence
 * that does not count is this prayer's own, when it begins with its adhan: that silence waits for the adhan to end.
 */
internal object AdhanDecision {
    /** An adhan later than this does not sound: a late call to prayer is a wrong one. The quiet notice still shows. */
    const val LATE_SOUND_MILLIS = 3 * 60_000L

    /** Later than this, nothing shows either: the phone was off or asleep when the time came. */
    const val LATE_NOTICE_MILLIS = 30 * 60_000L

    /** A silence window that starts at most this long before the adhan began with it (alarm order, rounding). */
    const val OWN_WINDOW_TOLERANCE_MILLIS = 60_000L

    /** The app's automatic silence at the adhan's time. */
    enum class AppSilence {
        /** None is on. */
        NONE,

        /** Only this prayer's own window, which began with its adhan: it is lifted while the adhan plays. */
        OWN_FROM_ADHAN,

        /** Another window, or one that began before the adhan, or a silence kept on between windows. */
        OTHER,

        /** Combined mode silenced the phone because the user is at a mosque. */
        AT_MOSQUE,
    }

    enum class Outcome {
        PLAY,

        /** Lift the prayer's own silence, which waits for the adhan's end, then play. */
        LIFT_AND_PLAY,
        QUIET_SILENT_PHONE,
        QUIET_IN_CALL,
        QUIET_LATE,

        /** Neither sound nor notice. */
        NOTHING,
    }

    data class Situation(
        val enabled: Boolean,
        val lateMillis: Long,
        val inCall: Boolean,
        /** The app's manual silence or a silence until an alarm: both are the user's own choice of quiet. */
        val userChoseAppSilence: Boolean,
        val appSilence: AppSilence,
        /** Ringer not normal, or a Do Not Disturb filter, outside the app's automatic silence. */
        val phoneQuiet: Boolean,
    )

    fun decide(situation: Situation): Outcome = with(situation) {
        when {
            !enabled || lateMillis > LATE_NOTICE_MILLIS -> Outcome.NOTHING
            // At the mosque the real adhan is heard: no notice either.
            appSilence == AppSilence.AT_MOSQUE -> Outcome.NOTHING
            lateMillis > LATE_SOUND_MILLIS -> Outcome.QUIET_LATE
            inCall -> Outcome.QUIET_IN_CALL
            userChoseAppSilence || appSilence == AppSilence.OTHER -> Outcome.QUIET_SILENT_PHONE
            appSilence == AppSilence.OWN_FROM_ADHAN -> Outcome.LIFT_AND_PLAY
            phoneQuiet -> Outcome.QUIET_SILENT_PHONE
            else -> Outcome.PLAY
        }
    }

    /** One silence window of the app that contains now, as the decision needs it. */
    data class Window(val prayer: Prayer, val prayerDay: LocalDate, val startMillis: Long)

    /**
     * The app's automatic silence for the adhan of [prayer] on [prayerDay] at [adhanMillis]. [windows] are the
     * undismissed windows containing now; [autoActive] whether the automatic silence is on; [combined] whether it
     * follows presence at a mosque.
     */
    fun appSilence(
        autoActive: Boolean,
        combined: Boolean,
        windows: List<Window>,
        prayer: Prayer,
        prayerDay: LocalDate,
        adhanMillis: Long,
    ): AppSilence = when {
        !autoActive -> AppSilence.NONE
        combined -> AppSilence.AT_MOSQUE
        windows.isNotEmpty() && windows.all {
            it.prayer == prayer && it.prayerDay == prayerDay && it.startMillis >= adhanMillis - OWN_WINDOW_TOLERANCE_MILLIS
        } -> AppSilence.OWN_FROM_ADHAN
        else -> AppSilence.OTHER
    }
}
