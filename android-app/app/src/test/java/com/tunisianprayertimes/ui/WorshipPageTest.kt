package com.tunisianprayertimes.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.BgCream
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The whole «عباداتي» page as the tab shows it: the folding Qibla, and every section at normal and double text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h4000dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorshipPageTest {
    @get:Rule val compose = createComposeRule()

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Composable
    private fun Page(fontScale: Float) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale),
            LocalLayoutDirection provides LayoutDirection.Rtl,
        ) {
            TunisianPrayerTimesTheme(fontFamily = AppFontFamily) {
                Box(Modifier.fillMaxSize().background(BgCream).testTag("worship_page")) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        WorshipPage(placeRevision = TUNIS_DELEGATION_ID)
                    }
                }
            }
        }
    }

    @Test
    fun qiblaStartsFoldedOpensFromItsHeadingAndKeepsTheChoice() {
        PrefsManager.setDelegationId(context, TUNIS_DELEGATION_ID)
        compose.setContent { Page(fontScale = 1f) }
        val toggle = compose.onNodeWithTag("worship_section_toggle")

        val folded = toggle.fetchSemanticsNode().config
        assertEquals(Role.Button, folded.getOrNull(SemanticsProperties.Role))
        assertEquals("مطويّ", folded.getOrNull(SemanticsProperties.StateDescription))
        assertEquals("فتح القبلة", folded.getOrNull(SemanticsActions.OnClick)?.label)
        assertTrue("TalkBack hears the heading", folded.contains(SemanticsProperties.Heading))
        compose.onNodeWithTag(TestTags.QIBLA_BEARING_VALUE).assertDoesNotExist()
        waitForTheNightCard()
        savePreview("page-font-100")

        toggle.performClick()
        compose.waitForIdle()
        val open = toggle.fetchSemanticsNode().config
        assertEquals("مفتوح", open.getOrNull(SemanticsProperties.StateDescription))
        assertEquals("طيّ القبلة", open.getOrNull(SemanticsActions.OnClick)?.label)
        assertTrue(qiblaSaved())
        savePreview("page-qibla-open-font-100")

        toggle.performClick()
        compose.waitForIdle()
        assertFalse(qiblaSaved())
    }

    @Test
    fun wholePageAtDoubleTextSize() {
        PrefsManager.setDelegationId(context, TUNIS_DELEGATION_ID)
        RuntimeEnvironment.setFontScale(2f)
        compose.setContent { Page(fontScale = 2f) }
        waitForTheNightCard()
        savePreview("page-font-200")
    }

    private fun qiblaSaved(): Boolean =
        context.getSharedPreferences("worship_tab", Context.MODE_PRIVATE).getBoolean("qibla_expanded", false)

    /** The voluntary times are read off the main thread: wait until the night card shows them. */
    private fun waitForTheNightCard() {
        val isha = context.getString(R.string.worship_isha_time)
        compose.waitUntil(timeoutMillis = 20_000) { compose.onAllNodesWithText(isha).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun savePreview(name: String) {
        compose.waitForIdle()
        // Robolectric cannot dispatch captureToImage's hardware redraw: draw the window itself.
        val bitmap = compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().last { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val output = File("build/outputs/worship-page-preview/$name.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private companion object {
        const val TUNIS_DELEGATION_ID = 615
    }
}
