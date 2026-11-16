package com.tunisianprayertimes.quran.audio

import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.hifz.HifzDrillTitle
import com.tunisianprayertimes.quran.hifz.HifzListenOneTitle
import com.tunisianprayertimes.quran.hifz.HifzListenTitle
import org.json.JSONArray
import org.json.JSONObject

/**
 * Separate passages recited one after another, each [itemTimes] times in a row, the whole list [rounds] times. With a
 * [gapFactor], each recitation is followed by one silence to say it again, joined, none between its verses: a hifz drill
 * («47 ثم 115 ثم 47»). Without one it plays straight through: the fixes of «مواضعي صوتا», the cue before a point.
 */
data class QuranPlaylist(
    /** «مواضعي صوتا», «تدريب». */
    val title: String,
    /** Each passage is recited [itemTimes] times per round. */
    val items: List<QuranRepeatRange>,
    /** One per item: «البقرة 67 ← 68 ← 69». */
    val labels: List<String>,
    /** 0: from the first passage again after the last, until the listener stops it. */
    val rounds: Int = 1,
    /** Silence after each passage, as a multiple of its whole length; 0 for none. */
    val gapFactor: Float = 1f,
    /** Null keeps the listener's own speed. */
    val speed: Float? = null,
    /**
     * What the silence after any other passage asks for from memory, and is long enough for: a drill's right wording,
     * said after its look-alike. Null when each silence is for the passage just recited.
     */
    val answer: QuranRepeatRange? = null,
    /** Identifies one requested playback, so its completion cannot be confused with an earlier identical drill. */
    val requestId: String? = null,
    /** How many times each passage is recited before the next one: «اسمع كل موضع 3 مرات». */
    val itemTimes: Int = 1,
) {
    val isValid: Boolean
        get() = items.isNotEmpty() && items.size == labels.size && rounds in 0..MAX_ROUNDS && itemTimes in 1..MAX_ITEM_TIMES &&
            items.all { it.isValid } && gapFactor in 0f..4f && (speed == null || speed in 0.5f..2f) && answer?.isValid != false

    /** The list starts again after its last passage until it is stopped. */
    val endless: Boolean get() = rounds == 0

    /** [round] as one the list really has: at least the first, and at most its last unless it is [endless]. */
    fun roundWithin(round: Int): Int = if (endless) round.coerceAtLeast(1) else round.coerceIn(1, rounds)

    fun encode(): String = JSONObject()
        .put("title", title)
        .put("rounds", rounds)
        .put("gap", gapFactor.toDouble())
        .put("speed", speed?.toDouble() ?: 0.0)
        .put("items", JSONArray(items.zip(labels).map { (range, label) ->
            JSONArray(listOf(range.from.surah, range.from.ayah, range.to.surah, range.to.ayah, label))
        }))
        .apply { answer?.let { put("answer", JSONArray(listOf(it.from.surah, it.from.ayah, it.to.surah, it.to.ayah))) } }
        .apply { requestId?.let { put("requestId", it) } }
        .apply { if (itemTimes != 1) put("times", itemTimes) }
        .toString()

    companion object {
        const val MAX_ROUNDS = 99
        const val MAX_ITEM_TIMES = 99

        fun decode(value: String?): QuranPlaylist? = runCatching {
            val source = JSONObject(value ?: return null)
            val rows = source.getJSONArray("items")
            val items = List(rows.length()) { index ->
                val row = rows.getJSONArray(index)
                QuranRepeatRange(QuranVerseReference(row.getInt(0), row.getInt(1)), QuranVerseReference(row.getInt(2), row.getInt(3)), 1) to row.getString(4)
            }
            QuranPlaylist(
                title = source.getString("title"),
                items = items.map { it.first },
                labels = items.map { it.second },
                rounds = source.getInt("rounds"),
                gapFactor = source.getDouble("gap").toFloat(),
                speed = source.getDouble("speed").toFloat().takeIf { it > 0f },
                answer = source.optJSONArray("answer")?.let { a ->
                    QuranRepeatRange(QuranVerseReference(a.getInt(0), a.getInt(1)), QuranVerseReference(a.getInt(2), a.getInt(3)), 1)
                },
                requestId = source.opt("requestId") as? String,
                itemTimes = source.optInt("times", 1),
            )
        }.getOrNull()?.takeIf { it.isValid }
    }
}

/** «انتهى التدريب», «انتهى الاستماع إلى المواضع»: said when a playlist titled [title] has been recited to its end. */
internal fun quranPlaylistEnded(title: String): String = when (title) {
    HifzDrillTitle -> "انتهى التدريب"
    HifzListenTitle -> "انتهى الاستماع إلى المواضع"
    HifzListenOneTitle -> "انتهى الاستماع إلى الموضع"
    else -> "انتهى الاستماع"
}

/** A playlist of points heard in turn, «الاستماع إلى المواضع» or one point's: its «التالي» goes to the next point. */
internal val QuranPlaylist.isPointListening: Boolean get() = title == HifzListenTitle || title == HifzListenOneTitle

/** «المرة 2 من 3»: which recitation of the passage under way, when each is heard more than once; null otherwise. */
internal fun QuranPlaybackState.playlistPass(): String? =
    playlist?.takeIf { it.itemTimes > 1 }?.let { "المرة ${repeatRound.coerceAtLeast(1)} من ${it.itemTimes}" }

/** «الاستماع إلى المواضع · 3 من 12 · المرة 2 من 3», where the lock screen and the notification say what plays. */
internal fun QuranPlaybackState.playlistProgress(): String? = playlist?.let { list ->
    listOfNotNull(list.title, "${playlistIndex + 1} من ${list.items.size}".takeIf { list.items.size > 1 }, playlistPass())
        .joinToString(" · ")
}

/** Where a playlist goes once an item has been recited; [pass] counts the item's recitations in a row, from 1. */
internal sealed interface QuranPlaylistStep {
    data class Item(val index: Int, val round: Int, val pass: Int = 1) : QuranPlaylistStep
    data object Finished : QuranPlaylistStep
}

/** The next item: what «التالي» goes to, whatever recitation of this one is under way. */
internal fun QuranPlaylist.after(index: Int, round: Int): QuranPlaylistStep = when {
    index + 1 < items.size -> QuranPlaylistStep.Item(index + 1, round)
    endless || round < rounds -> QuranPlaylistStep.Item(0, round + 1)
    else -> QuranPlaylistStep.Finished
}

/** Once recitation [pass] of item [index] has ended: the same passage again until it has had its [QuranPlaylist.itemTimes]. */
internal fun QuranPlaylist.afterPass(index: Int, round: Int, pass: Int): QuranPlaylistStep =
    if (pass in 1 until itemTimes) QuranPlaylistStep.Item(index, round, pass + 1) else after(index, round)

/** Finishing the reader's turn early follows the same path as the drill's pause ending by itself. */
internal fun QuranPlaybackState.drillPauseStep(): QuranPlaylistStep? {
    val list = playlist ?: return null
    val item = list.items.getOrNull(playlistIndex) ?: return null
    if (!playing || loading || error != null || notDownloaded || interruption != null ||
        list.gapFactor <= 0f || gapAfter != item.to.ayah || surah != item.to.surah ||
        playlistRound < 1 || !list.endless && playlistRound > list.rounds) return null
    return list.afterPass(playlistIndex, playlistRound, repeatRound.coerceAtLeast(1))
}

/** The item before, or the start of the first one again. */
internal fun QuranPlaylist.before(index: Int, round: Int): QuranPlaylistStep.Item = when {
    index > 0 -> QuranPlaylistStep.Item(index - 1, round)
    round > 1 -> QuranPlaylistStep.Item(items.lastIndex, round - 1)
    else -> QuranPlaylistStep.Item(0, 1)
}
