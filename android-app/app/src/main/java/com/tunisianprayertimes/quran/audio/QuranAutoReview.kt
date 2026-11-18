package com.tunisianprayertimes.quran.audio

import android.content.Context
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.PlaybackState
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.assets.QuranAssets
import com.tunisianprayertimes.quran.hifz.*
import com.tunisianprayertimes.ui.hifzPointName
import kotlinx.coroutines.*
import java.util.UUID

/** Android Auto is an adapter to the same Hifz state machines used by QuranScreen. */
internal class QuranAutoReview(
    private val context: Context,
    val sessions: HifzSessionCoordinator,
    private val scope: CoroutineScope,
    private val player: Player,
) : HifzSessionAudio {
    interface Player {
        fun foreground()
        fun idle()
        fun pause()
        fun resume()
        fun play(list: QuranPlaylist, reciter: String, index: Int, round: Int, position: Long?, surah: Int?)
        fun restore(place: String?)
        fun refresh()
    }
    private var selected = false
    private var request: Job? = null
    private var preparing = false
    private var listenId: String? = null
    private var lastMediaId = DAILY_ID
    val active get() = selected || sessions.hasSession && (sessions.carActive || sessions.active)
    val listeningOnly get() = listenId != null && QuranAudioController.state.value.playlist?.requestId == listenId
    fun owns(requestId: String?) = sessions.owns(requestId) || requestId != null && requestId == listenId

    fun start(mediaId: String) {
        lastMediaId = mediaId
        request?.cancel()
        player.pause()
        player.foreground()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                sessions.initialize()
                sessions.useCar()
                if (mediaId == LISTEN_ID) {
                    sessions.pause()
                    sessions.leaveCar()
                    selected = false
                    // As the phone's «الاستماع إلى المواضع» chose last: what waits for its question is asked first.
                    val settings = HifzListenSettings.read(context)
                    val list = sessions.catalog?.let { hifzListen(it, sessions.listenableNow().toHear(settings), settings) }
                    if (list != null) {
                        listenId = "auto-hifz-listen:${UUID.randomUUID()}"
                        prepare(list.copy(requestId = listenId), QuranAudioController.state.value.reciterId)
                    } else {
                        selected = true
                        sessions.inform(context.getString(if (HifzRepository.points.value.isEmpty()) R.string.quran_auto_review_empty
                            else R.string.quran_auto_review_listen_waiting))
                    }
                } else {
                    selected = true
                    val ready = when (mediaId) {
                        DAILY_ID -> sessions.startDaily()
                        // The whole notebook, in the phone's order; an unfinished list review goes on.
                        NOTEBOOK_ID -> sessions.startList()
                        else -> {
                            if (!sessions.hasSession) sessions.inform(context.getString(R.string.quran_auto_review_no_session))
                            sessions.resume(); sessions.hasSession
                        }
                    }
                    if (ready) onPlay() else player.idle()
                }
                player.refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { sessions.audioError(context.getString(R.string.quran_auto_review_failed)); player.idle() }
        }
        request = job; job.start()
    }

    fun onPlay() {
        selected = true
        sessions.useCar()
        sessions.resume()
        if (sessions.savingAnswer) { sessions.retry(); return }
        val saved = sessions.resumableClip()
        if (saved != null) {
            val current = QuranAudioController.state.value
            if (sessions.owns(current.playlist?.requestId) && !current.notDownloaded && current.error == null) player.resume()
            else prepare(saved.first, sessions.savedClipReciter(), saved.second, saved.third,
                sessions.savedClipPosition(), sessions.savedClipSurah())
        } else when (sessions.view().phase) {
            // Never the verse of another question still waiting.
            HifzSessionPhase.Question -> if (!sessions.view().cue || sessions.list("cue") == null) player.idle() else sessions.cue()
            HifzSessionPhase.Assessment -> sessions.answerAudio()
            HifzSessionPhase.Training -> sessions.drill()
            HifzSessionPhase.Answered, HifzSessionPhase.Finished -> player.idle()
        }
        player.refresh()
    }

    fun pauseAudio() {
        request?.cancel(); preparing = false
        player.pause()
        sessions.notePlayback(QuranAudioController.state.value, force = true)
        sessions.persistAsync()
        player.refresh()
    }

    fun stop() { pauseAudio(); sessions.pause(); player.idle(); player.refresh() }

    fun leaveForListening() {
        val leaving = active
        request?.cancel(); preparing = false
        listenId = null
        if (!leaving) return
        sessions.pause()
        sessions.leaveCar()
        sessions.reviewReturn.value = null
        sessions.reviewPlayed.value = false
        sessions.reviewAudio.value = false
        selected = false
    }

    fun detach() {
        // A phone review shares the session, but is not owned by this car adapter.
        if (sessions.carActive) { stop(); sessions.leaveCar() }
        selected = false
    }

    override fun play(list: QuranPlaylist) { prepare(list, sessions.savedClipReciter()) }
    override fun pause() { player.pause() }
    override fun restore(place: String?) { request?.cancel(); preparing = false; selected = false; player.restore(place) }

    private fun prepare(list: QuranPlaylist, reciterId: String, index: Int = 0, round: Int = 1,
        position: Long? = null, surah: Int? = null, ready: (() -> Unit)? = null) {
        request?.cancel()
        player.foreground()
        preparing = true
        player.refresh()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val available = withContext(Dispatchers.IO) {
                    val tracks = QuranRecitationTimings.load(context, QuranAudioController.reciter(reciterId))
                    list.items.all { range ->
                        tracks[range.from.surah - 1].startOf(range.from.ayah) != null &&
                            tracks[range.to.surah - 1].endOf(range.to.ayah) != null &&
                            (range.from.surah..range.to.surah).all { QuranAssets.resolve(context, tracks[it - 1].assetPath) != null }
                    }
                }
                preparing = false
                if (list.requestId?.startsWith("hifz-session:") == true &&
                    (!sessions.owns(list.requestId) || !sessions.active || !sessions.carActive)) return@launch
                if (!available) {
                    sessions.audioError(context.getString(R.string.quran_auto_review_download))
                    player.idle(); player.refresh(); return@launch
                }
                if (sessions.problem?.kind == HifzSessionProblemKind.Audio) sessions.dismissError()
                if (ready != null) ready() else player.play(list, reciterId, index, round, position, surah)
            } catch (cancelled: CancellationException) { preparing = false; throw cancelled }
            catch (_: Exception) {
                preparing = false
                sessions.audioError(context.getString(R.string.quran_auto_review_failed))
                player.idle()
            }
            player.refresh()
        }
        request = job; job.start()
    }

    /** The action itself names its question and phase. A late callback cannot assess the next question. */
    fun customAction(action: String): Boolean {
        if (!action.startsWith(ACTION_PREFIX)) return false
        val view = sessions.view()
        val prefix = "$ACTION_PREFIX${view.token}/"
        if (!active || !action.startsWith(prefix) || sessions.busy) return true
        sessions.useCar()
        when (action.removePrefix(prefix)) {
            "reveal" -> sessions.list("answer")?.let { list ->
                prepare(list, QuranAudioController.state.value.reciterId, ready = {
                    if (sessions.view().token == view.token && sessions.reveal(exposeAnswer = false)) sessions.answerAudio()
                })
            }
            "held" -> sessions.assess(HifzGrade.Held)
            "hesitated" -> sessions.assess(HifzGrade.Hesitated)
            "again" -> sessions.assess(HifzGrade.Again)
            "drill" -> sessions.drill()
            "cue" -> sessions.cue()
            "retry" -> if (sessions.savingAnswer || sessions.problem?.retrySave == true) sessions.retry()
                else if (!sessions.hasSession || lastMediaId == LISTEN_ID) start(lastMediaId)
                else { sessions.dismissError(); onPlay() }
            "dismiss" -> { sessions.dismissError(); sessions.refresh() }
            "return" -> { sessions.close(); selected = false }
            "continue" -> { sessions.resume(); player.refresh() }
            "more" -> { sessions.startDaily(restart = true); player.refresh() }
        }
        player.refresh()
        return true
    }

    fun next() { if (!sessions.savingAnswer) sessions.next(); player.refresh() }
    fun previous() { if (!sessions.savingAnswer) sessions.previous(); player.refresh() }

    fun playbackState(): PlaybackState {
        val view = sessions.view()
        val current = QuranAudioController.state.value
        val audio = owns(current.playlist?.requestId)
        val failure = sessions.error ?: current.error?.takeIf { audio }
        val status = when {
            failure != null -> PlaybackState.STATE_ERROR
            sessions.busy || preparing || audio && current.loading -> PlaybackState.STATE_BUFFERING
            audio && current.playing -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH
        if (audio) actions = actions or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_REWIND
        // As on the phone: on past a card before «أظهر» or once answered, and back to one; never between «أظهر» and the answer.
        if (view.phase == HifzSessionPhase.Training || view.canForward) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (view.canBack) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val state = PlaybackState.Builder().setActions(actions)
            .setState(status, if (audio) current.positionMs else 0L, if (audio && current.playing) 1f else 0f)
        fun add(command: String, label: Int, icon: Int) {
            state.addCustomAction(PlaybackState.CustomAction.Builder("$ACTION_PREFIX${view.token}/$command", context.getString(label), icon).build())
        }
        if (sessions.savingAnswer || sessions.problem?.retrySave == true || sessions.problem?.kind == HifzSessionProblemKind.Audio || audio && current.error != null) {
            add("retry", R.string.quran_auto_review_retry, R.drawable.ic_quran_repeat)
        } else if (failure != null) add("dismiss", R.string.quran_auto_review_dismiss, R.drawable.ic_adhkar_close)
        else when (view.phase) {
            HifzSessionPhase.Question -> {
                add("reveal", R.string.quran_auto_review_reveal, R.drawable.ic_quran_auto_reveal)
                if (view.cue) add("cue", R.string.quran_auto_review_cue, R.drawable.ic_quran_headphones)
            }
            HifzSessionPhase.Assessment -> {
                if (!view.assisted) add("held", R.string.quran_auto_review_held, R.drawable.ic_quran_auto_held)
                add("hesitated", R.string.quran_auto_review_hesitated, R.drawable.ic_quran_auto_hesitated)
                add("again", R.string.quran_auto_review_again, R.drawable.ic_quran_auto_again)
            }
            HifzSessionPhase.Training -> add("drill", R.string.quran_auto_review_drill, R.drawable.ic_quran_repeat)
            // Answered earlier in this review: nothing to grade again; skip on or back.
            HifzSessionPhase.Answered -> Unit
            HifzSessionPhase.Finished -> {
                if (sessions.reviewState.stopping) add("continue", R.string.quran_auto_review_continue, R.drawable.ic_play_arrow)
                // Only with something due since the review began, or due again: never an action with nothing to ask.
                else if (sessions.anythingDue()) add("more", R.string.quran_auto_review_more, R.drawable.ic_quran_repeat)
            }
        }
        add("return", R.string.quran_auto_review_return, R.drawable.ic_stop)
        failure?.let(state::setErrorMessage)
        return state.build()
    }

    fun metadata(): MediaMetadata {
        val view = sessions.view()
        val current = QuranAudioController.state.value
        val title = when {
            (sessions.error != null || sessions.notice != null) && view.point == null -> context.getString(R.string.quran_auto_review)
            view.phase == HifzSessionPhase.Finished -> context.getString(R.string.quran_auto_review_result)
            view.phase == HifzSessionPhase.Question -> view.question
            else -> view.point?.let { p -> sessions.catalog?.let { hifzPointName(it, p) } }.orEmpty()
        }
        val subtitle = sessions.error ?: sessions.notice ?: when (view.phase) {
            HifzSessionPhase.Question -> context.getString(R.string.quran_auto_review_question_hint)
            HifzSessionPhase.Assessment -> context.getString(if (view.assisted) R.string.quran_auto_review_assisted_hint else R.string.quran_auto_review_assessment_hint)
            HifzSessionPhase.Training -> context.getString(R.string.quran_auto_review_training_hint)
            // What the answer given did, as said when it was given.
            HifzSessionPhase.Answered -> sessions.reviewState.outcome(view.point).orEmpty()
            HifzSessionPhase.Finished -> "دون مساعدة ${view.tally.held} · بتردّد ${view.tally.hesitated} · نسيت ${view.tally.again}" +
                if (view.deferred > 0) " · مؤجّلة ${view.deferred}" else ""
        }
        val progress = if (view.point != null) "السؤال ${view.index + 1} من ${view.total}" else ""
        // «مراجعة اليوم» names the due questions; a list review needs no name (D5).
        val about = if (view.origin == HifzReviewOrigin.Daily) listOf(context.getString(R.string.quran_auto_review_daily), progress)
            else listOf(progress)
        val description = about.filter { it.isNotEmpty() }.joinToString(" · ")
        return MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID, CONTINUE_ID)
            .putString(MediaMetadata.METADATA_KEY_TITLE, title).putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, subtitle).putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, description)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, description)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, QuranAutoBrowse.iconUri(context).toString())
            .putLong(MediaMetadata.METADATA_KEY_DURATION, if (owns(current.playlist?.requestId)) current.durationMs else 0L).build()
    }

    companion object {
        const val PARENT_ID = "quran/hifz"
        const val DAILY_ID = "quran/hifz/daily"
        const val NOTEBOOK_ID = "quran/hifz/notebook"
        const val CONTINUE_ID = "quran/hifz/continue"
        const val LISTEN_ID = "quran/hifz/listen"
        const val ACTION_PREFIX = "quran.hifz/"
        fun handles(id: String) = id in setOf(DAILY_ID, NOTEBOOK_ID, CONTINUE_ID, LISTEN_ID)
        fun choices(context: Context, sessions: HifzSessionCoordinator): List<MediaBrowser.MediaItem> = buildList {
            fun addItem(id: String, title: Int, subtitle: String) = add(MediaBrowser.MediaItem(
                MediaDescription.Builder().setMediaId(id).setTitle(context.getString(title)).setSubtitle(subtitle)
                    .setIconUri(QuranAutoBrowse.iconUri(context)).build(), MediaBrowser.MediaItem.FLAG_PLAYABLE))
            if (sessions.hasSession) addItem(CONTINUE_ID, R.string.quran_auto_review_continue, sessions.view().question)
            addItem(DAILY_ID, R.string.quran_auto_review_daily, context.getString(R.string.quran_auto_review_daily_hint))
            addItem(NOTEBOOK_ID, R.string.quran_auto_review_notebook, context.getString(R.string.quran_auto_review_notebook_hint))
            addItem(LISTEN_ID, R.string.quran_auto_review_listen, context.getString(R.string.quran_auto_review_listen_hint))
        }
    }
}
