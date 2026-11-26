package com.tunisianprayertimes.remembrance

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.adhkar.DeathRemembranceTexts
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h640dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeathRemembranceNotificationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun scheduledNotificationsOpenTheirOwnHeadlineDeedAndSource() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        context.getSharedPreferences("death_remembrance", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", true).putString("frequency", "daily").commit()
        val manager = context.getSystemService(NotificationManager::class.java)
        val dates = listOf(
            LocalDate.of(2026, 12, 1) to "muslim_976b",
            LocalDate.of(2026, 12, 2) to "al_hadid_7",
            LocalDate.of(2026, 12, 3) to "tirmidhi_2307",
        )
        for ((day, id) in dates) {
            val midnight = day.atStartOfDay(ZoneId.of("Africa/Tunis")).toInstant()
            val moment = requireNotNull(DeathReminder.next(context, midnight))
            assertEquals(day, moment.day)
            DeathReminder.refresh(context, moment.at)
            val notification = manager.activeNotifications.single { it.notification.channelId == DeathReminder.CHANNEL_ID }.notification
            val item = DeathRemembranceTexts.ALL.single { it.id == id }
            assertEquals(item.headline, notification.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertEquals(item.deed.trimEnd('.'), notification.extras.getCharSequence(Notification.EXTRA_TEXT))
            assertEquals(item.deed.trimEnd('.'), notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
            val tap = shadowOf(notification.contentIntent).savedIntent
            assertEquals(DeathRemembranceActivity::class.java.name, tap.component?.className)
            assertReadingOnCard(tap, item)
        }
    }

    @Test
    fun aDateOnlyNotificationFromThe28DayRotationStillOpensItsOriginalReading() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val oldTap = Intent(context, DeathRemembranceActivity::class.java)
            .putExtra("com.tunisianprayertimes.remembrance.EPOCH_DAY", LocalDate.of(2026, 11, 25).toEpochDay())
        assertReadingOnCard(oldTap, DeathRemembranceTexts.ALL.single { it.id == "ibnmajah_4171" })
    }

    private fun assertReadingOnCard(intent: Intent, item: DeathRemembranceTexts.Remembrance) {
        val activity = Robolectric.buildActivity(DeathRemembranceActivity::class.java, intent).setup()
        try {
            compose.onNodeWithText(item.headline).assertIsDisplayed()
            compose.onNodeWithText(item.deed).assertIsDisplayed()
            compose.onNodeWithText(item.reference).performScrollTo().assertIsDisplayed()
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
