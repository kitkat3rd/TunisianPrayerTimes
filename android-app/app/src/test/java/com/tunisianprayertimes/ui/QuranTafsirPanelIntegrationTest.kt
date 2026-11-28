package com.tunisianprayertimes.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.quranVerseSentence
import com.tunisianprayertimes.quran.tafsir.TafsirRepository
import com.tunisianprayertimes.quran.tafsir.tafsirFixtureRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercise the semantic action that TalkBack invokes, without synthesizing a tap on the printed verse. */
internal fun ComposeTestRule.openTafsirAccessibilityAction() {
    val key = SemanticsActions.CustomActions
    val page = onNode(SemanticsMatcher("page has a tafsir accessibility action") { node ->
        key in node.config && node.config[key].any { "تفسير" in it.label }
    })
    val action = page.fetchSemanticsNode().config[key].single { "تفسير" in it.label }
    runOnIdle { assertTrue(action.action()) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuranTafsirPanelIntegrationTest {
    @get:Rule val compose = createComposeRule()

    private val selected = QuranVerseReference(67, 13)
    private val nextPage = QuranVerseReference(67, 14)
    private val sampleText = "TEST_CROSS_PAGE_PASSAGE"

    private fun passageRepository(): TafsirRepository {
        val files = mapOf(
            "quran/tafsir/catalog.json" to """{"version":1,"books":[{"id":"juzayy","source":"fixture","edition":"test","shareable":true,"surahs":[67]}]}""",
            "quran/tafsir/juzayy/67.json" to """{"units":[{"id":"span","from":13,"to":14,"text":"$sampleText"}]}""",
        )
        return tafsirFixtureRepository(files::get)
    }

    @Test
    fun versePickerCanSelectTheSecondVerseOfASharedPassage() {
        val session = QuranTafsirSession(passageRepository(), choosingPage = 2)
        val seventh = QuranVerseReference(1, 7)
        compose.setContent {
            if (session.pickerPage != 0) QuranTafsirVersePicker(
                verses = listOf(QuranVerseReference(1, 6), seventh),
                surahName = { "الفاتحة" },
                onPick = { session.show(it) },
                onDismiss = { session.pickerPage = 0 },
            )
        }
        compose.onNodeWithText(quranVerseSentence("الفاتحة", 7)).performClick()
        compose.runOnIdle {
            assertEquals(seventh, session.verse)
            assertEquals(0, session.pickerPage)
        }
    }

    @Test
    fun aDueAnswerOnTheNextPageStaysHiddenUntilTheReaderChoosesToReadThePassage() {
        val repo = passageRepository()
        val protected = mutableStateOf(true)
        val exposed = mutableListOf<Set<QuranVerseReference>>()
        var approved: Set<QuranVerseReference>? = null
        compose.setContent {
            QuranTafsirPanel(
                repository = repo, verse = selected, surahName = { "الملك" }, verseText = { null },
                onVerseChange = {}, onDismiss = {}, onTouch = {},
                isProtected = { protected.value && nextPage in it },
                onReadAnyway = { refs -> approved = refs; protected.value = false },
                onReview = {}, onPassageShown = { if (it.isNotEmpty()) exposed.add(it) },
            )
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("quran_tafsir_read_anyway").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(sampleText).assertDoesNotExist()
        compose.onNodeWithTag("quran_tafsir_share").assertDoesNotExist()
        compose.runOnIdle { assertTrue(exposed.isEmpty()) }
        compose.onNodeWithTag("quran_tafsir_read_anyway").performClick()
        compose.onNodeWithText(sampleText).assertExists()
        compose.onNodeWithTag("quran_tafsir_share").assertExists()
        compose.runOnIdle {
            assertEquals(setOf(selected, nextPage), approved)
            assertEquals(setOf(selected, nextPage), exposed.last())
        }
    }
}
