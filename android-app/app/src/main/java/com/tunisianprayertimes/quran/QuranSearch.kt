package com.tunisianprayertimes.quran

/** Why a result is listed; results come ordered from the most to the least exact. */
enum class QuranSearchKind {
    /** The query named a verse, such as «2:255» or «البقرة 255». */
    Reference,
    /** The words as typed, side by side. */
    Phrase,
    /** Every word is in the verse, though not side by side. */
    Words,
    /** Close to what was typed: a slip of the keyboard or an unusual spelling. */
    Similar,
}

/** [verses] are the ones the query touches on [page], in mushaf order. */
data class QuranSearchResult(
    val surahName: String,
    val text: String,
    val page: Int,
    val verses: List<QuranVerseReference>,
    val kind: QuranSearchKind = QuranSearchKind.Phrase,
    /** Source-token-aligned words converted with each verse's own scan signs before fragments are joined. */
    val mushafWords: List<String> = QuranMushafText.words(text.split(' ')),
    private val alignedSpelling: String? = null,
) {
    val hit: QuranSearchHit get() = QuranSearchHit(page, verses)

    fun matchRanges(query: String): List<IntRange> = if (kind == QuranSearchKind.Reference) emptyList()
        else quranMatchRanges(text, query, kind == QuranSearchKind.Similar, alignedSpelling)
}

/** What a tapped search result leaves on the page it opens: the verses to colour there. */
data class QuranSearchHit(val page: Int, val verses: List<QuranVerseReference>) {
    fun encode(): String = (listOf(page) + verses.flatMap { listOf(it.surah, it.ayah) }).joinToString(":")

    companion object {
        /** Null for anything [encode] could not have written, so a stale saved value shows nothing. */
        fun decode(value: String?): QuranSearchHit? {
            val numbers = value?.split(':')?.map { it.toIntOrNull() ?: return null } ?: return null
            if (numbers.size < 3 || numbers.size % 2 == 0 || numbers[0] < 1) return null
            return QuranSearchHit(numbers[0], numbers.drop(1).chunked(2).map { QuranVerseReference(it[0], it[1]) })
        }
    }
}

/**
 * The words of [text] that a search for [query] matched, as character ranges to emphasise.
 * With [similar], words a keyboard slip away from the query count too.
 */
fun quranMatchRanges(text: String, query: String, similar: Boolean = false, alignedSpelling: String? = null): List<IntRange> {
    val needle = normalizeQuranSearch(query)
    if (needle.isEmpty()) return emptyList()
    val typed = needle.split(' ')
    val tokens = Regex("\\S+").findAll(text).map { it.range to normalizeQuranSearch(it.value) }
        .filter { it.second.isNotEmpty() }.toList()
    val spellings = listOfNotNull(
        tokens.map { it.second },
        alignedSpelling?.let { normalizeQuranSearch(it).split(' ') }?.takeIf { it.size == tokens.size },
    )
    if (similar && needle.count { it != ' ' } >= 4) {
        for (spelling in spellings) {
            val phrase = spelling.joinToString(" ").nearPhrases(needle).firstOrNull() ?: continue
            var offset = 0
            return spelling.mapIndexedNotNull { index, word ->
                val start = offset
                offset += word.length + 1
                tokens[index].first.takeIf { start <= phrase.last && start + word.length > phrase.first }
            }
        }
    }
    val words = if (similar) typed + typed.map { withoutArticle(it) } else typed
    return tokens.indices.filter { index ->
        spellings.any { spelling ->
            val word = spelling[index]
            words.any { it in word || (similar && withinEdits(word, it, allowedEdits(it.length)) != null) }
        }
    }.map { tokens[it].first }
}

/** The word without its leading «ال», when enough of it is left to mean something. */
private fun withoutArticle(word: String): String = if (word.startsWith("ال") && word.length > 4) word.drop(2) else word

/** The searches the reader offers again, newest first, each kept once however it is spelled. */
fun List<String>.withRecentSearch(query: String, max: Int = 8): List<String> {
    val clean = query.replace(Regex("\\s+"), " ").trim()
    if (normalizeQuranSearch(clean).isEmpty()) return this
    val key = normalizeQuranSearch(clean)
    return (listOf(clean) + filter { normalizeQuranSearch(it) != key }).take(max)
}

/** How many slips of the keyboard a word of this length forgives. */
internal fun allowedEdits(length: Int): Int = when {
    length >= 9 -> 2
    length >= 4 -> 1
    else -> 0
}

/** A single insertion, deletion, substitution or adjacent swap, without copying the candidate substring. */
private fun isOneEdit(text: String, start: Int, length: Int, query: String): Boolean {
    val common = minOf(length, query.length)
    var i = 0
    while (i < common && text[start + i] == query[i]) i++
    if (i == common) return length != query.length
    return when {
        length > query.length -> text.regionMatches(start + i + 1, query, i, common - i)
        length < query.length -> text.regionMatches(start + i, query, i + 1, common - i)
        text.regionMatches(start + i + 1, query, i + 1, common - i - 1) -> true
        else -> i + 1 < common && text[start + i] == query[i + 1] && text[start + i + 1] == query[i] &&
            text.regionMatches(start + i + 2, query, i + 2, common - i - 2)
    }
}

/** Whole-word spans one edit from the phrase. A space costs the same as a mistyped letter. */
private fun String.nearPhrases(needle: String): Sequence<IntRange> = sequence {
    var start = 0
    while (length - start >= needle.length - 1) {
        for (size in needle.length - 1..minOf(needle.length + 1, length - start)) {
            val end = start + size
            if ((end == length || this@nearPhrases[end] == ' ') && isOneEdit(this@nearPhrases, start, size, needle)) {
                yield(start until end)
            }
        }
        val space = indexOf(' ', start)
        if (space < 0) break
        start = space + 1
    }
}

/** Bounded edit distance, counting adjacent swapped letters as one typing mistake. */
internal fun withinEdits(a: String, b: String, max: Int): Int? {
    if (a == b) return 0
    if (max == 0 || kotlin.math.abs(a.length - b.length) > max) return null
    if (max == 1) return if (isOneEdit(a, 0, a.length, b)) 1 else null
    var twoBack = IntArray(b.length + 1)
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        var rowBest = current[0]
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                current[j] = minOf(current[j], twoBack[j - 2] + 1)
            }
            if (current[j] < rowBest) rowBest = current[j]
        }
        if (rowBest > max) return null
        val swap = twoBack
        twoBack = previous
        previous = current
        current = swap
    }
    return previous[b.length].takeIf { it <= max }
}

/**
 * One verse as the search sees it: its fragments in two spellings, the Qaloun text and the
 * ordinary one, each normalised and cut into words.
 */
private class SearchForm(parts: List<String>) {
    val text = parts.joinToString(" ")
    private val starts = IntArray(parts.size).also { starts ->
        var offset = 0
        parts.forEachIndexed { index, part -> starts[index] = offset; offset += part.length + 1 }
    }
    /** Each word with the fragment it belongs to. */
    val words: List<Pair<String, Int>> by lazy {
        parts.flatMapIndexed { index, part -> part.split(' ').filter { it.isNotEmpty() }.map { it to index } }
    }

    fun partAt(offset: Int): Int {
        var index = starts.lastIndex
        while (index > 0 && starts[index] > offset) index--
        return index
    }

    /** Offset of the first occurrence between whole words, else of the first one anywhere. */
    fun find(needle: String): Pair<Int, Boolean>? {
        var from = 0
        var first = -1
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) break
            if (first < 0) first = at
            val end = at + needle.length
            if ((at == 0 || text[at - 1] == ' ') && (end == text.length || text[end] == ' ')) return at to true
            from = at + 1
        }
        return if (first >= 0) first to false else null
    }
}

private fun searchForms(entries: List<QuranSearchEntry>): List<SearchForm> =
    listOf(entries.map { it.normalized }, entries.map { it.spelling }).distinct().map(::SearchForm)

/** Alternate spellings can split a printed word (e.g. «يا أيها»). Only use positional highlights when aligned. */
private fun List<QuranSearchEntry>.alignedSpelling(): String? {
    if (none { it.searchText != it.text }) return null
    return joinToString(" ") {
        if (it.spelling.count { c -> c == ' ' } == it.normalized.count { c -> c == ' ' }) it.spelling else it.normalized
    }
}

private class SearchVerse(val surah: Int, val ayah: Int, val fragments: List<QuranSearchEntry>) {
    val reference = QuranVerseReference(surah, ayah)
    val forms = searchForms(fragments)
}

private class WordChoice(val position: Int, val cost: Int)
private class WordMatch(val cost: Int, val parts: List<Int>)

/** An unordered match needs a separate source word for each term, even when it is repeated or misspelled. */
private inline fun SearchForm.matchWords(count: Int, cost: (Int, String) -> Int): WordMatch? {
    if (count > words.size) return null
    val choices = ArrayList<List<WordChoice>>(count)
    for (term in 0 until count) {
        val options = words.mapIndexedNotNull { position, (word, _) ->
            val distance = cost(term, word)
            if (distance == Int.MAX_VALUE) null else WordChoice(position, distance)
        }
        if (options.isEmpty()) return null
        choices += options.sortedBy { it.cost }
    }
    val selected = distinctWordChoices(choices, words.size) ?: return null
    return WordMatch(selected.sumOf { it.cost }, selected.map { words[it.position].second }.distinct().sorted())
}

private fun distinctWordChoices(choices: List<List<WordChoice>>, wordCount: Int): List<WordChoice>? {
    val owners = IntArray(wordCount) { -1 }
    val selected = arrayOfNulls<WordChoice>(choices.size)
    // If a shared candidate is taken, try moving its owner to another matching word.
    fun assign(term: Int, visited: BooleanArray): Boolean {
        for (choice in choices[term]) {
            val position = choice.position
            if (visited[position]) continue
            visited[position] = true
            if (owners[position] == -1 || assign(owners[position], visited)) {
                owners[position] = term
                selected[term] = choice
                return true
            }
        }
        return false
    }
    for (term in choices.indices) if (!assign(term, BooleanArray(wordCount))) return null
    return selected.requireNoNulls().asList()
}

/** Everything on one scan of one chapter, for phrases that run from one verse into the next. */
private class SearchPage(private val entries: List<QuranSearchEntry>) {
    val number = entries.first().page
    private val forms = searchForms(entries)

    fun acrossVerses(needle: String, similar: Boolean = false): List<List<QuranSearchEntry>> {
        val found = mutableListOf<List<QuranSearchEntry>>()
        for (form in forms) {
            val spans = if (similar) form.text.nearPhrases(needle) else {
                generateSequence(form.text.indexOf(needle)) { form.text.indexOf(needle, it + needle.length) }
                    .takeWhile { it >= 0 }.map { it until it + needle.length }
            }
            for (span in spans) {
                val first = form.partAt(span.first)
                val last = form.partAt(span.last)
                if (entries[first].ayah != entries[last].ayah) found += entries.subList(first, last + 1)
            }
        }
        return found.distinctBy { group -> group.map { it.surah to it.ayah } }
    }
}

private enum class MatchRank(val kind: QuranSearchKind) {
    Reference(QuranSearchKind.Reference),
    WholePhrase(QuranSearchKind.Phrase),
    PartialPhrase(QuranSearchKind.Phrase),
    AcrossVerses(QuranSearchKind.Phrase),
    WholeWords(QuranSearchKind.Words),
    NearPhrase(QuranSearchKind.Similar),
    PartialWords(QuranSearchKind.Words),
    NearWords(QuranSearchKind.Similar),
}

private class Ranked(val rank: MatchRank, val distance: Int, val order: Int, val result: QuranSearchResult)

internal class QuranSearchIndex(entries: List<QuranSearchEntry>, private val surahs: List<QuranSurah>) {
    private val verses = entries.groupBy { it.surah to it.ayah }
        .map { (key, fragments) -> SearchVerse(key.first, key.second, fragments) }
        .sortedWith(compareBy({ it.surah }, { it.ayah }))
    private val versesByReference = verses.associateBy { it.reference }
    private val pages = entries.groupBy { it.surah to it.page }.values.map { SearchPage(it) }
    private val surahsByName: Map<String, Int> = buildMap {
        surahs.forEach { surah ->
            val name = normalizeQuranSearch(surah.name).removePrefix("سوره ").trim()
            put(name, surah.number)
            // «البقرة» is as often typed «بقرة».
            put(name.removePrefix("ال").trim(), surah.number)
        }
    }

    fun search(query: String): List<QuranSearchResult> {
        val needle = normalizeQuranSearch(query)
        if (needle.isEmpty()) return emptyList()
        val words = needle.split(' ')
        val ranked = ArrayList<Ranked>()
        val listed = HashSet<QuranVerseReference>()
        referenceOf(needle)?.let { reference ->
            val verse = versesByReference.getValue(reference)
            listed += reference
            ranked += Ranked(
                MatchRank.Reference, 0, order(reference),
                QuranSearchResult(
                    surahs[reference.surah - 1].name, verse.fragments.joinToString(" ") { it.text },
                    verse.fragments.first().page, listOf(reference), QuranSearchKind.Reference,
                    verse.fragments.flatMap { it.mushafWords },
                ),
            )
        }
        for (verse in verses) {
            if (verse.reference in listed) continue
            val match = bestMatch(verse, needle, words) ?: continue
            if (match.rank < MatchRank.NearPhrase) listed += verse.reference
            ranked += Ranked(match.rank, 0, order(verse.reference), resultOf(verse, match.parts, match.rank.kind))
        }
        ranked += acrossVerses(needle, similar = false)
        // A near miss is only worth listing when the exact words found little.
        if (ranked.count { it.rank.kind == QuranSearchKind.Phrase } < SIMILAR_BELOW && needle.count { it != ' ' } >= 4) {
            ranked += similar(needle, words, listed)
            ranked += acrossVerses(needle, similar = true)
        }
        if (ranked.isEmpty()) {
            // «الكرسي» is written «كرسيه» in the verse: try again without the article.
            val bare = words.joinToString(" ") { withoutArticle(it) }
            if (bare != needle) return search(bare).map { it.copy(kind = QuranSearchKind.Similar) }
        }
        var similarCount = 0
        val seen = HashSet<List<QuranVerseReference>>()
        return ranked.sortedWith(ranking).mapNotNull { candidate ->
            val result = candidate.result
            if (result.kind == QuranSearchKind.Similar && similarCount >= SIMILAR_LIMIT) return@mapNotNull null
            if (!seen.add(result.verses)) return@mapNotNull null
            if (result.kind == QuranSearchKind.Similar) similarCount++
            result
        }
    }

    private fun acrossVerses(needle: String, similar: Boolean): List<Ranked> = pages.flatMap { page ->
        page.acrossVerses(needle, similar).map { group ->
            val references = group.map { QuranVerseReference(it.surah, it.ayah) }.distinct()
            val rank = if (similar) MatchRank.NearPhrase else MatchRank.AcrossVerses
            val result = QuranSearchResult(
                surahs[group.first().surah - 1].name, group.joinToString(" ") { it.text }, page.number, references,
                rank.kind, group.flatMap { it.mushafWords }, group.alignedSpelling(),
            )
            Ranked(rank, if (similar) 1 else 0, order(references.first()), result)
        }
    }

    private class Match(val rank: MatchRank, val parts: List<Int>)

    private fun bestMatch(verse: SearchVerse, needle: String, words: List<String>): Match? {
        var best: Match? = null
        for (form in verse.forms) {
            val match = matchIn(form, needle, words) ?: continue
            if (best == null || match.rank < best.rank) best = match
        }
        return best
    }

    private fun matchIn(form: SearchForm, needle: String, words: List<String>): Match? {
        form.find(needle)?.let { (at, whole) ->
            val parts = (form.partAt(at)..form.partAt(at + needle.length - 1)).toList()
            return Match(if (whole) MatchRank.WholePhrase else MatchRank.PartialPhrase, parts)
        }
        if (words.size < 2 || words.any { it !in form.text }) return null
        val match = form.matchWords(words.size) { term, word ->
            when {
                word == words[term] -> 0
                words[term] in word -> 1
                else -> Int.MAX_VALUE
            }
        }
        return match?.let { Match(if (it.cost == 0) MatchRank.WholeWords else MatchRank.PartialWords, it.parts) }
    }

    private fun resultOf(verse: SearchVerse, parts: List<Int>, kind: QuranSearchKind): QuranSearchResult {
        val shown = parts.map { verse.fragments[it] }
        return QuranSearchResult(
            surahs[verse.surah - 1].name, shown.joinToString(" ") { it.text }, shown.first().page, listOf(verse.reference), kind,
            shown.flatMap { it.mushafWords }, shown.alignedSpelling(),
        )
    }

    private fun similar(needle: String, words: List<String>, skip: Set<QuranVerseReference>): List<Ranked> {
        val allowed = words.map { allowedEdits(it.length) }
        val tryWords = if (words.size == 1) allowed.single() > 1 else allowed.any { it > 0 }
        // Repeated words across the mushaf pay for edit distance once per query.
        val distances = words.map { HashMap<String, Int>() }
        val found = ArrayList<Ranked>()
        for (verse in verses) {
            if (verse.reference in skip) continue
            var best: Ranked? = null
            for (form in verse.forms) {
                val phrase = form.text.nearPhrases(needle).firstOrNull()
                if (phrase != null) {
                    val parts = form.partAt(phrase.first)..form.partAt(phrase.last)
                    best = Ranked(MatchRank.NearPhrase, 1, order(verse.reference), resultOf(verse, parts.toList(), QuranSearchKind.Similar))
                    break
                }
                if (!tryWords) continue
                val match = form.matchWords(words.size) { index, word ->
                    distances[index].getOrPut(word) { withinEdits(word, words[index], allowed[index]) ?: Int.MAX_VALUE }
                } ?: continue
                if (best == null || match.cost < best.distance) {
                    best = Ranked(MatchRank.NearWords, match.cost, order(verse.reference), resultOf(verse, match.parts, QuranSearchKind.Similar))
                }
            }
            best?.let { found += it }
        }
        return found
    }

    /** «2:25», «2 25», «البقرة 25» or «سورة البقرة 25»; digits typed in either script are read. */
    private fun referenceOf(needle: String): QuranVerseReference? {
        val tokens = needle.split(' ').filter { it.isNotEmpty() }.let { if (it.firstOrNull() == "سوره") it.drop(1) else it }
        if (tokens.size < 2) return null
        val ayah = numberOf(tokens.last()) ?: return null
        val head = tokens.dropLast(1)
        val surah = if (head.size == 1 && numberOf(head[0]) != null) numberOf(head[0]) else surahsByName[head.joinToString(" ")]
        return surah?.let { QuranVerseReference(it, ayah) }?.takeIf { ayah >= 1 && it in versesByReference }
    }

    private fun numberOf(token: String): Int? {
        if (token.isEmpty() || token.length > 4 || !token.all { Character.isDigit(it) }) return null
        return token.fold(0) { value, char -> value * 10 + Character.digit(char, 10) }
    }

    /** Mushaf order, except that the unnumbered basmalahs, which every chapter repeats, come last. */
    private fun order(reference: QuranVerseReference) =
        if (reference.ayah == 0) BASMALAH_ORDER + reference.surah else reference.surah * 1000 + reference.ayah

    private companion object {
        val ranking = compareBy<Ranked>({ it.rank }, { it.distance }, { it.order })
        const val BASMALAH_ORDER = 1_000_000
        const val SIMILAR_BELOW = 3
        const val SIMILAR_LIMIT = 40
    }
}
