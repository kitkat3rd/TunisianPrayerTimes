package com.tunisianprayertimes.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranHighlightRect
import com.tunisianprayertimes.quran.QuranHighlights
import com.tunisianprayertimes.quran.QuranPage
import com.tunisianprayertimes.quran.QuranSearchEntry
import com.tunisianprayertimes.quran.QuranSurah
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.assets.QuranAssets
import com.tunisianprayertimes.quran.assets.QuranPackInstaller
import com.tunisianprayertimes.quran.assets.QuranPackLayout
import com.tunisianprayertimes.quran.hifz.HifzGrade
import com.tunisianprayertimes.quran.hifz.HifzKind
import com.tunisianprayertimes.quran.hifz.HifzMistake
import com.tunisianprayertimes.quran.hifz.HifzPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow
import java.io.File
import java.io.InputStream
import kotlin.math.roundToInt

private val previewBoxes = listOf(
    QuranHighlightRect(0.72f, 0.4f, 0.94f, 0.5f),
    QuranHighlightRect(0.39f, 0.4f, 0.72f, 0.5f),
    QuranHighlightRect(0.08f, 0.4f, 0.39f, 0.5f),
)

/** Robolectric does not decode bitmap regions: draw the requested band of the synthetic page instead. */
@Implements(BitmapRegionDecoder::class)
class QuranExcerptTestDecoder {
    @Implementation fun getWidth(): Int = 500
    @Implementation fun getHeight(): Int = 800
    @Implementation fun recycle() = Unit

    @Implementation fun decodeRegion(region: Rect, options: BitmapFactory.Options?): Bitmap {
        val sample = options?.inSampleSize?.coerceAtLeast(1) ?: 1
        val bitmap = Bitmap.createBitmap(region.width() / sample, region.height() / sample, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.scale(1f / sample, 1f / sample)
        canvas.translate(-region.left.toFloat(), -region.top.toFloat())
        val paint = Paint().apply { color = Color.BLACK }
        previewBoxes.forEach { r -> canvas.drawRect(r.left * 500f, r.top * 800f, r.right * 500f, r.bottom * 800f, paint) }
        return bitmap
    }

    companion object {
        @JvmStatic @Implementation fun newInstance(stream: InputStream): BitmapRegionDecoder = Shadow.newInstanceOf(BitmapRegionDecoder::class.java)
    }
}

/** A real review card with a small, locally installed page: earlier ink, the cue, then the covered answer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class, shadows = [QuranExcerptTestDecoder::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuranExcerptPreviewTest {
    @get:Rule val compose = createComposeRule()

    private val refs = (1..3).map { QuranVerseReference(1, it) }
    private val boxes = previewBoxes
    private val highlights = QuranHighlights(mapOf(1 to refs.zip(boxes).associate { it.first to listOf(it.second) }))
    private val exposed = mutableSetOf<QuranVerseReference>()
    private lateinit var assetPath: String
    private lateinit var view: View

    @Before fun installPage() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val pack = requireNotNull(QuranPackLayout.load(context)?.pages)
        assetPath = pack.files.first().path
        val file = File(QuranPackInstaller(QuranAssets.cdnRoot(context)).installedDirectory(pack), assetPath)
        requireNotNull(file.parentFile).mkdirs()
        file.writeBytes(byteArrayOf(0))
        assertNotNull(QuranAssets.resolve(context, assetPath))
    }

    private fun catalog(path: String) = QuranCatalog(
        listOf(QuranPage(1, path, emptyList())), listOf(QuranSurah(1, "test", 1)),
        listOf("earlier-one", "cue-two", "answer-three").mapIndexed { i, text -> QuranSearchEntry(1, i + 1, text, 1) },
    )

    private fun showCard(path: String = assetPath, protected: Set<QuranVerseReference> = emptySet()) {
        val catalog = catalog(path)
        val point = HifzPoint(id = 3L, verse = refs[2], mistakes = listOf(HifzMistake(HifzKind.Word)), dueDay = 0L, createdAt = 0L)
        val queue = listOf(point)
        val state = HifzReviewState()
        val actions = object : HifzReviewActions {
            override fun grade(point: HifzPoint, grade: HifzGrade) {}
            override fun listenBefore(point: HifzPoint) {}
            override fun listen(point: HifzPoint) {}
            override fun drill(point: HifzPoint) {}
            override fun leaveCard() {}
            override fun more() {}
            override fun listenTo(ids: List<Long>) {}
            override fun dismiss() {}
        }
        state.follow(queue, actions)
        compose.setContent {
            view = LocalView.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalQuranHighlights provides highlights,
                LocalQuranExposure provides { exposed.addAll(it) },
            ) {
                AdhkarTheme {
                    HifzReviewCard(
                        state, catalog, null, queue, null, HifzAudioControls({}, {}, {}), emptySet(), 1L, actions,
                        maxHeight = 500.dp, beforeHidden = true, pointInView = true,
                        eraserHint = false, onEraserHintClosed = {}, protectedVerses = protected,
                    )
                }
            }
        }
    }

    private fun waitForBand() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("quran_excerpt_band", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun inkAt(box: QuranHighlightRect, protected: Set<QuranVerseReference> = emptySet()): Int {
        val band = quranExcerpts(highlights, catalog(assetPath), refs[1], showPrevious = true, protectedVerses = protected).single()
        val bounds = compose.onNodeWithTag("quran_excerpt_band", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val bitmap = compose.runOnIdle { Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } }
        val x = (bounds.left + (box.left + box.right) / 2f * bounds.width).roundToInt()
        val y = (bounds.top + ((box.top + box.bottom) / 2f - band.top) / (band.bottom - band.top) * bounds.height).roundToInt()
        return bitmap.getPixel(x, y).also { bitmap.recycle() }
    }

    @Test fun earlierInkIsVisibleInTheCard_andTheAnswerStaysCovered() {
        showCard()
        waitForBand()
        assertTrue("earlier verse is visible", Color.red(inkAt(boxes[0])) < 40)
        assertTrue("the answer stays covered", Color.red(inkAt(boxes[2])) > 100)
        assertTrue(exposed.containsAll(refs.take(2)))
        assertFalse(refs[2] in exposed)

        compose.onNodeWithTag("quran_excerpt").performClick()
        compose.onNodeWithTag("quran_excerpt_text").performClick()
        compose.onNodeWithText("earlier-one", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("cue-two", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("answer-three", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun anEarlierUnansweredVerseIsNeitherShownNorCountedAsSeen() {
        val protected = setOf(refs[0])
        showCard(protected = protected)
        waitForBand()
        assertTrue("the protected verse stays covered", Color.red(inkAt(boxes[0], protected)) > 100)
        assertFalse(refs[0] in exposed)
        assertTrue(refs[1] in exposed)
        assertFalse(refs[2] in exposed)

        compose.onNodeWithTag("quran_excerpt").performClick()
        compose.onNodeWithTag("quran_excerpt_text").performClick()
        compose.onNodeWithText("earlier-one", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("cue-two", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("answer-three", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun aMissingPageFallbackDoesNotCountUnshownContextAsSeen() {
        showCard(path = "quran/pages/not-installed.webp")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("quran_excerpt", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        assertTrue(refs[1] in exposed)
        assertFalse(refs[0] in exposed)
        assertFalse(refs[2] in exposed)
    }
}
