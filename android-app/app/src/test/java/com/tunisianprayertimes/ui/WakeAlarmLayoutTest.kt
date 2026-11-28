package com.tunisianprayertimes.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.tunisianprayertimes.*
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Alarm labels must stay complete at the widths used by the editor and its nested sheets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w320dp-h800dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WakeAlarmLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: AppCompatActivity
    private var playback by mutableStateOf(WakePlaybackOptions(wakeUpCheckEnabled = true))

    private fun show(scale: Float, content: @Composable () -> Unit) {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        activity = controller.setup().get()
        activity.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(LocalDensity.current.density, scale),
            ) {
                TunisianPrayerTimesTheme(fontFamily = AppFontFamily, content = content)
            }
        }
        compose.waitForIdle()
    }

    private fun assertLabelsFit() {
        val problems = mutableListOf<String>()
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        repeat(nodes.fetchSemanticsNodes().size) { index ->
            val layouts = mutableListOf<TextLayoutResult>()
            nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            layouts.forEach { layout ->
                val text = layout.layoutInput.text.text
                if (layout.didOverflowHeight || layout.getLineEnd(layout.lineCount - 1) != text.length || layout.splitsWord(text)) {
                    problems += "Incomplete or split label: $text (${layout.size}, ${layout.lineCount} lines, " +
                        "height=${layout.multiParagraph.height}, overflow=${layout.didOverflowHeight}, " +
                        "end=${layout.getLineEnd(layout.lineCount - 1)}, split=${layout.splitsWord(text)})"
                }
                val extraWidth = (layout.multiParagraph.width - layout.size.width).coerceAtLeast(0f)
                for (line in 0 until layout.lineCount) {
                    val rtl = layout.getParagraphDirection(layout.getLineStart(line)) == ResolvedTextDirection.Rtl
                    val offset = when (layout.layoutInput.style.textAlign) {
                        TextAlign.Center -> extraWidth / 2f
                        TextAlign.Left -> 0f
                        TextAlign.Right -> extraWidth
                        TextAlign.End -> if (rtl) 0f else extraWidth
                        else -> if (rtl) extraWidth else 0f
                    }
                    if (layout.isLineEllipsized(line) || layout.getLineLeft(line) - offset < -1f ||
                        layout.getLineRight(line) - offset > layout.size.width + 1f) {
                        problems += "Clipped label: $text (${layout.size}, line $line)"
                    }
                }
            }
        }
        assertTrue(problems.distinct().joinToString("\n"), problems.isEmpty())
    }

    private fun screenshot(name: String) {
        val bitmap = compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().last { it.isAttachedToWindow && it.width > 0 }
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        File("build/reports/wake-layout/$name.png").apply {
            parentFile!!.mkdirs()
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap.recycle()
    }

    private fun sound(scale: Float) {
        show(scale) {
            Column(Modifier.width(232.dp).verticalScroll(rememberScrollState())) {
                WakeSoundControls("نغمة المنبه الإضافي", playback) { playback = it }
            }
        }
        screenshot("sound-$scale")
        assertLabelsFit()
    }

    private fun editor(mode: WakeMainAlarmMode, scale: Float = 2f) {
        show(scale) {
            WakeEditorSheet(activity, 1,
                PrayerWakeConfig(id = "layout", prayer = Prayer.FAJR, enabled = true,
                    mainAlarm = WakeMainAlarmConfig(mode = mode), playback = playback),
                isNewAlarm = true, onDismissRequest = {}, onSave = {})
        }
        screenshot("editor-$mode-$scale")
        assertLabelsFit()
        compose.onNodeWithText(activity.getString(R.string.wake_editor_behavior_section_title)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(activity.getString(R.string.wake_editor_challenge_edit)).performScrollTo().performClick()
        compose.waitForIdle()
        assertLabelsFit()
        if (mode == WakeMainAlarmMode.FIXED_TIME && scale == 2f) {
            compose.onNodeWithText("صعب").performScrollTo().performClick()
            assertLabelsFit()
            screenshot("challenge-large")
        }
    }

    @Test fun nestedSoundLabelsFitAtNormalSize() = sound(1f)
    @Test fun nestedSoundLabelsFitAtLargeSize() = sound(2f)
    @Test fun fixedAlarmEditorFitsLargeText() = editor(WakeMainAlarmMode.FIXED_TIME)
    @Test fun fixedAlarmEditorFitsNormalText() = editor(WakeMainAlarmMode.FIXED_TIME, 1f)
    @Test fun prayerAlarmEditorFitsLargeText() = editor(WakeMainAlarmMode.PRAYER_RELATIVE)
    @Test fun fromNowEditorFitsLargeText() = editor(WakeMainAlarmMode.FROM_NOW)
    @Test fun fromNowEditorFitsNormalText() = editor(WakeMainAlarmMode.FROM_NOW, 1f)
    @Test fun fromNowEditorFitsMediumText() = editor(WakeMainAlarmMode.FROM_NOW, 1.3f)

    @Test fun extraAlarmEditorFitsLargeTextWithSoundAndChallengeExpanded() {
        show(2f) {
            WakeExtraAlarmEditor(PrayerWakeSubAlarm("extra", 15, OffsetDirection.BEFORE, playback),
                isNew = true, mainPlayback = playback, timeTextFor = { "12:59" },
                onSave = {}, onDelete = null, onDismiss = {})
        }
        compose.onNodeWithText(activity.getString(R.string.wake_editor_extra_sound)).performScrollTo().performClick()
        compose.onNodeWithText(activity.getString(R.string.wake_editor_challenge_edit)).performScrollTo().performClick()
        compose.waitForIdle()
        assertLabelsFit()
    }

    @Test fun fajrSetupFitsLargeText() {
        show(2f) { FajrAlarmSetupSheet(1, System.currentTimeMillis(), onAdd = {}, onDismiss = {}) }
        assertLabelsFit()
    }

    @Test fun confirmingTheCityKeepsFajrChoicesUntilAddIsTappedAgain() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PrefsManager.setDelegationId(app, PlaceResolution.TUNIS_DELEGATION_ID)
        PrefsManager.markFirstLaunchDone(app)
        PrefsManager.setPlaceProvisional(app, true)
        var added: PrayerWakeConfig? = null
        show(1f) {
            FajrAlarmSetupSheet(PlaceResolution.TUNIS_DELEGATION_ID, System.currentTimeMillis(),
                onAdd = { added = it }, onDismiss = {})
        }
        compose.onNodeWithText(activity.getString(R.string.wake_editor_minutes_short, 45)).performScrollTo().performClick()
        val gentleDetail = compose.onNodeWithText(activity.getString(R.string.wake_fajr_suggestion_gentle_body))
        gentleDetail.assertDoesNotExist()
        compose.onNodeWithText(activity.getString(R.string.wake_fajr_suggestion_gentle_title)).performScrollTo().performClick()
        gentleDetail.assertExists()
        val add = compose.onNodeWithText(activity.getString(R.string.wake_fajr_suggestion_add))
        add.performScrollTo().performClick()
        compose.onNodeWithTag("feature_cancel_place").performClick()
        add.performScrollTo().performClick()
        compose.onNodeWithTag("feature_confirm_suggested_place").performClick()
        compose.waitForIdle()
        assertNull(added)
        add.performScrollTo().performClick()
        assertEquals(-45, added?.mainAlarm?.prayerOffset?.minutes)
        assertEquals(false, added?.playback?.awakeCheckEnabled)
    }

    @Test fun alarmListCardFitsLargeText() {
        val occurrence = 1_800_000_000_000L
        show(2f) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                WakeAlarmRow("منبه الاستيقاظ", PrayerWakeConfig(id = "list", prayer = Prayer.FAJR,
                    enabled = true, playback = playback),
                    alarmNow = WakeAlarmNow(occurrence, WakeAlarmScheduler.Ring(occurrence, isExtra = false), false),
                    nowMillis = occurrence - 1_800_000L, onClick = {}, onEnabledChange = {},
                    onDeleteRequest = {}, onSkipNext = {})
            }
        }
        assertLabelsFit()
    }

    @Test fun nextAlarmDateAndDetailsFitLargeText() {
        show(2f) {
            Column(Modifier.width(260.dp).verticalScroll(rememberScrollState())) {
                WakeNextAlarmDetails(R.string.wake_alarm_next_title, R.drawable.ic_tab_alarms,
                    "12:59", "الثلاثاء 24 نوفمبر", "14 جمادى الثانية 1448 هـ",
                    "منبه قبل صلاة الفجر بعشرين دقيقة مع تأكيد الاستيقاظ")
            }
        }
        assertLabelsFit()
    }

    @Test fun alarmTimelineLabelsFitLargeText() {
        show(2f) {
            WakeNextAlarmTimeline(wakeTimelineStops(listOf(-180, 0, 180), 1_800_000_000_000L,
                1_800_000_000_000L), nowMillis = 1_799_999_000_000L, formatTime = { "12:59" })
        }
        assertLabelsFit()
    }
}
