package com.tunisianprayertimes.ui

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.assets.QuranDownloadProblem
import com.tunisianprayertimes.quran.assets.QuranPack
import com.tunisianprayertimes.quran.assets.QuranPackArchive
import com.tunisianprayertimes.quran.assets.QuranPackFile
import com.tunisianprayertimes.quran.assets.QuranPackLayout
import com.tunisianprayertimes.quran.assets.QuranPackSource
import com.tunisianprayertimes.quran.assets.QuranPackState
import com.tunisianprayertimes.quran.assets.QuranPackStatus
import com.tunisianprayertimes.quran.tafsir.TafsirBook
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
class FirstRunQuranDownloadsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val pages = pack("quran_pages", QuranPack.Kind.Pages)
    private val muyassar = pack("quran_tafsir_muyassar", QuranPack.Kind.Tafsir)
    private val ashur = pack("quran_tafsir_ashur", QuranPack.Kind.Tafsir)
    private val packs = listOf(pages, muyassar, ashur)
    private val source = object : QuranPackSource {
        override val states = MutableStateFlow(packs.associate { it.name to QuranPackState.Missing })
        override fun fetch(packs: List<QuranPack>, allowMetered: Boolean) { requests += packs }
        override fun cancel(packs: List<QuranPack>) { cancelled += packs }
        override fun confirmMobileData(packs: List<QuranPack>, launcher: ActivityResultLauncher<IntentSenderRequest>) {
            confirmed += packs
        }
        override fun remove(packs: List<QuranPack>) = error("No pack should be removed during onboarding")
        override fun directory(pack: QuranPack): File? = null
        override fun refresh() = Unit
    }
    private val requests = mutableListOf<List<QuranPack>>()
    private val cancelled = mutableListOf<QuranPack>()
    private val confirmed = mutableListOf<List<QuranPack>>()
    private var continued = 0

    private fun pack(name: String, kind: QuranPack.Kind) = QuranPack(
        name, kind, QuranPack.Delivery.Play, null, null, 1_000_000L,
        QuranPackArchive(name, 1_000_000L, "0".repeat(64)), listOf(QuranPackFile(name, 1_000_000L)),
    )

    private fun media(bookBundled: Boolean = false) = QuranMedia(
        QuranPackLayout("https://cdn.example/", packs), source, emptySet(), bookBundled = bookBundled,
        tafsirBooks = listOf(
            TafsirBook("ashur", "التحرير والتنوير", "", "", true, setOf(1), pack = ashur.name),
            TafsirBook("muyassar", "التفسير الميسّر", "", "", true, setOf(1), pack = muyassar.name),
        ),
    )

    @Test fun openingRestoringAndContinuingLeaveEveryDownloadOptional() {
        val media = media()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FirstRunQuranDownloadsContent(media) { continued++ } }
        compose.onNodeWithTag("onboarding_quran_downloads").assertIsDisplayed()
        compose.onNodeWithTag("quran_tafsir_card_muyassar").assertExists()
        compose.onNodeWithTag("quran_tafsir_card_ashur").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("onboarding_quran_continue").performClick()
        assertEquals(1, continued)
        assertTrue(requests.isEmpty())
        assertTrue(cancelled.isEmpty())
        assertTrue(confirmed.isEmpty())
    }

    @Test fun pagesAndMuyassarQueueIndependentlyAndContinueKeepsTheirDownloads() {
        val media = media()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FirstRunQuranDownloadsContent(media) { continued++ } }
        compose.onNodeWithTag("quran_pages_download_button").performScrollTo().performClick()
        assertEquals(listOf(listOf(pages)), requests)
        compose.runOnIdle { media.states = media.states + (pages.name to QuranPackState(QuranPackStatus.Pending)) }
        compose.onNodeWithTag("quran_tafsir_download").performScrollTo().performClick()
        compose.runOnIdle {
            media.states = media.states + (muyassar.name to QuranPackState(QuranPackStatus.Downloading, 300_000L, 1_000_000L))
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("quran_tafsir_progress").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("onboarding_quran_continue").assertIsEnabled().performClick()
        assertEquals(listOf(listOf(pages), listOf(muyassar)), requests)
        assertTrue(cancelled.isEmpty())
        assertEquals(1, continued)
    }

    @Test fun continueIsAvailableWhileLoadingAndBundledPagesNeverOfferAScanDownload() {
        val current = mutableStateOf<QuranMedia?>(null)
        compose.setContent { FirstRunQuranDownloadsContent(current.value) { continued++ } }
        compose.onNodeWithTag("quran_pages_download_button").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_quran_continue").assertIsEnabled().performClick()
        compose.runOnIdle { current.value = media(bookBundled = true) }
        compose.onNodeWithTag("quran_pages_download_button").assertDoesNotExist()
        compose.onNodeWithTag("quran_tafsir_download").assertExists()
        assertEquals(1, continued)
        assertTrue(requests.isEmpty())
    }

    @Test fun systemBackLeavesTheOfferWhileAPackWaitsForWifi() {
        val media = media()
        media.states = media.states + (pages.name to QuranPackState(QuranPackStatus.WaitingForWifi))
        lateinit var dispatcher: OnBackPressedDispatcher
        compose.setContent {
            dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            FirstRunQuranDownloadsContent(media) { continued++ }
        }
        compose.onNodeWithTag("quran_pages_mobile_data").assertExists()
        compose.runOnIdle { dispatcher.onBackPressed() }
        assertEquals(1, continued)
        assertTrue(requests.isEmpty())
        assertTrue(cancelled.isEmpty())
        assertTrue(confirmed.isEmpty())
    }

    @Test fun savedTafsirRemainsReadableWhileItsNewEditionDownloads() {
        val media = media()
        media.states = media.states + (muyassar.name to QuranPackState.Missing.copy(previousVersion = true))
        val app = RuntimeEnvironment.getApplication()
        val retained = app.getString(R.string.first_quran_retained)
        val update = app.getString(R.string.first_quran_update, quranSize(muyassar.archive.bytes))
        compose.setContent { FirstRunQuranDownloadsContent(media) { continued++ } }

        compose.onNodeWithText(retained).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("quran_tafsir_download").assertTextEquals(update).performScrollTo().performClick()
        assertEquals(listOf(listOf(muyassar)), requests)

        compose.runOnIdle {
            media.states = media.states + (muyassar.name to QuranPackState(
                QuranPackStatus.Downloading, 250_000L, 1_000_000L, previousVersion = true,
            ))
        }
        compose.onNodeWithText(retained).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("quran_tafsir_progress").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { media.states = media.states + (muyassar.name to QuranPackState.Available) }
        compose.onNodeWithText(app.getString(R.string.first_quran_available)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(retained).assertDoesNotExist()
        compose.onNodeWithTag("quran_tafsir_download").assertDoesNotExist()
    }

    @Test fun wifiAndPlayConfirmationActionsAffectOnlyTheSelectedPack() {
        val media = media()
        media.states = mapOf(
            pages.name to QuranPackState(QuranPackStatus.WaitingForWifi),
            muyassar.name to QuranPackState(QuranPackStatus.NeedsConfirmation),
        )
        val app = RuntimeEnvironment.getApplication()
        compose.setContent { FirstRunQuranDownloadsContent(media) { continued++ } }

        compose.onNodeWithText(app.getString(R.string.first_quran_wait_wifi)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("quran_pages_mobile_data").performScrollTo().performClick()
        assertEquals(listOf(listOf(pages)), confirmed)
        compose.onNodeWithText(app.getString(R.string.first_quran_wait_confirm)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("quran_tafsir_mobile_data").performScrollTo().performClick()
        assertEquals(listOf(listOf(pages), listOf(muyassar)), confirmed)
        compose.onNodeWithTag("quran_tafsir_cancel").performScrollTo().performClick()
        assertEquals(listOf(muyassar), cancelled)
        compose.onNodeWithTag("quran_pages_cancel").performScrollTo().performClick()
        assertEquals(listOf(muyassar, pages), cancelled)
        assertTrue(requests.isEmpty())
    }

    @Test fun failedPagesCanBeRetriedWhileUnpublishedTafsirExplainsItsAvailability() {
        val media = media()
        media.states = mapOf(
            pages.name to QuranPackState(QuranPackStatus.Failed, problem = QuranDownloadProblem.Storage),
            muyassar.name to QuranPackState(QuranPackStatus.Failed, problem = QuranDownloadProblem.Unpublished),
        )
        compose.setContent { FirstRunQuranDownloadsContent(media) { continued++ } }

        compose.onNodeWithText(quranDownloadProblemText(QuranDownloadProblem.Unpublished)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("quran_tafsir_download").assertDoesNotExist()
        compose.onNodeWithTag("quran_tafsir_cancel").assertDoesNotExist()
        compose.onNodeWithText(quranDownloadProblemText(QuranDownloadProblem.Storage)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("quran_pages_retry").performScrollTo().performClick()
        assertEquals(listOf(listOf(pages)), requests)
        compose.onNodeWithTag("onboarding_quran_continue").performClick()
        assertEquals(1, continued)
    }
}
