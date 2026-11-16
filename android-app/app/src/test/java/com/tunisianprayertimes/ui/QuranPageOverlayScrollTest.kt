package com.tunisianprayertimes.ui

import android.app.Application
import android.content.res.AssetManager
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.quran.QuranHighlightRect
import com.tunisianprayertimes.quran.QuranPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuranPageOverlayScrollTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val folder = TemporaryFolder()
    private val overlayOpen = mutableStateOf(true)
    private val eraserOn = mutableStateOf(false)
    private var tappedY = Float.NaN
    private var zoomed = false
    private var erased = false

    private fun show(erasing: Boolean = false) {
        eraserOn.value = erasing
        val context = ApplicationProvider.getApplicationContext<Application>()
        val assetPath = "overlay-scroll-${folder.root.name}/page.png"
        val archive = folder.newFile("page.apk")
        val image = Bitmap.createBitmap(360, 600, Bitmap.Config.ARGB_8888)
        ZipOutputStream(archive.outputStream()).use {
            it.putNextEntry(ZipEntry("assets/$assetPath"))
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.closeEntry()
        }
        image.recycle()
        val cookie = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
            .invoke(context.assets, archive.absolutePath) as Int
        check(cookie != 0) { "The generated page asset could not be loaded" }
        checkNotNull(loadQuranPageArt(context, assetPath))
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                QuranPageImage(
                    page = QuranPage(1, assetPath, emptyList()), active = true, pagesReady = true,
                    highlightRects = emptyList(), foundRects = emptyList(), hifzRects = emptyList(), selectedRects = emptyList(),
                    keepVisible = emptyList(), scrollBehindOverlay = overlayOpen.value,
                    bottomInsetPx = { if (overlayOpen.value) 400 else 0 },
                    verseAt = { _, y -> tappedY = y; null }, rectsOf = { emptyList() },
                    hiddenRects = if (eraserOn.value) listOf(QuranHighlightRect(0f, 0f, 1f, 1f)) else emptyList(),
                    erasing = eraserOn.value, lineAt = { _, _ -> QuranHighlightRect(0f, 0f, 1f, 1f) },
                    onErased = { erased = true },
                    onTap = {}, onZoomChanged = { zoomed = it }, onSettled = {},
                )
                if (overlayOpen.value) Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(400.dp).background(Color.White).testTag("popup"),
                )
            }
        }
    }

    private fun tapY(): Float {
        compose.onRoot().performTouchInput { click(Offset(centerX, 200f)) }
        compose.waitForIdle()
        return tappedY
    }

    private fun swipeUp() {
        compose.onRoot().performTouchInput { swipe(Offset(centerX, 340f), Offset(centerX, 140f), 300) }
        compose.waitForIdle()
    }

    @Test
    fun theFittedPageScrollsWithoutZoomingOrMovingThePopup() {
        show()
        val popup = compose.onNodeWithTag("popup").getUnclippedBoundsInRoot()
        val before = tapY()
        swipeUp()
        assertTrue("the page scrolled beneath the same screen position", tapY() > before + 0.1f)
        assertTrue("scrolling did not zoom", !zoomed)
        compose.onNodeWithTag("popup").assertIsDisplayed()
        assertEquals(popup, compose.onNodeWithTag("popup").getUnclippedBoundsInRoot())
        // The reverse drag brings the earlier lines back without dismissing the card.
        compose.onRoot().performTouchInput { swipe(Offset(centerX, 140f), Offset(centerX, 340f), 300) }
        compose.waitForIdle()
        assertEquals(before, tapY(), 0.02f)
    }

    @Test
    fun verticalDragsScrollCoveredTextInsteadOfErasingIt() {
        show(erasing = true)
        compose.runOnIdle { eraserOn.value = false }
        val before = tapY()
        compose.runOnIdle { eraserOn.value = true }
        swipeUp()
        assertTrue("vertical navigation must not reveal an answer", !erased)
        compose.runOnIdle { eraserOn.value = false }
        assertTrue("the covered page scrolled", tapY() > before + 0.1f)
        compose.onNodeWithTag("popup").assertIsDisplayed()
    }

    @Test
    fun closingThePopupRestoresTheFittedPage() {
        show()
        val before = tapY()
        swipeUp()
        compose.runOnIdle { overlayOpen.value = false }
        compose.waitForIdle()
        assertEquals(before, tapY(), 0.02f)
    }

    @Test
    fun withoutThePopupTheWholePageKeepsItsExistingFitBehavior() {
        overlayOpen.value = false
        show()
        val before = tapY()
        swipeUp()
        assertEquals(before, tapY(), 0.02f)
        assertTrue(!zoomed)
    }
}
