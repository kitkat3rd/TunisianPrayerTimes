package com.tunisianprayertimes.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-xhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HifzAudioStripTest {
    @get:Rule val compose = createComposeRule()
    private val audio = mutableStateOf(HifzAudio("تدريب", "دورك", playing = true))
    private var next = 0
    private var pauses = 0
    private var stops = 0

    private fun show(fontScale: Float = 1f) {
        compose.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                AdhkarTheme {
                    HifzAudioStrip(audio.value, HifzAudioControls(
                        pause = { pauses++ }, resume = {}, stop = { stops++ }, nextRepetition = { next++ },
                    ))
                }
            }
        }
    }

    @Test fun nextRepetitionIsASeparateActionAndDisappearsWhenRecitationResumes() {
        audio.value = audio.value.copy(nextRepetitionLabel = "التكرار التالي")
        show()
        compose.onNodeWithTag("hifz_audio_next_repetition").assertIsDisplayed().assertTextEquals("التكرار التالي").performClick()
        assertEquals(1, next)
        assertEquals(0, pauses)
        assertEquals(0, stops)
        compose.onNodeWithTag("hifz_audio_toggle").assertIsDisplayed()
        compose.onNodeWithTag("hifz_audio_stop").assertIsDisplayed()
        compose.runOnIdle { audio.value = audio.value.copy(nextRepetitionLabel = null) }
        compose.onNodeWithTag("hifz_audio_next_repetition").assertDoesNotExist()
    }

    @Test fun theFinalTurnOffersToFinishAndFitsWithLargeText() {
        audio.value = audio.value.copy(nextRepetitionLabel = "إنهاء التدريب")
        show(fontScale = 2f)
        compose.onNodeWithTag("hifz_audio_next_repetition").assertIsDisplayed().assertTextEquals("إنهاء التدريب").performClick()
        assertEquals(1, next)
    }

    @Test fun ordinaryRecitationHasNoNextRepetitionAction() {
        show()
        compose.onNodeWithTag("hifz_audio_next_repetition").assertDoesNotExist()
    }

    @Test fun pausedOrDownloadingAudioNeverOffersToAdvanceTheDrill() {
        audio.value = audio.value.copy(playing = false, nextRepetitionLabel = "التكرار التالي")
        show()
        compose.onNodeWithTag("hifz_audio_next_repetition").assertDoesNotExist()
        compose.runOnIdle { audio.value = audio.value.copy(playing = true, waiting = true) }
        compose.onNodeWithTag("hifz_audio_next_repetition").assertDoesNotExist()
    }
}
