package com.tunisianprayertimes.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.quranAyah
import com.tunisianprayertimes.quran.quranAyahSpan
import com.tunisianprayertimes.quran.tafsir.TafsirBook
import com.tunisianprayertimes.quran.tafsir.TafsirCorrections
import com.tunisianprayertimes.quran.tafsir.TafsirNote
import com.tunisianprayertimes.quran.tafsir.TafsirUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer

internal fun tafsirRange(from: Int, to: Int): String = when {
    from == 0 && to == 0 -> "البسملة"
    from == to -> quranAyah(from)
    else -> quranAyahSpan(from, to)
}

private fun tafsirShortTitle(book: TafsirBook) = when (book.id) {
    "muyassar" -> "الميسر"
    "mukhtasar" -> "المختصر"
    "juzayy" -> "ابن جزي"
    "ashur" -> "ابن عاشور"
    else -> book.title
}

private val creditMarks = Regex("[\\p{M}ـ]")
private fun creditKey(text: String) = creditMarks.replace(Normalizer.normalize(text.trim(), Normalizer.Form.NFD), "")

/** A catalog credit may already contain the full title; a passage can repeat it before its print citation. */
internal fun TafsirBook.attribution(citations: List<String> = emptyList(), withEdition: Boolean = false): String {
    val sourceParts = source.split('·').map(String::trim).filter(String::isNotEmpty)
    val titleKey = creditKey(title)
    val credit = buildList {
        if (sourceParts.none { creditKey(it) == titleKey } && title.isNotBlank()) add(title)
        addAll(sourceParts)
    }
    val baseNames = credit.map(::creditKey)
    val labels = credit.toMutableList()
    val seen = baseNames.toMutableSet()
    for (citation in citations) for (part in citation.split('·')) {
        val label = part.trim()
        val key = creditKey(label)
        if (key.isEmpty() || baseNames.any { it.endsWith(" $key") } || !seen.add(key)) continue
        labels.add(label)
    }
    if (withEdition && edition.isNotBlank() && seen.add(creditKey(edition))) labels.add(edition)
    return labels.joinToString(" · ")
}

@Composable
internal fun TafsirHeader(heading: String, bookLabel: String?, onClose: () -> Unit, onSize: () -> Unit, onCollapse: (() -> Unit)?, onShare: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(heading, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (bookLabel != null) Text(bookLabel, style = MaterialTheme.typography.labelSmall)
        }
        if (onCollapse != null) IconButton(onClick = onCollapse) {
            Icon(painterResource(R.drawable.ic_expand_more), "تصغير لوحة التفسير", Modifier.size(20.dp))
        }
        TextButton(onClick = onSize, modifier = Modifier.testTag("quran_tafsir_font"), contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text("حجم الخط", style = MaterialTheme.typography.labelMedium)
        }
        if (onShare != null) IconButton(onClick = onShare, modifier = Modifier.testTag("quran_tafsir_share")) {
            Icon(painterResource(R.drawable.ic_quran_share), "مشاركة الآية وتفسيرها", Modifier.size(20.dp))
        }
        IconButton(onClick = onClose, modifier = Modifier.testTag("quran_tafsir_close")) {
            Icon(painterResource(R.drawable.ic_close), "إغلاق التفسير", Modifier.size(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TafsirTabs(meaning: Boolean, onSelect: (Boolean) -> Unit) {
    PrimaryTabRow(selectedTabIndex = if (meaning) 0 else 1) {
        Tab(selected = meaning, onClick = { if (!meaning) onSelect(true) }, modifier = Modifier.testTag("quran_tafsir_tab_meaning"),
            text = { Text("المعنى") })
        Tab(selected = !meaning, onClick = { if (meaning) onSelect(false) }, modifier = Modifier.testTag("quran_tafsir_tab_books"),
            text = { Text("التفاسير") })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TafsirBookChoices(books: List<TafsirBook>, selected: String?, selectedUnavailable: Boolean,
                              downloadSize: (TafsirBook) -> String?, onSelect: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
        books.forEach { book ->
            val active = selected == book.id
            val size = downloadSize(book)
            Box(Modifier.heightIn(min = 48.dp).selectable(selected = active, role = Role.RadioButton, onClick = { if (!active || size != null || selectedUnavailable) onSelect(book.id) })
                .testTag("quran_tafsir_book_${book.id}"), contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(10.dp), color = if (active) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 5.dp)) {
                        Text(tafsirShortTitle(book), style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                        if (size != null || active && selectedUnavailable) Text(size?.let { "تنزيل · $it" } ?: "تنزيل",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
internal fun TafsirBody(
    modifier: Modifier, scroll: LazyListState, book: TafsirBook, units: List<TafsirUnit>, notes: List<TafsirNote>,
    glossary: List<TafsirUnit>, glossaryBook: TafsirBook?, verse: QuranVerseReference,
    glossaryMissing: Boolean, noteDownloadSize: (String) -> String?,
    verseText: (QuranVerseReference) -> String?, offPageText: (QuranVerseReference) -> String?, showVerse: Boolean, fontSize: Int, intro: Boolean,
    onBodySpaceNeeded: (Int) -> Unit,
    unavailable: Boolean, downloadAction: (@Composable (TafsirBook) -> Unit)?,
    onAbout: (String) -> Unit, onNote: (TafsirNote) -> Unit,
) {
    val from = units.minOfOrNull { it.from } ?: verse.ayah
    val to = units.maxOfOrNull { it.to } ?: verse.ayah
    val selectionColor = MaterialTheme.colorScheme.secondaryContainer
    val commentary = book.id == "juzayy" || book.id == "ashur"
    val entries = remember(units, verse, selectionColor, commentary) { annotatedTafsirParagraphs(units, verse.ayah, selectionColor, commentary) }
    val boundary = units.singleOrNull()
    val startsMidVerse = !intro && (boundary?.fromWord ?: 1) > 1
    val endWord = boundary?.toWord
    val endsMidVerse = !intro && endWord != null &&
        (verseText(QuranVerseReference(verse.surah, to))?.split(' ')?.count { it.isNotBlank() } ?: 0) > endWord
    val clipped = startsMidVerse || endsMidVerse
    val repeats = if (intro || unavailable) emptyList() else (from..to).mapNotNull { ayah ->
        val ref = QuranVerseReference(verse.surah, ayah)
        val words = if (clipped || showVerse) verseText(ref) else offPageText(ref)
        if (words.isNullOrBlank()) return@mapNotNull null
        if (!clipped) words else {
            val list = words.split(' ').filter(String::isNotBlank)
            val start = if (ayah == from) (boundary?.fromWord ?: 1) - 1 else 0
            val end = if (ayah == to) boundary?.toWord ?: list.size else list.size
            if (start !in list.indices || end !in start + 1..list.size) words
            else (if (start > 0) "… " else "") + list.subList(start, end).joinToString(" ") + (if (end < list.size) " …" else "")
        }
    }
    val contextLine = if (notes.isNotEmpty() || intro) null else when {
        startsMidVerse && endsMidVerse -> "يبدأ هذا المقطع من وسط ${quranAyah(from)} وينتهي في وسط ${quranAyah(to)}."
        startsMidVerse -> "يبدأ هذا المقطع من وسط ${quranAyah(from)}."
        endsMidVerse -> "ينتهي هذا المقطع في وسط ${quranAyah(to)}."
        from != to -> "يشرح ${tafsirShortTitle(book)} ${quranAyahSpan(from, to)} معًا."
        else -> null
    }
    val firstAnchor = entries.indexOfFirst { text -> text.spanStyles.any { it.item.background != Color.Unspecified } }
    val paragraphsStart = repeats.size + notes.size + (if (contextLine != null) 1 else 0)
    val desiredPixels = with(LocalDensity.current) { ((fontSize * 1.85f).sp.toPx() * 5).toInt() }
    val requestBodySpace by rememberUpdatedState(onBodySpaceNeeded)
    LaunchedEffect(scroll, paragraphsStart, desiredPixels, entries) {
        if (entries.isNotEmpty()) snapshotFlow {
            val layout = scroll.layoutInfo
            if (layout.visibleItemsInfo.isEmpty() || scroll.firstVisibleItemIndex != 0 || scroll.firstVisibleItemScrollOffset != 0) 0
            else {
                // The list viewport already excludes the measured tabs, selector and footer.
                // Subtract the actual reading note, repeated verse, and group label above the first paragraph.
                val start = layout.visibleItemsInfo.firstOrNull { it.index == paragraphsStart }?.offset ?: layout.viewportEndOffset
                (desiredPixels - (layout.viewportEndOffset - layout.afterContentPadding - start).coerceAtLeast(0)).coerceAtLeast(0)
            }
        }.collect { missing -> if (missing > 1) requestBodySpace(missing) }
    }
    var positioned by rememberSaveable(book.id, verse.surah, verse.ayah, units.firstOrNull()?.id) { mutableStateOf(false) }
    LaunchedEffect(entries, positioned) {
        if (!positioned && entries.isNotEmpty()) {
            if (firstAnchor > 0 && scroll.firstVisibleItemIndex == 0 && scroll.firstVisibleItemScrollOffset == 0) {
                scroll.scrollToItem(paragraphsStart + firstAnchor)
            }
            positioned = true
        }
    }
    val guard = rememberSheetScrollGuard(scroll)
    LazyColumn(modifier.nestedScroll(guard).testTag("quran_tafsir_text"), state = scroll,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (unavailable) {
            item(key = "download-${book.id}") { TafsirBookDownload(book, downloadAction) }
        } else {
            items(repeats.size) { index ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(repeats[index], Modifier.fillMaxWidth().padding(12.dp), fontFamily = QuranMushafFont,
                            fontSize = (fontSize + 4).sp, lineHeight = ((fontSize + 4) * 1.8f).sp)
                }
            }
            if (contextLine != null) item { Text(contextLine, style = MaterialTheme.typography.bodySmall) }
            items(notes.size) { index ->
                val note = notes[index]
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(remember(note.text) { buildAnnotatedString {
                            append(note.text)
                            styleQalounQuotes(note.text)
                        } }, fontFamily = AdhkarReadingFont, modifier = if (note.spoken.isNullOrBlank()) Modifier else Modifier.clearAndSetSemantics {
                            contentDescription = note.spoken.orEmpty()
                        }, style = MaterialTheme.typography.bodyMedium)
                        val label = when (note.targetBook) {
                            "juzayy" -> "اقرأ شرح ابن جزي لكلمة مصحفك"
                            "ashur" -> "اقرأ شرح ابن عاشور لكلمة مصحفك"
                            else -> null
                        }
                        if (label != null) TextButton(onClick = { onNote(note) }) {
                            Column {
                                Text(label)
                                note.targetBook?.let(noteDownloadSize)?.let { size ->
                                    Text("تنزيل · $size", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
            if (entries.isEmpty()) item {
                Text(if (intro) "لا تتوفر مقدمة هذه السورة في هذه النسخة."
                    else "لا يتوفر في ${book.title} نصّ لهذه الآية في هذه النسخة.")
            } else {
                items(entries.size) { index ->
                    val paragraph = entries[index]
                    Text(paragraph, modifier = if (paragraph.spanStyles.none { it.item.background != Color.Unspecified }) Modifier else Modifier.semantics {
                        stateDescription = "شرح الآية المحدّدة"
                    }, fontFamily = AdhkarReadingFont, fontSize = fontSize.sp, lineHeight = (fontSize * 1.85f).sp)
                }
                item { TafsirSource(book, units.mapNotNull { it.citation }.distinct(), onAbout) }
            }
        }
        if (glossaryBook != null && (glossaryMissing || glossary.isNotEmpty())) {
            item(key = "glossary-heading") { Text("معاني الكلمات", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall) }
            if (glossaryMissing) {
                item(key = "download-gharib") { TafsirBookDownload(glossaryBook, downloadAction) }
            } else {
                items(glossary.size) { index ->
                    val entry = glossary[index]
                    Column {
                        entry.headword?.let { Text(it, fontFamily = QuranMushafFont, color = MaterialTheme.colorScheme.primary,
                            fontSize = (fontSize + 2).sp, lineHeight = ((fontSize + 2) * 1.8f).sp) }
                        TafsirParagraph(entry.text, fontSize)
                    }
                }
                item { TafsirSource(glossaryBook, emptyList(), onAbout) }
            }
        }
    }
}

@Composable
private fun TafsirBookDownload(book: TafsirBook, download: (@Composable (TafsirBook) -> Unit)?) {
    Column(Modifier.fillMaxWidth().testTag("quran_tafsir_download_${book.id}")) {
        if (book.pack != null && download != null) download(book)
        else Text("تعذّر فتح ${book.title} على هذا الهاتف.")
    }
}

/** Offsets belong to the published string; line breaks and whitespace count toward them. */
private val tafsirMushafQuotes = Regex("﴿[^﴿﴾]+﴾")

private fun AnnotatedString.Builder.styleQalounQuotes(text: String) {
    for (quote in tafsirMushafQuotes.findAll(text)) {
        addStyle(SpanStyle(fontFamily = QuranMushafFont, fontSize = 1.2.em), quote.range.first, quote.range.last + 1)
    }
}

private fun annotatedTafsirParagraphs(units: List<TafsirUnit>, ayah: Int, tint: Color, commentary: Boolean): List<AnnotatedString> = buildList {
    for (unit in units) {
        val selected = unit.highlights.filter { it.ayah == ayah }
        var offset = 0
        for (line in unit.text.split('\n')) {
            val text = line.removeSuffix("\r")
            if (text.isNotBlank()) add(buildAnnotatedString {
                append(text)
                if (commentary) styleQalounQuotes(text)
                for (highlight in selected) {
                    val start = (highlight.start - offset).coerceIn(0, text.length)
                    val end = (highlight.end - offset).coerceIn(0, text.length)
                    if (start < end) addStyle(SpanStyle(background = tint), start, end)
                }
            })
            offset += line.length + 1
        }
    }
}

@Composable
private fun TafsirParagraph(text: String, size: Int) {
    Text(text, fontFamily = AdhkarReadingFont, fontSize = size.sp, lineHeight = (size * 1.85f).sp)
}

@Composable
private fun TafsirSource(book: TafsirBook, citations: List<String>, onAbout: (String) -> Unit) {
    val text = book.attribution(citations)
    Text(text, Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { onAbout(book.id) }
        .padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun TafsirStepper(previous: QuranVerseReference?, previousEnd: Int?, next: QuranVerseReference?, nextEnd: Int?, surah: Int,
                          surahName: (Int) -> String, onStep: (Boolean) -> Unit) {
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        listOf(false to previous, true to next).forEach { (forward, target) ->
            TextButton(enabled = target != null, onClick = { onStep(forward) },
                modifier = Modifier.weight(1f).testTag(if (forward) "quran_tafsir_next" else "quran_tafsir_previous")) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (forward) "التالي" else "السابق", fontWeight = FontWeight.SemiBold)
                    if (target != null) {
                        val end = if (forward) nextEnd else previousEnd
                        val ayah = tafsirRange(target.ayah, end ?: target.ayah)
                        Text(if (surah == target.surah) ayah else "${surahName(target.surah)} · $ayah",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
internal fun TafsirFontDialog(size: Int, onSize: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("حجم خط التفسير") },
        text = {
            Column {
                TafsirParagraph("معاني الآيات وتفسيرها", size)
                Slider(value = size.toFloat(), onValueChange = { onSize(it.roundToInt()) }, valueRange = 14f..32f, steps = 17,
                    modifier = Modifier.testTag("quran_tafsir_font_slider"))
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("تم") } })
}

@Composable
internal fun TafsirAboutDialog(book: TafsirBook, verse: QuranVerseReference, surahName: String,
                              units: List<TafsirUnit>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var reportFailed by remember { mutableStateOf(false) }
    val corrections by produceState(emptyList<String>(), book.id, book.baseId, units) {
        val embedded = units.mapNotNull { unit -> unit.correctionReason?.let { reason ->
            listOfNotNull(unit.correctionDate, reason).joinToString(" · ")
        } }
        val current = book.baseId?.takeUnless { book.preview }?.let { base ->
            withContext(Dispatchers.IO) {
                TafsirCorrections.forBase(context, book.id, base).values.map { "${it.date} · ${it.reason}" }
            }
        }.orEmpty()
        value = (embedded + current).distinct()
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (book.id == "gharib") "عن معجم الكلمات" else "عن هذا التفسير") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(book.attribution())
                if (book.edition.isNotBlank()) Text(book.edition)
                when (book.id) {
                    "muyassar", "mukhtasar" -> {
                        Text("أُلّف هذا التفسير على رواية حفص. نبيّن موضع اختلاف المعنى عن رواية قالون حيث يقتضي نصّ الكتاب ذلك.")
                        Text(buildAnnotatedString {
                            append("ألفاظ رواية حفص في ملاحظات القراءة من ")
                            withLink(LinkAnnotation.Url("https://tanzil.net")) { append("مشروع Tanzil") }
                            append(".")
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                    "gharib" -> Text("نعرض الكلمة بلفظ مصحف قالون ومعناها كما ورد في الكتاب. ونترك ما يشرح لفظًا لا يرد في مصحفك.")
                }
                if (corrections.isNotEmpty()) Text("التصحيحات المعتمدة", fontWeight = FontWeight.SemiBold)
                corrections.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (book.preview) Text("نسخة محلية للمعاينة.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    val reference = "$surahName · ${tafsirRange(verse.ayah, verse.ayah)}"
                    val uri = Uri.parse("https://github.com/bsafwen/TunisianPrayerTimes/issues/new").buildUpon()
                        .appendQueryParameter("title", "تصحيح في ${book.title} · $reference")
                        .appendQueryParameter("body", listOf(book.title, book.edition, reference, book.baseId.orEmpty(),
                            "وصف الخطأ:").filter(String::isNotBlank).joinToString("\n")).build()
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    catch (_: ActivityNotFoundException) { reportFailed = true }
                }) { Text("أبلغ عن خطأ") }
                if (reportFailed) Text("تعذّر فتح صفحة الإبلاغ على هذا الهاتف.")
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } })
}
