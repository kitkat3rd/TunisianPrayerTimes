package com.tunisianprayertimes.ui

import android.app.Application
import android.view.inspector.WindowInspector
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.quran.QuranRepository
import com.tunisianprayertimes.quran.assets.QuranPack
import com.tunisianprayertimes.quran.assets.QuranPackArchive
import com.tunisianprayertimes.quran.assets.QuranPackFile
import com.tunisianprayertimes.quran.assets.QuranPackLayout
import com.tunisianprayertimes.quran.assets.QuranPackSource
import com.tunisianprayertimes.quran.assets.QuranPackState
import com.tunisianprayertimes.quran.assets.QuranPackStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
class FirstRunRecitationDownloadTest {
    @get:Rule
    val compose = createComposeRule()

    private class RecordingSource(initial: Map<String, QuranPackState>) : QuranPackSource {
        override val states = MutableStateFlow(initial)
        val fetched = mutableListOf<Pair<List<String>, Boolean>>()
        val confirmations = mutableListOf<List<String>>()

        override fun fetch(packs: List<QuranPack>, allowMetered: Boolean) {
            fetched += packs.map { it.name } to allowMetered
        }

        override fun confirmMobileData(packs: List<QuranPack>, launcher: ActivityResultLauncher<IntentSenderRequest>) {
            confirmations += packs.map { it.name }
        }

        override fun directory(pack: QuranPack): File? = null
        override fun remove(packs: List<QuranPack>) = Unit
        override fun cancel(packs: List<QuranPack>) = Unit
        override fun refresh() = Unit
    }

    private val reciter = "ayoub-qaloun"

    private fun pack(name: String, chapters: IntRange, archiveMB: Long, owner: String = reciter): QuranPack {
        val bytes = archiveMB * 1_000_000L
        // Installed and download sizes deliberately differ so a label using the wrong figure fails.
        return QuranPack(
            name, QuranPack.Kind.Audio, QuranPack.Delivery.Cdn, owner, chapters, bytes * 2,
            QuranPackArchive("$name.zip", bytes, "0".repeat(64)),
            listOf(QuranPackFile("quran/audio/$name.mp3", bytes * 2)),
        )
    }

    private val bundled = pack("bundled", 1..1, 2)
    private val pair = pack("grouped", 2..3, 17)
    private val next = pack("next", 4..6, 5).copy(delivery = QuranPack.Delivery.Play)
    private val underway = pack("underway", 7..14, 8)
    private val waiting = pack("waiting", 15..114, 40)
    private val other = pack("other-reciter", 1..114, 92, owner = "hosary-qaloun")
    private val packs = listOf(bundled, pair, next, underway, waiting, other)
    private val source = RecordingSource(packs.associate { it.name to QuranPackState.Missing })

    private fun show(states: Map<String, QuranPackState> = emptyMap()): QuranMedia {
        source.states.value += states
        val media = QuranMedia(QuranPackLayout("https://cdn.example/", packs), source, setOf(bundled.name), metered = { true })
        compose.setContent { FirstRunRecitationDownload(media) }
        return media
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun openScopePicker(voice: String = reciter) {
        click("first_run_audio_choose")
        click("first_run_audio_reciter_$voice")
    }

    private fun select(scopeTag: String) {
        openScopePicker()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(scopeTag).fetchSemanticsNodes().isNotEmpty() }
        click(scopeTag)
    }

    @Test
    fun selectingAChapterShowsItsCompanionAndTheEntirePackSizeBeforeDownloading() {
        show()
        select("first_run_audio_surah_2")
        for (text in listOf("البقرة", "آل عمران", quranSize(pair.archive.bytes))) {
            compose.onAllNodesWithText(text, substring = true)[0].assertIsDisplayed()
        }
        assertTrue(source.fetched.isEmpty())
        assertTrue(source.confirmations.isEmpty())

        click("first_run_audio_download")
        assertEquals(listOf(listOf(pair.name) to true), source.fetched)
    }

    @Test
    fun surahSearchAcceptsVocalizedNamesAndBothFormsOfArabicDigits() {
        QuranRepository.load(ApplicationProvider.getApplicationContext<Application>())
        show()
        openScopePicker()
        val search = compose.onNodeWithTag("first_run_audio_search")
        val list = compose.onNodeWithTag("first_run_audio_picker_list")
        for ((query, number) in listOf("١١٤" to 114, "۱۱۳" to 113, "3" to 3, "الْبَقَرَة" to 2)) {
            list.performScrollToNode(hasTestTag("first_run_audio_search"))
            search.performTextReplacement(query)
            compose.onNodeWithTag("first_run_audio_full").assertDoesNotExist()
            val tag = "first_run_audio_surah_$number"
            list.performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsDisplayed()
            compose.onNodeWithTag("first_run_audio_surah_1").assertDoesNotExist()
        }
        search.performTextReplacement("")
        compose.onNodeWithTag("first_run_audio_full").assertIsDisplayed()
        search.performTextReplacement("٢")
        click("first_run_audio_surah_2")
        compose.onAllNodesWithText("البقرة", substring = true)[0].assertIsDisplayed()
        assertTrue(source.fetched.isEmpty())
    }

    @Test
    fun returningToRecitersAndClosingTheDraftKeepsTheSelectedSurahAndVoice() {
        show()
        select("first_run_audio_surah_2")
        openScopePicker(requireNotNull(other.reciterId))
        click("first_run_audio_picker_back")
        compose.onNodeWithTag("first_run_audio_reciter_$reciter").assertIsDisplayed()
        compose.onNodeWithTag("first_run_audio_full").assertDoesNotExist()
        click("first_run_audio_picker_close")
        compose.onAllNodesWithText("البقرة", substring = true)[0].assertIsDisplayed()
        compose.onAllNodesWithText("أيوب الركباوي", substring = true)[0].assertIsDisplayed()
        assertTrue(source.fetched.isEmpty())
        click("first_run_audio_download")
        assertEquals(listOf(listOf(pair.name) to true), source.fetched)
    }

    @Test
    fun systemBackReturnsToRecitersThenDismissesWithoutSelecting() {
        show()
        openScopePicker()
        fun pressDialogBack() = compose.runOnIdle {
            val window = WindowInspector.getGlobalWindowViews().last { it.isAttachedToWindow }
            requireNotNull(window.findViewTreeOnBackPressedDispatcherOwner()).onBackPressedDispatcher.onBackPressed()
        }
        pressDialogBack()
        compose.onNodeWithTag("first_run_audio_reciter_$reciter").assertIsDisplayed()
        pressDialogBack()
        compose.onNodeWithTag("first_run_audio_picker_list").assertDoesNotExist()
        compose.onNodeWithTag("first_run_audio_choose").assertIsDisplayed()
        assertTrue(source.fetched.isEmpty())
    }

    @Test
    @Config(qualifiers = "ar-rTN-w320dp-h640dp-mdpi")
    fun scrollingTheSurahsAtLargeFontKeepsBackAndCloseWithinReach() {
        RuntimeEnvironment.setFontScale(2f)
        QuranRepository.load(ApplicationProvider.getApplicationContext<Application>())
        show()
        openScopePicker()
        val list = compose.onNodeWithTag("first_run_audio_picker_list")
        val close = compose.onNodeWithTag("first_run_audio_picker_close")
        val back = compose.onNodeWithTag("first_run_audio_picker_back")
        val closeBeforeScroll = close.assertIsDisplayed().getUnclippedBoundsInRoot()
        val backBeforeScroll = back.assertIsDisplayed().getUnclippedBoundsInRoot()
        list.performScrollToNode(hasTestTag("first_run_audio_surah_16"))
        val row = compose.onNodeWithTag("first_run_audio_surah_16").assertIsDisplayed()
        val rowBounds = row.getUnclippedBoundsInRoot()
        assertTrue(rowBounds.bottom - rowBounds.top >= 48.dp)
        assertTrue(closeBeforeScroll.bottom - closeBeforeScroll.top >= 48.dp)
        assertTrue(backBeforeScroll.bottom - backBeforeScroll.top >= 48.dp)
        assertEquals(closeBeforeScroll, close.assertIsDisplayed().getUnclippedBoundsInRoot())
        assertEquals(backBeforeScroll, back.assertIsDisplayed().getUnclippedBoundsInRoot())
        click("first_run_audio_picker_close")
        compose.onNodeWithTag("first_run_audio_picker_list").assertDoesNotExist()
        assertTrue(source.fetched.isEmpty())
    }

    @Test
    fun fullRecitationQueuesOnlyMissingIdlePacksAndExcludesOtherVoices() {
        show(mapOf(
            underway.name to QuranPackState(QuranPackStatus.Downloading),
            waiting.name to QuranPackState(QuranPackStatus.WaitingForWifi),
        ))
        select("first_run_audio_full")
        compose.onAllNodesWithText(quranSize(pair.archive.bytes + next.archive.bytes), substring = true)[0].assertIsDisplayed()
        assertTrue(source.fetched.isEmpty())

        click("first_run_audio_download")
        assertEquals(listOf(listOf(pair.name, next.name) to false), source.fetched)
        assertTrue(source.confirmations.isEmpty())
    }

    @Test
    fun confirmingPlayAndAllowingMobileDataAffectOnlyThePacksInEachWait() {
        val media = show(mapOf(
            pair.name to QuranPackState.Available,
            next.name to QuranPackState(QuranPackStatus.NeedsConfirmation),
            underway.name to QuranPackState(QuranPackStatus.Downloading),
            waiting.name to QuranPackState(QuranPackStatus.WaitingForWifi),
        ))
        select("first_run_audio_full")
        click("first_run_audio_confirm")
        assertEquals(listOf(listOf(next.name)), source.confirmations)

        compose.runOnIdle { media.states += next.name to QuranPackState.Available }
        click("first_run_audio_confirm")
        assertEquals(listOf(listOf(next.name), listOf(waiting.name)), source.confirmations)
        assertTrue(source.fetched.isEmpty())
    }
}
