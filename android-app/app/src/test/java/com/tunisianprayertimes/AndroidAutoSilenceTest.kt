package com.tunisianprayertimes

import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.MatrixCursor
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.mosque.SilenceCoordinator
import com.tunisianprayertimes.mosque.SilenceOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidAutoSilenceTest {
    private lateinit var context: Context
    private lateinit var notifications: NotificationManager
    private lateinit var audio: AudioManager
    private lateinit var projection: ProjectionProvider

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        projection = Robolectric.setupContentProvider(ProjectionProvider::class.java, AndroidAutoConnection.AUTHORITY)
        notifications = context.getSystemService(NotificationManager::class.java)
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audio = context.getSystemService(AudioManager::class.java)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        PrefsManager.setEnabled(context, true)
    }

    @Test
    fun prayerAlarmSkipsSilenceWhileProjectingAndCanStartAfterDisconnect() {
        projection.state = 2
        val start = Intent("com.tunisianprayertimes.ACTION_SILENCE").putExtra("extra_prayer", "ISHA")
        val receiver = SilenceReceiver()

        receiver.onReceive(context, start)

        assertFalse(PrefsManager.isAutoSilenceActive(context))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, notifications.currentInterruptionFilter)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)

        projection.state = 0
        receiver.onReceive(context, start)

        assertTrue(PrefsManager.isAutoSilenceActive(context))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, notifications.currentInterruptionFilter)
    }

    @Test
    fun connectingLiftsExistingPrayerSilenceAndRestoresPreviousFilter() {
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        assertTrue(SilenceModeController.enableAutoSilence(context))

        connect()

        assertFalse(PrefsManager.isAutoSilenceActive(context))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, notifications.currentInterruptionFilter)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
    }

    @Test
    fun manualSilenceWorksWhileProjecting() {
        connect()

        assertTrue(SilenceModeController.setManualSilent(context))
        assertFalse(SilenceModeController.enableAutoSilence(context))

        assertTrue(PrefsManager.isManualSilenceActive(context))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, notifications.currentInterruptionFilter)
    }

    @Test
    fun skippedPrayerEndKeepsDoNotDisturbSetAfterDisconnect() {
        projection.state = 2
        assertFalse(SilenceModeController.enableAutoSilence(context))
        projection.state = 0
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)

        SilenceReceiver().onReceive(context, Intent("com.tunisianprayertimes.ACTION_UNSILENCE"))

        assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, notifications.currentInterruptionFilter)
    }

    @Test
    fun projectionClearsOwnedPrayerRuleAndSuppressesAutomaticRulesWhileKeepingWakeRule() {
        assertTrue(SilenceCoordinator.enrollOrRepair(context))
        assertTrue(SilenceCoordinator.enableTime(context))
        assertTrue(SilenceCoordinator.requested(context, SilenceOwner.PRAYER))
        assertTrue(SilenceCoordinator.setWake(context, true))

        connect()

        assertTrue(SilenceCoordinator.enableTime(context))
        assertTrue(SilenceCoordinator.set(context, SilenceOwner.MOSQUE, true))
        assertFalse(SilenceCoordinator.requested(context, SilenceOwner.PRAYER))
        assertFalse(SilenceCoordinator.requested(context, SilenceOwner.MOSQUE))
        assertTrue(SilenceCoordinator.requested(context, SilenceOwner.WAKE))
    }

    private fun connect() {
        projection.state = 2
        context.sendBroadcast(Intent(AndroidAutoConnection.ACTION_UPDATED))
        shadowOf(Looper.getMainLooper()).idle()
    }

    class ProjectionProvider : ContentProvider() {
        var state = 0

        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?) =
            MatrixCursor(arrayOf(AndroidAutoConnection.STATE_COLUMN)).apply { addRow(arrayOf(state)) }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read only")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
            error("Read only")
    }
}
