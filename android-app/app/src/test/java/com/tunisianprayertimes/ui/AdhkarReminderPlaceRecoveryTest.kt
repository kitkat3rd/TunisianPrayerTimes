package com.tunisianprayertimes.ui

import android.app.Application
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.work.testing.WorkManagerTestInitHelper
import com.tunisianprayertimes.FirstRunPlaceChoice
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.adhkar.DhikrReminder
import com.tunisianprayertimes.place.PrayerPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdhkarReminderPlaceRecoveryTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun returningToAPrayerRelativeDraftRevalidatesItAndWaitsForSave() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        controller.setup()
        try {
            WorkManagerTestInitHelper.initializeTestWorkManager(activity.applicationContext)
            PrefsManager.initializeFirstRun(activity)
            PrefsManager.completeFirstRun(activity, FirstRunPlaceChoice.Browse(PrayerPlace.Inm(615), null))
            val original = salawatDhikrPreset()
            var saved: DhikrReminder? = null
            activity.setContent {
                AdhkarTheme {
                    DhikrReminderEditor(activity, original, true, remember { SnackbarHostState() },
                        onDismiss = {}, onDirtyChange = {}, onSave = { rule, _ -> saved = rule })
                }
            }
            compose.onNodeWithContentDescription("زيادة العدد").performScrollTo().performClick()
            val save = compose.onNodeWithTag("adhkar_save_reminder")
            save.performClick()
            compose.onNodeWithTag("feature_cancel_place").performClick()
            assertFalse(PrefsManager.hasConfirmedPlace(activity))
            assertNull(saved)

            save.performClick()
            compose.onNodeWithTag("feature_confirm_suggested_place").performClick()
            compose.waitUntil { PrefsManager.hasConfirmedPlace(activity) }
            compose.onNodeWithTag("adhkar_editor_error").assertDoesNotExist()
            assertNull(saved)
            save.performClick()
            assertEquals(original.id, saved?.id)
            assertEquals(original.targetCount + 1, saved?.targetCount)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
