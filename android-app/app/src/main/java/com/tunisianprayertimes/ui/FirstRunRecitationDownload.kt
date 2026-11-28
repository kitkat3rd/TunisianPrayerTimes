package com.tunisianprayertimes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.QuranRepository
import com.tunisianprayertimes.quran.QuranSurah
import com.tunisianprayertimes.quran.assets.QuranPack
import com.tunisianprayertimes.quran.assets.QuranPackStatus
import com.tunisianprayertimes.quran.audio.QuranAudioController
import com.tunisianprayertimes.quran.audio.QuranReciter
import com.tunisianprayertimes.quran.quranNameKey
import com.tunisianprayertimes.ui.theme.BgCream
import com.tunisianprayertimes.ui.theme.CardBg
import com.tunisianprayertimes.ui.theme.CardBorder
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun FirstRunRecitationDownload(media: QuranMedia) {
    val layout = media.layout
    val reciters = remember(layout) {
        QuranAudioController.reciters.filter { layout?.audioPacks(it.id)?.isNotEmpty() == true }
    }
    if (reciters.isEmpty()) return
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    // Zero is the complete recitation. Keep the committed selection until a scope is chosen.
    var selectedSurah by rememberSaveable { mutableIntStateOf(0) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    val reciter = reciters.firstOrNull { it.id == selectedId }
    val context = LocalContext.current.applicationContext
    val needNames = pickerOpen || (reciter != null && selectedSurah > 0)
    val chapters by produceState<List<QuranSurah>?>(QuranRepository.loaded()?.surahs, context, needNames) {
        if (needNames && value == null) value = withContext(Dispatchers.IO) {
            runCatching { QuranRepository.load(context).surahs }.getOrDefault(emptyList())
        }
    }
    val nameOf = { number: Int -> chapters?.getOrNull(number - 1)?.name ?: number.toString() }
    val subtitle = if (reciter == null) stringResource(R.string.first_run_audio_description) else {
        val scope = if (selectedSurah == 0) stringResource(R.string.first_run_audio_full)
            else stringResource(R.string.first_run_audio_surah, nameOf(selectedSurah))
        stringResource(R.string.first_run_audio_choice, scope, reciter.name)
    }
    FirstRunLibraryItem(
        title = stringResource(R.string.first_run_audio_title),
        subtitle = subtitle,
        icon = R.drawable.ic_quran_headphones,
        modifier = Modifier.testTag("first_run_audio"),
    ) {
        if (reciter == null) {
            FirstRunLibraryAction(stringResource(R.string.first_run_audio_choose), { pickerOpen = true },
                Modifier.testTag("first_run_audio_choose"))
        } else {
            val packs = remember(layout, reciter, selectedSurah) {
                if (selectedSurah == 0) layout?.audioPacks(reciter.id).orEmpty()
                else media.recitationPacks(reciter.id, selectedSurah..selectedSurah)
            }
            if (selectedSurah > 0) {
                val companions = packs.firstOrNull()?.let { quranPackCompanions(it, selectedSurah, nameOf) }.orEmpty()
                if (companions.isNotEmpty()) {
                    Text(stringResource(R.string.first_run_audio_companions, companions),
                        color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (packs.isNotEmpty()) RecitationDownloadActions(media, packs, complete = selectedSurah == 0)
            TextButton(onClick = { pickerOpen = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("first_run_audio_choose")) {
                Text(stringResource(R.string.first_run_audio_change), color = GreenPrimaryDark)
            }
        }
    }
    if (pickerOpen) {
        RecitationPicker(media, reciters, chapters, selectedId, selectedSurah,
            onSelect = { id, number ->
                selectedId = id
                selectedSurah = number
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun RecitationPicker(
    media: QuranMedia,
    reciters: List<QuranReciter>,
    chapters: List<QuranSurah>?,
    selectedId: String?,
    selectedSurah: Int,
    onSelect: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var draftId by rememberSaveable { mutableStateOf<String?>(null) }
    val draft = reciters.firstOrNull { it.id == draftId }
    val title = draft?.name ?: stringResource(R.string.first_run_audio_reciters)
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(enabled = draft != null) { draftId = null }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(
                modifier = Modifier.safeDrawingPadding().padding(horizontal = 12.dp, vertical = 10.dp)
                    .widthIn(max = 452.dp).fillMaxWidth().heightIn(max = 584.dp).semantics { paneTitle = title },
                shape = RoundedCornerShape(24.dp), color = BgCream,
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().background(GreenPrimaryDark).padding(horizontal = 6.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (draft != null) {
                            IconButton(onClick = { draftId = null },
                                modifier = Modifier.size(48.dp).testTag("first_run_audio_picker_back")) {
                                Icon(painterResource(R.drawable.ic_adhkar_back), stringResource(R.string.first_run_audio_back),
                                    Modifier.size(24.dp), tint = Color.White)
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = if (draft == null) 12.dp else 0.dp)) {
                            Text(title, Modifier.semantics { heading() }, color = Color.White,
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            val riwaya = draft?.riwaya ?: reciters.map { it.riwaya }.distinct().singleOrNull()
                            if (riwaya != null) Text(riwaya, style = MaterialTheme.typography.bodySmall, color = Color.White)
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).testTag("first_run_audio_picker_close")) {
                            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.first_run_audio_close),
                                Modifier.size(20.dp), tint = Color.White)
                        }
                    }
                    key(draft?.id) {
                        val listModifier = Modifier.fillMaxWidth().weight(1f, fill = draft != null).testTag("first_run_audio_picker_list")
                        if (draft == null) {
                            LazyColumn(listModifier, contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(reciters, key = { it.id }) { voice ->
                                    RecitationChoice(voice.name, "first_run_audio_reciter_${voice.id}", selectedId == voice.id,
                                        { draftId = voice.id }, detail = media.reciterStatus(voice.id))
                                }
                            }
                        } else {
                            RecitationScopes(media, draft, chapters, selectedSurah.takeIf { selectedId == draft.id },
                                listModifier, onChoose = { onSelect(draft.id, it) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecitationScopes(
    media: QuranMedia,
    reciter: QuranReciter,
    chapters: List<QuranSurah>?,
    selectedSurah: Int?,
    modifier: Modifier,
    onChoose: (Int) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val listState = rememberLazyListState()
    val updateQuery: (String) -> Unit = { value ->
        if (query.isNotBlank() && value.isBlank()) listState.requestScrollToItem(0)
        query = value
    }
    val packs = remember(media.layout, reciter.id) { media.layout?.audioPacks(reciter.id).orEmpty() }
    val available = remember(packs) { (1..114).filter { number -> packs.any { it.surahs?.contains(number) == true } } }
    val nameOf = { number: Int -> chapters?.getOrNull(number - 1)?.name ?: number.toString() }
    val matches = remember(available, chapters, query) {
        val needle = quranNameKey(query)
        val number = needle.toIntOrNull() // Recognizes both Western and Arabic-Indic digits.
        available.filter { it == number || (number == null && quranNameKey(nameOf(it)).contains(needle)) }
    }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(12.dp)) {
        if (query.isBlank()) {
            item(key = "full") {
                RecitationChoice(stringResource(R.string.first_run_audio_full), "first_run_audio_full", selectedSurah == 0,
                    { onChoose(0) }, detail = media.reciterStatus(reciter.id), container = GreenPrimary.copy(alpha = 0.09f),
                    leading = { Icon(painterResource(R.drawable.ic_tab_quran), null, Modifier.size(26.dp), tint = GreenPrimaryDark) })
            }
        }
        item(key = "search") {
            Column(Modifier.fillMaxWidth().padding(top = if (query.isBlank()) 16.dp else 0.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (query.isBlank()) {
                    Text(stringResource(R.string.first_run_audio_single), Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = GreenPrimaryDark)
                }
                OutlinedTextField(
                    value = query, onValueChange = updateQuery, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("first_run_audio_search"),
                    label = { Text(stringResource(R.string.first_run_audio_search)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_adhkar_search), null, Modifier.size(20.dp), tint = GreenPrimaryDark) },
                    trailingIcon = if (query.isEmpty()) null else ({
                        IconButton(onClick = { updateQuery("") }) {
                            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.first_run_audio_clear),
                                Modifier.size(18.dp), tint = GreenPrimaryDark)
                        }
                    }),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GreenPrimary, unfocusedBorderColor = TextMuted,
                        focusedLabelColor = GreenPrimaryDark, unfocusedLabelColor = TextMuted,
                        cursorColor = GreenPrimary, focusedContainerColor = CardBg, unfocusedContainerColor = CardBg,
                    ),
                )
            }
        }
        when {
            chapters == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = GreenPrimary) }
            matches.isEmpty() -> item {
                Text(stringResource(R.string.first_run_audio_empty), Modifier.padding(12.dp),
                    color = TextMuted, style = MaterialTheme.typography.bodyMedium)
            }
            else -> items(matches, key = { it }) { number ->
                RecitationChoice(stringResource(R.string.first_run_audio_surah, nameOf(number)), "first_run_audio_surah_$number",
                    selectedSurah == number, { onChoose(number) }, container = Color.Transparent, leading = {
                        Box(Modifier.widthIn(min = 34.dp).background(CardBg, RoundedCornerShape(8.dp)).padding(4.dp),
                            contentAlignment = Alignment.Center) {
                            Text(number.toString(), style = MaterialTheme.typography.labelMedium,
                                color = GreenPrimaryDark, fontWeight = FontWeight.Bold)
                        }
                    })
                HorizontalDivider(color = CardBorder)
            }
        }
    }
}

@Composable
private fun RecitationChoice(
    title: String,
    tag: String,
    chosen: Boolean,
    onClick: () -> Unit,
    detail: String? = null,
    container: Color = CardBg,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(container)
            .clickable(role = Role.Button, onClick = onClick).semantics { selected = chosen }
            .heightIn(min = 52.dp).testTag(tag).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = GreenPrimaryDark, fontWeight = FontWeight.SemiBold)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
        Icon(painterResource(if (chosen) R.drawable.ic_check else R.drawable.ic_qibla_chevron), null,
            Modifier.size(18.dp), tint = GreenPrimary)
    }
}

@Composable
private fun RecitationDownloadActions(media: QuranMedia, packs: List<QuranPack>, complete: Boolean) {
    val missing = media.missing(packs)
    if (missing.isEmpty()) {
        Text(stringResource(R.string.first_run_audio_available), color = GreenPrimaryDark, style = MaterialTheme.typography.bodyMedium)
        return
    }
    val summary = media.summary(missing)
    val launcher = rememberMobileDataLauncher()
    when (summary?.status) {
        QuranPackStatus.NeedsConfirmation, QuranPackStatus.WaitingForWifi -> {
            val waiting = missing.filter { media.state(it).status == summary.status }
            val wifi = summary.status == QuranPackStatus.WaitingForWifi
            if (wifi) Text(stringResource(R.string.first_quran_wait_wifi), color = TextMuted, style = MaterialTheme.typography.bodyMedium)
            FirstRunLibraryAction(
                stringResource(if (wifi) R.string.first_quran_use_mobile else R.string.first_quran_confirm_download,
                    quranSize(waiting.sumOf { it.archive.bytes })),
                { media.source?.confirmMobileData(waiting, launcher) },
                Modifier.testTag("first_run_audio_confirm"),
            )
        }
        QuranPackStatus.Pending, QuranPackStatus.Downloading, QuranPackStatus.Transferring -> {
            val progress = media.progress(packs) ?: 0f
            Text(if (summary.status == QuranPackStatus.Transferring && missing.size == 1)
                stringResource(R.string.first_run_audio_preparing) else stringResource(R.string.first_run_audio_progress, quranPercent(progress)),
                color = GreenPrimaryDark, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = GreenPrimary)
        }
        QuranPackStatus.Failed -> Text(quranDownloadProblemText(summary.problem), color = MaterialTheme.colorScheme.error)
        else -> Unit
    }
    val fetchable = missing.filterNot { media.state(it).active }
    if (fetchable.isNotEmpty()) {
        if (media.isMetered()) Text(stringResource(if (complete) R.string.first_run_audio_metered else R.string.first_quran_metered),
            color = TextMuted, style = MaterialTheme.typography.bodyMedium)
        val label = if (summary?.status == QuranPackStatus.Failed) R.string.first_run_audio_retry else R.string.first_run_audio_download
        FirstRunLibraryAction(stringResource(label, quranSize(fetchable.sumOf { it.archive.bytes })),
            { media.fetch(fetchable, allowMetered = !complete) }, Modifier.testTag("first_run_audio_download"))
    }
    val active = missing.filter { media.state(it).active }
    if (active.isNotEmpty()) {
        TextButton(onClick = { media.source?.cancel(active) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.first_quran_stop_download), color = GreenPrimaryDark)
        }
    }
}
