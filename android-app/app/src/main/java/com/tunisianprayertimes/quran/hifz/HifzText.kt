package com.tunisianprayertimes.quran.hifz

import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranMushafText
import com.tunisianprayertimes.quran.QuranVerse
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.audio.QuranPlaylist
import com.tunisianprayertimes.quran.audio.QuranRepeatRange
import com.tunisianprayertimes.quran.normalizeQuranSearch
import com.tunisianprayertimes.quran.quranAyah
import com.tunisianprayertimes.quran.quranVerseSentence

/**
 * A verse's words as printed, in the mushaf's spelling with their marks ([QuranMushafText]), and as compared:
 * without marks, in plain letters, taken from the bundled text itself. A token that is only a mark stays with the
 * word before it. Scan signs are attached at their original token positions before this grouping.
 */
class HifzWords(
    val printed: List<String>,
    val keys: List<String>,
    /** The words as recited, to tell words apart that [keys] make one: see [hifzSameRecited]. */
    val recited: List<HifzRecited> = keys.map { HifzRecited(it, "", false) },
) {
    val size: Int get() = keys.size
    fun text(from: Int = 0, to: Int = size): String = printed.subList(from.coerceIn(0, size), to.coerceIn(0, size)).joinToString(" ")
}

/**
 * A word as it is recited: its letters with their vowels, hamza seat and dagger alef ([body]), and the vowel or tanween of
 * its last letter ([end]). The marks a word takes from the words around it are left out: stop signs, Qaloun's small
 * marks, the sila, open tanween (the same tanween), the iqlab meem (tanween), the shadda of idgham on the first letter,
 * the vowel of a joining alef, a sukun printed or left out, a madd before the next word's hamza, «ىٰ» for «ى».
 */
class HifzRecited(val body: String, val end: String, /** The next word starts with a joining alef. */ val beforeJoin: Boolean) {
    override fun toString(): String = body + end
}

/**
 * Two words are one word when they are recited alike (owner, 15 November: «أَنفُسَهُمْ» and «أَنفُسِهِمْ» are two
 * words). A silent last letter takes a vowel before a joining alef: «عَلَيْهِمُ اُ۬لْقِتَالُ» is «عَلَيْهِمْ».
 */
fun hifzSameRecited(a: HifzRecited, b: HifzRecited): Boolean {
    if (a.body != b.body) return false
    if (a.end == b.end) return true
    fun helping(x: HifzRecited) = x.beforeJoin && x.end.length == 1 && x.end[0] in ShortVowels
    return (a.end.isEmpty() && helping(b)) || (b.end.isEmpty() && helping(a))
}

/** How two verses' recited words meet; see [hifzOverlap]. */
fun hifzRecitedOverlap(a: HifzWords, b: HifzWords): HifzOverlap = hifzOverlap(a.recited, b.recited, ::hifzSameRecited)

/** How many opening words two verses share, as recited. */
fun hifzRecitedPrefix(a: HifzWords, b: HifzWords): Int {
    var n = 0
    while (n < a.size && n < b.size && hifzSameRecited(a.recited[n], b.recited[n])) n++
    return n
}

private const val ShortVowels = "\u064E\u064F\u0650"
private const val Shadda = '\u0651'
private const val Sukun = "\u0652"
private const val Maddah = "\u0653"
/** Stop signs, Qaloun's small marks, the sila, the iqlab meem, the tatweel: marks of the words around a word. */
private val ContextMarks = ('\u06D6'..'\u06DF').toSet() + setOf('\u06E2', '\u06E3', '\u06E5', '\u06E6', '\u06E9', '\u06EA', '\u06EC', '\u06ED', '\u0640')
private val JoiningAlef = Regex("^ا[\u064E-\u0650]?[\u06DF\u06EA\u06EC]")
private fun Char.isMark(): Boolean = Character.getType(this).let {
    it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt()
}

/** [token] as recited, [next] the word after it. */
internal fun hifzRecited(token: String, next: String?): HifzRecited {
    val text = java.text.Normalizer.normalize(token, java.text.Normalizer.Form.NFC)
        // Tanween before «ب» is printed as its vowel with a small meem.
        .replace("\u064E\u06E2", "\u064B").replace("\u064F\u06E2", "\u064C").replace("\u0650\u06E2", "\u064D")
        // Open tanween, printed before some letters, is the same tanween.
        .replace('\u0657', '\u064B').replace('\u065E', '\u064C').replace('\u0656', '\u064D')
        .filterNot { it in ContextMarks }
    val chars = StringBuilder(text)
    // The shadda a first letter takes from the word before it (idgham).
    if (chars.length > 1 && chars[1] == Shadda) chars.deleteCharAt(1)
    else if (chars.length > 2 && chars[1].isMark() && chars[2] == Shadda) chars.deleteCharAt(2)
    // A joining alef takes its vowel from the word before it.
    if (chars.isNotEmpty() && (chars[0] == 'ا' || chars[0] == 'ٱ')) while (chars.length > 1 && chars[1].isMark()) chars.deleteCharAt(1)
    var last = chars.length
    while (last > 0 && chars[last - 1].isMark()) last--
    val body = chars.substring(0, last)
    var end = chars.substring(last).replace(Maddah, "")
    if (end == Sukun) end = ""
    // «إِلَىٰ» and «إِلَى» are one word.
    if (end == "\u0670" && body.endsWith('ى')) end = ""
    else if (end.startsWith("\u0670") && body.endsWith('ى')) end = end.substring(1)
    val joins = next?.let { n -> n.startsWith('ٱ') || JoiningAlef.containsMatchIn(java.text.Normalizer.normalize(n, java.text.Normalizer.Form.NFC)) } ?: false
    return HifzRecited(body, end, joins)
}

/** For a catalog verse, retain the mushaf's signs without changing the comparison keys or selected word indexes. */
fun hifzWords(verse: QuranVerse): HifzWords = hifzWords(verse.text.split(' '), verse.mushafWords)

/** Unreferenced source text, used for comparison-only callers and small fixtures. */
fun hifzWords(text: String): HifzWords {
    val tokens = text.split(' ').filter { it.isNotBlank() }
    return hifzWords(tokens, QuranMushafText.words(tokens))
}

private fun hifzWords(tokens: List<String>, converted: List<String>): HifzWords {
    val printed = mutableListOf<String>()
    val keys = mutableListOf<String>()
    val sources = mutableListOf<String>()
    var pending = ""
    tokens.zip(converted).forEach { (token, shown) ->
        val key = normalizeQuranSearch(token).replace(" ", "")
        when {
            key.isNotEmpty() -> { printed += pending + shown; keys += key; sources += token; pending = "" }
            shown.isEmpty() -> {}
            printed.isNotEmpty() -> printed[printed.lastIndex] = printed.last() + " " + shown
            else -> pending += "$shown "
        }
    }
    return HifzWords(printed, keys, sources.mapIndexed { i, token -> hifzRecited(token, sources.getOrNull(i + 1)) })
}

/** How two verses' words meet: shared opening, shared closing, and the longest shared run. */
data class HifzOverlap(val prefix: Int, val suffix: Int, val run: Int, val runA: Int, val runB: Int)

fun <T> hifzOverlap(a: List<T>, b: List<T>, same: (T, T) -> Boolean = { x, y -> x == y }): HifzOverlap {
    var prefix = 0
    while (prefix < a.size && prefix < b.size && same(a[prefix], b[prefix])) prefix++
    var suffix = 0
    while (suffix < a.size && suffix < b.size && same(a[a.size - 1 - suffix], b[b.size - 1 - suffix])) suffix++
    var run = 0
    var runA = 0
    var runB = 0
    var previous = IntArray(b.size + 1)
    for (i in 1..a.size) {
        val row = IntArray(b.size + 1)
        for (j in 1..b.size) {
            if (same(a[i - 1], b[j - 1])) {
                row[j] = previous[j - 1] + 1
                if (row[j] > run) { run = row[j]; runA = i - row[j]; runB = j - row[j] }
            }
        }
        previous = row
    }
    return HifzOverlap(prefix, suffix, run, runA, runB)
}

/** The words of each verse that the other does not share, from a word-level alignment. */
fun <T> hifzDifferences(a: List<T>, b: List<T>, same: (T, T) -> Boolean = { x, y -> x == y }): Pair<Set<Int>, Set<Int>> {
    val lengths = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
        lengths[i][j] = if (same(a[i], b[j])) lengths[i + 1][j + 1] + 1 else maxOf(lengths[i + 1][j], lengths[i][j + 1])
    }
    val keptA = mutableSetOf<Int>()
    val keptB = mutableSetOf<Int>()
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
        when {
            same(a[i], b[j]) -> { keptA += i; keptB += j; i++; j++ }
            lengths[i + 1][j] >= lengths[i][j + 1] -> i++
            else -> j++
        }
    }
    return (a.indices.toSet() - keptA) to (b.indices.toSet() - keptB)
}

/**
 * How verses open, to show the fewest opening words that tell one from every other. Only first words are
 * read when it is built, which is quick; a verse's other words are read when it is asked about. Use it
 * from one thread.
 */
class HifzOpenings(catalog: QuranCatalog) {
    private val verses: Map<QuranVerseReference, QuranVerse> = catalog.verses.filter { it.ayah > 0 }.associateBy { it.reference }
    private val byFirstWord: Map<String, List<QuranVerseReference>> = verses.entries.groupBy({ firstKey(it.value.text) }, { it.key })
    private val keys = HashMap<QuranVerseReference, List<String>>()

    /** The verse's words as compared: without marks, in plain letters. */
    fun words(verse: QuranVerseReference): List<String> = keys.getOrPut(verse) { verses[verse]?.let { hifzWords(it).keys }.orEmpty() }

    /** The verse's first [count] words as printed, with their marks. */
    fun printed(verse: QuranVerseReference, count: Int): String? = verses[verse]?.let { hifzWords(it).text(0, count) }

    /**
     * The fewest opening words no other verse starts with, at least one: what a prompt shows of [verse].
     * Its whole length when another verse begins with all of its words; 0 for a verse it does not know.
     */
    fun distinct(verse: QuranVerseReference): Int {
        val own = words(verse)
        if (own.isEmpty()) return 0
        var others = byFirstWord[own.first()].orEmpty().filter { it != verse }
        var count = 1
        while (others.isNotEmpty() && count < own.size) {
            others = others.filter { words(it).size > count && words(it)[count] == own[count] }
            count++
        }
        return count
    }

    private companion object {
        /** A verse's first word as compared; a token that is only a mark is not a word. */
        fun firstKey(text: String): String =
            text.split(' ').firstNotNullOfOrNull { token -> normalizeQuranSearch(token).replace(" ", "").takeIf { it.isNotEmpty() } }.orEmpty()
    }
}

/** The look-alike index, built once per process on a background dispatcher. */
object HifzLookAlikesCache {
    @Volatile var loaded: HifzLookAlikes? = null
        private set

    fun get(catalog: QuranCatalog): HifzLookAlikes = loaded ?: synchronized(this) {
        loaded ?: HifzLookAlikes(catalog).also { loaded = it }
    }
}

/** A verse that may be mistaken for another, and why. */
data class HifzLookAlike(val verse: QuranVerseReference, val overlap: HifzOverlap, val score: Int)

/**
 * Look-alikes computed from the app's own Qaloun text. Candidates share at least two words in a
 * row with the verse, found through an index of word pairs, so a query reads only a few verses.
 */
class HifzLookAlikes(private val catalog: QuranCatalog) {
    private val references: List<QuranVerseReference>
    private val words: List<List<String>>
    private val position: Map<QuranVerseReference, Int>
    private val pairs: Map<String, IntArray>

    init {
        val numbered = catalog.verses.filter { it.ayah > 0 }.sortedBy { it.reference }
        references = numbered.map { it.reference }
        words = numbered.map { hifzWords(it).keys }
        position = references.withIndex().associate { it.value to it.index }
        val index = HashMap<String, MutableList<Int>>()
        words.forEachIndexed { verse, keys ->
            keys.zipWithNext { x, y -> "$x $y" }.distinct().forEach { index.getOrPut(it) { mutableListOf() } += verse }
        }
        pairs = index.mapValues { it.value.toIntArray() }
    }

    fun words(verse: QuranVerseReference): List<String> = position[verse]?.let(words::get).orEmpty()

    /** Verses the recitation could drift into: a long shared opening first, then a long shared run. */
    fun similar(verse: QuranVerseReference, limit: Int = 3): List<HifzLookAlike> = rank(verse, limit) { a, _, overlap ->
        val enough = overlap.prefix >= 2 || overlap.run >= minOf(4, a.size - 1).coerceAtLeast(2)
        // A long shared run decides; a shared opening of 2 words or more counts twice, since recitation
        // drifts from where the verses start alike. A one-word opening («ومن», «إن») counts for nothing.
        if (!enough) null else (if (overlap.prefix >= 2) overlap.prefix else 0) + overlap.run
    }

    /** Verses whose ending the recitation could take: a shared run right before differing last words. */
    fun endings(verse: QuranVerseReference, limit: Int = 3): List<HifzLookAlike> = rank(verse, limit) { a, b, overlap ->
        val endA = overlap.runA + overlap.run
        val endB = overlap.runB + overlap.run
        val beforeEnding = overlap.run >= 2 && endA in (a.size - 3).coerceAtLeast(1) until a.size && endB < b.size &&
            a[endA] != b[endB]
        if (!beforeEnding) null else overlap.run * 10 + (if (endB >= b.size - 3) 5 else 0)
    }

    private fun rank(
        verse: QuranVerseReference,
        limit: Int,
        score: (a: List<String>, b: List<String>, overlap: HifzOverlap) -> Int?,
    ): List<HifzLookAlike> {
        val self = position[verse] ?: return emptyList()
        val a = words[self]
        val candidates = a.zipWithNext { x, y -> "$x $y" }.distinct()
            .flatMap { pairs[it]?.asList().orEmpty() }.toSet() - self
        return candidates.mapNotNull { index ->
            val b = words[index]
            val overlap = hifzOverlap(a, b)
            val value = score(a, b, overlap) ?: return@mapNotNull null
            val other = references[index]
            // Within the same chapter, nearer verses are likelier.
            val nearness = if (other.surah == verse.surah) 3 else 0
            HifzLookAlike(other, overlap, value * 10 + nearness)
        }.sortedWith(compareByDescending<HifzLookAlike> { it.score }.thenBy { kotlin.math.abs(index(it.verse) - self) })
            .take(limit)
    }

    private fun index(verse: QuranVerseReference): Int = position[verse] ?: 0
}

/**
 * The closings a verse may be ended with by mistake, as the reader remembers them: the last two words of the verses
 * around it (its page and the pages either side, then its chapter, the nearest first), each closing once, never its
 * own. Each comes with the nearest verse that ends so.
 */
fun hifzClosings(catalog: QuranCatalog, verse: QuranVerseReference, limit: Int = 8): List<Pair<String, QuranVerseReference>> {
    val own = catalog.verse(verse)?.let { hifzWords(it) } ?: return emptyList()
    val ownKey = own.keys.takeLast(2)
    val pages = catalog.pagesForVerse(verse.surah, verse.ayah)
    val nearby = pages.flatMap { page -> (page - 1..page + 1).flatMap { catalog.versesOnPage(it) } }
    val chapter = catalog.verses.filter { it.surah == verse.surah }
    val around = (nearby + chapter).filter { it.ayah > 0 && it.reference != verse }.distinctBy { it.reference }
        .sortedBy { if (it.surah == verse.surah) kotlin.math.abs(it.ayah - verse.ayah) else 10_000 }
    val seen = mutableSetOf<List<String>>()
    val closings = mutableListOf<Pair<String, QuranVerseReference>>()
    for (candidate in around) {
        val words = hifzWords(candidate)
        val key = words.keys.takeLast(2)
        if (words.size < 2 || key == ownKey || !seen.add(key)) continue
        closings += words.text(words.size - 2) to candidate.reference
        if (closings.size == limit) break
    }
    return closings
}

/** «كلمة واحدة», «كلمتين», «7 كلمات», «17 كلمة». */
fun hifzWordsLabel(count: Int): String = when (count) {
    1 -> "كلمة واحدة"
    2 -> "كلمتين"
    in 3..10 -> "$count${HifzReminder.NB}كلمات"
    else -> "$count${HifzReminder.NB}كلمة"
}

/** «تتفقان في كلمتين متتاليتين», «تتفقان في 4 كلمات متتالية». */
fun hifzRunLabel(count: Int): String = when (count) {
    1 -> "تتفقان في كلمة واحدة"
    2 -> "تتفقان في كلمتين متتاليتين"
    in 3..10 -> "تتفقان في $count${HifzReminder.NB}كلمات متتالية"
    else -> "تتفقان في $count${HifzReminder.NB}كلمة متتالية"
}

/** «آية واحدة», «آيتان», «5 آيات», «68 آية». */
fun hifzVersesLabel(count: Int): String = when {
    count == 1 -> "آية واحدة"
    count == 2 -> "آيتان"
    count % 100 in 3..10 -> "$count${HifzReminder.NB}آيات"
    else -> "$count${HifzReminder.NB}آية"
}

/** After a preposition: «بعد آيتين», «بعد 5 آيات». */
fun hifzVersesAfterLabel(count: Int): String = if (count == 2) "آيتين" else hifzVersesLabel(count)

/** «آية أخرى», «آيتان أخريان», «3 آيات أخرى», «12 آية أخرى». */
private fun hifzOtherVersesLabel(count: Int): String = when {
    count == 1 -> "آية أخرى"
    count == 2 -> "آيتان أخريان"
    count % 100 in 3..10 -> "$count${HifzReminder.NB}آيات أخرى"
    else -> "$count${HifzReminder.NB}آية أخرى"
}

/** Everything a fix card and a hint need for one of a point's mistakes, from the index text. */
class HifzFix(
    val point: HifzPoint,
    val mistake: HifzMistake,
    val words: HifzWords,
    val otherWords: HifzWords?,
    /** Words of the verse the other does not share. */
    val differs: Set<Int>,
    val otherDiffers: Set<Int>,
    /** The first word of the verse after the part they share: «هنا يفترقان». */
    val partAt: Int?,
    /** For a skip or a hesitation at the start: the verses around it. */
    val before: HifzWords?,
    val after: HifzWords?,
    /** «لماذا يلتبس؟», facts a script can prove. */
    val reasons: List<String>,
    /** Where an ending is said from: the few words it shares with its look-alike just before they part. */
    private val endingFrom: Int? = null,
    /** The first word of [otherWords] after the part the verses share, where the look-alike goes its own way. */
    val otherPartAt: Int? = null,
) {
    /**
     * Where an ending is said from: the words shared with its look-alike before they part, or else the clause it
     * closes, from «وَاللَّهُ», «فَإِنَّ», «إِنَّ», never from the middle of it («كُلِّ شَيْءٍ قَدِيرٌ»).
     */
    private val endingStart: Int
        get() = endingFrom ?: hifzClauseStart(words, ((partAt ?: (words.size - 1)) - 2).coerceAtLeast(0))

    /** The words that tell the verse from its look-alike: from where they part, never the whole verse. */
    val focus: Set<Int> get() = partAt?.let { at -> differs.filter { it >= at }.toSet() }?.ifEmpty { null } ?: differs

    /** The look-alike's own words from where it parts. */
    val otherFocus: Set<Int> get() = otherPartAt?.let { at -> otherDiffers.filter { it >= at }.toSet() }?.ifEmpty { null } ?: otherDiffers

    /** The wording to keep in mind, never the wrong one. */
    val wording: String
        get() = when (mistake.kind) {
            // As the ending is said: «فَهُمْ لَا يَرْجِعُونَ», «وَاللَّهُ عَلَىٰ كُلِّ شَيْءٍ قَدِيرٌ».
            HifzKind.Ending -> words.text(endingStart)
            HifzKind.Similar -> partAt?.let { words.text(it, it + 5) + if (it + 5 < words.size) " …" else "" } ?: words.text(0, 5)
            HifzKind.Skip, HifzKind.Start, HifzKind.Passage -> {
                val shown = (distinctAt + 1).coerceAtLeast(4)
                words.text(0, shown) + if (shown < words.size) " …" else ""
            }
            // Each wrong word with its neighbours; words far apart are joined by «…».
            HifzKind.Word -> wordingWords.withIndex().joinToString(" ") { (n, i) ->
                if (n > 0 && i > wordingWords[n - 1] + 1) "… ${words.printed[i]}" else words.printed[i]
            }
        }

    /**
     * The words [wording] shows, as indexes of [words], in order; a gap between two of them is the «…» of a
     * word slip.
     */
    val wordingWords: List<Int>
        get() = when (mistake.kind) {
            HifzKind.Ending -> (endingStart until words.size).toList()
            HifzKind.Similar -> (partAt?.let { it until minOf(it + 5, words.size) } ?: (0 until minOf(5, words.size))).toList()
            HifzKind.Skip, HifzKind.Start, HifzKind.Passage -> (0 until minOf((distinctAt + 1).coerceAtLeast(4), words.size)).toList()
            HifzKind.Word -> markedWords.flatMap { (it - 1).coerceAtLeast(0) until minOf(it + 2, words.size) }.distinct().sorted()
                .ifEmpty { (0 until minOf(4, words.size)).toList() }
        }

    /** A word slip's words that are in the verse. */
    private val markedWords: List<Int> get() = mistake.slipWords.filter { it < words.size }.sorted()

    /** The words of [wording] to mind: the right words, or where the verse parts from its look-alike. */
    val stressed: Set<Int>
        get() = when (mistake.kind) {
            HifzKind.Word -> markedWords.toSet()
            HifzKind.Ending, HifzKind.Similar -> focus
            HifzKind.Skip, HifzKind.Start, HifzKind.Passage -> emptySet()
        }

    /** «خاتمتها», «بعد «فَقَدِ»», «أولها». */
    val wordingLabel: String
        get() = when (mistake.kind) {
            HifzKind.Ending -> "خاتمتها"
            HifzKind.Similar -> partAt?.takeIf { it > 0 }?.let { "بعد «${words.printed[it - 1]}»" } ?: "أولها"
            HifzKind.Skip -> if (point.verse.ayah == 1) "أول السورة" else "لا تتجاوزها"
            HifzKind.Start -> "أولها"
            // The passage's first verse: how it opens, from the end of the one before.
            HifzKind.Passage -> if (point.verse.ayah == 1) "أول السورة" else "أولها"
            HifzKind.Word -> "انتبه إلى"
        }

    /** The first word of the verse that its neighbours do not open with. */
    val distinctAt: Int
        get() {
            val shared = listOfNotNull(before, after).maxOfOrNull { hifzRecitedPrefix(words, it) } ?: 0
            return shared.coerceAtMost(words.size - 1)
        }
}

/**
 * «ماذا بعد الآية الأولى؟», «ما أول آية في سورة الملك؟»: the one question a point is asked, wherever it is asked (the
 * review, its reminder): the verse recited on from the one before, whole, as it is recited in prayer. Nothing about what
 * slipped, which would say where to take care: no one warns a reader in prayer.
 */
fun hifzQuestion(catalog: QuranCatalog, point: HifzPoint): String =
    if (point.verse.ayah > 1) "ماذا بعد ${quranAyah(point.verse.ayah - 1)}؟"
    else "ما أول آية في سورة ${catalog.surahs.getOrNull(point.verse.surah - 1)?.name.orEmpty()}؟"

/**
 * «ماذا بعد الآية الأولى من سورة الملك؟»: the same question, its surah named, where nothing around it says which: a line
 * of the reminder, which is the question and nothing else.
 */
fun hifzQuestionAbout(catalog: QuranCatalog, point: HifzPoint): String {
    val surah = catalog.surahs.getOrNull(point.verse.surah - 1)?.name.orEmpty()
    return if (point.verse.ayah > 1) "ماذا بعد ${quranVerseSentence(surah, point.verse.ayah - 1)}؟" else "ما أول آية في سورة $surah؟"
}

/**
 * «اقرأ 3 آيات من حفظك», «اقرأ آيتين من حفظك»: what a forgotten passage's question adds, how much to recite and never
 * its words; null for one verse, whose end the reader knows.
 */
fun hifzPassageLength(point: HifzPoint): String? =
    if (!point.passage) null else "اقرأ ${hifzVersesAfterLabel(point.span.size)} من حفظك"

/**
 * The start of the clause a verse's last words close, looking back at most 3 words from [from]: a word that opens with
 * «و» or «ف», or «إن» and «إنه». [from] itself when none is that near.
 */
internal fun hifzClauseStart(words: HifzWords, from: Int): Int {
    val openers = setOf("ان", "انه", "انهم", "انا")
    for (i in (from - 1) downTo (from - 3).coerceAtLeast(0)) {
        val key = words.keys[i]
        if (key in openers || (key.length > 2 && (key.startsWith('و') || key.startsWith('ف')))) return i
    }
    return from
}

/** «لَسَٰحِرٌ عَلِيمٞ»: up to [count] words from [from], quoted; null past the verse's end. */
private fun HifzWords.quoteFrom(from: Int, count: Int = 3): String? = if (from >= size) null else "«${text(from, from + count)}»"

/** «، ثم تختلفان: هنا «…»، وهناك «…».»: where each of two verses goes on after the words they share. */
private fun hifzParting(words: HifzWords, at: Int, other: HifzWords, otherAt: Int): String {
    val here = words.quoteFrom(at)?.let { "هنا $it" } ?: "هنا تنتهي الآية"
    val there = other.quoteFrom(otherAt)?.let { "وهناك $it" } ?: "وهناك تنتهي الآية"
    return "، ثم تختلفان: $here، $there."
}

/**
 * «تبدأ هذه الآية والآية 35 من سورة الأعراف بـ«فَمَنْ أَظْلَمُ مِمَّن»، ثم تختلفان: هنا «…»، وهناك «…».»: two verses
 * that open with the same [shared] words, the words quoted when they are few.
 */
private fun hifzSharedOpening(words: HifzWords, other: HifzWords, shared: Int, otherName: String): String =
    (if (shared <= 4) "تبدأ هذه الآية و$otherName بـ«${words.text(0, shared)}»"
    else "تتفق هذه الآية و$otherName في أول ${hifzWordsLabel(shared)}") + hifzParting(words, shared, other, shared)

/**
 * «تتفق هذه الآية والآية الثانية من سورة يونس في «إِنَّ هَٰذَا»، ثم تختلفان: …»: the longest run of words two verses
 * share; with where they part when that is where the reader went wrong.
 */
private fun hifzSharedRun(words: HifzWords, other: HifzWords, overlap: HifzOverlap, otherName: String, parting: Boolean): String {
    val start = if (overlap.run <= 4) "تتفق هذه الآية و$otherName في «${words.text(overlap.runA, overlap.runA + overlap.run)}»"
        else "تتفق هذه الآية و$otherName في ${hifzRunLabel(overlap.run).removePrefix("تتفقان في ")}"
    return if (parting) start + hifzParting(words, overlap.runA + overlap.run, other, overlap.runB + overlap.run) else "$start."
}

/** A first word's «وَ» or «فَ» (the conjunction, with its fatha), and the rest of the word as recited. */
private fun HifzRecited.conjunction(): Pair<Char?, HifzRecited> =
    if (body.length > 2 && body[0] in "وف" && body[1] == '\u064E') body[0] to HifzRecited(body.substring(2), end, beforeJoin)
    else null to this

/**
 * Why an opening slips, from the text: the verses that open with the same words as [verse], named when they are 3 or
 * fewer, with what follows in each; then those that open alike but for «و» or «ف» («فَمَنْ أَظْلَمُ», «وَمَنْ أَظْلَمُ»).
 */
internal fun hifzOpeningReasons(
    catalog: QuranCatalog,
    verse: QuranVerseReference,
    words: HifzWords,
    index: HifzLookAlikes,
    name: (QuranVerseReference) -> String,
): List<String> {
    if (words.size < 2) return emptyList()
    val (ownConjunction, ownFirst) = words.recited[0].conjunction()
    // The first word's letters without a leading «و» or «ف»: what a candidate's first word must end with.
    val ownStem = words.keys[0].removePrefix("و").removePrefix("ف").ifEmpty { words.keys[0] }
    val alike = mutableListOf<Pair<QuranVerseReference, HifzWords>>()
    val twins = mutableListOf<Pair<Char?, Pair<QuranVerseReference, HifzWords>>>()
    for (candidate in catalog.verses) {
        if (candidate.ayah <= 0 || candidate.reference == verse) continue
        // The letters first, which the index holds: only a few verses are read whole.
        val keys = index.words(candidate.reference)
        if (keys.size < 2 || keys[1] != words.keys[1] || !keys[0].endsWith(ownStem) || keys[0].length > ownStem.length + 1) continue
        val other = hifzWords(candidate)
        if (hifzRecitedPrefix(words, other) >= 2) { alike += candidate.reference to other; continue }
        val (conjunction, first) = other.recited[0].conjunction()
        if (conjunction != ownConjunction && hifzSameRecited(first, ownFirst) && hifzSameRecited(other.recited[1], words.recited[1])) {
            twins += conjunction to (candidate.reference to other)
        }
    }
    val reasons = mutableListOf<String>()
    if (alike.isNotEmpty()) {
        val shares = alike.associate { (ref, other) -> ref to hifzRecitedPrefix(words, other) }
        val longest = shares.values.max()
        val closest = alike.filter { shares[it.first] == longest }
        val count = hifzOtherVersesLabel(closest.size)
        val names = closest.map { name(it.first) }.let { if (it.size <= 2) it.joinToString(" و") else it.dropLast(1).joinToString("، ") + "، و" + it.last() }
        // The same verse word for word elsewhere («فَبِأَيِّ ءَالَآءِ رَبِّكُمَا تُكَذِّبَانِ»): nothing follows to compare.
        val same = longest == words.size && closest.all { it.second.size == longest }
        reasons += when {
            same && closest.size > 3 -> "$count في المصحف مثل هذه الآية تمامًا."
            same -> "$count في المصحف مثل هذه الآية تمامًا: $names."
            closest.size > 3 && longest <= 5 -> "تبدأ بـ«${words.text(0, longest)}» $count في المصحف."
            closest.size > 3 -> "تتفق $count في المصحف مع هذه الآية في أول ${hifzWordsLabel(longest)}."
            longest <= 5 -> "تبدأ بـ«${words.text(0, longest)}» $count: $names."
            else -> "تتفق $count مع هذه الآية في أول ${hifzWordsLabel(longest)}: $names."
        }
        // What follows the shared words, here and in each look-alike: where the recitation goes wrong.
        if (closest.size <= 3 && !same) {
            val here = words.quoteFrom(longest)?.let { "بعدها هنا $it" } ?: "هنا تنتهي الآية"
            fun next(other: HifzWords) = other.recited.drop(longest).take(3)
            val first = next(closest[0].second)
            val one = first.isNotEmpty() && closest.all { (_, o) -> next(o).size == first.size && next(o).zip(first).all { (x, y) -> hifzSameRecited(x, y) } }
            val there = if (one) {
                "وفي ${when (closest.size) { 1 -> "الأخرى"; 2 -> "الأخريين"; else -> "الآيات الأخرى" }} ${closest[0].second.quoteFrom(longest)}"
            } else closest.joinToString("، ") { (ref, other) ->
                other.quoteFrom(longest)?.let { "وفي ${name(ref)} $it" } ?: "وتنتهي عندها ${name(ref)}"
            }
            reasons += "$here، $there."
        }
    }
    // «فَمَنْ أَظْلَمُ» against «وَمَنْ أَظْلَمُ»: which conjunction, or none.
    twins.groupBy({ it.first }, { it.second }).entries.sortedByDescending { it.value.size }.take(2).forEach { (conjunction, group) ->
        val how = when {
            conjunction == 'و' && ownConjunction == 'ف' -> "بالواو لا بالفاء"
            conjunction == 'ف' && ownConjunction == 'و' -> "بالفاء لا بالواو"
            conjunction == 'و' -> "بزيادة الواو"
            conjunction == 'ف' -> "بزيادة الفاء"
            ownConjunction == 'و' -> "بلا واو"
            else -> "بلا فاء"
        }
        // Quoted from the nearest of them, as printed.
        val sample = group.minBy { (ref, _) -> if (ref.surah == verse.surah) kotlin.math.abs(ref.ayah - verse.ayah) else 10_000 + ref.surah }.second
        reasons += "${if (reasons.isEmpty()) "تبدأ" else "وتبدأ"} ${hifzOtherVersesLabel(group.size)} بـ«${sample.text(0, 2)}»، $how."
    }
    return reasons
}

/** Builds the fix of one of [point]'s mistakes from the index; null when its verse is not in it. */
fun hifzFix(catalog: QuranCatalog, point: HifzPoint, mistake: HifzMistake, lookAlikes: HifzLookAlikes? = null): HifzFix? {
    val verse = catalog.verse(point.verse) ?: return null
    val words = hifzWords(verse)
    val surahName = { n: Int -> catalog.surahs.getOrNull(n - 1)?.name.orEmpty() }
    /** Another verse in a sentence: «الآية الثالثة» in this surah, «الآية 35 من سورة الأعراف» in another. */
    val name = { r: QuranVerseReference -> if (r.surah == point.verse.surah) quranAyah(r.ayah) else quranVerseSentence(surahName(r.surah), r.ayah) }
    val reasons = mutableListOf<String>()
    fun near(n: Int) = catalog.verse(point.verse.surah, n)?.takeIf { n >= 1 }?.let { hifzWords(it) }
    return when (mistake.kind) {
        HifzKind.Ending, HifzKind.Similar -> {
            val other = mistake.other?.let { catalog.verse(it) }?.let { hifzWords(it) }
            if (other == null) {
                return HifzFix(point, mistake, words, null, emptySet(), emptySet(), mistake.word, null, null, emptyList())
            }
            // Words as recited: «لَسَٰحِرٌ» and «لَسِحْرٞ» are two words, though their letters are the same.
            val overlap = hifzRecitedOverlap(words, other)
            val (differs, otherDiffers) = hifzDifferences(words.recited, other.recited, ::hifzSameRecited)
            val runEnd = overlap.runA + overlap.run
            val partAt = when {
                mistake.word != null -> mistake.word
                mistake.kind == HifzKind.Similar && overlap.prefix in 2 until words.size -> overlap.prefix
                // No shared opening: the verses part after the words they share in the middle.
                mistake.kind == HifzKind.Similar && overlap.run > 0 && runEnd < words.size -> runEnd
                // Where the shared run before the ending stops, as the record sheet suggests it.
                mistake.kind == HifzKind.Ending && runEnd < words.size && overlap.run > 0 && runEnd >= words.size - 3 -> runEnd
                // The verses end alike: they part on the differing words just before that shared ending.
                mistake.kind == HifzKind.Ending && overlap.suffix in 1 until words.size -> {
                    val last = words.size - overlap.suffix - 1
                    (last downTo 0).takeWhile { it in differs }.lastOrNull() ?: last
                }
                mistake.kind == HifzKind.Ending -> (words.size - 2).coerceAtLeast(1)
                else -> differs.minOrNull()
            }
            // Each line names the look-alike and the words, and is said only where it is true of the text.
            val otherRef = mistake.other!!
            val otherName = name(otherRef)
            when {
                overlap.prefix == words.size && words.size == other.size ->
                    reasons += "تطابق $otherName هذه الآية، والفرق فيما يأتي بعد كلٍّ منهما."
                overlap.prefix >= 2 && partAt == overlap.prefix -> reasons += hifzSharedOpening(words, other, overlap.prefix, otherName)
                overlap.run > 0 && partAt == runEnd && (overlap.run > 1 || mistake.kind == HifzKind.Ending) ->
                    reasons += hifzSharedRun(words, other, overlap, otherName, parting = true)
                overlap.run > 1 -> reasons += hifzSharedRun(words, other, overlap, otherName, parting = false)
            }
            // Nearness is a cause: the recitation drifts more easily to a verse in the same surah.
            if (otherRef.surah == point.verse.surah) {
                val between = kotlin.math.abs(otherRef.ayah - point.verse.ayah) - 1
                reasons += if (between == 0) "الآيتان متتاليتان في السورة نفسها." else "الآيتان في السورة نفسها، بينهما ${hifzVersesLabel(between)}."
            }
            // Where the look-alike parts, matched to the verse's own parting.
            val otherPartAt = when {
                partAt == null -> null
                overlap.run > 0 && partAt == runEnd -> overlap.runB + overlap.run
                partAt == overlap.prefix -> overlap.prefix
                mistake.kind == HifzKind.Ending -> (other.size - (words.size - partAt)).coerceIn(0, other.size)
                else -> null
            }
            // A short shared lead-in is how the ending is said: «وَاللَّهُ غَنِيٌّ حَلِيمٌ».
            val endingFrom = overlap.runA.takeIf { mistake.kind == HifzKind.Ending && overlap.run in 1..4 && partAt == runEnd }
            HifzFix(point, mistake, words, other, differs, otherDiffers, partAt, null, null, reasons, endingFrom, otherPartAt)
        }
        HifzKind.Skip, HifzKind.Passage -> {
            val v = point.verse.ayah
            val before = near(v - 1)
            // Only facts of the verse and of its link with the one before it, which is what the fix mends: never the verse
            // after it, whose opening may be another point's answer, and wherever the recitation went. That verse is
            // kept only to size the verse's own opening ([HifzFix.distinctAt]).
            val after = near(v + 1)
            before?.let { b ->
                val p = hifzRecitedPrefix(words, b)
                if (p >= 2) reasons += hifzSharedOpening(words, b, p, "الآية التي قبلها")
            }
            // Where it lies on its page. The page is the printed one (the image + 1). In the printed mushaf, odd pages are
            // on the right (owner, 15 November): the sheet is turned only before an odd page.
            val pages = catalog.pagesForVerse(point.verse.surah, v)
            fun versesOn(page: Int) = catalog.versesOnPage(page).filter { it.ayah > 0 }
            when {
                v > 1 && pages.isNotEmpty() && versesOn(pages.first()).firstOrNull()?.reference == point.verse -> {
                    val page = pages.first() + 1
                    reasons += "هذه الآية في أول الصفحة $page، والآية التي قبلها في آخر الصفحة ${page - 1}" +
                        if (page % 2 == 1) "، وبينهما تقلب الورقة في المصحف المطبوع." else "."
                }
                pages.isNotEmpty() && versesOn(pages.last()).lastOrNull()?.reference == point.verse -> {
                    val page = pages.last() + 1
                    reasons += "هذه الآية آخر آية في الصفحة $page" + if (page % 2 == 0) "، وبعدها تقلب الورقة في المصحف المطبوع." else "."
                }
            }
            HifzFix(point, mistake, words, null, emptySet(), emptySet(), null, before, after, reasons.distinct())
        }
        HifzKind.Start -> {
            lookAlikes?.let { reasons += hifzOpeningReasons(catalog, point.verse, words, it, name) }
            HifzFix(point, mistake, words, null, emptySet(), emptySet(), null, near(point.verse.ayah - 1), near(point.verse.ayah + 1), reasons)
        }
        HifzKind.Word -> {
            // A forgotten opening is told as a slip on the first word: the same facts as «أول الآية».
            if (mistake.atStart) lookAlikes?.let { reasons += hifzOpeningReasons(catalog, point.verse, words, it, name) }
            HifzFix(point, mistake, words, null, emptySet(), emptySet(), mistake.word, null, null, reasons)
        }
    }
}

/**
 * The fix of each of [point]'s mistakes, in the order they were first told; empty when its verse is not in the
 * index.
 */
fun hifzFixes(catalog: QuranCatalog, point: HifzPoint, lookAlikes: HifzLookAlikes? = null): List<HifzFix> =
    point.mistakes.mapNotNull { hifzFix(catalog, point, it, lookAlikes) }

/** «البقرة · الآية 17», «البقرة · الآيات 67 ← 68 ← 69»: a label, as the rest of the Quran tab names verses. */
fun hifzRangeLabel(surahName: String, range: QuranRepeatRange): String = when {
    range.from == range.to -> "$surahName · الآية ${range.from.ayah}"
    // A forgotten passage may be long: its ends only.
    range.from.surah == range.to.surah && range.to.ayah - range.from.ayah > 2 ->
        "$surahName · الآيات من ${range.from.ayah} إلى ${range.to.ayah}"
    else -> "$surahName · الآيات " + (range.from.ayah..range.to.ayah).joinToString(" ← ")
}

/**
 * The passage a point is drilled and heard on: the verse; with the verse before it when its opening slipped or it was
 * skipped, wherever the recitation went. What went wrong is the end of the verse before: recited with it, the mind
 * learns that the right verse comes as soon as that one ends (owner, 22 October).
 */
fun hifzPassage(point: HifzPoint): QuranRepeatRange {
    val v = point.verse
    val from = if (point.linked) (v.ayah - 1).coerceAtLeast(1) else v.ayah
    // A forgotten passage is recited whole, from the end of the verse before it to its last (owner, 12 November).
    return QuranRepeatRange(QuranVerseReference(v.surah, from), point.end, 1)
}

/** The title of a drill's playlist, in the dock and the notification. */
const val HifzDrillTitle = "تدريب"
/** The title of one point's right wording heard once. */
const val HifzListenOneTitle = "الاستماع إلى الموضع"
/** The title of «الاستماع إلى المواضع», several points heard in turn. */
const val HifzListenTitle = "الاستماع إلى المواضع"
/** The title of the verse before a point, heard once as a cue. */
const val HifzCueTitle = "ما قبل الموضع"

/**
 * «درّب»: the right verse and its look-alikes in turn, the right one first and last, never the same verse twice in a
 * row (5 recitations for one look-alike, heard twice; one more pair for each further one); or the right verse or the
 * passage around a skip, 3 times. Each recitation is followed by the drill's silence: after the right passage, to say it
 * again, joined; after a look-alike, to say the right one from memory, as long as it.
 */
fun hifzDrill(catalog: QuranCatalog, point: HifzPoint): QuranPlaylist {
    val name = { n: Int -> catalog.surahs.getOrNull(n - 1)?.name.orEmpty() }
    val own = hifzPassage(point)
    val others = point.lookAlikes
    val ownLabel = hifzRangeLabel(name(own.from.surah), own)
    val (items, labels) = if (others.isNotEmpty()) {
        val right = "الصواب · $ownLabel"
        val turns = (if (others.size == 1) others + others else others).map { other ->
            QuranRepeatRange(other, other, 1) to "الشبيهة · ${hifzRangeLabel(name(other.surah), QuranRepeatRange(other, other, 1))}"
        }
        (listOf(own) + turns.flatMap { listOf(it.first, own) }) to (listOf(right) + turns.flatMap { listOf(it.second, right) })
    } else List(3) { own } to List(3) { ownLabel }
    return QuranPlaylist(
        title = HifzDrillTitle,
        items = items,
        labels = labels,
        rounds = 1,
        gapFactor = HifzDrillGap,
        speed = HifzDrillSpeed.takeIf { it != 1f },
        answer = own.takeIf { others.isNotEmpty() },
    )
}

/**
 * «مواضعي صوتا»: every fix in turn, the right wording only, in the mushaf's order; one point's is «الاستماع إلى الموضع».
 * As [settings] say: each passage heard several times in a row, the list again after its last point, a silence after
 * each recitation as long as it, to say it again, as the drill's; once, straight through, by default.
 */
internal fun hifzListen(catalog: QuranCatalog, points: List<HifzPoint>, settings: HifzListenSettings = HifzListenSettings.Once): QuranPlaylist? {
    if (points.isEmpty()) return null
    val name = { n: Int -> catalog.surahs.getOrNull(n - 1)?.name.orEmpty() }
    val items = points.sortedBy { it.verse }.map { hifzPassage(it) }.distinct()
    return QuranPlaylist(
        title = if (points.size == 1) HifzListenOneTitle else HifzListenTitle,
        items = items,
        labels = items.map { hifzRangeLabel(name(it.from.surah), it) },
        rounds = if (settings.endless) 0 else 1,
        gapFactor = if (settings.pause) HifzDrillGap else 0f,
        speed = HifzDrillSpeed.takeIf { it != 1f },
        itemTimes = settings.times.coerceIn(1, QuranPlaylist.MAX_ITEM_TIMES),
    )
}

/**
 * The verse before [point], heard once as a cue before reciting the point from memory: whatever the listener's
 * own repetition and silence settings, it never repeats. Null for a first verse.
 */
fun hifzCue(catalog: QuranCatalog, point: HifzPoint): QuranPlaylist? {
    if (point.verse.ayah <= 1) return null
    val before = QuranVerseReference(point.verse.surah, point.verse.ayah - 1)
    val range = QuranRepeatRange(before, before, 1)
    return QuranPlaylist(
        title = HifzCueTitle,
        items = listOf(range),
        labels = listOf(hifzRangeLabel(catalog.surahs.getOrNull(before.surah - 1)?.name.orEmpty(), range)),
        rounds = 1,
        gapFactor = 0f,
        speed = HifzDrillSpeed.takeIf { it != 1f },
    )
}
