package com.tunisianprayertimes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.quranVerseSentence
import com.tunisianprayertimes.quran.tafsir.TafsirBook
import com.tunisianprayertimes.quran.tafsir.TafsirCatalogStatus
import com.tunisianprayertimes.quran.tafsir.TafsirRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun tafsirPageAction(onTafsirPage: (() -> Unit)?): CustomAccessibilityAction? =
    onTafsirPage?.let { open -> CustomAccessibilityAction("تفسير آيات الصفحة") { open(); true } }

/** The reader owns the focused verse even when the selected book groups it with other verses. */
@Stable
internal class QuranTafsirSession(
    val repository: TafsirRepository,
    focus: QuranVerseReference? = null,
    introduction: Boolean = false,
    choosingPage: Int = 0,
) {
    var catalog by mutableStateOf<Pair<TafsirCatalogStatus, List<TafsirBook>>?>(null)
    val books get() = catalog?.second
    val announcementBooks get() = when (catalog?.first) {
        TafsirCatalogStatus.AVAILABLE, TafsirCatalogStatus.EMPTY -> books
        else -> null
    }
    var verse by mutableStateOf(focus)
        private set
    var intro by mutableStateOf(introduction)
        private set
    var pickerPage by mutableIntStateOf(choosingPage)
    var passage by mutableStateOf(emptySet<QuranVerseReference>())
    var heightPx by mutableIntStateOf(0)
    val open get() = verse != null
    val offered get() = books?.any { it.id != "gharib" } == true
    fun hasIntro(surah: Int) = books?.any { it.id == "ashur" && surah in it.introSurahs } == true

    fun show(target: QuranVerseReference, introduction: Boolean = false) {
        pickerPage = 0
        passage = emptySet()
        verse = target
        intro = introduction
    }

    fun close() {
        verse = null
        intro = false
        pickerPage = 0
        passage = emptySet()
        heightPx = 0
    }
}

@Composable
internal fun rememberQuranTafsirSession(): QuranTafsirSession {
    val context = LocalContext.current.applicationContext
    val repo = remember(context) { TafsirRepository(context) }
    val saver = remember(repo) {
        listSaver<QuranTafsirSession, Int>(
            save = { listOf(it.verse?.surah ?: 0, it.verse?.ayah ?: 0, if (it.intro) 1 else 0, it.pickerPage) },
            restore = { QuranTafsirSession(repo, if (it[0] > 0) QuranVerseReference(it[0], it[1]) else null, it[2] == 1, it[3]) },
        )
    }
    val session = rememberSaveable(saver = saver) { QuranTafsirSession(repo) }
    LaunchedEffect(session) {
        session.catalog = withContext(Dispatchers.IO) { repo.catalogStatus() to repo.books() }
        if (!session.offered || (session.intro && !session.hasIntro(session.verse?.surah ?: 0))) session.close()
    }
    return session
}

@Composable
internal fun QuranTafsirVersePicker(
    verses: List<QuranVerseReference>,
    surahName: (Int) -> String,
    onPick: (QuranVerseReference) -> Unit,
    onDismiss: () -> Unit,
) {
    // Only the references are offered here. Reading a passage still goes through the memorization gate.
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("اختر آية لتقرأ تفسيرها") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                for (verse in verses) TextButton(onClick = { onPick(verse) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (verse.ayah == 0) "بسملة سورة الفاتحة" else quranVerseSentence(surahName(verse.surah), verse.ayah))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } },
    )
}
