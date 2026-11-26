package com.tunisianprayertimes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.audio.quranTimes
import com.tunisianprayertimes.quran.hifz.HifzListenSettings
import com.tunisianprayertimes.quran.hifz.HifzListenTimes
import com.tunisianprayertimes.quran.hifz.HifzPoint
import com.tunisianprayertimes.quran.hifz.HifzReminder
import com.tunisianprayertimes.quran.hifz.toHear
import com.tunisianprayertimes.ui.theme.*

/**
 * The points that can be heard now: all but those whose question waits now, and those whose audio would answer one that
 * waits (a look-alike, the verse before). Hearing a point puts its next question a while after (HifzLearning), so one
 * between its questions is heard as freely as its correction is read; one asked now would be asked with its answer heard.
 */
internal fun List<HifzPoint>.listenable(from: List<HifzPoint>, today: Long, now: Long, eligibleDueIds: Set<Long>?): List<HifzPoint> =
    from.filter { spoiledBy(it, today, now, eligibleDueIds) == null }

/**
 * «الاستماع إلى المواضع», for the road or the gym: [candidates] heard in the mushaf's order, as many times each as chosen,
 * the list again after its last point, a silence after each recitation to say it. Those whose question waits now
 * ([waiting]) are left out, said here with the review that asks them ([onReview]). The choices are kept for the next
 * listening, in the car too.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun HifzListenSheet(
    /** The points that can be heard now; see [listenable]. */
    candidates: List<HifzPoint>,
    /** How many of the points asked for are left out, their question waiting now. */
    waiting: Int,
    onReview: (() -> Unit)?,
    onStart: (List<HifzPoint>, HifzListenSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val saved = remember { HifzListenSettings.read(context) }
    var times by rememberSaveable { mutableIntStateOf(saved.times) }
    var endless by rememberSaveable { mutableStateOf(saved.endless) }
    var pause by rememberSaveable { mutableStateOf(saved.pause) }
    var mastered by rememberSaveable { mutableStateOf(saved.mastered) }
    val settings = HifzListenSettings(times, endless, pause, mastered)
    val held = candidates.count { it.mastered }
    val heard = candidates.toHear(settings)
    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = BgCream,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                .testTag("hifz_listen_sheet"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("الاستماع إلى المواضع", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
            if (candidates.isNotEmpty()) {
                Text("تسمع الصواب في كل موضع بترتيب المصحف، ويستمرّ الاستماع والشاشة مطفأة.", fontSize = 13.sp, color = TextMuted)
            }
            if (waiting > 0) Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(GoldLight).padding(start = 12.dp, end = 4.dp, top = 8.dp)
                    .testTag("hifz_listen_waiting"),
            ) {
                Text(hifzListenWaitingLine(waiting), Modifier.padding(end = 8.dp), fontSize = 14.sp, color = GreenPrimaryDark)
                if (onReview != null) TextButton(onClick = onReview, modifier = Modifier.testTag("hifz_listen_review")) { Text("ابدأ مراجعة اليوم") }
                else Spacer(Modifier.height(8.dp))
            }
            if (candidates.isNotEmpty()) {
                // Only when there is something to choose: some held, some not.
                if (held in 1 until candidates.size) {
                    Text("أيّ المواضع؟", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuranChoiceChip("التي لم تثبت · ${candidates.size - held}", !mastered, "hifz_listen_learning") { mastered = false }
                        QuranChoiceChip("كلها · ${candidates.size}", mastered, "hifz_listen_all") { mastered = true }
                    }
                }
                Text("كم مرة تسمع كل موضع؟", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HifzListenTimes.forEach { n -> QuranChoiceChip(quranTimes(n), n == times, "hifz_listen_times_$n") { times = n } }
                }
                Text("وبعد آخر موضع؟", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // What happens, not an order, as in «ماذا تريد أن تكرّر؟».
                    QuranChoiceChip("تعود إلى أول موضع", endless, "hifz_listen_endless") { endless = true }
                    QuranChoiceChip("يتوقّف الاستماع", !endless, "hifz_listen_stop") { endless = false }
                }
                Text("وقفة بعد كل مرة لتردّده", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuranChoiceChip("بلا وقفة", !pause, "hifz_listen_no_pause") { pause = false }
                    QuranChoiceChip("بمدة تلاوة الموضع", pause, "hifz_listen_pause") { pause = true }
                }
                Text(
                    hifzListenPlanLine(settings),
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AdhkarSoftGreen).padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("hifz_listen_plan"),
                    fontSize = 14.sp, color = GreenPrimary,
                )
                Button(
                    onClick = { HifzListenSettings.write(context, settings); onStart(heard, settings) },
                    enabled = heard.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("hifz_listen_start"),
                ) {
                    Icon(painterResource(R.drawable.ic_quran_headphones), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("ابدأ الاستماع", fontSize = 16.sp)
                }
                Text("ينقلك زرّا التالي والسابق في السماعة أو في السيارة من موضع إلى آخر.", fontSize = 12.sp, color = TextMuted)
            }
        }
    }
}

/** «حان سؤالك عن 3 مواضع، فلن تسمعها قبل أن تجيب.» */
internal fun hifzListenWaitingLine(count: Int): String {
    val them = when (count) { 1 -> "تسمعه"; 2 -> "تسمعهما"; else -> "تسمعها" }
    return "حان سؤالك عن ${HifzReminder.pointsAbout(count)}، فلن $them قبل أن تجيب."
}

/** «تسمع كل موضع 3 مرات، وتردّده بعد كل مرة، ثم الذي بعده. وبعد آخرها تعود إلى أولها، حتى توقف الاستماع.» */
internal fun hifzListenPlanLine(settings: HifzListenSettings): String {
    val say = if (!settings.pause) "" else if (settings.times == 1) "، وتردّده بعدها" else "، وتردّده بعد كل مرة"
    val end = if (settings.endless) "وبعد آخرها تعود إلى أولها، حتى توقف الاستماع." else "ويتوقّف الاستماع بعد آخرها."
    return "تسمع كل موضع ${quranTimes(settings.times)}$say، ثم الذي بعده. $end"
}
