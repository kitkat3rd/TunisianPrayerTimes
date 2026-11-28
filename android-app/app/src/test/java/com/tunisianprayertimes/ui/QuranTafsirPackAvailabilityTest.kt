package com.tunisianprayertimes.ui

import android.app.Application
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.createComposeRule
import com.tunisianprayertimes.quran.assets.QuranPack
import com.tunisianprayertimes.quran.assets.QuranPackArchive
import com.tunisianprayertimes.quran.assets.QuranPackFile
import com.tunisianprayertimes.quran.assets.QuranPackLayout
import com.tunisianprayertimes.quran.assets.QuranPackState
import com.tunisianprayertimes.quran.assets.QuranPackStatus
import com.tunisianprayertimes.quran.tafsir.TafsirBook
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class QuranTafsirPackAvailabilityTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun installingAnotherBookOrReplacingAnOldEditionReloadsTheReaderWhileDownloadProgressDoesNot() {
        val books = listOf("muyassar", "gharib").map { id ->
            TafsirBook(id, id, "fixture", "edition", false, setOf(1), pack = "quran_tafsir_$id")
        }
        val packs = books.map { book ->
            val name = requireNotNull(book.pack)
            QuranPack(
                name, QuranPack.Kind.Tafsir, QuranPack.Delivery.Cdn, null, null, 100L,
                QuranPackArchive("v1/packs/$name-0123456789ab.zip", 100L, "0".repeat(64)),
                listOf(QuranPackFile(QuranPackLayout.tafsirAsset(book.id), 100L, "1".repeat(64))),
            )
        }
        val media = QuranMedia(QuranPackLayout("https://example.test/", packs), null, emptySet(), tafsirBooks = books)
        val (meaning, glossary) = packs.map { it.name }
        val oldEdition = QuranPackState(QuranPackStatus.Downloading, 10, 100, previousVersion = true)
        val missing = false to false
        val previous = true to true
        val current = true to false
        media.states = mapOf(meaning to oldEdition)

        val reloads = mutableListOf<Map<String, Pair<Boolean, Boolean>>>()
        compose.setContent {
            val revision = rememberTafsirPackAvailability(media, books)
            LaunchedEffect(revision) { reloads.add(revision) }
        }
        fun update(pack: String, state: QuranPackState) {
            compose.runOnIdle { media.states = media.states + (pack to state) }
            compose.waitForIdle()
        }
        update(meaning, oldEdition.copy(downloadedBytes = 65))
        update(glossary, QuranPackState(QuranPackStatus.Downloading, 90, 100))
        update(glossary, QuranPackState.Available)
        update(meaning, QuranPackState.Available)
        update(glossary, QuranPackState.Missing)

        assertEquals(listOf(
            mapOf("muyassar" to previous, "gharib" to missing),
            mapOf("muyassar" to previous, "gharib" to current),
            mapOf("muyassar" to current, "gharib" to current),
            mapOf("muyassar" to current, "gharib" to missing),
        ), reloads)
    }
}
