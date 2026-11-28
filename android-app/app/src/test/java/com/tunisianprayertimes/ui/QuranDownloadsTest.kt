package com.tunisianprayertimes.ui

import android.app.Application
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import com.tunisianprayertimes.quran.assets.QuranDownloadProblem
import com.tunisianprayertimes.quran.assets.QuranPack
import com.tunisianprayertimes.quran.assets.QuranPackArchive
import com.tunisianprayertimes.quran.assets.QuranPackFile
import com.tunisianprayertimes.quran.assets.QuranPackLayout
import com.tunisianprayertimes.quran.assets.QuranPackSource
import com.tunisianprayertimes.quran.assets.QuranPackState
import com.tunisianprayertimes.quran.assets.QuranPackStatus
import com.tunisianprayertimes.quran.tafsir.TafsirBook
import com.tunisianprayertimes.quran.tafsir.TafsirCatalogStatus
import com.tunisianprayertimes.quran.tafsir.tafsirFixtureRepository
import com.tunisianprayertimes.quran.QuranVerseReference
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
class QuranDownloadsTest {
    @get:Rule
    val compose = createComposeRule()

    private class FakeSource : QuranPackSource {
        override val states = MutableStateFlow<Map<String, QuranPackState>>(emptyMap())
        val fetched = mutableListOf<Pair<List<String>, Boolean>>()
        val confirmed = mutableListOf<String>()
        val removed = mutableListOf<String>()
        override fun directory(pack: QuranPack): File? = null
        override fun fetch(packs: List<QuranPack>, allowMetered: Boolean) { fetched += packs.map { it.name } to allowMetered }
        override fun confirmMobileData(packs: List<QuranPack>, launcher: ActivityResultLauncher<IntentSenderRequest>) { confirmed += packs.map { it.name } }
        override fun remove(packs: List<QuranPack>) { removed += packs.map { it.name } }
        val cancelled = mutableListOf<String>()
        override fun cancel(packs: List<QuranPack>) { cancelled += packs.map { it.name } }
        override fun refresh() = Unit
    }

    private fun pack(name: String, kind: QuranPack.Kind, surahs: IntRange? = null, bytes: Long = 30_000_000L) = QuranPack(
        name, kind, QuranPack.Delivery.Play, if (kind == QuranPack.Kind.Audio) "hosary-qaloun" else null, surahs, bytes,
        QuranPackArchive("v1/packs/$name-0123456789ab.zip", bytes, "0".repeat(64)), listOf(QuranPackFile("quran/x/$name", bytes)),
    )

    private val pages = pack("quran_pages", QuranPack.Kind.Pages, bytes = 176_000_000L)
    private val first = pack("quran_hosary_01", QuranPack.Kind.Audio, 1..1, 1_000_000L)
    private val second = pack("quran_hosary_02", QuranPack.Kind.Audio, 2..2, 168_000_000L)
    private val rest = pack("quran_hosary_03", QuranPack.Kind.Audio, 3..114, 100_000_000L)
    private val layout = QuranPackLayout("https://cdn.example/", listOf(pages, first, second, rest))

    private fun media(source: FakeSource, metered: Boolean = false, bundled: Set<String> = emptySet()) =
        QuranMedia(layout, source, bundled, metered = { metered })

    private val books = listOf(
        "muyassar" to "التفسير الميسر", "mukhtasar" to "المختصر في تفسير القرآن الكريم",
        "gharib" to "الميسر في غريب القرآن الكريم", "juzayy" to "التسهيل لعلوم التنزيل", "ashur" to "التحرير والتنوير",
    ).map { (id, title) -> TafsirBook(id, title, "الناشر", "الطبعة", true, setOf(1), pack = "quran_tafsir_$id") }
    private val bookPacks = books.mapIndexed { index, book ->
        val bytes = (index + 1) * 1_000_000L
        pack(requireNotNull(book.pack), QuranPack.Kind.Tafsir, bytes = bytes)
            .copy(files = listOf(QuranPackFile("quran/tafsir/${book.id}.db", bytes)))
    }

    @Test
    fun restoredVerseDoesNotDownloadUntilAReaderExplicitlySelectsAPack() {
        val source = FakeSource()
        val media = QuranMedia(QuranPackLayout(layout.cdn, bookPacks), source, emptySet(), tafsirBooks = books)
        val session = QuranTafsirSession(tafsirFixtureRepository({ null }), QuranVerseReference(1, 3))
        session.catalog = TafsirCatalogStatus.AVAILABLE to books
        val confirmations = mutableListOf<List<String>>()
        fun dispatch() = session.startRequestedBooks(media) { packs -> confirmations += packs.map { it.name } }
        dispatch()
        assertTrue(source.fetched.isEmpty())
        assertTrue(confirmations.isEmpty())

        media.states = mapOf(
            "quran_tafsir_juzayy" to QuranPackState.Available,
            "quran_tafsir_ashur" to QuranPackState(QuranPackStatus.Downloading),
            "quran_tafsir_mukhtasar" to QuranPackState(QuranPackStatus.WaitingForWifi),
            "quran_tafsir_gharib" to QuranPackState(QuranPackStatus.NeedsConfirmation),
        )
        for (id in listOf("muyassar", "mukhtasar", "ashur", "juzayy", "mukhtasar", "unknown")) session.requestBook(id)
        dispatch()
        dispatch()
        assertEquals(listOf(listOf("quran_tafsir_muyassar") to true), source.fetched)
        assertEquals(listOf(listOf("quran_tafsir_mukhtasar")), confirmations)
        session.requestBook("gharib")
        dispatch()
        assertEquals(listOf(listOf("quran_tafsir_mukhtasar"), listOf("quran_tafsir_gharib")), confirmations)
        assertEquals(1, source.fetched.size)
        assertEquals(QuranVerseReference(1, 3), session.verse)
    }

    @Test
    fun downloadsOnlyListsBooksWhoseNamesAndPackIdsAgreeWithTheReleasedCatalog() {
        val full = QuranPackLayout(layout.cdn, layout.packs + bookPacks.reversed())
        val catalog = books.filterNot { it.id == "ashur" }.map {
            if (it.id == "juzayy") it.copy(pack = books.first().pack) else it
        }
        val bundled = setOf(requireNotNull(books.first().pack))
        val media = QuranMedia(full, FakeSource(), bundled, tafsirBooks = catalog)
        assertEquals(setOf("mukhtasar", "gharib"), media.tafsirPacks.map { it.tafsirBookId }.toSet())
        assertEquals(bookPacks[2], media.tafsir("gharib"))
        assertEquals(null, media.tafsir("juzayy"))
        assertEquals(null, media.tafsir("ashur"))
        assertEquals(null, media.tafsir("muyassar"))
    }

    @Test
    fun aBookRequestedWhileReadingCanUseMobileDataAndCancelItsOwnDownload() {
        val source = FakeSource()
        val allPacks = QuranPackLayout(layout.cdn, layout.packs + bookPacks)
        val media = QuranMedia(allPacks, source, emptySet(), tafsirBooks = books)
        val selected = requireNotNull(media.tafsir("juzayy"))
        var starts = 0
        var cancellations = 0
        compose.setContent {
            QuranTafsirDownloadCard(media, selected, onUseMobileData = {},
                onDownloadStarted = { starts++ }, onDownloadCancelled = { cancellations++ })
        }
        compose.onNodeWithTag("quran_tafsir_download").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(listOf(selected.name) to true), source.fetched)
        assertEquals(1, starts)
        compose.runOnIdle { media.states = mapOf(selected.name to QuranPackState(QuranPackStatus.Pending)) }
        compose.onNodeWithTag("quran_tafsir_cancel").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(selected.name), source.cancelled)
        assertEquals(1, cancellations)
    }

    @Test
    fun downloadsSheetShowsFiveBooksAndActsOnlyOnTheSelectedOne() {
        val source = FakeSource()
        val allPacks = QuranPackLayout(layout.cdn, layout.packs + bookPacks.reversed())
        val media = QuranMedia(allPacks, source, emptySet(), tafsirBooks = books)
        val glossary = requireNotNull(media.tafsir("gharib"))
        val concise = requireNotNull(media.tafsir("muyassar"))
        media.states = mapOf(first.name to QuranPackState.Available, concise.name to QuranPackState.Available)
        val deletedReciters = mutableListOf<String>()
        compose.setContent { QuranDownloadsSheet(media, rememberMobileDataLauncher(), { deletedReciters += it }, {}) }
        for (book in books) {
            val card = hasAnyAncestor(hasTestTag("quran_tafsir_card_${book.id}"))
            compose.onNode(card and hasText(book.title, substring = true)).performScrollTo().assertIsDisplayed()
        }
        val inGlossary = hasAnyAncestor(hasTestTag("quran_tafsir_card_gharib"))
        fun glossaryAction(tag: String) = compose.onNode(inGlossary and hasTestTag("quran_tafsir_$tag"))
        glossaryAction("download").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(listOf(glossary.name) to true), source.fetched)
        fun setGlossary(state: QuranPackState) = compose.runOnIdle {
            media.states = media.states + (glossary.name to state)
        }
        setGlossary(QuranPackState(QuranPackStatus.Downloading, 1_500_000, 3_000_000))
        glossaryAction("progress").performScrollTo().assertIsDisplayed()
        glossaryAction("cancel").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(glossary.name), source.cancelled)
        setGlossary(QuranPackState(QuranPackStatus.NeedsConfirmation))
        glossaryAction("mobile_data").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(glossary.name), source.confirmed)
        setGlossary(QuranPackState(QuranPackStatus.Failed, problem = QuranDownloadProblem.Storage))
        compose.onNode(inGlossary and hasText(quranDownloadProblemText(QuranDownloadProblem.Storage))).assertExists()
        glossaryAction("download").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(listOf(glossary.name) to true, listOf(glossary.name) to true), source.fetched)
        setGlossary(QuranPackState.Available)
        glossaryAction("delete").performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(source.removed.isEmpty())
        compose.onNodeWithTag("quran_tafsir_delete_confirm").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(glossary.name), source.removed)
        assertTrue(deletedReciters.isEmpty())
    }

    @Test
    fun returningToDownloadedPagesStartsWithTheSourcesCurrentSnapshot() {
        val source = FakeSource().apply { states.value = mapOf(pages.name to QuranPackState.Available) }
        val media = media(source)
        assertTrue(media.pagesStateKnown)
        assertTrue(media.pagesReady)
        compose.setContent {
            QuranPagesEmptyState(media, onDownload = {}, onUseMobileData = {})
            QuranPagesDownloadBanner(media, onDownload = {}, onUseMobileData = {})
        }
        compose.onNodeWithTag("quran_pages_empty").assertDoesNotExist()
        compose.onNodeWithTag("quran_pages_download").assertDoesNotExist()
    }

    @Test
    fun checkingTheDeviceDoesNotShowMissingPagesButAConfirmedMissingPackDoes() {
        val media = media(FakeSource())
        assertFalse(media.pagesStateKnown)
        compose.setContent {
            QuranPagesEmptyState(media, onDownload = {}, onUseMobileData = {})
            QuranPagesDownloadBanner(media, onDownload = {}, onUseMobileData = {})
        }
        compose.onNodeWithTag("quran_pages_empty").assertDoesNotExist()
        compose.onNodeWithTag("quran_pages_download").assertDoesNotExist()
        compose.runOnIdle { media.states = mapOf(pages.name to QuranPackState.Missing) }
        assertTrue(media.pagesStateKnown)
        compose.onNodeWithTag("quran_pages_empty").assertIsDisplayed()
        compose.onNodeWithTag("quran_pages_download").assertIsDisplayed()
    }

    @Test
    fun aRecitationStartsOnceItsChaptersAreDownloaded() {
        val source = FakeSource()
        val media = media(source)
        val starter = QuranRecitationStarter { media }
        var started = 0
        starter.start("hosary-qaloun", 1..2) { started++ }
        assertEquals(0, started)
        // One recitation is worth mobile data; the listener asked for it.
        assertEquals(listOf(listOf("quran_hosary_01", "quran_hosary_02") to true), source.fetched)
        assertEquals(listOf("quran_hosary_01", "quran_hosary_02"), starter.pendingPacks.map { it.name })

        media.states = mapOf("quran_hosary_01" to QuranPackState.Available)
        starter.onStatesChanged()
        assertEquals(0, started)
        media.states = media.states + ("quran_hosary_02" to QuranPackState.Available)
        starter.onStatesChanged()
        starter.onStatesChanged()
        assertEquals(1, started)
        assertTrue(starter.pendingPacks.isEmpty())
    }

    @Test
    fun aRecitationOnTheDeviceStartsAtOnceAndFetchesTheNextChapterOnWifiOnly() {
        val source = FakeSource()
        val onWifi = media(source).apply { states = mapOf("quran_hosary_01" to QuranPackState.Available) }
        var started = 0
        QuranRecitationStarter { onWifi }.start("hosary-qaloun", 1..1) { started++ }
        assertEquals(1, started)
        assertEquals(listOf(listOf("quran_hosary_02") to false), source.fetched)

        val metered = FakeSource()
        val onMobile = media(metered, metered = true).apply { states = mapOf("quran_hosary_01" to QuranPackState.Available) }
        QuranRecitationStarter { onMobile }.start("hosary-qaloun", 1..1) { started++ }
        assertEquals(2, started)
        assertTrue(metered.fetched.isEmpty())
    }

    @Test
    fun aWaitingRecitationIsForgottenWhenTheReaderLeaves() {
        val source = FakeSource()
        val media = media(source)
        val starter = QuranRecitationStarter { media }
        var started = 0
        starter.start("hosary-qaloun", 3..3) { started++ }
        starter.cancel()
        media.states = mapOf("quran_hosary_03" to QuranPackState.Available)
        starter.onStatesChanged()
        assertEquals(0, started)
        // Leaving only forgets the start: the download goes on.
        assertTrue(source.cancelled.isEmpty())
    }

    @Test
    fun cancellingAWaitingRecitationStopsOnlyTheDownloadItAskedFor() {
        val source = FakeSource()
        // The third chapter was already coming, from «تنزيل كل التلاوات».
        val media = media(source).apply { states = mapOf("quran_hosary_03" to QuranPackState(QuranPackStatus.Downloading, 1L, 100L)) }
        val starter = QuranRecitationStarter { media }
        var started = 0
        starter.start("hosary-qaloun", 2..3) { started++ }
        starter.cancelDownload()
        assertEquals(listOf("quran_hosary_02"), source.cancelled)
        assertTrue(starter.pendingPacks.isEmpty())
        media.states = mapOf("quran_hosary_02" to QuranPackState.Available, "quran_hosary_03" to QuranPackState.Available)
        starter.onStatesChanged()
        assertEquals(0, started)
    }

    @Test
    fun bundledPacksNeedNoDownload() {
        val source = FakeSource()
        val media = media(source, bundled = setOf("quran_pages", "quran_hosary_01", "quran_hosary_02", "quran_hosary_03"))
        var started = 0
        QuranRecitationStarter { media }.start("hosary-qaloun", 1..114) { started++ }
        assertEquals(1, started)
        assertTrue(media.pagesReady)
        assertTrue(source.fetched.isEmpty())
    }

    @Test
    fun theReaderDownloadsTheBookWhereTheLayoutHasOne() {
        val book = pack("quran_book", QuranPack.Kind.Book, bytes = 90_000_000L)
        val source = FakeSource()
        val media = QuranMedia(QuranPackLayout("https://cdn.example/", listOf(book, first, second, rest)), source, emptySet(), metered = { false })
        assertEquals(book, media.pages)
        assertTrue(!media.pagesReady)
        media.fetch(listOfNotNull(media.pages), allowMetered = false)
        assertEquals(listOf(listOf("quran_book") to false), source.fetched)
        source.states.value = mapOf("quran_book" to QuranPackState.Available)
        media.states = source.states.value
        assertTrue(media.pagesReady)
    }

    @Test
    fun pagesBannerOffersTheDownloadThenShowsItsProgressAndProblems() {
        val source = FakeSource().apply { states.value = mapOf(pages.name to QuranPackState.Missing) }
        val media = media(source)
        val downloads = mutableListOf<Boolean>()
        compose.setContent { QuranPagesDownloadBanner(media, onDownload = { downloads += it }, onUseMobileData = {}) }

        compose.onNodeWithText("صفحات المصحف غير محمّلة", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("quran_pages_download_button").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(true), downloads)

        media.states = mapOf("quran_pages" to QuranPackState(QuranPackStatus.Downloading, 88_000_000L, 176_000_000L))
        compose.onNodeWithText("جارٍ تنزيل صفحات المصحف", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("quran_pages_progress").assertIsDisplayed()

        media.states = mapOf("quran_pages" to QuranPackState(QuranPackStatus.WaitingForWifi, 0, 176_000_000L))
        compose.onNodeWithTag("quran_pages_mobile_data").assertIsDisplayed()

        media.states = mapOf("quran_pages" to QuranPackState(QuranPackStatus.Failed, problem = QuranDownloadProblem.Storage))
        compose.onNodeWithText("لا توجد مساحة كافية على الجهاز.").assertIsDisplayed()
        compose.onNodeWithTag("quran_pages_retry").assertIsDisplayed()

        media.states = mapOf("quran_pages" to QuranPackState.Available)
        compose.onNodeWithTag("quran_pages_download").assertDoesNotExist()
    }

    @Test
    fun listeningSheetCountsDownloadedChaptersAndDeletesThemAfterConfirming() {
        val source = FakeSource()
        val media = media(source).apply {
            states = mapOf("quran_hosary_01" to QuranPackState.Available, "quran_hosary_02" to QuranPackState.Available)
        }
        var downloadAll = 0
        val deleted = mutableListOf<String>()
        compose.setContent {
            QuranRecitationDownloadsSection(media, "hosary-qaloun", onDownloadAll = { downloadAll++ }, onUseMobileData = {},
                onDelete = { packs -> deleted += packs.map { it.name } })
        }
        compose.onNodeWithText("التلاوات المحمّلة: 2 من 114\u00A0سورة").assertIsDisplayed()
        compose.onNodeWithTag("quran_audio_download_all").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, downloadAll)

        compose.onNodeWithTag("quran_audio_delete_downloads").performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(deleted.isEmpty())
        compose.onNodeWithTag("quran_audio_delete_confirm").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("quran_hosary_01", "quran_hosary_02"), deleted)
    }

    @Test
    fun aDownloadUnderWayCanBeStoppedAndDeleted() {
        val source = FakeSource()
        val media = media(source).apply {
            states = mapOf("quran_hosary_01" to QuranPackState.Available, "quran_hosary_02" to QuranPackState(QuranPackStatus.Downloading, 1L, 100L))
        }
        val deleted = mutableListOf<String>()
        compose.setContent {
            QuranRecitationDownloadsSection(media, "hosary-qaloun", onDownloadAll = {}, onUseMobileData = {},
                onDelete = { packs -> deleted += packs.map { it.name } })
        }
        compose.onNodeWithText("أوقف التنزيل واحذف ما حُمّل").assertIsDisplayed()
        compose.onNodeWithTag("quran_audio_delete_downloads").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("quran_audio_delete_confirm").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("quran_hosary_01", "quran_hosary_02"), deleted)
    }
}
