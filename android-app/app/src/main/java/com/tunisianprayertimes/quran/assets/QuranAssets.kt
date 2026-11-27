package com.tunisianprayertimes.quran.assets

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the Quran's large files wherever this install keeps them: inside the app when the build
 * bundles them, otherwise in packs from Google Play (Play installs, for the packs Play carries)
 * or from the quran-cdn Worker.
 */
object QuranAssets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var source: QuranPackSource? = null
    private val bundledFolders = ConcurrentHashMap<String, Set<String>>()

    /** Null when this build bundles every Quran file. IO thread. */
    fun layout(context: Context): QuranPackLayout? = QuranPackLayout.load(context.applicationContext)

    /** Null when this build bundles every Quran file. IO thread, the first time. */
    fun source(context: Context): QuranPackSource? {
        val layout = layout(context) ?: return null
        return source ?: synchronized(this) {
            source ?: createSource(context.applicationContext, layout).also { source = it }
        }
    }

    /** Where [assetPath] can be read on this device, or null until its pack is downloaded. IO thread. */
    fun resolve(context: Context, assetPath: String): QuranAssetSource? {
        if (isBundled(context, assetPath)) return QuranAssetSource.Bundled(assetPath)
        val pack = layout(context)?.packOf(assetPath) ?: return null
        val source = source(context) ?: return null
        val folder = source.directory(pack) ?: return null
        val file = File(folder, assetPath)
        if (file.isFile) return QuranAssetSource.Downloaded(file)
        // A pack on the device without one of its files is damaged: drop it, so that it reads as
        // missing and the next request downloads it again instead of finding nothing forever.
        Log.w(TAG, "${pack.name} lacks $assetPath; removing it so it is downloaded again")
        source.remove(listOf(pack))
        return null
    }

    /** Whether [assetPath] can come from a download at all; false means no build part carries it. IO thread. */
    fun downloadable(context: Context, assetPath: String): Boolean = layout(context)?.packOf(assetPath) != null

    /**
     * Whether this build carries the book (the mushaf in the print's own type) itself, as a debug build made with
     * -PquranFonts does: its pages are then drawn from the app, whatever pack the layout names for them. IO thread.
     */
    fun bookBundled(context: Context): Boolean = isBundled(context, "quran/book/plates.json")

    /** Packs whose every file this build carries itself; they never need a download. IO thread. */
    fun bundledPacks(context: Context): Set<String> =
        layout(context)?.packs.orEmpty().filter { pack -> pack.files.all { isBundled(context, it.path) } }.map { it.name }.toSet()

    private fun isBundled(context: Context, assetPath: String): Boolean {
        val folder = assetPath.substringBeforeLast('/', "")
        val names = bundledFolders.getOrPut(folder) {
            runCatching { context.assets.list(folder)?.toSet() }.getOrNull().orEmpty()
        }
        return assetPath.substringAfterLast('/') in names
    }

    internal fun cdnRoot(context: Context): File = File(context.noBackupFilesDir, "quran-packs")

    private fun createSource(context: Context, layout: QuranPackLayout): QuranPackSource {
        val cdn = lazy { CdnQuranPackSource(context, layout, scope) }
        val fromPlay = layout.playPacks()
        if (!installedByPlay(context) || fromPlay.packs.isEmpty()) return cdn.value
        return try {
            PlayOrCdnPackSource(layout, cdn, QuranPackInstaller(cdnRoot(context)), scope) { onUnavailable ->
                PlayQuranPackSource(fromPlay, AssetPackManagerFactory.getInstance(context), scope, onUnavailable)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Play Asset Delivery is unavailable; using the quran-cdn Worker", error)
            cdn.value
        }
    }

    /** Play delivers packs to installs it made, and to bundletool's local testing. */
    internal fun installedByPlay(context: Context): Boolean {
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()
        if (installer == PLAY_STORE) return true
        val metadata = runCatching {
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
        }.getOrNull()
        return metadata?.containsKey(LOCAL_TESTING) == true
    }

    private const val TAG = "QuranPacks"
    private const val PLAY_STORE = "com.android.vending"
    // Added to the manifest by bundletool build-apks --local-testing.
    private const val LOCAL_TESTING = "local_testing_dir"
}

/**
 * For Play installs. Play's packs come through Play Asset Delivery until Play reports it can never
 * serve this install, then from the quran-cdn Worker; packs Play does not carry always come from
 * the Worker. Play packs a launch downloaded from the Worker stay usable when a later launch uses Play.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayOrCdnPackSource(
    private val layout: QuranPackLayout,
    private val cdn: Lazy<QuranPackSource>,
    /** Finds packs from the Worker without starting its downloads. */
    private val cdnInstaller: QuranPackInstaller,
    private val scope: CoroutineScope,
    createPlay: (onUnavailable: () -> Unit) -> QuranPackSource,
) : QuranPackSource {
    private val usePlay = MutableStateFlow(true)
    // A retained copy can be current or an older, still readable commentary.
    private val fromCdn = MutableStateFlow<Map<String, QuranPackState>>(emptyMap())
    // What Play was asked for and whether mobile data was allowed; the Worker takes over these requests.
    private val askedOfPlay = ConcurrentHashMap<String, Boolean>()
    private val play = createPlay(::switchToCdn)
    // Packs only the Worker serves, whichever source the Play packs come from.
    private val workerOnly = layout.packs.filter { it.delivery == QuranPack.Delivery.Cdn }.map { it.name }.toSet()
    private val removedTafsir = MutableStateFlow(false)
    private val playTafsir = layout.tafsir?.takeIf { it.name !in workerOnly }
    private val checkingBook = QuranPackState(QuranPackStatus.Transferring)
    private val checkedBook = MutableStateFlow(checkingBook)
    // Used if the private copy could not be written, e.g. on a full phone.
    @Volatile private var verifiedPlayFile: Pair<File, Long>? = null
    private val checkedPlayStates = combine(play.states, checkedBook) { states, checked ->
        if (playTafsir != null && states[playTafsir.name]?.status == QuranPackStatus.Available)
            states + (playTafsir.name to checked)
        else states
    }

    init {
        scanCdn()
        playTafsir?.let { pack ->
            // Play controls the lifetime of its pack directory on updates. Keep the completed
            // edition in private storage even if the reader hasn't opened this book yet.
            scope.launch(Dispatchers.IO) {
                combine(play.states, removedTafsir) { states, removed ->
                    !removed && states[pack.name]?.status == QuranPackStatus.Available
                }.distinctUntilChanged().collect { delivered ->
                    verifiedPlayFile = null
                    checkedBook.value = checkingBook
                    if (delivered && !removedTafsir.value) {
                        checkedBook.value = retainDeliveredBook(pack)
                        // A delete can finish while the copy is streaming.
                        if (removedTafsir.value) cdnInstaller.remove(pack)
                        scanCdn()
                    }
                }
            }
        }
    }

    override val states: StateFlow<Map<String, QuranPackState>> = usePlay.flatMapLatest { viaPlay ->
        if (viaPlay) {
            val playPacks = combine(checkedPlayStates, fromCdn) { fromPlay, stored ->
                fromPlay + stored.mapValues { (name, local) ->
                    val current = fromPlay[name] ?: QuranPackState.Missing
                    when {
                        current.status == QuranPackStatus.Available -> current
                        local.previousVersion -> current.copy(previousVersion = true)
                        else -> local
                    }
                }
            }
            if (workerOnly.isEmpty()) playPacks
            else combine(playPacks, cdn.value.states) { fromPlay, fromWorker -> fromPlay + fromWorker.filterKeys { it in workerOnly } }
        } else combine(checkedPlayStates, cdn.value.states) { fromPlay, fromWorker -> fromWorker + fromPlay.filterValues { it.available } }
    // Until the sources have answered, availability is unknown. A synthetic Missing map would
    // briefly show a download prompt for packs already on the device during a cold launch.
    }.combine(removedTafsir) { states, removed ->
        if (removed && playTafsir != null) states + (playTafsir.name to QuranPackState.Missing) else states
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val active: QuranPackSource get() = if (usePlay.value) play else cdn.value

    private fun retainDeliveredBook(pack: QuranPack): QuranPackState {
        if (cdnInstaller.isInstalled(pack)) return QuranPackState.Available
        val source = play.directory(pack) ?: return QuranPackState(QuranPackStatus.Failed, problem = QuranDownloadProblem.Integrity)
        try {
            cdnInstaller.retainPlayTafsir(pack, source)
            if (cdnInstaller.isInstalled(pack)) return QuranPackState.Available
        } catch (error: java.io.IOException) {
            Log.w("QuranPacks", "Could not retain ${pack.name} in private storage", error)
            val entry = pack.files.single()
            val file = File(source, entry.path)
            if (file.isFile && file.length() == entry.bytes && entry.sha256 != null &&
                runCatching { QuranPackInstaller.sha256(file) }.getOrNull() == entry.sha256) {
                verifiedPlayFile = file to file.lastModified()
                return QuranPackState.Available
            }
        } catch (error: QuranPackException) {
            return QuranPackState(QuranPackStatus.Failed, problem = error.problem)
        }
        return QuranPackState(QuranPackStatus.Failed, problem = QuranDownloadProblem.Integrity)
    }

    // The active source's copy first, so that repairing a damaged copy never removes the other one.
    override fun directory(pack: QuranPack): File? {
        if (pack == playTafsir && removedTafsir.value) return null
        val fromWorker = { cdnInstaller.readableDirectory(pack) }
        if (pack == playTafsir) {
            if (cdnInstaller.isInstalled(pack)) return cdnInstaller.installedDirectory(pack)
            verifiedPlayFile?.let { (file, modified) ->
                val source = play.directory(pack)
                val entry = pack.files.single()
                if (source != null && file == File(source, entry.path) && file.length() == entry.bytes && file.lastModified() == modified) return source
            }
            return fromWorker()
        }
        return when {
            pack.name in workerOnly -> fromWorker()
            usePlay.value -> play.directory(pack) ?: fromWorker()
            else -> fromWorker() ?: play.directory(pack)
        }
    }

    override fun fetch(packs: List<QuranPack>, allowMetered: Boolean) {
        val (fromWorker, fromPlay) = packs.partition { it.name in workerOnly }
        if (fromWorker.isNotEmpty()) cdn.value.fetch(fromWorker, allowMetered)
        if (fromPlay.isEmpty()) return
        if (playTafsir != null && playTafsir in fromPlay) {
            if (usePlay.value && checkedBook.value.status == QuranPackStatus.Failed) play.remove(listOf(playTafsir))
            removedTafsir.value = false
        }
        if (!usePlay.value) return cdn.value.fetch(fromPlay, allowMetered)
        fromPlay.forEach { pack -> askedOfPlay.merge(pack.name, allowMetered, Boolean::or) }
        play.fetch(fromPlay, allowMetered)
    }

    override fun confirmMobileData(packs: List<QuranPack>, launcher: ActivityResultLauncher<IntentSenderRequest>) {
        val (fromWorker, fromPlay) = packs.partition { it.name in workerOnly }
        if (fromWorker.isNotEmpty()) cdn.value.confirmMobileData(fromWorker, launcher)
        // Play's dialog covers every pack it holds back, so only ask when one of these waits.
        val waiting = fromPlay.filter { states.value[it.name]?.status in WAITING }
        if (waiting.isNotEmpty()) active.confirmMobileData(waiting, launcher)
    }

    override fun remove(packs: List<QuranPack>) {
        val (fromWorker, fromPlay) = packs.partition { it.name in workerOnly }
        if (fromWorker.isNotEmpty()) cdn.value.remove(fromWorker)
        if (fromPlay.isEmpty()) return
        if (playTafsir in fromPlay) removedTafsir.value = true
        fromPlay.forEach { askedOfPlay.remove(it.name) }
        play.remove(fromPlay)
        if (!usePlay.value) cdn.value.remove(fromPlay)
        else scope.launch(Dispatchers.IO) {
            fromPlay.forEach(cdnInstaller::remove)
            scanCdn()
        }
    }

    override fun cancel(packs: List<QuranPack>) {
        val (fromWorker, fromPlay) = packs.partition { it.name in workerOnly }
        if (fromWorker.isNotEmpty()) cdn.value.cancel(fromWorker)
        if (fromPlay.isEmpty()) return
        fromPlay.forEach { askedOfPlay.remove(it.name) }
        active.cancel(fromPlay)
    }

    override fun refresh() {
        active.refresh()
        if (usePlay.value && workerOnly.isNotEmpty()) cdn.value.refresh()
        scanCdn()
    }

    private fun scanCdn() {
        scope.launch(Dispatchers.IO) {
            fromCdn.value = buildMap {
                for (pack in layout.packs) {
                    if (pack.name in workerOnly) continue
                    if (cdnInstaller.isInstalled(pack)) put(pack.name, QuranPackState.Available)
                    else if (pack == playTafsir && cdnInstaller.readableDirectory(pack) != null) {
                        put(pack.name, QuranPackState.Missing.copy(previousVersion = true))
                    }
                }
            }
        }
    }

    /** Play will never deliver to this install: the Worker does, starting with what Play was asked for. */
    private fun switchToCdn() {
        if (!usePlay.compareAndSet(true, false)) return
        val asked = askedOfPlay.toMap()
        askedOfPlay.clear()
        val delivered = play.states.value.filter { (name, state) ->
            state.status == QuranPackStatus.Available && (name != playTafsir?.name || checkedBook.value.status == QuranPackStatus.Available)
        }.keys
        val handover = layout.packs.filter { it.name in asked && it.name !in delivered }.groupBy { asked.getValue(it.name) }
        scope.launch {
            // The Worker's source decides how to queue from its states; wait until it has read them.
            val worker = cdn.value
            worker.states.first { it.isNotEmpty() }
            handover.forEach { (allowMetered, packs) -> worker.fetch(packs, allowMetered) }
        }
    }

    private companion object {
        val WAITING = setOf(QuranPackStatus.WaitingForWifi, QuranPackStatus.NeedsConfirmation)
    }
}
