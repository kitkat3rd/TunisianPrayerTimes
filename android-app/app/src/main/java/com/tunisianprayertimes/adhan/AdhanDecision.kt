package com.tunisianprayertimes.adhan

/**
 * Whether an adhan whose time has come sounds, kept free of Android so the rule can be tested.
 *
 * Every app silence takes priority, including this prayer's silence starting at the same time as its adhan.
 * The adhan never lifts or delays silence. The user's silent, vibrate or Do Not Disturb mode and calls also keep it quiet.
 * So does an alarm that is ringing, and a ring volume at zero, each with its own notice: on 15 Nov 2026 the OnePlus
 * played a Fajr adhan muted by a zero volume for its whole 4 min 21 s, and its notification gave no reason.
 */
internal object AdhanDecision {
    /** An adhan later than this does not sound: a late call to prayer is a wrong one. The quiet notice still shows. */
    const val LATE_SOUND_MILLIS = 3 * 60_000L

    /** Later than this, nothing shows either: the phone was off or asleep when the time came. */
    const val LATE_NOTICE_MILLIS = 30 * 60_000L

    /** The app's automatic silence at the adhan's time. */
    enum class AppSilence {
        /** None is on. */
        NONE,

        /** Automatic silence is active, or its time-only window is already due. */
        AUTOMATIC,

        /** Combined mode silenced the phone because the user is at a mosque. */
        AT_MOSQUE,
    }

    enum class Outcome {
        PLAY,

        QUIET_SILENT_PHONE,

        /**
         * The app's prayer silence had begun, or begins with the adhan: the user is, or will be, at the mosque, where
         * the adhan must not ring (owner, 2026-11-15). Someone who goes after the adhan starts the silence later.
         */
        QUIET_PRAYER_SILENCE,

        /** The ringer is on, but the adhan's volume reads zero: playing would be heard by nobody. */
        QUIET_ZERO_VOLUME,
        QUIET_IN_CALL,

        /** An alarm (the app's wake alarm or another app's) is ringing: the two would sound over each other. */
        QUIET_ALARM_RINGING,
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
        /** Ringer not normal, or a Do Not Disturb filter. */
        val phoneQuiet: Boolean,
        /** The volume the adhan plays at (the ring volume) reads zero. */
        val volumeZero: Boolean,
        /** A player with the alarm usage is playing. */
        val alarmRinging: Boolean,
    )

    fun decide(situation: Situation): Outcome = with(situation) {
        when {
            !enabled || lateMillis > LATE_NOTICE_MILLIS -> Outcome.NOTHING
            lateMillis > LATE_SOUND_MILLIS -> Outcome.QUIET_LATE
            inCall -> Outcome.QUIET_IN_CALL
            userChoseAppSilence -> Outcome.QUIET_SILENT_PHONE
            appSilence != AppSilence.NONE -> Outcome.QUIET_PRAYER_SILENCE
            phoneQuiet -> Outcome.QUIET_SILENT_PHONE
            // A silent phone also reads zero; that case has its own reason above.
            volumeZero -> Outcome.QUIET_ZERO_VOLUME
            alarmRinging -> Outcome.QUIET_ALARM_RINGING
            else -> Outcome.PLAY
        }
    }

    /**
     * A due time-only window also blocks playback before its silence alarm has been delivered. Combined mode
     * follows actual mosque silence instead of treating the entire observation window as silenced.
     */
    fun appSilence(
        autoActive: Boolean,
        combined: Boolean,
        windowActive: Boolean,
    ): AppSilence = when {
        combined && autoActive -> AppSilence.AT_MOSQUE
        autoActive || (!combined && windowActive) -> AppSilence.AUTOMATIC
        else -> AppSilence.NONE
    }
}
