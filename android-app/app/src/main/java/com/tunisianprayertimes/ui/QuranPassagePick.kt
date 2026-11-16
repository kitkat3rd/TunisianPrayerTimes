package com.tunisianprayertimes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranHighlights
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.ui.theme.*

/**
 * A forgotten passage is at most this many lines of the mushaf's text, on at most two pages that follow each other
 * (owner, 12 November): a page's worth. The surah's heading and its basmala are not lines of it.
 */
internal const val QuranPassageMaxLines = 15

/** Two line boxes of one line may differ by this much at their tops; two lines are a line's height apart. */
private const val QuranLineTopTolerance = 0.02f

/**
 * The lines of text the passage from [start] to [last] of its surah takes in the mushaf: on each of its pages, every
 * line one of its verses is on, a line it shares with the verse before or after counted once.
 */
internal fun quranPassageLines(highlights: QuranHighlights, catalog: QuranCatalog, start: QuranVerseReference, last: Int): Int =
    (start.ayah..last).flatMap { ayah ->
        catalog.pagesForVerse(start.surah, ayah).flatMap { page -> highlights.rectangles(page, start.surah, ayah).map { page to it.top } }
    }.groupBy({ it.first }, { it.second }).values.sumOf { tops ->
        tops.sorted().fold(mutableListOf<Float>()) { lines, top ->
            if (lines.isEmpty() || top - lines.last() > QuranLineTopTolerance) lines += top
            lines
        }.size
    }

/** The passage from [start] to [last] may be recorded as one: in one surah, two verses or more, within the limit. */
internal fun quranPassageFits(highlights: QuranHighlights, catalog: QuranCatalog, start: QuranVerseReference, last: Int): Boolean {
    if (last <= start.ayah) return false
    val pages = (start.ayah..last).flatMap { catalog.pagesForVerse(start.surah, it) }
    if (pages.isEmpty() || pages.max() - pages.min() > 1) return false
    return quranPassageLines(highlights, catalog, start, last) <= QuranPassageMaxLines
}

/**
 * While a forgotten passage's last verse is tapped on the mushaf: one compact bar in the dock's place, under the page,
 * so that every line of the page stays free to tap (the owner's note on the first mockup). [title] says what is chosen,
 * [line] how far or why a tap was not taken ([note]); «تابع» once a last verse is chosen, ✕ back to the four cards.
 */
@Composable
internal fun QuranPassageBar(
    title: String,
    line: String,
    note: Boolean,
    canContinue: Boolean,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        // No taller than the dock whose place it takes: the page keeps that room clear above the bar of tabs.
        modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 6.dp).testTag("quran_passage_bar"),
        shape = RoundedCornerShape(16.dp),
        color = BgCream,
        border = BorderStroke(1.dp, CardBorder),
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                if (canContinue) Button(
                    onClick = onContinue, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                    modifier = Modifier.testTag("quran_passage_continue"),
                ) { Text("تابع") }
                IconButton(onClick = onCancel, modifier = Modifier.size(48.dp).testTag("quran_passage_cancel")) {
                    Icon(painterResource(R.drawable.ic_adhkar_close), "إلغاء", Modifier.size(18.dp), tint = TextMuted)
                }
            }
            // Said again by TalkBack as it changes: how far the passage goes, or why a tap was not taken. One line, smaller
            // if it must: a second one would raise the bar over the page's last line.
            Text(
                line, Modifier.padding(end = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
                fontSize = 12.sp, color = if (note) QuranHifzViolet else TextMuted, maxLines = 1,
                autoSize = WholeWords(12.sp, maxLines = 1, floor = 9.sp),
            )
        }
    }
}
