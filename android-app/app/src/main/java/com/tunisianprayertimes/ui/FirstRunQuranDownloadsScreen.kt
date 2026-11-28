package com.tunisianprayertimes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.assets.QuranDownloadProblem
import com.tunisianprayertimes.quran.assets.QuranPack
import com.tunisianprayertimes.quran.assets.QuranPackStatus
import com.tunisianprayertimes.ui.theme.BgCream
import com.tunisianprayertimes.ui.theme.CardBorder
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.TextMuted

@Composable
internal fun FirstRunQuranDownloadsScreen(onContinue: () -> Unit) {
    FirstRunQuranDownloadsContent(rememberQuranMedia(), onContinue)
}

@Composable
internal fun FirstRunQuranDownloadsContent(media: QuranMedia?, onContinue: () -> Unit) {
    BackHandler(onBack = onContinue)
    val title = stringResource(R.string.first_quran_offline_title)
    val confirmation = rememberMobileDataLauncher()
    Box(
        Modifier.fillMaxSize().background(BgCream).safeDrawingPadding()
            .testTag("onboarding_quran_downloads").semantics { paneTitle = title },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 504.dp).fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val description = if (media != null && media.layout == null) R.string.first_quran_no_downloads
                    else R.string.first_quran_offline_choice
                FirstRunHeader(title, stringResource(description), FirstRunArt.Quran)
                if (media == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (media.layout != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(), color = CardBorder,
                        shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, CardBorder),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            media.pages?.let { pages ->
                                FirstRunPackItem(media, pages) { media.source?.confirmMobileData(listOf(pages), confirmation) }
                            }
                            media.tafsir("muyassar")?.let { book ->
                                FirstRunPackItem(media, book) { media.source?.confirmMobileData(listOf(book), confirmation) }
                            }
                            FirstRunRecitationDownload(media)
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = CardBorder)
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                if (media?.states?.values?.any { it.active } == true) {
                    Text(stringResource(R.string.first_quran_background), Modifier.padding(bottom = 6.dp),
                        color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
                }
                Button(
                    onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .testTag("onboarding_quran_continue"),
                    shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                ) { Text(stringResource(R.string.first_quran_continue), fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun FirstRunPackItem(media: QuranMedia, pack: QuranPack, confirm: () -> Unit) {
    val isBook = pack.kind == QuranPack.Kind.Tafsir
    val prefix = if (isBook) "quran_tafsir" else "quran_pages"
    val title = if (isBook) media.tafsirTitle(pack) ?: return else stringResource(R.string.first_quran_pages_title)
    val subtitle = stringResource(if (isBook) R.string.first_quran_tafsir_description else R.string.first_quran_pages_description)
    val state = media.state(pack)
    val size = quranSize(pack.archive.bytes)
    FirstRunLibraryItem(
        title, subtitle, if (isBook) R.drawable.ic_adhkar_list else R.drawable.ic_tab_quran,
        Modifier.testTag(if (isBook) "quran_tafsir_card_${pack.tafsirBookId}" else "quran_pages_download"),
    ) {
        if ((!isBook && media.pagesReady) || state.status == QuranPackStatus.Available) {
            Text(stringResource(R.string.first_quran_available), color = GreenPrimary, fontSize = 14.sp)
            return@FirstRunLibraryItem
        }
        if (!isBook && !media.pagesStateKnown) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@FirstRunLibraryItem
        }
        if (state.previousVersion) Text(stringResource(R.string.first_quran_retained), color = TextMuted)
        when (state.status) {
            QuranPackStatus.Missing, QuranPackStatus.Failed -> {
                if (state.status == QuranPackStatus.Failed) Text(quranDownloadProblemText(state.problem), color = MaterialTheme.colorScheme.error)
                if (state.problem != QuranDownloadProblem.Unpublished) {
                    if (!isBook && media.isMetered()) Text(stringResource(R.string.first_quran_metered), color = TextMuted)
                    val action = when {
                        state.status == QuranPackStatus.Failed -> R.string.first_quran_retry
                        state.previousVersion -> R.string.first_quran_update
                        isBook -> R.string.first_quran_download_tafsir
                        else -> R.string.first_quran_download_pages
                    }
                    val tag = when {
                        isBook -> "quran_tafsir_download"
                        state.status == QuranPackStatus.Failed -> "quran_pages_retry"
                        else -> "quran_pages_download_button"
                    }
                    FirstRunLibraryAction(stringResource(action, size),
                        onClick = { media.source?.fetch(listOf(pack), allowMetered = true) }, modifier = Modifier.testTag(tag))
                }
            }
            QuranPackStatus.WaitingForWifi, QuranPackStatus.NeedsConfirmation -> {
                val wifi = state.status == QuranPackStatus.WaitingForWifi
                Text(stringResource(if (wifi) R.string.first_quran_wait_wifi else R.string.first_quran_wait_confirm), color = TextMuted)
                FirstRunLibraryAction(stringResource(if (wifi) R.string.first_quran_use_mobile else R.string.first_quran_confirm_download, size),
                    confirm, Modifier.testTag("${prefix}_mobile_data"))
            }
            QuranPackStatus.Pending -> Text(stringResource(R.string.first_quran_pending), color = TextMuted)
            QuranPackStatus.Downloading, QuranPackStatus.Transferring -> {
                val total = state.totalBytes.takeIf { it > 0 } ?: pack.archive.bytes.coerceAtLeast(1L)
                val progress = if (state.status == QuranPackStatus.Transferring) 1f
                    else (state.downloadedBytes.toFloat() / total).coerceIn(0f, 1f)
                Text(if (state.status == QuranPackStatus.Transferring) stringResource(R.string.first_quran_preparing)
                    else stringResource(R.string.first_quran_download_progress, quranPercent(progress)), color = TextMuted)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().testTag("${prefix}_progress"))
            }
            QuranPackStatus.Available -> Unit
        }
        if (state.active) TextButton(onClick = { media.source?.cancel(listOf(pack)) }, modifier = Modifier.testTag("${prefix}_cancel")) {
            Text(stringResource(R.string.first_quran_stop_download))
        }
    }
}
