package com.tunisianprayertimes.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import com.tunisianprayertimes.quran.QuranMushafText
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.tafsir.*
import com.tunisianprayertimes.ui.theme.BgCream
import com.tunisianprayertimes.ui.theme.CardBg
import com.tunisianprayertimes.ui.theme.CardBorder
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TextDark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class TafsirCatalog(val books: List<TafsirBook>, val installed: Set<String>, val status: TafsirCatalogStatus)

/** Null while a request is in flight. A new book or verse starts with no old text on screen. */
@Composable
private fun <T> loadTafsir(vararg keys: Any?, load: () -> T): Result<T>? = key(keys.toList()) {
    produceState<Result<T>?>(null) {
        value = try {
            Result.success(withContext(Dispatchers.IO) { load() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }.value
}

private val conciseBooks = listOf("muyassar", "mukhtasar")
private val scholarlyBooks = listOf("juzayy", "ashur")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuranTafsirPanel(
    repository: TafsirRepository,
    verse: QuranVerseReference,
    surahName: (Int) -> String,
    verseText: (QuranVerseReference) -> String?,
    onVerseChange: (QuranVerseReference) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    intro: Boolean = false,
    forceFull: Boolean = false,
    isProtected: (Set<QuranVerseReference>) -> Boolean,
    onReadAnyway: (Set<QuranVerseReference>) -> Unit,
    onReview: (Set<QuranVerseReference>) -> Unit,
    onPassageShown: (Set<QuranVerseReference>) -> Unit,
    onTouch: () -> Unit,
    packRevision: Any? = null,
    downloadAction: (@Composable (TafsirBook, () -> Unit, () -> Unit) -> Unit)? = null,
    offPageText: (QuranVerseReference) -> String? = { null },
    onPassageHighlight: (Set<QuranVerseReference>) -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("quran_tafsir_reader", Context.MODE_PRIVATE) }
    var chosenTab by rememberSaveable { mutableStateOf(prefs.getString("tab", "meaning") ?: "meaning") }
    var concise by rememberSaveable { mutableStateOf(prefs.getString("meaning", "muyassar") ?: "muyassar") }
    var scholar by rememberSaveable { mutableStateOf(prefs.getString("books", "juzayy") ?: "juzayy") }
    var fontSize by rememberSaveable { mutableIntStateOf(prefs.getInt("size", 16).coerceIn(14, 32)) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var fontDialog by rememberSaveable { mutableStateOf(false) }
    var aboutId by rememberSaveable { mutableStateOf<String?>(null) }
    var requestedBook by rememberSaveable { mutableStateOf<String?>(null) }
    var linkedBook by rememberSaveable { mutableStateOf<String?>(null) }
    var linkedUnit by rememberSaveable { mutableStateOf<String?>(null) }
    var stepBook by rememberSaveable { mutableStateOf<String?>(null) }
    var stepUnit by rememberSaveable { mutableStateOf<String?>(null) }
    var stepSurah by rememberSaveable { mutableIntStateOf(0) }
    var stepAyah by rememberSaveable { mutableIntStateOf(-1) }
    var retry by remember { mutableIntStateOf(0) }
    val catalogResult = loadTafsir(repository, packRevision, retry) {
        val books = repository.books()
        TafsirCatalog(books, books.filter(repository::isInstalled).mapTo(mutableSetOf()) { it.id }, repository.catalogStatus())
    }
    val catalog = catalogResult?.getOrNull()
    val books = catalog?.books.orEmpty().associateBy { it.id }
    val meaning = conciseBooks.mapNotNull(books::get)
    val commentaries = scholarlyBooks.mapNotNull(books::get)
    LaunchedEffect(catalog?.status, meaning.map { it.id }, commentaries.map { it.id }) {
        if (catalog?.status == TafsirCatalogStatus.AVAILABLE) {
            if (meaning.isEmpty() && commentaries.isNotEmpty()) {
                prefs.edit().putBoolean("scholarly_only", true).apply()
            } else if (meaning.isNotEmpty()) {
                if (concise !in meaning.map { it.id }) {
                    concise = meaning.first().id
                    prefs.edit().putString("meaning", concise).apply()
                }
                if (prefs.getBoolean("scholarly_only", false)) {
                    chosenTab = "meaning"
                    prefs.edit().putString("tab", chosenTab).remove("scholarly_only").apply()
                }
            }
        }
    }
    var unreadable by remember(verse, packRevision) { mutableStateOf<String?>(null) }
    val meaningTab = !intro && meaning.isNotEmpty() &&
        (chosenTab == "meaning" || commentaries.isEmpty())
    val choices = if (meaningTab) meaning else commentaries
    val preferred = if (meaningTab) concise else scholar
    val ordinary = choices.find { it.id == preferred }
    val preferInstalled = ordinary != null && (ordinary.id !in catalog!!.installed || ordinary.id == unreadable)
    val choice = when {
        intro -> books["ashur"]
        linkedBook != null -> books[linkedBook]
        requestedBook != null -> choices.find { it.id == requestedBook }
        preferInstalled -> choices.firstOrNull { it.id != ordinary?.id && it.id in catalog!!.installed } ?: ordinary
        else -> ordinary ?: choices.firstOrNull()
    }
    val openingIntro = intro && linkedBook == null
    val targetUnit = if (linkedBook != null) linkedUnit else stepUnit.takeIf {
        stepSurah == verse.surah && stepAyah == verse.ayah && stepBook == choice?.id
    }
    val result = if (choice != null && !openingIntro) loadTafsir(repository, choice.id, verse, targetUnit, packRevision, retry) {
        repository.read(choice.id, verse, targetUnit)
    } else null
    val introResult = if (openingIntro && choice != null) loadTafsir(repository, verse.surah, retry) {
        repository.introduction(verse.surah)
    } else null
    val reading = result?.getOrNull()
    LaunchedEffect(reading, openingIntro) {
        // The complete edition includes a basmala chapter. An earlier sample starts at the opening surah.
        if (!openingIntro && reading?.book?.id == "juzayy" && !reading.unavailable &&
            verse.surah == 1 && verse.ayah == 0 && reading.units.isEmpty()) {
            onVerseChange(QuranVerseReference(1, 1))
        }
    }
    val edition = reading?.book ?: choice
    val previous = reading?.previous
    val next = reading?.next
    LaunchedEffect(choice?.id, reading?.unavailable, requestedBook, linkedBook) {
        if (requestedBook == null && linkedBook == null && choice != null && reading?.unavailable == true &&
            choice.id == preferred && choices.any { it.id != preferred && it.id in catalog!!.installed }) {
            unreadable = choice.id
        }
    }
    val introduction = introResult?.getOrNull()
    val units = if (openingIntro) listOfNotNull(introduction) else reading?.units.orEmpty()
    val exposed = when {
        openingIntro && introduction != null -> introduction.quotedVerses
        reading != null && !reading.unavailable -> reading.exposed + verse
        else -> emptySet()
    }
    val guarded = if (openingIntro && introduction != null) exposed + verse else exposed
    val blocked = guarded.isNotEmpty() && isProtected(guarded)
    LaunchedEffect(blocked) { if (blocked) aboutId = null }
    val currentShown by rememberUpdatedState(onPassageShown)
    val highlight by rememberUpdatedState(onPassageHighlight)
    val shownVerses = remember(units, verse.surah, blocked, openingIntro) {
        if (blocked || openingIntro) emptySet() else units.flatMapTo(mutableSetOf()) { unit ->
            (unit.from..unit.to).map { QuranVerseReference(verse.surah, it) }
        }
    }
    LaunchedEffect(shownVerses) { highlight(shownVerses) }
    LifecycleResumeEffect(exposed, blocked) {
        if (!blocked && exposed.isNotEmpty()) currentShown(exposed)
        onPauseOrDispose { }
    }
    val touch by rememberUpdatedState(onTouch)
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val density = LocalDensity.current
    val textScale = density.fontScale
    val hasTabs = !intro && linkedBook == null && meaning.isNotEmpty() && commentaries.isNotEmpty()
    val hasChoices = !intro && linkedBook == null && choices.size > 1
    val chromeHeight = 48 + 48 + (if (hasTabs) 48 else 0) + (if (hasChoices) 48 else 0) + (if (intro) 0 else 64)
    val minimumHeight = (chromeHeight + 5 * fontSize * 1.85f * textScale).toInt()
    val heightLimit = (screenHeight - 134).coerceAtLeast(0)
    val preferredHeight = maxOf((screenHeight * .5f).toInt(), minimumHeight).coerceAtMost(heightLimit)
    var tallest by rememberSaveable(screenHeight, fontSize) { mutableIntStateOf(preferredHeight) }
    LaunchedEffect(preferredHeight) { tallest = maxOf(tallest, preferredHeight) }
    val isFull = forceFull || expanded || screenHeight - chromeHeight < 4 * fontSize * 1.85f * textScale
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val mainScroll = key(verse, requestedBook ?: preferred, stepUnit, openingIntro) { rememberLazyListState() }
    val linkedScroll = key(linkedBook, linkedUnit) { rememberLazyListState() }
    val scroll = if (linkedBook != null) linkedScroll else mainScroll
    LaunchedEffect(scroll.isScrollInProgress) { if (scroll.isScrollInProgress) touch() }
    fun close() {
        when {
            aboutId != null -> aboutId = null
            fontDialog -> fontDialog = false
            linkedBook != null -> { linkedBook = null; linkedUnit = null }
            else -> onDismiss()
        }
    }
    fun selectBook(id: String) {
        requestedBook = id
        stepUnit = null
        if (id in catalog?.installed.orEmpty()) {
            if (meaningTab) concise = id else scholar = id
            prefs.edit().putString(if (meaningTab) "meaning" else "books", id).apply()
        }
        touch()
    }
    LaunchedEffect(requestedBook, catalog?.installed) {
        requestedBook?.takeIf { it in catalog?.installed.orEmpty() }?.let { id ->
            if (id in conciseBooks) concise = id else scholar = id
            prefs.edit().putString(if (id in conciseBooks) "meaning" else "books", id).apply()
        }
    }
    fun step(forward: Boolean) {
        val target = if (forward) next else previous
        if (target == null || blocked) return
        stepBook = choice?.id
        stepUnit = if (forward) reading?.nextUnit else reading?.previousUnit
        stepSurah = target.surah
        stepAyah = target.ayah
        onVerseChange(target)
        touch()
    }
    val selectedAbout = edition?.takeIf { it.id == aboutId } ?: books[aboutId]
    val panel: @Composable ColumnScope.() -> Unit = {
        val loadFailed = catalog?.status == TafsirCatalogStatus.INVALID || catalogResult?.isFailure == true ||
            result?.isFailure == true || introResult?.isFailure == true
        val pending = catalogResult == null || (choice != null && if (openingIntro) introResult == null else result == null)
        val showControls = linkedBook == null && !openingIntro
        val from = units.minOfOrNull { it.from } ?: verse.ayah
        val to = units.maxOfOrNull { it.to } ?: verse.ayah
        val heading = if (openingIntro) "عن سورة ${surahName(verse.surah)}" else "${surahName(verse.surah)} · ${tafsirRange(from, to)}"
        TafsirHeader(heading, bookLabel = edition?.title?.takeIf { !openingIntro && (!showControls || choices.size == 1) },
            onClose = ::close, onSize = { fontDialog = true },
            onCollapse = if (expanded && !forceFull) ({ expanded = false }) else null, onShare =
            if (edition?.shareable == true && units.isNotEmpty() && !openingIntro && !blocked) ({
                if (!isProtected(exposed)) {
                    val body = tafsirShareText(surahName(verse.surah), verse, units, verseText,
                        reading?.notes.orEmpty(), edition, reading?.passageNotes.orEmpty())
                    if (body != null) context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, body), null))
                }
            }) else null)
        if (showControls && meaning.isNotEmpty() && commentaries.isNotEmpty()) {
            TafsirTabs(meaningTab) { wantMeaning ->
                chosenTab = if (wantMeaning) "meaning" else "books"
                prefs.edit().putString("tab", chosenTab).apply()
                requestedBook = null
                stepUnit = null
                touch()
            }
        }
        if (showControls && choices.size > 1) TafsirBookChoices(choices, choice?.id, ::selectBook)
        when {
            blocked -> Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.Center) {
                Text("لهذه الآيات سؤال ينتظرك. هل تراجعه قبل قراءة التفسير؟")
                Button(onClick = { onReview(guarded) }) { Text("راجع أولًا") }
                TextButton(onClick = { onReadAnyway(guarded) }, modifier = Modifier.testTag("quran_tafsir_read_anyway")) { Text("اقرأ الآن وأجّل السؤال") }
            }
            pending -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            loadFailed -> Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.Center) {
                Text("تعذّر فتح التفسير.")
                TextButton(onClick = { retry++ }) { Text("أعد المحاولة") }
            }
            choice == null -> Text("لا تتوفر كتب تفسير في هذه النسخة.", Modifier.weight(1f).padding(20.dp))
            else -> TafsirBody(
                modifier = Modifier.weight(1f).then(if (showControls && reading != null) Modifier.pointerInput(reading, rtl) {
                    var dx = 0f
                    detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = {
                        if (kotlin.math.abs(dx) >= 55.dp.toPx()) step((dx > 0) == rtl)
                    }) { change, delta -> dx += delta; change.consume() }
                } else Modifier), scroll = scroll, book = edition ?: choice, units = units,
                notes = reading?.notes.orEmpty(), glossary = reading?.glossary.orEmpty(), glossaryBook = books["gharib"],
                verse = verse, verseText = verseText, offPageText = offPageText, showVerse = isFull && !openingIntro, fontSize = fontSize, intro = openingIntro,
                onBodySpaceNeeded = { missingPx ->
                    if (!isFull && missingPx > 0) {
                        val extra = kotlin.math.ceil(missingPx / density.density).toInt()
                        tallest = (maxOf(preferredHeight, tallest) + extra).coerceAtMost(heightLimit)
                    }
                },
                fallback = ordinary?.title?.takeIf { preferInstalled && requestedBook == null && showControls && choice.id != ordinary.id },
                unavailable = reading?.unavailable == true, downloadAction = downloadAction?.let { download -> { book ->
                    download(book, {
                        val previous = if (scholar == book.id) "juzayy" else scholar
                        scholar = book.id
                        prefs.edit().putString("before_download", previous).putString("books", book.id).apply()
                        touch()
                    }, {
                        scholar = prefs.getString("before_download", "juzayy") ?: "juzayy"
                        prefs.edit().putString("books", scholar).remove("before_download").apply()
                        requestedBook = null
                        touch()
                    })
                } },
                onAbout = { aboutId = it }, onNote = { note ->
                    linkedBook = note.targetBook
                    linkedUnit = note.targetUnit
                    touch()
                },
            )
        }
        if (reading != null && !blocked && !openingIntro && linkedBook == null) {
            TafsirStepper(previous, reading.previousEnd, next, reading.nextEnd, verse.surah, surahName, ::step)
        } else if (linkedBook != null) {
            TextButton(onClick = ::close) { Text("العودة إلى المعنى") }
        }
    }
    val appColors = MaterialTheme.colorScheme
    val colors = remember(appColors) {
        if (appColors.surface.luminance() < .4f) appColors else appColors.copy(
            primary = GreenPrimary, surfaceTint = GreenPrimary, surface = BgCream, onSurface = TextDark,
            surfaceContainer = BgCream, surfaceContainerHigh = CardBg, surfaceVariant = CardBg,
            secondaryContainer = AdhkarSoftGreen, onSecondaryContainer = GreenPrimaryDark, outlineVariant = CardBorder,
        )
    }
    MaterialTheme(colorScheme = colors) {
        if (isFull) {
            AppModalBottomSheet(onDismissRequest = ::close, containerColor = colors.surface,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                Column(Modifier.fillMaxWidth().fillMaxHeight().testTag("quran_tafsir_panel"), content = panel)
            }
        } else {
            BackHandler(onBack = ::close)
            Surface(modifier.fillMaxWidth().height(maxOf(tallest, preferredHeight).dp).testTag("quran_tafsir_panel"),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), color = colors.surface, shadowElevation = 5.dp) {
                Column {
                    Box(Modifier.fillMaxWidth().pointerInput(Unit) {
                        var distance = 0f
                        detectVerticalDragGestures(onDragStart = { distance = 0f }, onDragEnd = {
                            if (distance < -25.dp.toPx()) expanded = true
                        }) { change, dy -> distance += dy; change.consume() }
                    }, contentAlignment = Alignment.Center) {
                        TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp)) {
                            Text("عرض التفسير كاملًا", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    panel()
                }
            }
        }
        if (fontDialog) TafsirFontDialog(fontSize, { size ->
            fontSize = size
            prefs.edit().putInt("size", size).apply()
            touch()
        }, { fontDialog = false })
        if (selectedAbout != null && !blocked) TafsirAboutDialog(selectedAbout, verse, surahName(verse.surah),
            if (selectedAbout.id == "gharib") reading?.glossary.orEmpty() else units) { aboutId = null }
    }
}

internal fun tafsirShareText(
    surahName: String,
    verse: QuranVerseReference,
    units: List<TafsirUnit>,
    verseText: (QuranVerseReference) -> String?,
    notes: List<TafsirNote>,
    book: TafsirBook,
    passageNotes: List<TafsirNote> = notes,
): String? {
    if (!book.shareable || units.isEmpty()) return null
    // A scholarly highlight locates the quoted lemma, while a concise highlight covers the reviewed explanation.
    val highlights = if (book.id !in conciseBooks) emptyList() else units.flatMap { unit ->
        unit.highlights.filter { it.ayah == verse.ayah && it.start >= 0 && it.end in (it.start + 1)..unit.text.length }
            .map { unit.text.substring(it.start, it.end) }
    }
    val from = if (highlights.isEmpty()) units.minOf { it.from } else verse.ayah
    val to = if (highlights.isEmpty()) units.maxOf { it.to } else verse.ayah
    val words = (from..to).map { ayah ->
        val text = verseText(QuranVerseReference(verse.surah, ayah))?.let(QuranMushafText::plain)?.takeIf { it.isNotBlank() } ?: return null
        if (from == to) text else "$text (${ltr(ayah.toString())})"
    }
    val reference = when {
        from == 0 && to == 0 -> "البسملة"
        from == to -> ltr(from.toString())
        else -> "${ltr(from.toString())} إلى ${ltr(to.toString())}"
    }
    val citation = book.attribution(units.mapNotNull { it.citation }, withEdition = true)
    return (listOf("﴿${words.joinToString(" ")}﴾ [$surahName: $reference] · رواية قالون عن نافع، العدّ المدني الأخير") +
        (if (highlights.isEmpty()) passageNotes else notes).map { it.text } +
        highlights.ifEmpty { units.map { it.text } } + citation).joinToString("\n\n")
}
