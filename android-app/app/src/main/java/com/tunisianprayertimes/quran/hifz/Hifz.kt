package com.tunisianprayertimes.quran.hifz

import android.content.Context
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.quran.QuranVerseReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What went wrong when reciting a verse from memory, in the order a verse goes: its start, the verse as a whole, its
 * middle, its end. [label] is how the reader says it when recording, [short] the one noun every list and title uses.
 */
/** The kinds of slip, the most common first: the order the record sheet offers them in. Stored by [key]. */
enum class HifzKind(val key: String, val label: String, val short: String) {
    /** A word forgotten or said wrong, or a vowel, in the right verse; its first word, when its opening would not come. */
    Word("word", "نسيت كلمة أو أخطأت فيها", "كلمة"),
    /** Could not recall how the verse begins: recorded before the choices were three, now a word slip on its first word. */
    Start("start", "نسيت أولها", "أول الآية"),
    /**
     * Not recited: the recitation went on elsewhere, anywhere in the Qur'an. Where does not matter: the fix is to link
     * the verse to the one before it.
     */
    Skip("skip", "تخطيتها", "تخطٍّ"),
    /** Drifted into a similar verse, in the same chapter or another. */
    Similar("similar", "انتقلت إلى آية شبيهة بالخطأ", "شبيهة"),
    /** Closed the verse with another verse's ending. */
    Ending("ending", "ختمتها بخاتمة أخرى", "خاتمة"),
    /**
     * The verse would not come, nor those after it: a passage forgotten, told of the passage's own point
     * ([HifzPoint.passage]). Like a skip, it is linked to the verse before it.
     */
    Passage("passage", "نسيتها مع آيات بعدها", "آيات متتالية");

    /** The slip is told by the verse it was confused with: a different look-alike is a slip of its own. */
    val lookAlike: Boolean get() = this == Ending || this == Similar

    /** One choice on «أخطأت»: a look-alike, whether it parts from the verse in its course or at its ending. */
    fun sameChoice(other: HifzKind): Boolean = this == other || (lookAlike && other.lookAlike)

    /** It may name the verse the recitation went into: not a skip nor a passage, where that does not matter. */
    val namesOther: Boolean get() = this != Skip && this != Passage

    companion object {
        fun of(key: String): HifzKind? = entries.firstOrNull { it.key == key }
    }
}

/** The five prayers a reminder may follow, in the day's order. */
val HifzPrayers = listOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA)

/** One time of the day the reminder may come: after one of the five prayers, or at a clock time. */
sealed interface HifzWhen {
    /** For storage and alarms: «FAJR», «at1290». */
    val key: String

    data class After(val prayer: Prayer) : HifzWhen {
        override val key: String get() = prayer.name
    }

    /** At [minute] of the day, by the clock. */
    data class At(val minute: Int) : HifzWhen {
        override val key: String get() = "at$minute"
    }

    companion object {
        fun of(key: String?): HifzWhen? = when {
            key == null -> null
            key.startsWith("at") -> key.drop(2).toIntOrNull()?.takeIf { it in 0 until 24 * 60 }?.let(::At)
            else -> HifzPrayers.firstOrNull { it.name == key }?.let(::After)
        }
    }
}

/**
 * When the reminder of «المراجعة» comes, every day: after some of the five prayers and at some clock times, as
 * minutes of the day. One choice for every point, in the settings; never empty, the reminder being turned off apart.
 */
data class HifzTimes(val prayers: Set<Prayer> = emptySet(), val minutes: Set<Int> = emptySet()) {
    val none: Boolean get() = prayers.isEmpty() && minutes.isEmpty()
    /** How many reminders a day. */
    val count: Int get() = prayers.size + minutes.size
    /** The day's times, the prayers first. */
    val times: List<HifzWhen> get() = HifzPrayers.filter { it in prayers }.map { HifzWhen.After(it) } + minutes.sorted().map { HifzWhen.At(it) }

    operator fun contains(time: HifzWhen): Boolean = when (time) {
        is HifzWhen.After -> time.prayer in prayers
        is HifzWhen.At -> time.minute in minutes
    }

    /** «FAJR,ISHA,at1290», for storage. */
    fun encode(): String = times.joinToString(",") { it.key }

    companion object {
        /** Null when [value] is missing, empty or not a list of times. */
        fun decode(value: String?): HifzTimes? {
            value ?: return null
            val times = value.split(',').filter { it.isNotEmpty() }.map { HifzWhen.of(it) ?: return null }
            return HifzTimes(
                times.filterIsInstance<HifzWhen.After>().map { it.prayer }.toSet(),
                times.filterIsInstance<HifzWhen.At>().map { it.minute }.toSet(),
            ).takeIf { !it.none }
        }
    }
}

/**
 * The quick choices of the reminder's times; any other is the reader's own, «مخصص». Named by their times, «أوقات»:
 * «مرات» and «قراءات» count a mistake's answers, and «مرة في اليوم» is a mistake's own choice.
 */
enum class HifzTimesPreset(val key: String, val label: String, val times: HifzTimes) {
    /** Once a day, after Fajr: the time of hifz and of review. */
    Once("once", "بعد الفجر فقط", HifzTimes(setOf(Prayer.FAJR))),
    /** After Fajr, Dhuhr and Isha: morning, midday and before sleep. */
    Thrice("thrice", "3${HifzReminder.NB}أوقات في اليوم", HifzTimes(setOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ISHA))),
    Prayers("prayers", "بعد كل صلاة", HifzTimes(HifzPrayers.toSet()));

    companion object {
        fun of(times: HifzTimes): HifzTimesPreset? = entries.firstOrNull { it.times == times }
    }
}

/** The most clock times in a day. */
const val HifzMaxClockTimes = 6

/**
 * Starting target before checks space out, in different days with a right answer (owner's plan, 16 November: days, not
 * answers; docs/hifz-one-review-plan.md section 0). This and the intervals below are product defaults, not a validated
 * Quran-learning prescription; the reader can choose a different target.
 */
const val HifzFreshRecalls = 3
/** Upper limit offered by the current learning plan, in days. */
const val HifzMaxRecalls = 5
/** One counted right answer a day: a night between counted answers. Every other answer is saved, not counted. */
const val HifzFreshDailySuccessLimit = 1

/**
 * The proposed target from recent difficulty, including the new slip: 3, 4 or 5 days. Slips are counted once a day
 * ([HifzPoint.autoRecallsAfterSlip]): a reader who retests often is not given a bigger plan for it. Previous mastery
 * never reduces reinforcement after another mistake. Lifetime mistake totals are kept separately.
 */
fun hifzAutoRecalls(slips: Int): Int = when {
    slips >= 3 -> HifzMaxRecalls
    slips == 2 -> 4
    else -> HifzFreshRecalls
}

/**
 * How many right answers a point asks for before its checks space out: as the app judges from its history ([Auto]),
 * one for a passing slip ([Light]), or the most ([Intense]). The reader's choice on «أخطأت» or in the correction.
 */
enum class HifzPace(val key: String) {
    Auto("auto"), Light("light"), Intense("intense");

    companion object {
        fun of(key: String?): HifzPace = entries.firstOrNull { it.key == key } ?: Auto
    }
}
/**
 * Minimum separation between a fresh answer being seen/heard and a counted recall. A scheduling safeguard;
 * prayer times organize delivery but do not prove that the answer has left memory.
 */
const val HifzFreshGapMs = 45 * 60_000L
private const val HifzRecentDifficultyMs = 30 * 24 * 60 * 60_000L
private const val HifzRecentSlipLimit = 60

/**
 * A prayer, as the reminder of «المراجعة» keeps clear of it: from its adhan, or the start of its silence when that comes
 * first, to [end] (exclusive), when the reminder after it comes. The silence as the reader set it, also for a prayer
 * the app does not silence. A Friday's Dhuhr is the Jumu'a.
 */
data class HifzPrayerSpan(val prayer: Prayer, val start: Long, val end: Long)

/** The reader's prayers, for the reminder's times and a fresh point's wait; none until the app gives them, as in tests. */
object HifzPrayerSpans {
    /** The prayers of the prayer days around [at], from the day before to the day after. */
    @Volatile var around: (at: Long) -> List<HifzPrayerSpan> = { emptyList() }
    /** The prayers that may end a wait from [from]: [around], unless the app knows that wait is over anyway. */
    @Volatile var forWait: (from: Long) -> List<HifzPrayerSpan> = { around(it) }
}

/**
 * Eligibility is always [HifzFreshGapMs] after [from]. Prayer avoidance is a separate delivery rule.
 */
fun hifzWaitEnd(from: Long): Long = from + HifzFreshGapMs

/** A nearby prayer for schedule diagnostics only; it never shortens [hifzWaitEnd]. */
fun hifzWaitPrayer(from: Long): HifzPrayerSpan? =
    HifzPrayerSpans.forWait(from).filter { it.start > from && it.end < from + HifzFreshGapMs }.minByOrNull { it.end }

/**
 * [at], or the end of the prayer it falls in, and so on while that end falls in another: a reminder never comes during
 * a prayer, whether its time was set by the clock or its alarm came late.
 */
fun hifzOutsidePrayers(at: Long): Long {
    var t = at
    // A few at most: prayers whose spans meet, as Maghrib and Isha may far north.
    repeat(5) {
        t = HifzPrayerSpans.around(t).filter { t >= it.start && t < it.end }.maxOfOrNull { it.end } ?: return t
    }
    return t
}

/**
 * One way a verse went wrong, as told on «أخطأت». However many there are, a verse's mistakes are one point: one
 * question, one count, one plan ([HifzPoint]).
 */
data class HifzMistake(
    val kind: HifzKind,
    /**
     * The look-alike the recitation went into ([HifzKind.Ending], [HifzKind.Similar]). None for a skip: wherever the
     * recitation went, the fix is the same, the verse linked to the one before it (owner, 22 October); one stored before
     * then is not read.
     */
    val other: QuranVerseReference? = null,
    /** Zero-based words of the verse: where it parts from [other] (one at most), or the wrong or forgotten words. */
    val slipWords: Set<Int> = emptySet(),
    /** The reader may confuse the two verses either way. */
    val bothWays: Boolean = false,
    /** When it was last told, as wall-clock milliseconds: «أخطأت» offers the latest again. */
    val at: Long = 0L,
) {
    /** The first of [slipWords]: where a look-alike parts, or the first wrong word. */
    val word: Int? get() = slipWords.minOrNull()

    /**
     * The verse's opening was what slipped: a start forgotten, or a word slip on its first word. It is practised, and
     * heard, from the end of the verse before, which is what calls it up.
     */
    val atStart: Boolean get() = kind == HifzKind.Start || (kind == HifzKind.Word && 0 in slipWords)
}

/**
 * The mistake a new record of [kind] tells again: the same kind, and for a look-alike the same other verse, or one not
 * known on either side. A second look-alike is a mistake of its own, never written over the first.
 */
fun List<HifzMistake>.same(kind: HifzKind, other: QuranVerseReference? = null): HifzMistake? =
    // The very look-alike first: one recorded without it («لا أعرف») is the same only when none names it.
    filter { it.kind.sameChoice(kind) }.let { same ->
        same.firstOrNull { kind.lookAlike && other != null && it.other == other }
            ?: same.firstOrNull { !kind.lookAlike || other == null || it.other == null || it.other == other }
    }

/**
 * A verse the reader went wrong in, as references only: every text shown comes from the app's own Qaloun index. One
 * point per verse, whatever went wrong in it ([mistakes]): it is asked as a whole, the verse recited from memory, and
 * holds once the whole verse is recalled. Only the reader creates a point; nothing is recorded automatically.
 */
data class HifzPoint(
    val id: Long,
    /** The verse recited wrongly, or the verse skipped. */
    val verse: QuranVerseReference,
    /** What went wrong in it, in the order first told; never empty. */
    val mistakes: List<HifzMistake>,
    /** «ضابطي»: the reader's own way to remember the verse. */
    val key: String = "",
    /** How many times the verse went wrong: each «أخطأت» on it, and each question it was not recalled at. */
    val slips: Int = 1,
    /** The review ladder's step; see [HifzLadder]. */
    val step: Int = 0,
    /** The next check, as an epoch day. */
    val dueDay: Long,
    val createdAt: Long,
    val lastSlipAt: Long = createdAt,
    /** When the point was last held for good, as wall-clock milliseconds. */
    val masteredAt: Long? = null,
    /**
     * Right answers still needed before the checks space out ([HifzFreshRecalls] after a slip, 0 once on the ladder):
     * until then the point is asked at every reminder, a while after its slip or its last answer ([hifzWaitEnd]).
     */
    val fresh: Int = 0,
    /** Actual spaced checks, oldest first. Older notebooks have no inferred review history. */
    val checks: List<HifzCheck> = emptyList(),
    /** How many right answers it asks for while fresh; see [HifzPace]. */
    val pace: HifzPace = HifzPace.Auto,
    /**
     * While fresh, the reminder times that ask it, the reader's own for this slip; null for those of the settings. One
     * time of day is once a day: see [daily]. Its spaced checks come at the settings' times.
     */
    val times: HifzTimes? = null,
    /** What [HifzPace.Auto] asks for in this fresh phase, judged from the history when it began; see [hifzAutoRecalls]. */
    val autoRecalls: Int = HifzFreshRecalls,
    /** Null only for legacy callers/data: infer their old single-private-slot behavior. New plans store a boolean. */
    val oncePerDay: Boolean? = null,
    /** Known recent slip times, for adapting the next phase without rewriting lifetime history or current progress. */
    val recentSlipTimes: List<Long> = emptyList(),
    /**
     * A forgotten passage's last verse, in [verse]'s surah; null for one verse. A passage is one point, like one long
     * verse: one question, one answer, one plan (owner, 12 November). It may share verses with a verse's own point.
     */
    val last: Int? = null,
    /** Every right answer without help ever given, counted or not: never trimmed with [checks]. */
    val rightAnswers: Int = 0,
    /** The days with at least one right answer without help: never trimmed with [checks]. */
    val rightDays: Int = 0,
) {
    /** A forgotten passage, from [verse] to [last]. */
    val passage: Boolean get() = last != null
    /** The verses it asks: [verse] alone, or the passage from [verse] to [last]. */
    val span: List<QuranVerseReference> get() = (verse.ayah..(last ?: verse.ayah)).map { QuranVerseReference(verse.surah, it) }
    /** Its last verse: [verse] itself, or the passage's last. */
    val end: QuranVerseReference get() = QuranVerseReference(verse.surah, last ?: verse.ayah)
    /** [other] is one of the verses it asks ([span]). */
    fun asks(other: QuranVerseReference): Boolean = other.surah == verse.surah && other.ayah in verse.ayah..(last ?: verse.ayah)
    val mastered: Boolean get() = step >= HifzMasteredStep
    /**
     * Asked once a day: no more since the plan counts days (16 November). One counted right answer a day already
     * waits for the next day; before it, a fresh point is asked at each of its reminder times. [oncePerDay] is still
     * read and written, unused.
     */
    val daily: Boolean get() = false
    /** The right answers this fresh phase asks for in all, as [pace] says. */
    val recalls: Int get() = when (pace) {
        HifzPace.Auto -> autoRecalls
        HifzPace.Light -> 1
        HifzPace.Intense -> HifzMaxRecalls
    }
    /** The mistake told last, which «أخطأت» offers again. */
    val latest: HifzMistake get() = mistakes.maxBy { it.at }
    /** Its opening slipped in one of its mistakes; see [HifzMistake.atStart]. */
    val atStart: Boolean get() = mistakes.any { it.atStart }
    /** The verses it was confused with, as told. */
    val lookAlikes: List<QuranVerseReference> get() = mistakes.filter { it.kind.lookAlike }.mapNotNull { it.other }.distinct()
    /**
     * The verse was skipped: not recited, the recitation going on elsewhere, anywhere in the Qur'an. Where does not
     * matter: the fix is always to link the verse to the one before it (owner, 22 October).
     */
    val skipped: Boolean get() = mistakes.any { it.kind == HifzKind.Skip }
    /**
     * Practised, heard and corrected with the end of the verse before it, which calls it up: a forgotten opening, a
     * skipped verse, a forgotten passage.
     */
    val linked: Boolean get() = atStart || skipped || passage
    /** When any of its verses was last [seen]: a passage's answer is all of them. */
    fun seenAt(seen: Map<QuranVerseReference, Long>): Long? = span.mapNotNull { seen[it] }.maxOrNull()
    /**
     * When a [fresh] point may be asked again: a while after its slip or its last counted answer, whichever came later.
     * An answer that did not count moves nothing.
     */
    val freshDueAt: Long get() = hifzWaitEnd(maxOf(lastSlipAt, checks.lastOrNull { it.counted }?.at ?: 0L))
    /** Recent difficulty has priority until two unaided checks show recovery. */
    val needsCare: Boolean
        get() {
            if (checks.isEmpty()) return slips >= 3 && !mastered
            if (lastSlipAt > checks.last().at) return true
            val recent = dailyChecksSinceSlip.takeLast(3)
            if (recent.size >= 2 && recent.takeLast(2).all { it.grade == HifzGrade.Held }) return false
            return recent.any { it.grade == HifzGrade.Again } ||
                recent.count { it.grade == HifzGrade.Hesitated } >= 2 ||
                (recent.size < 2 && lastSlipAt > checks.first().at) ||
                (checks.size < 2 && slips >= 3 && !mastered)
        }
    /**
     * Feedback is about observed checks, never an invented history for an older notebook; said of the reader, as they
     * answered, never a verdict on them: «لم تتذكّرها في آخر سؤال».
     */
    val recentFeedback: String?
        get() {
            val last = checks.lastOrNull() ?: return null
            if (lastSlipAt > last.at) return "أخطأت فيها بعد آخر سؤال"
            // The last two answers given, as said: not the first of each day, which «يحتاج عناية» goes by.
            val lastTwo = checks.filter { it.at >= lastSlipAt }.takeLast(2)
            if (lastTwo.size == 2 && lastTwo.all { it.grade == HifzGrade.Held }) return "تذكّرتها دون مساعدة في آخر سؤالين"
            return when (last.grade) {
                HifzGrade.Held -> "تذكّرتها دون مساعدة في آخر سؤال"
                HifzGrade.Hesitated -> "تذكّرتها بتردّد أو بمساعدة في آخر سؤال"
                HifzGrade.Again -> "نسيتها أو أخطأت فيها في آخر سؤال"
            }
        }
    /**
     * The checks since the last slip, the first of each day only: a fresh point answered at several reminders of one
     * day shows recovery once, not as many times.
     */
    private val dailyChecksSinceSlip: List<HifzCheck>
        get() = checks.filter { it.at >= lastSlipAt }.groupBy { it.day }.values.map { day -> day.minBy { it.at } }.sortedBy { it.at }
    /** Checks still to pass, each «أتقنتها», before the point is held («ثبتت»). */
    val checksToHold: Int get() = (HifzMasteredStep - step).coerceAtLeast(0)
    /**
     * When a fresh point may be asked, its answer [seen] last at that time (the verse shown, heard or corrected), also
     * a while ago: a recall right after seeing the answer would be none.
     */
    fun freshDueAt(seen: Long?): Long = if (seen == null) freshDueAt else maxOf(freshDueAt, hifzWaitEnd(seen))
    /**
     * When a fresh point may next be asked: [freshDueAt], and for one asked [daily], not before the day after it was
     * answered.
     */
    fun freshAskAt(seen: Long?, zone: ZoneId = ZoneId.systemDefault()): Long =
        maxOf(freshDueAt(seen), LocalDate.ofEpochDay(dueDay).atStartOfDay(zone).toInstant().toEpochMilli())
    /**
     * To be asked now, at [now] on [today]: a fresh point once its gap has passed, after its slip, its last answer and
     * its verse last [seen], a [daily] one on a day it was not answered; any other on its day or after, unless its
     * verse was looked at today ([seenToday]), which puts its check to the next day. A point is asked only when its
     * answer counts: never one just seen.
     */
    fun isDue(
        today: Long, now: Long, seen: Map<QuranVerseReference, Long> = emptyMap(),
        seenToday: Set<QuranVerseReference> = emptySet(),
    ): Boolean = if (fresh > 0) now >= freshDueAt(seenAt(seen)) && dueDay <= today else dueDay <= today && span.none { it in seenToday }
}

/**
 * A slip's plan as the reader sets it on «أخطأت» or in its correction: how many right answers ([pace]), and at which
 * reminder times ([times]; null for the settings').
 */
data class HifzPlan(val pace: HifzPace, val times: HifzTimes?, val oncePerDay: Boolean = times?.count == 1)

/**
 * [kind] told of this verse: the same mistake as it is told this time, or another one beside those it has. Counts
 * nothing by itself: a record counts the verse again ([HifzRepository.record]), a place added after a failed answer
 * does not, the answer having counted.
 */
fun HifzPoint.told(kind: HifzKind, other: QuranVerseReference?, slipWords: Set<Int>, bothWays: Boolean, at: Long): HifzPoint {
    val same = mistakes.same(kind, other)
    val told = same?.copy(
        // A look-alike said again: as it is told this time, in its course or at its ending.
        kind = kind,
        // A skip or a passage names no other verse, where it went not mattering; a look-alike kept unless named again.
        other = if (!kind.namesOther) null else other ?: same.other,
        // The words chosen now, which the sheet starts from the earlier ones; none chosen keeps those.
        slipWords = slipWords.ifEmpty { same.slipWords },
        bothWays = bothWays || same.bothWays,
        at = at,
    ) ?: HifzMistake(kind, other.takeIf { kind.namesOther }, slipWords, bothWays, at)
    val index = mistakes.indexOf(same)
    return copy(mistakes = if (index < 0) mistakes + told else mistakes.toMutableList().apply { set(index, told) })
}

/**
 * The mistake at [index] corrected to [changed], no slip counted. Corrected into one the verse has already, the two
 * are one: the other goes.
 */
fun HifzPoint.corrected(index: Int, changed: HifzMistake): HifzPoint {
    val clash = mistakes.filterIndexed { i, _ -> i != index }.same(changed.kind, changed.other)
    return copy(mistakes = mistakes.mapIndexedNotNull { i, mistake -> if (i == index) changed else mistake.takeIf { it != clash } })
}

/**
 * A slip again, recorded or a failed answer: fresh again, its right answers asked anew, as many as its history now
 * calls for. The reader's explicit pace and frequency remain in effect unless [plan] changes them.
 * A [daily] point already asked today ([askedToday]) waits for the next day.
 */
fun HifzPoint.relapsed(today: Long, now: Long, askedToday: Boolean, plan: HifzPlan? = null): HifzPoint {
    val count = slips + 1
    val next = copy(
        slips = count, autoRecalls = autoRecallsAfterSlip(now),
        pace = plan?.pace ?: pace,
        times = if (plan != null) plan.times else times,
        oncePerDay = plan?.oncePerDay ?: daily,
        recentSlipTimes = (knownRecentSlips(now) + now).distinct().sorted().takeLast(HifzRecentSlipLimit),
    )
    return next.copy(
        fresh = next.recalls, step = 0, dueDay = if (next.daily && askedToday) today + 1 else today, masteredAt = null,
        lastSlipAt = now,
    )
}

/** The right answers asked after a failed answer now; see [relapsed]. */
val HifzPoint.recallsAfterAgain: Int
    get() = copy(autoRecalls = autoRecallsAfterSlip()).recalls

/** Legacy records contribute only timestamps we actually know, never invented dates for their lifetime total. */
private fun HifzPoint.knownRecentSlips(now: Long): List<Long> =
    (recentSlipTimes + createdAt + lastSlipAt + checks.filter { it.grade == HifzGrade.Again }.map { it.at })
        .filter { it in (now - HifzRecentDifficultyMs)..now }.distinct()

/**
 * A proposal for the next failure only; elapsed time never silently changes the target of an active fresh phase. Slips
 * are counted once a day: several misses on one day are one difficult day.
 */
fun HifzPoint.autoRecallsAfterSlip(now: Long = System.currentTimeMillis()): Int =
    hifzAutoRecalls((knownRecentSlips(now) + now).map { epochDay(it) }.distinct().size)

/** Counted right answers since the slip, one a day at most: the days the plan has had. */
val HifzPoint.rightSinceSlip: Int
    get() = checks.filter { it.counted && it.at >= lastSlipAt && it.grade == HifzGrade.Held }
        .groupingBy { it.day }.eachCount().values.sumOf { it.coerceAtMost(HifzFreshDailySuccessLimit) }

/** Counted reinforcement successes on [day], also used when a plan is edited. */
private fun HifzPoint.rightOn(day: Long): Int =
    checks.count { it.counted && it.at >= lastSlipAt && it.day == day && it.grade == HifzGrade.Held }

/**
 * The plan changed by the reader on [today]: a fresh point keeps the right answers it already gave ([rightSinceSlip]);
 * a count they already reach ends its questions, its checks spacing out from the next day. Asked once a day, one asked
 * today waits for tomorrow; asked at several times, it may come at the next one.
 */
fun HifzPoint.withPlan(plan: HifzPlan, today: Long): HifzPoint {
    val changed = copy(pace = plan.pace, times = plan.times, oncePerDay = plan.oncePerDay)
    if (fresh == 0) return changed
    val left = changed.recalls - rightSinceSlip
    if (left <= 0) return changed.copy(fresh = 0, step = 0, dueDay = today + HifzLadder.first())
    val askedToday = checks.any { it.day == today }
    return changed.copy(
        fresh = left,
        dueDay = when {
            (changed.daily && askedToday) || rightOn(today) >= HifzFreshDailySuccessLimit -> maxOf(dueDay, today + 1)
            changed.daily -> dueDay
            else -> minOf(dueDay, today)
        },
    )
}

/**
 * Waiting gaps after reinforcement and successive successful checks, not offsets from the first day.
 * Nine checks through the 28-day gap earn «ثبتت»; maintenance then repeats every 60 days.
 */
val HifzLadder = listOf(1, 3, 5, 7, 10, 13, 17, 22, 28, 60)
/** A point that passed all nine checks, including the 28-day gap, is held («ثبتت»). */
const val HifzMasteredStep = 9

/** How a check went: «أتقنتها», «ترددت», «ما زالت تحتاج تدريبا». */
enum class HifzGrade { Held, Hesitated, Again }

/**
 * One answer to a point's question, whoever started it. [counted]: it moved the plan (decided by the time alone, R0);
 * an answer that did not is kept too, for «أجوبتك», and moves nothing. A miss always counts.
 */
data class HifzCheck(val day: Long, val grade: HifzGrade, val at: Long, val counted: Boolean = true)

private const val HifzCheckLimit = 60
/** Answers that did not count, kept besides the counted ones: the latest only. */
private const val HifzUncountedLimit = 20

/**
 * The last [HifzCheckLimit] counted answers and the last [HifzUncountedLimit] others, in their order: a day of many
 * retests never pushes out the counted history the plan is read from.
 */
internal fun List<HifzCheck>.trimmedChecks(): List<HifzCheck> {
    val counted = filter { it.counted }.takeLast(HifzCheckLimit).toSet()
    val others = filter { !it.counted }.takeLast(HifzUncountedLimit).toSet()
    return filter { it in counted || it in others }
}

/** Compact format of the notebook's checks; an answer that did not count ends with «:0». */
fun encodeHifzChecks(checks: List<HifzCheck>): String = checks.trimmedChecks()
    .joinToString(",") { "${it.day}:${it.grade.name}:${it.at}" + if (it.counted) "" else ":0" }

fun decodeHifzChecks(value: String?): List<HifzCheck> = value.orEmpty().split(',').mapNotNull { row ->
    val parts = row.split(':')
    if (parts.size != 3 && parts.size != 4) return@mapNotNull null
    val day = parts[0].toLongOrNull() ?: return@mapNotNull null
    val grade = HifzGrade.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
    val at = parts[2].toLongOrNull() ?: return@mapNotNull null
    HifzCheck(day, grade, at, counted = parts.getOrNull(3) != "0")
}.trimmedChecks()

/**
 * The point after a review graded [grade] on [today]. A fresh point counts its right answers down, at the next reminders,
 * and its checks space out after the last: from the next day, then along [HifzLadder]. Later, a hesitation brings the
 * point back in 2 days, one step lower: the next «أتقنتها» then waits the same time as before instead of a longer one,
 * and a held point hesitated on leaves «ثبتت» until it is held again. A failed check is a slip like any other: the point
 * is fresh again, asked at every reminder until it is answered right [HifzFreshRecalls] times.
 */
fun HifzPoint.graded(grade: HifzGrade, today: Long, now: Long, counted: Boolean = true): HifzPoint {
    val held = grade == HifzGrade.Held
    // The totals count every right answer, before [checks] is trimmed; a day once, at its first right answer.
    val totals = if (!held) this else copy(
        rightAnswers = rightAnswers + 1,
        rightDays = rightDays + if (checks.none { it.day == today && it.grade == HifzGrade.Held }) 1 else 0,
    )
    // R0 at save time: a miss always counts; a right answer or a hesitation only when its question could count when it
    // was asked ([counted]), and in the fresh phase one right answer a day. The others are saved and move nothing.
    val counts = grade == HifzGrade.Again ||
        (counted && !(fresh > 0 && held && rightOn(today) >= HifzFreshDailySuccessLimit))
    if (!counts) return totals.copy(checks = (checks + HifzCheck(today, grade, now, counted = false)).trimmedChecks())
    return with(totals) { when {
        grade == HifzGrade.Again -> relapsed(today, now, askedToday = true)
        // Asked once a day: the next question is tomorrow's.
        fresh > 0 -> when {
            grade == HifzGrade.Hesitated -> copy(dueDay = if (daily) today + 1 else today)
            fresh == 1 -> copy(fresh = 0, step = 0, dueDay = today + HifzLadder.first())
            else -> copy(fresh = fresh - 1, dueDay = if (daily || rightOn(today) + 1 >= HifzFreshDailySuccessLimit) today + 1 else today)
        }
        grade == HifzGrade.Held -> {
            val next = step + 1
            copy(step = next, dueDay = today + HifzLadder[next.coerceAtMost(HifzLadder.lastIndex)],
                masteredAt = if (next == HifzMasteredStep) now else masteredAt)
        }
        else -> {
            val back = (step - 1).coerceAtLeast(0)
            copy(step = back, dueDay = today + 2, masteredAt = masteredAt.takeIf { back >= HifzMasteredStep })
        }
    }.copy(checks = (checks + HifzCheck(today, grade, now)).trimmedChecks()) }
}

/** A «أتقنتها» now makes the point held («ثبتت»). */
val HifzPoint.holdsNext: Boolean get() = fresh == 0 && step + 1 == HifzMasteredStep

/** A «أتقنتها» now ends the fresh point's questions at every reminder: its checks space out from the next day. */
val HifzPoint.spacesNext: Boolean get() = fresh == 1

/**
 * Days until the next check if the review is graded [grade]; 0 for a point asked again at the next reminders, which
 * every failed check makes.
 */
fun HifzPoint.daysAfter(grade: HifzGrade, today: Long = todayEpochDay()): Int = when {
    grade == HifzGrade.Again -> if (daily) 1 else 0
    fresh > 0 -> if (daily || (grade == HifzGrade.Held && (fresh == 1 || rightOn(today) + 1 >= HifzFreshDailySuccessLimit))) 1 else 0
    grade == HifzGrade.Held -> HifzLadder[(step + 1).coerceAtMost(HifzLadder.lastIndex)]
    else -> 2
}

/**
 * The points a review may ask at [now] on [today]: those due, a fresh point a while after its answer was last [seen],
 * a spaced check only on a day its verse was not looked at ([seenToday]).
 */
fun List<HifzPoint>.askable(
    today: Long, now: Long, seen: Map<QuranVerseReference, Long> = emptyMap(),
    seenToday: Set<QuranVerseReference> = emptySet(),
): List<HifzPoint> = filter { it.isDue(today, now, seen, seenToday) }

/**
 * Due points in balanced groups: up to three fresh slips and two spaced checks, filling unused places from either
 * group. Within each group, recent difficulty and oldest due dates lead. A stream of new slips cannot hide all checks.
 */
fun List<HifzPoint>.dueToday(today: Long, now: Long, limit: Int = Int.MAX_VALUE): List<HifzPoint> {
    val ranked = filter { it.isDue(today, now) }.sortedWith(
        compareByDescending<HifzPoint> { it.needsCare }.thenBy { it.dueDay }
            .thenByDescending { it.slips }.thenBy { it.verse },
    )
    val fresh = ArrayDeque(ranked.filter { it.fresh > 0 })
    val checks = ArrayDeque(ranked.filter { it.fresh == 0 })
    return buildList {
        while (size < limit && (fresh.isNotEmpty() || checks.isNotEmpty())) {
            repeat(3) { if (size < limit && fresh.isNotEmpty()) add(fresh.removeFirst()) }
            repeat(2) { if (size < limit && checks.isNotEmpty()) add(checks.removeFirst()) }
        }
    }
}

/**
 * At most [limit] points due, taken by [dueToday]'s priority: what a reminder lists, or with no limit, what a review can
 * ask ([reviewSitting]); [first], chosen by the reader, asked first.
 * No point is asked after another one shows its verse (R3): a point whose question or answer [shows] another one's verse
 * comes after it, so that both are asked, a look-alike after the verse it was confused with, a verse after the one
 * before it; otherwise mushaf order, as a hafiz goes through the pages. Points that would each see the other's answer
 * first (a look-alike pair both ways, or a longer loop) cannot all be asked: the one answered last waits for the next
 * reminder, also against one ranked lower, so that they take turns.
 */
fun List<HifzPoint>.reviewBatch(today: Long, now: Long, limit: Int, first: HifzPoint? = null): List<HifzPoint> {
    if (limit <= 0) return emptyList()
    val answered = { point: HifzPoint -> maxOf(point.lastSlipAt, point.checks.lastOrNull { it.counted }?.at ?: Long.MIN_VALUE) }
    val sees = { a: HifzPoint, b: HifzPoint -> a.id != b.id && a.showsAnswerOf(b) }
    val chosen = mutableListOf<HifzPoint>().apply { first?.let(::add) }
    val ranked = dueToday(today, now).filterNot { it.id == first?.id || (first != null && sees(first, it)) }
    val candidates = (listOfNotNull(first) + ranked)
    val availableFresh = candidates.count { it.fresh > 0 }
    val availableChecks = candidates.count { it.fresh == 0 }
    val freshQuota = minOf(availableFresh, maxOf((limit * 3 + 4) / 5, limit - availableChecks))
    val checkQuota = minOf(availableChecks, limit - freshQuota)
    fun groupCount(fresh: Boolean) = chosen.count { (it.fresh > 0) == fresh }
    fun hasRoom(point: HifzPoint, reserved: Boolean): Boolean {
        val fresh = point.fresh > 0
        val quota = if (fresh) freshQuota else checkQuota
        val count = groupCount(fresh)
        return chosen.size < limit && (!reserved || count < quota)
    }
    /** The chosen points on a loop with [point]: each shows the next one's verse, back to [point]. */
    fun loopWith(point: HifzPoint): List<HifzPoint> {
        val all = chosen + point
        fun reach(step: (HifzPoint, HifzPoint) -> Boolean): Set<Long> {
            val found = mutableSetOf(point.id)
            val todo = ArrayDeque(listOf(point))
            while (todo.isNotEmpty()) {
                val from = todo.removeFirst()
                all.filter { it.id !in found && step(from, it) }.forEach { found += it.id; todo += it }
            }
            return found
        }
        val ahead = reach(sees)
        val behind = reach { a, b -> sees(b, a) }
        return chosen.filter { it.id in ahead && it.id in behind }
    }
    /** Takes [point] if it fits as the batch stands: room left, and no loop. */
    fun offer(point: HifzPoint, reserved: Boolean) {
        if (chosen.none { it.id == point.id } && hasRoom(point, reserved) && loopWith(point).isEmpty()) chosen += point
    }
    fun select(reserved: Boolean) {
        val passed = mutableListOf<HifzPoint>()
        for (point in ranked) {
            if (chosen.any { it.id == point.id }) continue
            val beforeFresh = groupCount(true)
            val beforeChecks = groupCount(false)
            val dropped = mutableListOf<HifzPoint>()
            while (true) {
                val loop = loopWith(point).filter { it.id != first?.id }
                if (loop.isEmpty()) break
                val latest = loop.maxWith(compareBy<HifzPoint>({ answered(it) }, { it.id }))
                if (answered(latest) <= answered(point)) break
                chosen.remove(latest)
                dropped += latest
            }
            // Refilling a category blocked by answer exposure must not consume the other category's reserved places.
            val keepsReservations = reserved || (
                groupCount(true) + (if (point.fresh > 0) 1 else 0) >= minOf(beforeFresh, freshQuota) &&
                groupCount(false) + (if (point.fresh == 0) 1 else 0) >= minOf(beforeChecks, checkQuota)
            )
            if (loopWith(point).isEmpty() && hasRoom(point, reserved) && keepsReservations) {
                chosen += point
                // A cycle may free several places. Earlier compatible candidates refill those places first.
                if (dropped.isNotEmpty()) passed.forEach { offer(it, reserved) }
            } else chosen += dropped
            passed += point
        }
    }
    select(reserved = true)
    // A category may have fewer compatible questions than its quota. Fill those unused places without losing checks.
    if (chosen.size < limit) select(reserved = false)
    return listOfNotNull(first) + chosen.filter { it.id != first?.id }.sortedWith(compareBy<HifzPoint>({ it.verse }, { it.id })).answerSafeOrder()
}

/**
 * [this] in its own order, except that a point comes once every one whose verse it [shows] is asked, so that no answer
 * gives away a question still to come; on a loop, which cannot be ordered so, the first one left goes. A
 * [reviewBatch]'s order after its first question, and the order of a list review ([hifzListPlan]).
 */
internal fun List<HifzPoint>.answerSafeOrder(): List<HifzPoint> {
    val rest = toMutableList()
    // Worked out once: a list may be the whole notebook.
    val shown = associate { point -> point.id to point.answerVerses() }
    fun sees(a: HifzPoint, b: HifzPoint) = a.id != b.id &&
        b.span.any { it in shown.getValue(a.id) || (it.surah == a.verse.surah && it.ayah == a.verse.ayah - 1) }
    /** The points [from] shows, and those they show in turn, among those left. */
    fun reach(from: HifzPoint): Set<Long> {
        val found = mutableSetOf<Long>()
        val todo = ArrayDeque(listOf(from))
        while (todo.isNotEmpty()) {
            val at = todo.removeFirst()
            rest.filter { it.id !in found && sees(at, it) }.forEach { found += it.id; todo += it }
        }
        return found
    }
    return buildList {
        while (rest.isNotEmpty()) {
            // On a loop, one of the loop itself, which only one of its own answers can give away: never a point that
            // merely leads into it, whose question would give away the loop's too.
            val next = rest.firstOrNull { point -> rest.none { sees(point, it) } }
                ?: rest.firstOrNull { point -> reach(point).all { id -> point.id in reach(rest.first { it.id == id }) } }
                ?: rest.first()
            add(next)
            rest.remove(next)
        }
    }
}

/**
 * One review of everything due now. [lead], the questions the reminder that opens it listed or the point the reader
 * chose, is asked first, in its order; a point whose verse a lead question or answer shows is left for later, as after
 * any answer that shows it. Which of the rest can be asked is [reviewBatch]'s choice with no limit: of points that would
 * each see the other's answer first, the one answered last waits for the next reminder. They come by [dueToday]'s
 * priority in groups of [HifzBatchSize], each group in mushaf order, so that a reader who stops early has answered what
 * matters most; a point whose verse another one shows comes just before it ([prerequisitesFirst]).
 */
fun List<HifzPoint>.reviewSitting(today: Long, now: Long, lead: List<HifzPoint> = emptyList()): List<HifzPoint> {
    val leadIds = lead.mapTo(HashSet()) { it.id }
    val rest = filter { point -> point.id !in leadIds && lead.none { it.showsAnswerOf(point) } }
    val asked = rest.reviewBatch(today, now, rest.size).mapTo(HashSet()) { it.id }
    return lead + rest.dueToday(today, now).filter { it.id in asked }.chunked(HifzBatchSize)
        .flatMap { group -> group.sortedWith(compareBy<HifzPoint>({ it.verse }, { it.id })) }
        .prerequisitesFirst()
}

/**
 * [this] in its own order, except that a point whose verse another one [shows] comes just before it, after any whose
 * verse it shows in turn: no answer gives away a question still to come, and what comes first stays first. On a loop,
 * which cannot be ordered so, the point reached first goes last.
 */
internal fun List<HifzPoint>.prerequisitesFirst(): List<HifzPoint> {
    // Worked out once: a review may hold every point of the notebook.
    val shown = associate { point -> point.id to point.answerVerses() }
    fun sees(a: HifzPoint, b: HifzPoint) = a.id != b.id &&
        b.span.any { it in shown.getValue(a.id) || (it.surah == a.verse.surah && it.ayah == a.verse.ayah - 1) }
    val placed = mutableSetOf<Long>()
    val reached = mutableSetOf<Long>()
    val order = mutableListOf<HifzPoint>()
    fun place(point: HifzPoint) {
        if (point.id in placed || !reached.add(point.id)) return
        forEach { other -> if (other.id !in placed && sees(point, other)) place(other) }
        placed += point.id
        order += point
    }
    forEach(::place)
    return order
}

/**
 * The verses a point's correction shows, and its audio plays: its own verse and its look-alikes; for a forgotten start
 * or a skipped verse, the verse before it too, which it is linked to.
 */
internal fun HifzPoint.answerVerses(): Set<QuranVerseReference> = buildSet {
    addAll(span)
    addAll(lookAlikes)
    if (linked && verse.ayah > 1) add(QuranVerseReference(verse.surah, verse.ayah - 1))
}

/** A verse a point's question shows, the end of the verse before it, or its answer ([answerVerses]). */
internal fun HifzPoint.shows(other: QuranVerseReference): Boolean =
    other in answerVerses() || (other.surah == verse.surah && other.ayah == verse.ayah - 1)

/** Its question or answer shows [other]'s answer: one of the verses [other] asks. */
internal fun HifzPoint.showsAnswerOf(other: HifzPoint): Boolean = other.span.any { shows(it) }

/**
 * The point of [verse] alone, whatever went wrong in it; null for a verse not in the notebook. A forgotten passage
 * starting there is a point of its own ([passageOf]).
 */
fun List<HifzPoint>.pointOf(verse: QuranVerseReference): HifzPoint? = firstOrNull { it.verse == verse && !it.passage }

/** The forgotten passage from [start] to [last], told again when the same passage is forgotten again. */
fun List<HifzPoint>.passageOf(start: QuranVerseReference, last: Int): HifzPoint? =
    firstOrNull { it.passage && it.verse == start && it.last == last }

/** Every point that asks [verse]: its own, and the passages it is in. */
fun List<HifzPoint>.pointsAsking(verse: QuranVerseReference): List<HifzPoint> = filter { it.asks(verse) }

/** A reminder: [time] of the prayer day [day], an epoch day, coming at [at] in wall-clock milliseconds. */
data class HifzSlot(val day: Long, val time: HifzWhen, val at: Long)

/** Prayer days looked through for a reminder: prayer times further off may not be known yet. */
private const val HifzSlotScanDays = 4

/**
 * The first of these times later than [after], of the prayer day [fromDay] or a later one: a day's Isha that ends after
 * midnight (plan 6.9) is still that day's, never the next day's first reminder. Prayer days are taken from the one
 * before [after], and a day's reminders come on that day or, for Isha, just after midnight: two prayer days past the
 * earliest found, none can come before it. [slotAt] gives when a time of a prayer day comes, or null when that day's
 * prayer times are not known.
 */
fun HifzTimes.firstAfter(
    after: Long, fromDay: Long = Long.MIN_VALUE, zone: ZoneId = ZoneId.systemDefault(),
    slotAt: (day: Long, time: HifzWhen) -> Long?,
): HifzSlot? {
    if (none) return null
    val start = maxOf(epochDay(after, zone) - 1, fromDay)
    var best: HifzSlot? = null
    for (day in start..start + HifzSlotScanDays) {
        if (best != null && day > best.day + 1) break
        for (time in times) {
            val at = slotAt(day, time) ?: continue
            if (at > after && (best == null || at < best.at)) best = HifzSlot(day, time, at)
        }
    }
    return best
}

/**
 * The next reminder later than [after]: the first of a fresh point's times (its own, or [times]) by which it may be
 * asked again, its answer last [seen] a while before; or, for the spaced checks, the first of [times] of their prayer
 * day, and of no day already reminded of them ([remindedDay], the last prayer day that was). A check whose verse was
 * looked at today ([seenToday]) is the next day's.
 */
fun List<HifzPoint>.nextReminder(
    times: HifzTimes, today: Long, after: Long, remindedDay: Long, seenToday: Set<QuranVerseReference> = emptySet(),
    seen: Map<QuranVerseReference, Long> = emptyMap(), zone: ZoneId = ZoneId.systemDefault(),
    /** The prayer day each point asked once a day was last asked by a reminder, answered or not. */
    dailyAsked: Map<Long, Long> = emptyMap(),
    /** Last prayer day each spaced check actually appeared in a delivered batch. */
    checksAsked: Map<Long, Long> = emptyMap(),
    slotAt: (day: Long, time: HifzWhen) -> Long?,
): HifzSlot? {
    val fresh = filter { it.fresh > 0 }.mapNotNull {
        // Once a day: not before its day, nor on a day a reminder already asked it.
        val from = if (it.daily) maxOf(it.dueDay, dailyAsked[it.id]?.plus(1) ?: Long.MIN_VALUE) else it.dueDay
        (it.times ?: times).firstAfter(maxOf(after, it.freshDueAt(it.seenAt(seen)) - 1), from, zone, slotAt)
    }
    val checksDay = filter { it.fresh == 0 }.minOfOrNull {
        maxOf(
            if (it.span.any { v -> v in seenToday } && it.dueDay <= today) today + 1 else it.dueDay,
            checksAsked[it.id]?.plus(1) ?: Long.MIN_VALUE,
            remindedDay + 1,
        )
    }
    val checks = checksDay?.let { times.firstAfter(after, it, zone, slotAt) }
    return (fresh + listOfNotNull(checks)).minByOrNull { it.at }
}

fun epochDay(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

fun todayEpochDay(): Long = LocalDate.now().toEpochDay()

/** The primary recording and optional mirror, with their exact pre-commit values for guarded Undo. */
data class HifzRecordChange(val originals: List<HifzPoint?>, val saved: List<HifzPoint>)

/** The notebook, kept in a small JSON file and observed by every screen. */
object HifzRepository {
    private val mutablePoints = MutableStateFlow<List<HifzPoint>>(emptyList())
    val points: StateFlow<List<HifzPoint>> = mutablePoints.asStateFlow()
    @Volatile private var loaded = false
    private val lock = Any()
    /** False until the notebook has been read: an empty list then means nothing yet, not no points. */
    val isLoaded: Boolean get() = loaded

    /** Reads the notebook once; call off the main thread. */
    fun load(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            read(context)
        }
    }

    /** Re-reads the active notebook after a test replaces it. Call off the main thread. */
    internal fun reload(context: Context) = synchronized(lock) { read(context) }

    private fun read(context: Context) {
        val target = file(context)
        val activeExists = target.exists() || File("${target.path}.bak").exists()
        val loadedPoints = if (activeExists) {
            // Always use AtomicFile for the active notebook: it recovers a write interrupted by a crash.
            // Disk read failures propagate; they must not be mistaken for incompatible development data.
            val bytes = AtomicFile(target).readFully()
            val decoded = try {
                decodeHifzPoints(bytes.decodeToString()).sortedBy { it.verse }
            } catch (_: JSONException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            // The app is still in development: reset obsolete or malformed notebooks without a recovery flow.
            // Commit the reset before exposing it, so a failed write still prevents loading.
            decoded ?: emptyList<HifzPoint>().also { write(context, it) }
        } else emptyList()
        mutablePoints.value = loadedPoints
        loaded = true
    }

    /**
     * Records a slip: a new point for a verse not in the notebook, else what went wrong is told to the verse's point,
     * which counts again, whether the same mistake came back or another one came. Returns the point as saved: fresh,
     * asked at the reminders a while after ([hifzWaitEnd]), until it is answered right as many times as its plan says
     * ([plan]: the reader's choice; null keeps the point's, or the app's for a new one).
     */
    fun record(
        context: Context,
        verse: QuranVerseReference,
        kind: HifzKind,
        other: QuranVerseReference? = null,
        slipWords: Set<Int> = emptySet(),
        bothWays: Boolean = false,
        now: Long = System.currentTimeMillis(),
        plan: HifzPlan? = null,
    ): HifzPoint = change(context) { points ->
        val point = recordedPoint(points, verse, kind, other, slipWords, bothWays, now, plan)
        (points.filterNot { it.id == point.id } + point) to point
    }

    /** Records both directions in one commit and captures their originals at that same commit, for safe retries. */
    fun recordWithUndo(
        context: Context,
        verse: QuranVerseReference,
        kind: HifzKind,
        other: QuranVerseReference? = null,
        slipWords: Set<Int> = emptySet(),
        bothWays: Boolean = false,
        now: Long = System.currentTimeMillis(),
        plan: HifzPlan? = null,
    ): HifzRecordChange = change(context) { points ->
        val original = points.pointOf(verse)
        val saved = recordedPoint(points, verse, kind, other, slipWords, bothWays, now, plan)
        val next = points.filterNot { it.id == saved.id } + saved
        // An already recorded reverse mistake remains its own unchanged point.
        val mirror = other?.takeIf {
            it != verse && bothWays && kind.lookAlike && points.pointOf(it)?.mistakes?.same(kind, verse) == null
        }
        if (mirror == null) {
            next to HifzRecordChange(listOf(original), listOf(saved))
        } else {
            val mirrored = recordedPoint(next, mirror, kind, verse, emptySet(), true, now + 1, plan)
            (next.filterNot { it.id == mirrored.id } + mirrored) to
                HifzRecordChange(listOf(original, points.pointOf(mirror)), listOf(saved, mirrored))
        }
    }

    private fun recordedPoint(
        points: List<HifzPoint>, verse: QuranVerseReference, kind: HifzKind, other: QuranVerseReference?,
        slipWords: Set<Int>, bothWays: Boolean, now: Long, plan: HifzPlan?, last: Int? = null,
    ): HifzPoint {
        val today = epochDay(now)
        val existing = if (last != null) points.passageOf(verse, last) else points.pointOf(verse)
        return existing?.told(kind, other, slipWords, bothWays, now)
            ?.relapsed(today, now, askedToday = existing.checks.any { it.day == today }, plan = plan)
            ?: HifzPoint(
                // Its own id, even beside a point recorded in the same millisecond.
                id = generateSequence(now) { it + 1 }.first { id -> points.none { it.id == id } },
                verse = verse, mistakes = listOf(HifzMistake(kind, other.takeIf { kind.namesOther }, slipWords, bothWays, now)),
                dueDay = today, createdAt = now, pace = plan?.pace ?: HifzPace.Auto, times = plan?.times,
                oncePerDay = plan?.oncePerDay ?: false, recentSlipTimes = listOf(now), last = last,
            ).let { it.copy(fresh = it.recalls) }
    }

    /**
     * Records a forgotten passage, from [start] to [last] of its surah: one point, asked as one. The same passage
     * forgotten again counts again; a verse's own point inside it stays as it is (owner, 12 November: never merged).
     */
    fun recordPassageWithUndo(
        context: Context,
        start: QuranVerseReference,
        last: Int,
        now: Long = System.currentTimeMillis(),
        plan: HifzPlan? = null,
    ): HifzRecordChange = change(context) { points ->
        require(last > start.ayah) { "A passage is two verses or more" }
        val original = points.passageOf(start, last)
        val saved = recordedPoint(points, start, HifzKind.Passage, null, emptySet(), false, now, plan, last)
        (points.filterNot { it.id == saved.id } + saved) to HifzRecordChange(listOf(original), listOf(saved))
    }

    fun update(context: Context, point: HifzPoint) {
        change(context) { points -> points.map { if (it.id == point.id) point else it } to Unit }
    }

    /** A shared review receipt is replayable after a process death, without applying the answer twice. */
    internal fun gradeReview(
        context: Context, expected: HifzPoint, grade: HifzGrade, day: Long, at: Long, counted: Boolean = true,
    ): Boolean = change(context) { points ->
        val current = points.firstOrNull { it.id == expected.id } ?: return@change points to false
        if (current.checks.any { it.at == at && it.grade == grade }) return@change points to true
        // Preserve edits, and reject a deleted/replaced question or a response already written by another caller.
        if (current.verse != expected.verse || current.mistakes != expected.mistakes || current.checks != expected.checks) {
            return@change points to false
        }
        points.map { if (it.id == current.id) current.graded(grade, day, at, counted) else it } to true
    }

    /** An edited mistake and any newly requested reverse mistake are saved together, so retry cannot leave half an edit. */
    fun updateWithMirror(context: Context, changed: HifzPoint, editingIndex: Int) {
        change(context) { points ->
            val old = points.firstOrNull { it.id == changed.id } ?: return@change points to Unit
            val before = old.mistakes.getOrNull(editingIndex)
            val edited = changed.mistakes.firstOrNull { it !in old.mistakes }
            val next = points.map { if (it.id == changed.id) changed else it }
            val mirror = edited?.other?.takeIf {
                edited.bothWays && edited.kind.lookAlike && (before?.bothWays != true || before.other != edited.other) &&
                    next.pointOf(it)?.mistakes?.same(edited.kind, changed.verse) == null
            }
            if (edited == null || mirror == null) {
                next to Unit
            } else {
                val mirrored = recordedPoint(
                    next, mirror, edited.kind, changed.verse, emptySet(), true, System.currentTimeMillis(),
                    HifzPlan(changed.pace, changed.times, changed.daily),
                )
                (next.filterNot { it.id == mirrored.id } + mirrored) to Unit
            }
        }
    }

    /** Puts back a point exactly as it was, e.g. to undo a record, a grade or a delete. */
    fun restore(context: Context, point: HifzPoint?, removeId: Long? = null) {
        change(context) { points ->
            val kept = points.filterNot { it.id == point?.id || it.id == removeId }
            (if (point != null) kept + point else kept) to Unit
        }
    }

    /**
     * Undoes one recording, including its mirrored verse, only while every saved point is still unchanged. The
     * originals correspond to [expected]; null removes a newly created point. Guard and commit are one operation,
     * so an older Undo cannot erase a later answer, edit or deletion, or restore only half of a mirrored recording.
     */
    fun restoreIfUnchanged(context: Context, expected: List<HifzPoint>, originals: List<HifzPoint?>): Boolean = synchronized(lock) {
        require(expected.size == originals.size && expected.map { it.id }.distinct().size == expected.size)
        require(expected.zip(originals).all { (saved, before) -> before == null || (before.id == saved.id && before.verse == saved.verse) })
        load(context)
        if (expected.any { saved -> mutablePoints.value.firstOrNull { it.id == saved.id } != saved }) return@synchronized false
        if (expected.isEmpty()) return@synchronized true
        change(context) { points ->
            val replaced = expected.map { it.id }.toSet()
            (points.filterNot { it.id in replaced } + originals.filterNotNull()) to true
        }
    }

    fun delete(context: Context, id: Long) {
        change(context) { points -> points.filterNot { it.id == id } to Unit }
    }

    private fun <T> change(context: Context, block: (List<HifzPoint>) -> Pair<List<HifzPoint>, T>): T = synchronized(lock) {
        load(context)
        val (next, result) = block(mutablePoints.value)
        val sorted = next.sortedBy { it.verse }
        write(context, sorted)
        mutablePoints.value = sorted
        result
    }

    private fun write(context: Context, points: List<HifzPoint>) {
        val bytes = encodeHifzPoints(points).toByteArray()
        val target = file(context)
        target.parentFile?.mkdirs()
        // Synced to disk and then moved over the notebook, so a crash or a power cut never leaves half a file.
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            // AtomicFile logs some sync/rename failures instead of throwing. Surface them to the caller too.
            stream.fd.sync()
            atomic.finishWrite(stream)
            if (!atomic.readFully().contentEquals(bytes)) throw IOException("The notebook commit could not be verified")
        } catch (error: IOException) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, "hifz/hifz.json")
}

/** The notebook's format: one point per verse, its mistakes inside it, since version 2. */
private const val HifzNotebookVersion = 2
private const val HifzLearningPlanVersion = 2

internal fun encodeHifzPoints(points: List<HifzPoint>): String = JSONObject()
    .put("version", HifzNotebookVersion)
    .put("learningPlan", HifzLearningPlanVersion)
    .put("points", JSONArray(points.map { point ->
        JSONObject()
            .put("id", point.id)
            .put("surah", point.verse.surah)
            .put("ayah", point.verse.ayah)
            .apply { point.last?.let { put("last", it) } }
            .put("mistakes", JSONArray(point.mistakes.map { mistake ->
                JSONObject()
                    .put("kind", mistake.kind.key)
                    .apply {
                        mistake.other?.let { put("otherSurah", it.surah); put("otherAyah", it.ayah) }
                        if (mistake.slipWords.isNotEmpty()) put("words", JSONArray(mistake.slipWords.sorted()))
                        if (mistake.bothWays) put("bothWays", true)
                    }
                    .put("at", mistake.at)
            }))
            .apply {
                point.masteredAt?.let { put("masteredAt", it) }
                if (point.fresh > 0) put("fresh", point.fresh)
                if (point.pace != HifzPace.Auto) put("pace", point.pace.key)
                point.times?.let { put("times", it.encode()) }
                if (point.autoRecalls != HifzFreshRecalls) put("autoRecalls", point.autoRecalls)
                point.oncePerDay?.let { put("oncePerDay", it) }
                if (point.recentSlipTimes.isNotEmpty()) put("recentSlipTimes", JSONArray(point.recentSlipTimes.takeLast(HifzRecentSlipLimit)))
                if (point.rightAnswers > 0) put("rightAnswers", point.rightAnswers)
                if (point.rightDays > 0) put("rightDays", point.rightDays)
            }
            .put("key", point.key)
            .put("slips", point.slips)
            .put("step", point.step)
            .put("dueDay", point.dueDay)
            .put("createdAt", point.createdAt)
            .put("lastSlipAt", point.lastSlipAt)
            .put("checks", encodeHifzChecks(point.checks))
    }))
    .toString()

/**
 * The notebook read back. Another version throws and is reset by [HifzRepository.load]: the app is still in
 * development, and notebooks from before one point per verse are not converted.
 */
internal fun decodeHifzPoints(value: String): List<HifzPoint> {
    val notebook = JSONObject(value)
    require(notebook.optInt("version") == HifzNotebookVersion) { "Notebook version ${notebook.optInt("version")}" }
    val oldPlan = notebook.optInt("learningPlan", 1) < HifzLearningPlanVersion
    val rows = notebook.getJSONArray("points")
    return List(rows.length()) { rows.getJSONObject(it) }.mapNotNull { row ->
        val verse = QuranVerseReference(row.getInt("surah"), row.getInt("ayah"))
        if (verse.surah !in 1..114 || verse.ayah < 1) return@mapNotNull null
        val listed = row.optJSONArray("mistakes") ?: return@mapNotNull null
        val mistakes = List(listed.length()) { listed.getJSONObject(it) }.mapNotNull { told ->
            HifzMistake(
                kind = HifzKind.of(told.optString("kind")) ?: return@mapNotNull null,
                other = if (told.has("otherSurah")) QuranVerseReference(told.getInt("otherSurah"), told.getInt("otherAyah")) else null,
                slipWords = told.optJSONArray("words")?.let { list -> List(list.length()) { list.getInt(it) }.filter { it >= 0 }.toSet() }.orEmpty(),
                bothWays = told.optBoolean("bothWays"),
                at = told.optLong("at"),
            )
        }.ifEmpty { return@mapNotNull null }
        HifzPoint(
            id = row.getLong("id"),
            verse = verse,
            mistakes = mistakes,
            key = row.optString("key"),
            slips = row.optInt("slips", 1).coerceAtLeast(1),
            step = row.optInt("step").coerceAtLeast(0),
            dueDay = row.getLong("dueDay"),
            createdAt = row.getLong("createdAt"),
            lastSlipAt = row.optLong("lastSlipAt", row.getLong("createdAt")),
            masteredAt = if (row.has("masteredAt")) row.getLong("masteredAt") else null,
            fresh = row.optInt("fresh").coerceIn(0, HifzMaxRecalls),
            checks = decodeHifzChecks(row.optString("checks")),
            pace = HifzPace.of(row.optString("pace").ifEmpty { null }),
            times = HifzTimes.decode(row.optString("times").ifEmpty { null }),
            autoRecalls = row.optInt("autoRecalls", if (oldPlan) 3 else HifzFreshRecalls).coerceIn(1, HifzMaxRecalls),
            oncePerDay = if (row.has("oncePerDay")) row.optBoolean("oncePerDay") else null,
            recentSlipTimes = row.optJSONArray("recentSlipTimes")?.let { list ->
                List(list.length()) { list.getLong(it) }.distinct().sorted().takeLast(HifzRecentSlipLimit)
            }.orEmpty(),
            last = row.optInt("last").takeIf { it > verse.ayah },
            rightAnswers = row.optInt("rightAnswers").coerceAtLeast(0),
            rightDays = row.optInt("rightDays").coerceAtLeast(0),
        ).let { if (oldPlan) it.withCurrentLearningPlan() else it }
    }
}

/**
 * Preserve recorded evidence when adopting the stronger plan. Old maintenance repetitions cannot stand in for
 * the five new retention checks. Existing due dates may move earlier, never later merely because of migration.
 */
private fun HifzPoint.withCurrentLearningPlan(): HifzPoint {
    // The first plan asked 3, 4 or 5 answers; today's plan asks as many days (3, 4 or 5), never more.
    val changed = copy(autoRecalls = autoRecalls.coerceIn(HifzFreshRecalls, HifzMaxRecalls))
    if (fresh > 0) {
        val last = checks.lastOrNull()
        return changed.copy(
            fresh = (changed.recalls - rightSinceSlip).coerceAtLeast(1),
            dueDay = if (last != null && rightOn(last.day) >= HifzFreshDailySuccessLimit) maxOf(dueDay, last.day + 1) else dueDay,
            masteredAt = null,
        )
    }
    val retained = step.coerceAtMost(4)
    val next = checks.lastOrNull()?.let { it.day + HifzLadder[retained] } ?: dueDay
    return changed.copy(step = retained, masteredAt = null, dueDay = minOf(dueDay, next))
}

/**
 * The questions a reminder lists. A review asks everything due, by priority in groups of this many, each group in
 * mushaf order; see [reviewSitting].
 */
const val HifzBatchSize = 5

/**
 * The drill after a wrong answer: one pause after each passage, as long as what is to be said in it. The drill, the
 * listening of points and the cue are all at the reciter's pace.
 */
const val HifzDrillGap = 1f
const val HifzDrillSpeed = 1f

/**
 * How «المراجعة» behaves; kept in its own preferences. Only when it asks: the reminder and its times. [markers], the
 * reader's «مواضعي على الصفحات», is set from the reader's menu.
 */
data class HifzSettings(
    /** The reminder that asks about the points due, at [times]. */
    val reminder: Boolean = true,
    /** New notebooks start with one chosen routine. Existing stored schedules survive migration. */
    val times: HifzTimes = HifzTimesPreset.Once.times,
    val markers: Boolean = true,
    val dailyAlertLimit: Int = 3,
    val quietHours: Boolean = false,
    val quietStartMinute: Int = 22 * 60,
    val quietEndMinute: Int = 6 * 60,
    /** Optional stopping point shown by the UI; due work and manual continuation remain available. */
    val dailyGoal: Int = 10,
) {
    companion object {
        private const val PREFS = "quran_hifz"
        /**
         * Settings that are no more: the round's size, the listening aids, the briefing, the drill's pause and speed;
         * and the briefing's daily notes, «briefed_» and a chapter or hizb.
         */
        private val RemovedKeys = listOf("per_day", "heads_up", "stop_before", "briefing", "drill_gap", "drill_speed")
        private fun removed(key: String) = key in RemovedKeys || key.startsWith("briefed_")

        fun read(context: Context): HifzSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stale = prefs.all.keys.filter(::removed)
            val firstStrategyRead = !prefs.contains("strategy_version")
            val notebook = File(context.applicationContext.filesDir, "hifz/hifz.json")
            val legacy = firstStrategyRead && (prefs.all.isNotEmpty() || notebook.exists() || File("${notebook.path}.bak").exists())
            val times = HifzTimes.decode(prefs.getString("times", null))
                ?: if (legacy) HifzTimesPreset.Prayers.times else HifzTimesPreset.Once.times
            if (firstStrategyRead || stale.isNotEmpty()) prefs.edit().apply {
                stale.forEach { remove(it) }
                if (firstStrategyRead) { putInt("strategy_version", 1); putString("times", times.encode()) }
            }.apply()
            val quietStart = prefs.getInt("quiet_start_minute", 22 * 60).coerceIn(0, 1439)
            val quietEnd = prefs.getInt("quiet_end_minute", 6 * 60).coerceIn(0, 1439)
            return HifzSettings(
                reminder = prefs.getBoolean("reminder", true),
                times = times,
                markers = prefs.getBoolean("markers", true),
                dailyAlertLimit = prefs.getInt("daily_alert_limit", 3).coerceIn(1, 10),
                quietHours = prefs.getBoolean("quiet_hours", false) && quietStart != quietEnd,
                quietStartMinute = quietStart,
                quietEndMinute = quietEnd,
                dailyGoal = prefs.getInt("daily_goal", 10).coerceIn(1, 50),
            )
        }

        fun write(context: Context, settings: HifzSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("reminder", settings.reminder)
                .putString("times", settings.times.encode())
                .putBoolean("markers", settings.markers)
                .putInt("strategy_version", 1)
                .putInt("daily_alert_limit", settings.dailyAlertLimit.coerceIn(1, 10))
                .putBoolean("quiet_hours", settings.quietHours && settings.quietStartMinute != settings.quietEndMinute)
                .putInt("quiet_start_minute", settings.quietStartMinute.coerceIn(0, 1439))
                .putInt("quiet_end_minute", settings.quietEndMinute.coerceIn(0, 1439))
                .putInt("daily_goal", settings.dailyGoal.coerceIn(1, 50))
                .apply()
        }
    }
}
