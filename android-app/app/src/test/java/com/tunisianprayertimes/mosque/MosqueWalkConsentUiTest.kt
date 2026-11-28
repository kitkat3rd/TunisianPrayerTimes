package com.tunisianprayertimes.mosque

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w320dp-h640dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MosqueWalkConsentUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun defaultArabicTextShowsSharingChoice() = verifySharingChoice(1f)
    @Test fun enlargedArabicTextShowsSharingChoice() = verifySharingChoice(1.5f)

    private fun verifySharingChoice(scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                TunisianPrayerTimesTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) { MosqueParticipationSettings() }
                }
            }
        }
        val c = ApplicationProvider.getApplicationContext<Context>()
        for (label in listOf(R.string.mr_share_walks, R.string.mr_walk_consequence, R.string.mr_feedback)) {
            compose.onNodeWithText(c.getString(label), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
    }
}
