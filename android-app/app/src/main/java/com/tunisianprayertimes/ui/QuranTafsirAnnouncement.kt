package com.tunisianprayertimes.ui

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.quran.tafsir.TafsirBook

private const val SeenTafsirBooks = "tafsir_announced_titles"

@Composable
internal fun QuranTafsirAnnouncement(books: List<TafsirBook>, prefs: SharedPreferences) {
    val titles = remember(books) { books.map { it.title }.toSet() }
    var previous by remember(prefs) {
        mutableStateOf(prefs.getStringSet(SeenTafsirBooks, emptySet()).orEmpty().toSet())
    }
    if (previous == titles) return
    val added = titles - previous
    val removed = previous - titles
    val message = buildList {
        if (previous.isEmpty()) {
            add("اضغط على أي آية ثم اختر «تفسير» لقراءة شرحها.")
        } else if (added.isNotEmpty()) {
            add("أُضيف إلى كتب التفسير " + added.joinToString(" و") { "«$it»" } + ".")
        }
        if (removed.isNotEmpty()) {
            add("لم يعد " + removed.joinToString(" و") { "«$it»" } + " متاحًا في هذا الإصدار.")
        }
    }.joinToString(" ")
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).testTag("quran_tafsir_notice"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {
                prefs.edit().putStringSet(SeenTafsirBooks, titles).apply()
                previous = titles
            }) { Text("حسنًا") }
        }
    }
}
