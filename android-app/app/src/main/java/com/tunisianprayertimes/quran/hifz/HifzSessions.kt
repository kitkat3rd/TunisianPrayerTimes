package com.tunisianprayertimes.quran.hifz

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.snapshots.Snapshot
import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranRepository
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.audio.*
import com.tunisianprayertimes.ui.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** One owner for the existing phone review state machines, independent of a particular screen or service. */
internal object HifzSessions {
    private var instance: HifzSessionCoordinator? = null
    @Synchronized fun get(context: Context): HifzSessionCoordinator = instance
        ?: HifzSessionCoordinator(context.applicationContext).also { instance = it }
}

/** One review at a time, of one kind ([Review]), whatever its [HifzReviewOrigin]. */
internal enum class HifzSessionMode { None, Review }
/** Where the review's cards came from: the points due now («مراجعة اليوم»), or a list the reader chose in the notebook. */
internal enum class HifzReviewOrigin { Daily, List }
/** [Answered]: a card answered earlier in this review, come back to: shown read-only, nothing to grade again. */
internal enum class HifzSessionPhase { Question, Assessment, Training, Answered, Finished }

/** The same selection and linked questions are used for the notebook's preview and the list review it opens. */
internal data class HifzListPlan(
    val points: List<HifzPoint>,
    val selectedCount: Int,
) {
    val addedDueCount: Int get() = points.size - selectedCount
}

/**
 * A list review of [order]: a due point a card would show, left out of the list, comes in before that card, as a
 * correction opened from the notebook would ask it first. [lead]: the first of [order] is the row the reader tapped,
 * asked first whatever it shows (owner, 16 November: a tap gives that point's test); a due point only it would give
 * away is not pulled in before it, except the tapped card: it is asked after it, as helped, its answer saved and not
 * counted.
 */
internal fun hifzListPlan(points: List<HifzPoint>, order: List<Long>, dueIds: Set<Long>, lead: Boolean = false): HifzListPlan {
    val byId = points.associateBy { it.id }
    val listed = order.distinct().mapNotNull { byId[it] }
    val first = listed.firstOrNull()?.takeIf { lead }
    val selected = listed.filter { it.id != first?.id }.toMutableList()
    val selectedCount = listed.size
    val due = points.filter { it.id in dueIds && it.id != first?.id }
    while (true) {
        val linked = due.filter { question -> selected.none { it.id == question.id } && selected.any { it.showsAnswerOf(question) } }
        if (linked.isEmpty()) break
        selected += linked
    }
    return HifzListPlan(listOfNotNull(first) + selected.answerSafeOrder(), selectedCount)
}

/**
 * The notebook's order, as its list shows it: what needs care first, then by surah. The car's «مراجعة الدفتر» asks in
 * the same order as the phone.
 */
internal fun List<HifzPoint>.notebookOrder(): List<Long> {
    val care = filter { it.needsCare }
    return care.map { it.id } + filterNot { it.needsCare }.groupBy { it.verse.surah }.toSortedMap().values.flatten().map { it.id }
}

internal enum class HifzSessionProblemKind { AnswerSave, PositionSave, Restore, PointChanged, Audio }

internal data class HifzSessionProblem(val kind: HifzSessionProblemKind, val message: String) {
    val retrySave: Boolean get() = kind == HifzSessionProblemKind.AnswerSave || kind == HifzSessionProblemKind.PositionSave
}

internal interface HifzSessionAudio {
    fun play(list: QuranPlaylist)
    fun pause()
    fun restore(place: String?)
}

internal data class HifzSessionView(
    val mode: HifzSessionMode,
    val phase: HifzSessionPhase,
    val point: HifzPoint?,
    val index: Int,
    val total: Int,
    val question: String,
    val tally: HifzTally,
    val assisted: Boolean,
    val deferred: Int,
    val token: String,
    val origin: HifzReviewOrigin = HifzReviewOrigin.Daily,
    /** Back to the card before, or on past this one: never after «أظهر» before an answer. */
    val canBack: Boolean = false,
    val canForward: Boolean = false,
    /** The verse before the question may be played: it is no other question waiting. */
    val cue: Boolean = false,
)

internal class HifzSessionCoordinator(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::uptimeMillis,
) {
    val reviewIds = mutableStateOf<List<Long>>(emptyList())
    val reviewSession = mutableIntStateOf(0)
    val reviewStartedDay = mutableLongStateOf(todayEpochDay())
    val reviewDayDraft = mutableStateOf<String?>(null)
    val reviewAudio = mutableStateOf(false)
    val reviewPlayed = mutableStateOf(false)
    val reviewReturn = mutableStateOf<String?>(null)
    var reviewState by mutableStateOf(HifzReviewState(clock = elapsed, wallClock = now))
        private set
    var mode by mutableStateOf(HifzSessionMode.None)
        private set
    /** The review's cards: the points due («مراجعة اليوم»), or a list chosen in the notebook. Saved with the session. */
    var origin by mutableStateOf(HifzReviewOrigin.Daily)
        private set
    var initialized by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var paused by mutableStateOf(true)
        private set
    var carActive by mutableStateOf(false)
        private set
    var problem by mutableStateOf<HifzSessionProblem?>(null)
        private set
    val error: String? get() = problem?.message
    var notice by mutableStateOf<String?>(null)
        private set
    var phoneAudio: HifzSessionAudio? = null
    var carAudio: HifzSessionAudio? = null
    var catalog: QuranCatalog? = null
        private set
    private var sessionId = UUID.randomUUID().toString()
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    private val initialization = Mutex()
    private val persistenceLock = Any()
    private var version = 0L
    private var writtenVersion = 0L
    private var committing = false
    private var pending by mutableStateOf<PendingGrade?>(null)
    private var clip by mutableStateOf<QuranPlaylist?>(null)
    private var clipPosition by mutableLongStateOf(0L)
    private var clipIndex by mutableIntStateOf(0)
    private var clipRound by mutableIntStateOf(1)
    private var clipEnded by mutableStateOf(false)
    private var lastReceiptAt = 0L
    private var learningListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var reminderUpdate: Job? = null
    private var clipReciterId by mutableStateOf(QuranAudioController.DEFAULT_RECITER_ID)
    private var clipSurah by mutableIntStateOf(0)
    private val preferences by lazy { context.getSharedPreferences("hifz_shared_session", Context.MODE_PRIVATE) }
    private val saverScope = object : SaverScope { override fun canBeSaved(value: Any) = true }

    val hasSession: Boolean get() = mode != HifzSessionMode.None
    val active: Boolean get() = hasSession && !paused
    val savingAnswer: Boolean get() = pending != null || busy
    private fun day() = epochDay(now())
    private fun bridge() = if (carActive) carAudio else phoneAudio

    suspend fun initialize() = initialization.withLock {
        if (initialized) return@withLock
        val saved = withContext(Dispatchers.IO) {
            HifzRepository.load(context)
            QuranAudioController.initialize(context)
            catalog = QuranRepository.load(context)
            preferences.getString("session", null)
        }
        if (saved != null) try { restore(JSONObject(saved)) } catch (_: Exception) {
            // Retain the recovery evidence; the notebook itself is never reset because a session cannot be read.
            withContext(Dispatchers.IO) { preferences.edit().putString("unreadable_session", saved).commit() }
            problem = HifzSessionProblem(HifzSessionProblemKind.Restore, "تعذّر استرجاع موضع المراجعة. احتفظنا بالدفتر ونتائجه المحفوظة.")
        }
        initialized = true
        paused = true
        carActive = false
        if (pending != null) finishPending()
        refresh()
        learningListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            scope.launch {
                if (!busy) { refresh(); changed() }
                reminderUpdate?.cancel()
                reminderUpdate = scope.launch {
                    delay(100)
                    withContext(Dispatchers.IO) { runCatching { HifzReminder.refresh(context) } }
                }
            }
        }.also { context.getSharedPreferences("hifz_learning", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(it) }
        scope.launch {
            snapshotFlow { encode().toString() to busy }.collect {
                if (!busy && pending == null) {
                    persistSafely()
                    changed()
                }
            }
        }
        scope.launch { HifzRepository.points.collect { if (!busy) { refresh(); changed() } } }
        changed()
    }

    fun dayTally(): HifzTally = reviewDayDraft.value?.split(':', limit = 2)
        ?.takeIf { it.size == 2 && it[0] == day().toString() }?.let { HifzTally.decode(it[1]) } ?: run {
            val points = HifzRepository.points.value
            val checks = points.mapNotNull { p -> p.checks.firstOrNull { it.day == day() } }
            HifzTally(checks.count { it.grade == HifzGrade.Held }, checks.count { it.grade == HifzGrade.Hesitated },
                checks.count { it.grade == HifzGrade.Again }, points.count { it.mastered && it.masteredAt?.let(::epochDay) == day() })
        }

    /** The day's answers so far, kept for the next review: every answer is saved, whichever review gave it. */
    fun keepDay() { if (mode != HifzSessionMode.None) reviewDayDraft.value = "${day()}:${reviewState.day.encode()}" }
    private val daily: Boolean get() = mode == HifzSessionMode.Review && origin == HifzReviewOrigin.Daily
    private val listing: Boolean get() = mode == HifzSessionMode.Review && origin == HifzReviewOrigin.List

    /**
     * The day's review: everything due now ([reviewSitting]), [lead] first, as a reminder listed them or the reader chose
     * one. Without [lead], or [restart], the review left unfinished goes on when it can ([dailyCanContinue]).
     */
    fun startDaily(lead: List<Long> = emptyList(), restart: Boolean = false): Boolean {
        if (!initialized || savingAnswer) return false
        refresh()
        val learning = HifzLearning.read(context, day())
        val eligible = HifzRepository.points.value.askable(day(), now(), learning.seen, learning.exposed)
        if (!restart && lead.isEmpty() && dailyCanContinue(eligible.mapTo(HashSet()) { it.id })) {
            resume(); return true
        }
        keepDay()
        val chosen = eligible.reviewSitting(day(), now(), lead.mapNotNull { id -> eligible.firstOrNull { it.id == id } })
        if (chosen.isEmpty()) { inform("لا توجد أسئلة مستحقة الآن. تبقى مواعيد المراجعة كما هي."); return false }
        captureListening()
        begin(chosen.map { it.id }, HifzReviewOrigin.Daily, learning)
        HifzReminder.clearReminder(context)
        refresh(); changed()
        return true
    }

    /**
     * A list review: [order], the notebook's points as listed, or with none all of them in the phone's order
     * ([notebookOrder]), whose unfinished review then goes on. [lead]: the first of [order] is a row the reader tapped,
     * asked first; see [hifzListPlan]. Its cards count by R0, decided at each one's first look.
     */
    fun startList(order: List<Long>? = null, lead: Boolean = false): Boolean {
        if (!initialized || savingAnswer) return false
        val points = HifzRepository.points.value
        if (order == null && listCanContinue()) { resume(); return true }
        val ids = order ?: points.notebookOrder()
        val learning = HifzLearning.read(context, day())
        val due = points.askable(day(), now(), learning.seen, learning.exposed).map { it.id }.toSet()
        val plan = hifzListPlan(points, ids, due, lead = lead && order != null)
        if (plan.points.isEmpty()) {
            inform(if (carActive) "دفتر المراجعة فارغ. أضف مواضعك من الهاتف حين يكون ذلك آمنًا."
                else "دفتر المراجعة فارغ. أضف إليه المواضع التي تريد مراجعتها.")
            return false
        }
        keepDay(); captureListening()
        begin(plan.points.map { it.id }, HifzReviewOrigin.List, learning)
        refresh(); changed()
        return true
    }

    /** A new review of [ids], from [from]: what the day answered so far goes with it. */
    private fun begin(ids: List<Long>, from: HifzReviewOrigin, learning: HifzLearning) {
        reviewState = HifzReviewState(elapsed, dayTally(), learning.exposed, learning.seen, now, list = from == HifzReviewOrigin.List)
        reviewIds.value = ids
        reviewStartedDay.longValue = day()
        reviewSession.intValue++
        mode = HifzSessionMode.Review
        origin = from
        sessionId = UUID.randomUUID().toString()
        paused = false
        problem = null
        notice = null
    }

    fun usePhone() { carActive = false; paused = false; changed() }
    fun useCar() { carActive = true; paused = false; changed() }
    fun leaveCar() { carActive = false; changed() }
    fun inform(message: String) { notice = message; changed() }
    fun dismissNotice() { notice = null; changed() }
    fun audioError(message: String) {
        // Playback must not replace a warning about an answer still waiting to be saved.
        if (problem?.retrySave != true) problem = HifzSessionProblem(HifzSessionProblemKind.Audio, message)
        changed()
    }
    fun resume() { if (!savingAnswer) {
        if (hasSession) notice = null
        captureListening(); paused = false; reviewState.resume()
        // A list review left at its end, a card skipped there: on from the first one still unanswered.
        if (listing) reviewState.reopen(queue())
        takeInDue(); refresh(); changed()
    } else retry() }

    /** The points a daily review may ask now. */
    private fun dueNow(): List<HifzPoint> {
        val learning = HifzLearning.read(context, day())
        return HifzRepository.points.value.askable(day(), now(), learning.seen, learning.exposed)
    }

    /** Something is due now, for a new daily review to ask. */
    fun anythingDue(): Boolean = dueNow().isNotEmpty()

    /** The points «مواضعي صوتا» can recite now, as on the phone: all but those whose question waits now. */
    fun listenableNow(): List<HifzPoint> {
        val points = HifzRepository.points.value
        return points.listenable(points, day(), now(), dueNow().mapTo(HashSet()) { it.id })
    }

    /**
     * The daily review left unfinished can go on: today's, with a question left, and none of [eligibleIds], the points
     * due now, a point it already answered or put off, which only a new review can ask again. What fell due since it
     * began joins it as it goes on.
     */
    fun dailyCanContinue(eligibleIds: Set<Long>): Boolean =
        initialized && daily && reviewStartedDay.longValue == day() && reviewState.point(queue()) != null &&
            eligibleIds.none { it in reviewState.gradedIds || it in reviewState.deferredIds }

    /** A list review left unfinished, on any day: a card of it still unanswered, skipped ones too. */
    fun listCanContinue(): Boolean = initialized && listing && reviewState.unanswered(queue()) > 0

    /** The cards a list review left unfinished still asks; 0 when there is none to go on with. */
    fun listLeft(): Int = if (listCanContinue()) reviewState.unanswered(queue()) else 0

    /** What fell due since the daily review began joins it, after the questions it holds. */
    private fun takeInDue() {
        if (!daily || reviewState.point(queue()) == null) return
        val held = reviewIds.value.toSet()
        val due = dueNow().filter { it.id !in held }
        if (due.isNotEmpty()) reviewIds.value = reviewIds.value + due.reviewSitting(day(), now()).map { it.id }
    }

    fun pause() {
        bridge()?.pause()
        notePlayback(QuranAudioController.state.value, force = true)
        paused = true
        changed()
        persistAsync()
    }

    /** A closed surface keeps an unfinished attempt for the other surface. */
    fun close() {
        if (savingAnswer) return
        keepDay()
        pause()
        bridge()?.restore(reviewReturn.value)
        reviewReturn.value = null
        reviewPlayed.value = false
        // Done: nothing left to go on with. A list review keeps a card skipped and never answered for «تابع المراجعة»;
        // a review of due points leaves it due, for the next one.
        if (view().phase == HifzSessionPhase.Finished && !reviewState.stopping && !listCanContinue()) mode = HifzSessionMode.None
        carActive = false
        changed()
    }

    fun clear() {
        if (savingAnswer) return
        mode = HifzSessionMode.None
        pending = null
        reviewIds.value = emptyList()
        reviewSession.intValue++
        reviewState = HifzReviewState(clock = elapsed, wallClock = now)
        clip = null
        reviewReturn.value = null
        reviewPlayed.value = false
        reviewAudio.value = false
        paused = true
        carActive = false
        changed()
    }

    fun refresh() {
        if (!initialized || savingAnswer) return
        if (daily && reviewStartedDay.longValue != day()) {
            // A new date is not a failure. Answers are already in the notebook; unanswered points stay due.
            // Do not carry yesterday's tally or audio into today's review. A list review goes on: its cards not looked
            // at yet decide R0 when they are.
            reviewActions.leaveCard()
            reviewIds.value = emptyList()
            mode = HifzSessionMode.None
            paused = true
            reviewDayDraft.value = null
            return
        }
        val learning = HifzLearning.read(context, day())
        reviewState.syncExposed(learning.exposed, reviewActions, learning.seen)
        if (mode != HifzSessionMode.None) {
            reviewState.follow(queue(), reviewActions)
            reviewState.shown(reviewState.point(queue()))
        }
    }

    private fun queue() = reviewState.questions(reviewIds.value.mapNotNull { id -> HifzRepository.points.value.firstOrNull { it.id == id } })

    fun view(): HifzSessionView {
        val cards = queue()
        val index = reviewState.index(cards)
        val point = cards.getOrNull(index)
        val phase = when {
            mode == HifzSessionMode.None || point == null || reviewState.atEnd(cards) -> HifzSessionPhase.Finished
            reviewState.training(point) -> HifzSessionPhase.Training
            reviewState.answered(point) -> HifzSessionPhase.Answered
            reviewState.revealed(point) -> HifzSessionPhase.Assessment
            else -> HifzSessionPhase.Question
        }
        val question = point?.let { p -> catalog?.let { hifzQuestionAbout(it, p) } }.orEmpty()
        val open = mode != HifzSessionMode.None
        return HifzSessionView(mode, phase, point, index, reviewState.count(cards),
            question, reviewState.round, reviewState.assisted(point), reviewState.deferredIds.size,
            "$sessionId:${mode.name}:${point?.id}:$phase", origin,
            canBack = open && reviewState.canStepBack(cards), canForward = open && reviewState.canStepForward(cards),
            cue = phase == HifzSessionPhase.Question && point != null && cueAllowed(point, cards))
    }

    /**
     * The verse before [point]'s, its cue, is no other question still waiting: a card of this review unanswered, skipped
     * or to come, or a point due now. Hearing it would give that answer away.
     */
    private fun cueAllowed(point: HifzPoint, cards: List<HifzPoint>): Boolean {
        if (point.verse.ayah <= 1) return false
        val before = QuranVerseReference(point.verse.surah, point.verse.ayah - 1)
        return cards.none { it.id != point.id && !reviewState.answered(it) && before in it.span } &&
            dueNow().none { it.id != point.id && before in it.span }
    }

    fun reveal(exposeAnswer: Boolean = true): Boolean {
        if (savingAnswer) return false
        if (mode != HifzSessionMode.None) reviewState.reveal(queue(), reviewActions, exposeAnswer)
        refresh(); changed()
        return view().phase == HifzSessionPhase.Assessment
    }

    fun assess(grade: HifzGrade) {
        if (savingAnswer || view().phase != HifzSessionPhase.Assessment) return
        view().point?.let { point ->
            val actual = if (grade == HifzGrade.Held && reviewState.assisted(point)) HifzGrade.Hesitated else grade
            val outcome = hifzAnswerOutcome(point, actual, reviewState.counted(point), "في تذكير لاحق", "غدًا")
            reviewState.grade(queue(), reviewActions, grade, outcome = outcome.text(withBack = true, short = false))
        }
        changed()
    }

    /** On: the drill's «التالي», a skip before «أظهر», or past a card answered. Nothing after «أظهر» before an answer. */
    fun next() {
        if (savingAnswer) return
        if (view().phase == HifzSessionPhase.Training) reviewState.next(queue(), reviewActions)
        else reviewState.step(queue(), reviewActions, 1)
        refresh(); changed()
    }

    /** Back to the card before: read-only when answered; one skipped is asked as it was. */
    fun previous() {
        if (!savingAnswer) reviewState.step(queue(), reviewActions, -1)
        refresh(); changed()
    }

    fun list(kind: String): QuranPlaylist? = view().point?.let { point -> catalog?.let { index -> when (kind) {
        "cue" -> hifzCue(index, point)
        "drill" -> hifzDrill(index, point)
        else -> hifzListen(index, listOf(point))
    } } }

    fun cue() { view().point?.let { reviewActions.listenBefore(it) } }
    fun answerAudio() { view().point?.let { reviewActions.listen(it) } }
    fun drill() {
        view().point?.let { reviewState.drill(it, reviewActions) }
        changed()
    }

    private fun play(list: QuranPlaylist?) {
        list ?: return
        captureListening()
        reviewAudio.value = true
        reviewPlayed.value = true
        clip = list.copy(requestId = "hifz-session:$sessionId:${UUID.randomUUID()}")
        clipReciterId = QuranAudioController.state.value.reciterId
        clipPosition = 0L; clipIndex = 0; clipRound = 1; clipEnded = false
        clipSurah = list.items.first().from.surah
        bridge()?.play(requireNotNull(clip))
        changed()
    }

    fun owns(requestId: String?) = requestId != null && requestId == clip?.requestId
    fun resumableClip(): Triple<QuranPlaylist, Int, Int>? = clip?.takeUnless { clipEnded }?.let { Triple(it, clipIndex, clipRound) }
    fun savedClipPosition() = clipPosition
    fun savedClipReciter() = clipReciterId
    fun savedClipSurah(): Int? = clipSurah.takeIf { it in 1..114 }

    fun notePlayback(state: QuranPlaybackState, force: Boolean = false) {
        if (!clipEnded && owns(state.finishedPlaylist?.requestId)) { clipEnded = true; changed() }
        if (!owns(state.playlist?.requestId)) return
        if (force || !state.playing || state.positionMs / 5_000L != clipPosition / 5_000L || clipIndex != state.playlistIndex) {
            clipPosition = state.positionMs
            clipIndex = state.playlistIndex
            clipRound = state.playlistRound.coerceAtLeast(1)
            clipSurah = state.surah ?: 0
        }
    }

    fun captureListening() {
        if (reviewReturn.value != null || reviewPlayed.value) return
        val state = QuranAudioController.state.value
        val surah = state.surah ?: return
        if (state.playlist != null || state.notDownloaded || state.loading || state.error != null) return
        reviewReturn.value = listOf(state.reciterId, surah, state.positionMs, state.repeat?.encode(state.repeatRound).orEmpty(), if (state.playing) 1 else 0).joinToString("|")
        if (state.playing) bridge()?.pause()
    }

    val reviewActions: HifzReviewActions = object : HifzReviewActions {
        override fun grade(point: HifzPoint, grade: HifzGrade) = Unit // The durable receipt already wrote it.
        override fun tryGrade(point: HifzPoint, grade: HifzGrade, counted: Boolean, outcome: String): Boolean {
            if (mode == HifzSessionMode.None) return false
            if (committing) return true
            requestGrade(point, grade, counted, outcome)
            return false
        }
        // R0 in a list review: the same test as the reminders' and «الآن»'s, read before the look is written.
        override fun canCount(point: HifzPoint): Boolean {
            if (!listing) return true
            val learning = HifzLearning.read(context, day())
            return HifzRepository.points.value.firstOrNull { it.id == point.id }?.isDue(day(), now(), learning.seen, learning.exposed) == true
        }
        override fun seen(verses: Set<QuranVerseReference>) = expose(verses)
        override fun exposed(verses: Set<QuranVerseReference>) = exposeToday(verses)
        override fun listenBefore(point: HifzPoint) {
            val shown = view()
            if (shown.point?.id == point.id && shown.cue) play(catalog?.let { hifzCue(it, point) })
        }
        override fun listen(point: HifzPoint) { if (view().point?.id == point.id) play(catalog?.let { hifzListen(it, listOf(point)) }) }
        override fun drill(point: HifzPoint) { if (view().point?.id == point.id) play(catalog?.let { hifzDrill(it, point) }) }
        override fun leaveCard() { if (reviewAudio.value) bridge()?.pause(); reviewAudio.value = false; clip = null }
        override fun more() { startDaily(restart = true) }
        override fun listenTo(ids: List<Long>) { play(catalog?.let { hifzListen(it, HifzRepository.points.value.filter { p -> p.id in ids }) }) }
        override fun dismiss() { close() }
    }

    private fun expose(verses: Set<QuranVerseReference>) {
        if (verses.isEmpty()) return
        HifzLearning.read(context, day()).expose(verses, now()).write(context)
        changed()
    }

    /** A test's own «أظهر»: seen today, its time written only if its answer is not a right one without help. */
    private fun exposeToday(verses: Set<QuranVerseReference>) {
        if (verses.isEmpty()) return
        HifzLearning.read(context, day()).exposeToday(verses).write(context)
        changed()
    }

    private fun requestGrade(point: HifzPoint, grade: HifzGrade, counted: Boolean, outcome: String) {
        if (!initialized || savingAnswer) return
        val current = view()
        if (current.point?.id != point.id || current.phase != HifzSessionPhase.Assessment) return
        val stamp = maxOf(now(), lastReceiptAt + 1)
        lastReceiptAt = stamp
        pending = PendingGrade(mode, point, grade, counted, outcome, day(), stamp)
        reviewState.inputBlocked = true
        scope.launch { finishPending() }
    }

    private suspend fun finishPending() {
        val receipt = pending ?: return
        if (busy) return
        busy = true
        reviewState.inputBlocked = true
        changed()
        try {
            persist() // Journal the explicit response before changing the notebook.
            val saved = withContext(Dispatchers.IO) {
                // Every answer is saved; [graded] decides what it moves (R0 at save time).
                HifzRepository.gradeReview(context, receipt.point, receipt.grade, receipt.day, receipt.at, receipt.counted).also { saved ->
                    // A right answer without help forgives its own «أظهر»: the next test needs no new wait. Any other
                    // answer writes the look's time now.
                    if (saved && receipt.grade != HifzGrade.Held) {
                        HifzLearning.read(context, day()).expose(receipt.point.span.toSet(), now()).write(context)
                    }
                }
            }
            if (!saved) {
                pending = null
                problem = HifzSessionProblem(HifzSessionProblemKind.PointChanged, "تغيّر هذا الموضع أو حُذف. لم نسجّل إجابة جديدة عليه.")
            } else if (!receipt.applied) {
                committing = true
                reviewState.inputBlocked = false
                if (mode != HifzSessionMode.None && reviewState.point(queue())?.id == receipt.point.id) {
                    reviewState.grade(queue(), reviewActions, receipt.grade, confirmed = true, outcome = receipt.outcome)
                    keepDay()
                }
                receipt.applied = true
            }
            pending = null
            persist()
            if (saved && problem?.retrySave == true) problem = null
            scope.launch(Dispatchers.IO) { runCatching { HifzReminder.refresh(context) } }
        } catch (_: IOException) {
            pending = receipt
            problem = HifzSessionProblem(HifzSessionProblemKind.AnswerSave, "تعذّر حفظ الإجابة. أعد المحاولة؛ لن تُحسب الإجابة مرتين.")
        } finally {
            committing = false
            busy = false
            reviewState.inputBlocked = pending != null
            refresh(); changed()
        }
    }

    fun retry() { if (!busy) scope.launch {
        if (pending != null) finishPending()
        else if (problem?.kind == HifzSessionProblemKind.PositionSave) persistSafely()
    } }
    fun dismissError() {
        if (problem?.kind != HifzSessionProblemKind.AnswerSave) problem = null
        changed()
    }

    private fun changed() { Snapshot.sendApplyNotifications(); revision.update { it + 1 } }

    private fun <T : Any> saved(saver: Saver<T, Any>, state: T): JSONArray =
        JSONArray((with(saver) { saverScope.save(state) } as? List<*>) ?: emptyList<Any>())
    private fun <T : Any> restored(saver: Saver<T, Any>, rows: JSONArray): T? =
        saver.restore(List(rows.length()) { rows.optString(it) })

    private fun encode() = JSONObject().put("version", 1).put("id", sessionId).put("mode", mode.name).put("origin", origin.name)
        .put("dailyIds", JSONArray(reviewIds.value)).put("generation", reviewSession.intValue)
        .put("day", reviewStartedDay.longValue).put("dayDraft", reviewDayDraft.value)
        .put("review", saved(HifzReviewState.Saver, reviewState))
        .put("return", reviewReturn.value).put("played", reviewPlayed.value).put("clip", clip?.encode())
        .put("position", clipPosition).put("clipIndex", clipIndex).put("clipRound", clipRound).put("clipEnded", clipEnded)
        .put("clipReciter", clipReciterId)
        .put("clipSurah", clipSurah)
        .put("pending", pending?.encode())
        .put("lastReceiptAt", lastReceiptAt)

    private fun restore(row: JSONObject) {
        require(row.getInt("version") == 1)
        sessionId = row.getString("id")
        // A review of due points saved before 16 November reads on; a saved «مراجعة الدفتر», whose cards were kept
        // apart, does not (development phase: no migration), and the notebook is kept.
        mode = when (val saved = row.getString("mode")) {
            "Daily" -> HifzSessionMode.Review
            else -> HifzSessionMode.valueOf(saved)
        }
        origin = HifzReviewOrigin.valueOf(row.optString("origin", HifzReviewOrigin.Daily.name))
        reviewIds.value = row.getJSONArray("dailyIds").let { ids -> List(ids.length()) { ids.getLong(it) } }
        reviewSession.intValue = row.optInt("generation")
        reviewStartedDay.longValue = row.getLong("day")
        reviewDayDraft.value = row.opt("dayDraft") as? String
        reviewState = restored(HifzReviewState.Saver, row.getJSONArray("review")) ?: error("Invalid review state")
        reviewReturn.value = row.opt("return") as? String
        reviewPlayed.value = row.optBoolean("played")
        clip = QuranPlaylist.decode(row.opt("clip") as? String)
        clipPosition = row.optLong("position")
        clipIndex = row.optInt("clipIndex")
        clipRound = row.optInt("clipRound", 1)
        clipEnded = row.optBoolean("clipEnded")
        clipReciterId = row.optString("clipReciter", QuranAudioController.DEFAULT_RECITER_ID)
        clipSurah = row.optInt("clipSurah")
        pending = row.optJSONObject("pending")?.let(PendingGrade::decode)
        lastReceiptAt = maxOf(row.optLong("lastReceiptAt"), pending?.at ?: 0L,
            HifzRepository.points.value.flatMap { it.checks }.maxOfOrNull { it.at } ?: 0L)
    }

    suspend fun persist() {
        if (!initialized) return
        val encoded = encode().toString()
        val request = ++version
        withContext(Dispatchers.IO) {
            synchronized(persistenceLock) {
                if (request > writtenVersion) {
                    if (!preferences.edit().putString("session", encoded).commit()) throw IOException("Session commit failed")
                    writtenVersion = request
                }
            }
        }
    }

    /** A playback service can stop without cancelling the shared session's final save. */
    fun persistAsync() = scope.launch { persistSafely() }

    private suspend fun persistSafely() {
        try {
            persist()
            if (problem?.kind == HifzSessionProblemKind.PositionSave) { problem = null; changed() }
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (problem?.kind != HifzSessionProblemKind.AnswerSave) {
                problem = HifzSessionProblem(HifzSessionProblemKind.PositionSave, "تعذّر حفظ موضع توقّفك في المراجعة. إجاباتك المحفوظة باقية.")
            }
            changed()
        }
    }

    fun dispose() {
        learningListener?.let { context.getSharedPreferences("hifz_learning", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(it) }
        scope.cancel()
    }

    private data class PendingGrade(val mode: HifzSessionMode, val point: HifzPoint, val grade: HifzGrade,
        val counted: Boolean, val outcome: String, val day: Long, val at: Long, var applied: Boolean = false) {
        fun encode() = JSONObject().put("mode", mode.name).put("point", encodeHifzPoints(listOf(point)))
            .put("grade", grade.name).put("counted", counted).put("outcome", outcome).put("day", day).put("at", at).put("applied", applied)
        companion object {
            fun decode(row: JSONObject) = PendingGrade(HifzSessionMode.Review,
                decodeHifzPoints(row.getString("point")).single(), HifzGrade.valueOf(row.getString("grade")),
                row.getBoolean("counted"), row.getString("outcome"), row.getLong("day"), row.getLong("at"), row.optBoolean("applied"))
        }
    }
}
