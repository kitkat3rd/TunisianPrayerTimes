package com.tunisianprayertimes.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.tafsir.TafsirRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w411dp-h1050dp-mdpi", application = Application::class)
@OptIn(ExperimentalTestApi::class)
class QuranTafsirPanelTest {
    // Asset reads complete on IO; schedule the resulting recompositions on the test frame clock.
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val focus = mutableStateOf(QuranVerseReference(1, 2))
    private val instance = mutableIntStateOf(0)
    private var downloads = 0

    @Before fun forgetPreviousChoice() {
        app.getSharedPreferences("quran_tafsir_reader", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun switchingConciseBooksKeepsVerseAndGlossaryButChangesTextNotesAndAttribution() {
        show()
        awaitText("Muyassar passage two and three")
        awaitText("Muyassar note")
        scrollToText("Glossary source")

        click("book_mukhtasar")
        awaitText("Mukhtasar passage two")
        compose.onNodeWithText("Muyassar note").assertDoesNotExist()
        scrollToText("Mukhtasar source")
        scrollToText("Glossary source")

        click("tab_books")
        awaitText("Juzayy passage two through four")
        click("book_ashur")
        awaitText("Ashur passage two")
        click("tab_meaning")
        awaitText("Mukhtasar passage two")
        compose.runOnIdle { instance.intValue++ }
        awaitText("Mukhtasar passage two")
        compose.onNodeWithTag("quran_tafsir_book_mukhtasar").assertIsSelected()
        click("tab_books")
        awaitText("Ashur passage two")
        assertEquals(QuranVerseReference(1, 2), focus.value)
    }

    @Test fun stepsFollowTheChosenBookAndReachTheUnnumberedBasmala() {
        show()
        awaitText("Muyassar passage two and three")
        click("next")
        awaitText("Muyassar passage four")
        assertEquals(QuranVerseReference(1, 4), focus.value)
        click("previous")
        awaitText("Muyassar passage two and three")
        click("book_mukhtasar")
        awaitText("Mukhtasar passage two")
        click("next")
        awaitText("Mukhtasar passage three")
        click("previous")
        awaitText("Mukhtasar passage two")
        click("previous")
        awaitText("Mukhtasar passage one")
        click("previous")
        awaitText("Mukhtasar basmala")
        assertEquals(QuranVerseReference(1, 0), focus.value)
        compose.onNodeWithTag("quran_tafsir_previous").assertIsNotEnabled()
        click("share")
        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        val body = requireNotNull(send.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(body.contains("[الفاتحة: البسملة]"))
        assertTrue(body.contains("Mukhtasar basmala"))
        assertTrue(body.contains("Mukhtasar source"))
        assertFalse(body.contains("Muyassar source"))
    }

    @Test fun missingPackAllowsItsOfflineExcerptAndRemembersExplicitDownloadChoice() {
        show(ashurPack = true)
        awaitText("Muyassar note")
        compose.onNodeWithText("اقرأ شرح ابن عاشور لكلمة مصحفك").performSemanticsAction(SemanticsActions.OnClick)
        awaitText("Ashur offline excerpt")
        compose.onNodeWithText("العودة إلى المعنى").performSemanticsAction(SemanticsActions.OnClick)
        awaitText("Muyassar passage two and three")
        click("tab_books")
        awaitText("Juzayy passage two through four")
        click("book_ashur")
        awaitText("Download fixture pack")
        compose.onNodeWithText("Download fixture pack").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, downloads)
        compose.runOnIdle { instance.intValue++ }
        awaitText("Juzayy passage two through four")
        assertEquals("ashur", app.getSharedPreferences("quran_tafsir_reader", Context.MODE_PRIVATE).getString("books", null))
    }

    @Test fun privatePreviewIsReadableWithSharingDisabledAcrossBooks() {
        show(preview = true)
        awaitText("Muyassar passage two and three")
        compose.onNodeWithTag("quran_tafsir_share").assertDoesNotExist()
        click("tab_books")
        awaitText("Juzayy passage two through four")
        compose.onNodeWithTag("quran_tafsir_share").assertDoesNotExist()
    }

    private fun show(ashurPack: Boolean = false, preview: Boolean = false) {
        val repository = fixtureRepository(ashurPack, preview)
        compose.setContent {
            AdhkarTheme {
                key(instance.intValue) {
                    QuranTafsirPanel(repository, focus.value, surahName = { "الفاتحة" },
                        verseText = { "كلمات تجريبية" }, onVerseChange = { focus.value = it }, onDismiss = {},
                        isProtected = { false }, onReadAnyway = {}, onReview = {}, onPassageShown = {}, onTouch = {},
                        downloadAction = { _, started, _ ->
                            Button(onClick = { downloads++; started() }) { Text("Download fixture pack") }
                        })
                }
            }
        }
    }

    private fun click(action: String) {
        compose.onNodeWithTag("quran_tafsir_$action").performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun awaitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun scrollToText(text: String) {
        compose.onNodeWithTag("quran_tafsir_text").performScrollToNode(hasText(text, substring = true))
        compose.onNodeWithText(text, substring = true).assertExists()
    }

    /** Test-only passages, with different native boundaries for the two concise books. */
    private fun fixtureRepository(ashurPack: Boolean, preview: Boolean): TafsirRepository {
        fun passage(id: String, from: Int, to: Int, text: String) =
            JSONObject().put("id", id).put("from", from).put("to", to).put("text", text)
        val sources = mapOf("muyassar" to "Muyassar", "mukhtasar" to "Mukhtasar", "gharib" to "Glossary",
            "juzayy" to "Juzayy", "ashur" to "Ashur")
        val entries = mapOf(
            "muyassar" to listOf(passage("m2", 2, 3, "Muyassar passage two and three"), passage("m4", 4, 4, "Muyassar passage four")),
            "mukhtasar" to listOf(passage("k0", 0, 0, "Mukhtasar basmala"), passage("k1", 1, 1, "Mukhtasar passage one"),
                passage("k2", 2, 2, "Mukhtasar passage two"), passage("k3", 3, 3, "Mukhtasar passage three")),
            "gharib" to listOf(passage("g2", 2, 2, "Glossary definition").put("headword", "مدخل تجريبي")),
            "juzayy" to listOf(passage("j2", 2, 4, "Juzayy passage two through four")),
            "ashur" to if (ashurPack) listOf(passage("a2", 2, 2, "Ashur offline excerpt").put("kind", "excerpt"))
                       else listOf(passage("a2", 2, 2, "Ashur passage two")),
        )
        val books = sources.map { (id, name) ->
            JSONObject().put("id", id).put("edition", "Test edition").put("source", "$name source")
                .put("shareable", true).put("surahs", JSONArray(listOf(1))).also {
                    if (id == "ashur" && ashurPack) it.put("pack", "quran_tafsir_ashur")
                }
        }
        val assets = entries.map { (id, units) ->
            val chapter = JSONObject().put("units", JSONArray(units))
            if (id == "muyassar") chapter.put("notes", JSONArray().put(JSONObject().put("ayah", 2)
                .put("text", "Muyassar note").put("targetBook", "ashur").put("targetUnit", "a2")))
            "quran/tafsir/$id/1.json" to chapter.toString()
        }.toMap() + ("quran/tafsir/catalog.json" to JSONObject().put("version", 1)
            .put("preview", preview).put("books", JSONArray(books)).toString())
        return TafsirRepository { path -> assets[path] }
    }
}
