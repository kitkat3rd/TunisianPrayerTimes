package com.tunisianprayertimes.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import com.tunisianprayertimes.quran.tafsir.TafsirBook
import com.tunisianprayertimes.quran.tafsir.TafsirPackAccess
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
    private val revision = mutableIntStateOf(0)
    private val downloading = mutableStateMapOf<String, Boolean>()
    private val requested = mutableListOf<String>()
    @Volatile private var installed: Set<String> = emptySet()
    private val sources = mapOf("muyassar" to "Muyassar", "mukhtasar" to "Mukhtasar", "gharib" to "Glossary",
        "juzayy" to "Juzayy", "ashur" to "Ashur")

    @Before fun forgetPreviousChoice() {
        app.getSharedPreferences("quran_tafsir_reader", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun firstReadingIsQuietAndLaterBookChangesCanBeAcknowledged() {
        val prefs = app.getSharedPreferences("tafsir_announcement_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val first = TafsirBook("muyassar", "الميسّر", "", "", false, setOf(1))
        val added = first.copy(id = "ashur", title = "التحرير والتنوير")
        val books = mutableStateOf(listOf(first))
        compose.setContent { QuranTafsirAnnouncement(books.value, prefs) }
        compose.onNodeWithTag("quran_tafsir_notice").assertDoesNotExist()

        compose.runOnIdle { books.value = listOf(first, added) }
        compose.onNodeWithText(added.title, substring = true).assertExists()
        compose.onNodeWithText("حسنًا").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("quran_tafsir_notice").assertDoesNotExist()

        compose.runOnIdle { books.value = listOf(added) }
        compose.onNodeWithText(first.title, substring = true).assertExists()
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

    @Test fun firstLaunchOffersIndependentDownloadsAndCompletingThemKeepsTheFocusedVerse() {
        show(initiallyInstalled = emptySet())
        awaitText("Download muyassar")
        assertTrue(requested.isEmpty())
        click("book_muyassar")
        awaitText("Downloading muyassar")
        assertEquals(listOf("muyassar"), requested)
        scrollToText("Download gharib")
        compose.onNodeWithText("Download gharib").performSemanticsAction(SemanticsActions.OnClick)
        click("book_mukhtasar")
        awaitText("Downloading mukhtasar")
        scrollToText("Downloading gharib")
        assertEquals(listOf("muyassar", "gharib", "mukhtasar"), requested)

        finish("gharib")
        awaitText("Downloading mukhtasar")
        scrollToText("Glossary definition")
        click("book_muyassar")
        awaitText("Downloading muyassar")
        scrollToText("Glossary definition")
        click("book_mukhtasar")
        awaitText("Downloading mukhtasar")

        finish("mukhtasar")
        awaitText("Mukhtasar passage two")
        scrollToText("Glossary definition")
        assertEquals(QuranVerseReference(1, 2), focus.value)
        compose.runOnIdle { instance.intValue++ }
        awaitText("Mukhtasar passage two")
        compose.onNodeWithTag("quran_tafsir_book_mukhtasar").assertIsSelected()
    }

    @Test fun readingLinkOpensTheTargetDownloadAndReturnsToTheOriginalBook() {
        show(initiallyInstalled = setOf("muyassar", "juzayy"))
        awaitText("Muyassar note")
        compose.onNodeWithText("اقرأ شرح ابن عاشور لكلمة مصحفك").performSemanticsAction(SemanticsActions.OnClick)
        awaitText("Downloading ashur")
        assertEquals(listOf("ashur"), requested)
        finish("ashur")
        awaitText("Ashur passage two")
        compose.onNodeWithText("العودة إلى المعنى").performSemanticsAction(SemanticsActions.OnClick)
        awaitText("Muyassar passage two and three")
        assertEquals(QuranVerseReference(1, 2), focus.value)
    }

    @Test fun rememberingAnUninstalledCommentaryShowsItsOwnDownloadCard() {
        show(initiallyInstalled = setOf("juzayy"))
        awaitText("Download muyassar")
        click("tab_books")
        awaitText("Juzayy passage two through four")
        click("book_ashur")
        awaitText("Downloading ashur")
        compose.runOnIdle { instance.intValue++ }
        awaitText("Downloading ashur")
        assertEquals(listOf("ashur"), requested)
        compose.onNodeWithTag("quran_tafsir_book_ashur").assertIsSelected()
    }

    @Test fun introductionComesFromTheDownloadedAshurPack() {
        show(initiallyInstalled = emptySet(), intro = true)
        awaitText("Download ashur")
        compose.onNodeWithText("Ashur introduction").assertDoesNotExist()
        compose.onNodeWithText("Download ashur").performSemanticsAction(SemanticsActions.OnClick)
        finish("ashur")
        awaitText("Ashur introduction")
        compose.onNodeWithText("Ashur passage two").assertDoesNotExist()
    }

    @Test fun privatePreviewIsReadableWithSharingDisabledAcrossBooks() {
        show(preview = true)
        awaitText("Muyassar passage two and three")
        compose.onNodeWithTag("quran_tafsir_share").assertDoesNotExist()
        click("tab_books")
        awaitText("Juzayy passage two through four")
        compose.onNodeWithTag("quran_tafsir_share").assertDoesNotExist()
    }

    private fun finish(book: String) = compose.runOnIdle {
        check(downloading.remove(book) == true)
        installed = installed + book
        revision.intValue++
    }

    private fun request(book: String) {
        if (book in installed || downloading[book] == true) return
        requested.add(book)
        downloading[book] = true
    }

    private fun show(initiallyInstalled: Set<String> = sources.keys, intro: Boolean = false, preview: Boolean = false) {
        installed = initiallyInstalled
        val repository = fixtureRepository(preview)
        compose.setContent {
            AdhkarTheme {
                key(instance.intValue) {
                    QuranTafsirPanel(repository, focus.value, surahName = { "الفاتحة" },
                        verseText = { "كلمات تجريبية" }, onVerseChange = { focus.value = it }, onDismiss = {},
                        isProtected = { false }, onReadAnyway = {}, onReview = {}, onPassageShown = {}, onTouch = {},
                        packRevision = revision.intValue, intro = intro,
                        onRequestBook = { request(it.id) },
                        bookDownloadSize = { book -> if (book.id in installed || downloading[book.id] == true) null else "1 MB" },
                        downloadAction = { book, started, _ ->
                            if (downloading[book.id] == true) Text("Downloading ${book.id}")
                            else Button(onClick = { request(book.id); started() }) { Text("Download ${book.id}") }
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
    private fun fixtureRepository(preview: Boolean): TafsirRepository {
        fun passage(id: String, from: Int, to: Int, text: String) =
            JSONObject().put("id", id).put("from", from).put("to", to).put("text", text)
        val entries = mapOf(
            "muyassar" to listOf(passage("m2", 2, 3, "Muyassar passage two and three"), passage("m4", 4, 4, "Muyassar passage four")),
            "mukhtasar" to listOf(passage("k0", 0, 0, "Mukhtasar basmala"), passage("k1", 1, 1, "Mukhtasar passage one"),
                passage("k2", 2, 2, "Mukhtasar passage two"), passage("k3", 3, 3, "Mukhtasar passage three")),
            "gharib" to listOf(passage("g2", 2, 2, "Glossary definition").put("headword", "مدخل تجريبي")),
            "juzayy" to listOf(passage("j2", 2, 4, "Juzayy passage two through four")),
            "ashur" to listOf(passage("intro", 0, 0, "Ashur introduction").put("kind", "intro"),
                passage("a2", 2, 2, "Ashur passage two")),
        )
        val books = sources.map { (id, name) ->
            JSONObject().put("id", id).put("edition", "Test edition").put("source", "$name source")
                .put("baseId", "a".repeat(64)).put("pack", "quran_tafsir_$id")
                .put("shareable", true).put("surahs", JSONArray(listOf(1))).also {
                    if (id == "ashur") it.put("introSurahs", JSONArray(listOf(1)))
                }
        }
        val chapters = entries.mapValues { (id, units) ->
            val chapter = JSONObject().put("units", JSONArray(units))
            if (id == "muyassar") chapter.put("notes", JSONArray().put(JSONObject().put("ayah", 2)
                .put("text", "Muyassar note").put("targetBook", "ashur").put("targetUnit", "a2")))
            chapter.toString()
        }
        val catalog = JSONObject().put("version", 1).put("preview", preview).put("books", JSONArray(books)).toString()
        val packs = object : TafsirPackAccess {
            override fun revision(bookId: String) = if (bookId in installed) "test/$bookId" else null
            override fun metadata(bookId: String) = mapOf("baseId" to "a".repeat(64), "book" to bookId,
                "source" to "${sources.getValue(bookId)} source", "edition" to "Test edition")
            override fun read(bookId: String, surah: Int) = chapters[bookId]?.takeIf { bookId in installed && surah == 1 }
        }
        return TafsirRepository({ path -> catalog.takeIf { path == "quran/tafsir/catalog.json" } }, packs)
    }
}
