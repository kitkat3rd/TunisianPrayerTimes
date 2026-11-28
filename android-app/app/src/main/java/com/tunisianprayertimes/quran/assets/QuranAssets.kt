package com.tunisianprayertimes.quran.assets

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.flow.update
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

    /** Identifies the verified database actually open, including an older edition retained on the phone. IO thread. */
    fun revision(context: Context, assetPath: String): String? {
        val pack = layout(context)?.packOf(assetPath)?.takeIf { it.kind == QuranPack.Kind.Tafsir } ?: return null
        val source = source(context) ?: return null
        if (source is PlayOrCdnPackSource) return source.databaseRevision(pack)
        return source.directory(pack)?.let { privateRevision(pack, it) }
    }

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
        // Earlier debug installs expanded the bundled commentary into this private cache. Migrate
        // a matching, verified copy before exposing the new downloads list to the reader.
        layout.tafsir("ashur")?.let { pack ->
            try {
                QuranPackInstaller(cdnRoot(context)).migrateBundledTafsir(pack, File(context.cacheDir, "tafsir-database"))
            } catch (error: java.io.IOException) {
                Log.w(TAG, "Could not retain the unpacked Ibn Ashur edition", error)
            } catch (error: QuranPackException) {
                Log.w(TAG, "Could not verify the unpacked Ibn Ashur edition", error)
            }
        }
        val cdn = lazy { CdnQuranPackSource(context, layout, scope) }
        val fromPlay = layout.playPacks()
        if (!installedByPlay(context) || (fromPlay.packs.isEmpty() && layout.tafsir("ashur") == null)) return cdn.value
        return try {
            PlayOrCdnPackSource(layout, cdn, QuranPackInstaller(cdnRoot(context)), scope) { onUnavailable, onPacksUnavailable ->
                PlayQuranPackSource(fromPlay, AssetPackManagerFactory.getInstance(context), scope, onUnavailable, onPacksUnavailable)
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

private fun privateRevision(pack: QuranPack, folder: File): String =
    if (folder.name == "${pack.name}-${pack.archive.sha256.take(12)}") pack.files.single().sha256 ?: folder.name
    else folder.name // The content-addressed directory of an earlier authenticated archive.

/**
 * For Play installs. A rejected book can switch to its published CDN archive while other downloads
 * stay with Play. If Play cannot serve this installation, the Worker takes over the outstanding requests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayOrCdnPackSource(
    private val layout: QuranPackLayout,
    private val cdn: Lazy<QuranPackSource>,
    /** Finds packs from the Worker without starting its downloads. */
    private val cdnInstaller: QuranPackInstaller,
    private val scope: CoroutineScope,
    createPlay: (onUnavailable: () -> Unit, onPacksUnavailable: (Set<String>) -> Unit) -> QuranPackSource,
) : QuranPackSource {
    constructor(
        layout: QuranPackLayout, cdn: Lazy<QuranPackSource>, cdnInstaller: QuranPackInstaller, scope: CoroutineScope,
        createPlay: (onUnavailable: () -> Unit) -> QuranPackSource,
    ) : this(layout, cdn, cdnInstaller, scope, { unavailable, _ -> createPlay(unavailable) })

    private val byName = layout.packs.associateBy { it.name }
    private val playNames = layout.playPacks().packs.map { it.name }.toSet()
    // Historical CDN-only layouts can also have a physically installed Play copy of Ibn Ashur.
    private val padBooks = layout.tafsirPacks.filter { it.name in playNames || it.tafsirBookId == "ashur" }.associateBy { it.name }
    private val cdnNames = MutableStateFlow(byName.keys - playNames)
    private val checked = MutableStateFlow<Map<String, QuranPackState>>(emptyMap())
    private val retained = MutableStateFlow<Map<String, QuranPackState>>(emptyMap())
    private val removed = MutableStateFlow<Set<String>>(emptySet())
    private val refreshGeneration = MutableStateFlow(0L)
    private val asked = ConcurrentHashMap<String, Boolean>()
    private val routeLock = Any()
    private val scanLock = Any()
    private val updatingDirect = ConcurrentHashMap.newKeySet<String>()
    private val ready = CompletableDeferred<QuranPackSource>()
    private val play = createPlay(::switchToCdn, ::packsUnavailable).also { ready.complete(it) }
    private val checking = QuranPackState(QuranPackStatus.Transferring)

    // On a full phone a verified Play-managed file remains readable without a private duplicate.
    private data class VerifiedFile(val file: File, val bytes: Long, val modified: Long, val sha256: String)
    private val direct = ConcurrentHashMap<String, VerifiedFile>()

    init {
        scanRetained()
        for ((name, pack) in padBooks) {
            scope.launch(Dispatchers.IO) {
                combine(play.states, removed, refreshGeneration) { states, deleted, generation ->
                    (name !in deleted && states[name]?.status == QuranPackStatus.Available) to generation
                }.distinctUntilChanged().collect { (delivered, _) ->
                    if (delivered) {
                        checked.update { it + (name to checking) }
                        val result = retainDeliveredBook(pack)
                        if (!result.available) direct.remove(name)
                        checked.update { it + (name to result) }
                        // Play can report the same old edition after an explicit update request.
                        // A published archive can replace it while its verified PAD copy stays open.
                        if (updatingDirect.remove(name) && result.previousVersion && pack.cdnPublished)
                            handOver(setOf(name))
                        if (name in removed.value) cdnInstaller.remove(pack)
                        scanRetained()
                        if (name in cdnNames.value) cdn.value.refresh()
                    } else {
                        // Play may start updating an old book which is still readable directly.
                        // Only an explicit deletion revokes that copy before its bytes change.
                        if (name in removed.value) direct.remove(name)
                        checked.update { it - name }
                    }
                }
            }
        }
    }

    private val playStates = combine(play.states, checked, retained) { reported, verified, local ->
        buildMap {
            for ((name, state) in reported) {
                if (name !in playNames && name !in padBooks) continue
                put(name, if (name in padBooks && state.status == QuranPackStatus.Available) verified[name] ?: checking else state)
            }
            for ((name, copy) in local) put(name, withReadableCopy(get(name), copy))
            for ((name, file) in direct) {
                val pack = padBooks.getValue(name)
                val copy = if (file.sha256 == pack.files.single().sha256) QuranPackState.Available
                    else QuranPackState.Missing.copy(previousVersion = true)
                put(name, withReadableCopy(get(name), copy))
            }
        }
    }

    override val states: StateFlow<Map<String, QuranPackState>> = cdnNames.flatMapLatest { fromCdn ->
        if (fromCdn.isEmpty()) playStates
        else combine(playStates, cdn.value.states) { pad, worker ->
            buildMap {
                putAll(pad.filterKeys { it !in fromCdn })
                putAll(worker.filterKeys { it in fromCdn })
                for ((name, copy) in pad) {
                    if (name in fromCdn && copy.available) put(name, withReadableCopy(get(name), copy))
                }
            }
        }
    }.combine(removed) { states, deleted ->
        if (deleted.isEmpty()) states else states + deleted.associateWith { QuranPackState.Missing }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private fun withReadableCopy(transfer: QuranPackState?, copy: QuranPackState): QuranPackState = when {
        transfer == null || copy.status == QuranPackStatus.Available -> copy
        transfer.status == QuranPackStatus.Available -> transfer
        else -> transfer.copy(previousVersion = true)
    }

    private fun retainDeliveredBook(pack: QuranPack): QuranPackState {
        if (cdnInstaller.isInstalled(pack)) {
            direct.remove(pack.name)
            return QuranPackState.Available
        }
        val invalid = QuranPackState(QuranPackStatus.Failed, problem = QuranDownloadProblem.Integrity)
        val source = play.directory(pack) ?: return invalid
        val file = File(source, pack.files.single().path)
        val modified = file.lastModified()
        val revision = try {
            cdnInstaller.authenticatedPlayRevision(pack, file) ?: return invalid
        } catch (_: java.io.IOException) {
            return invalid
        }
        val current = revision.archive.sha256 == pack.archive.sha256
        val readable = if (current) QuranPackState.Available else QuranPackState.Missing.copy(previousVersion = true)
        try {
            if (current) cdnInstaller.retainPlayTafsir(pack, source)
            else cdnInstaller.retainLegacyPlayAshur(pack, source)
            if (cdnInstaller.isInstalled(pack)) {
                direct.remove(pack.name)
                return QuranPackState.Available
            }
            if (!current && cdnInstaller.readableDirectory(pack) != null) return readable
        } catch (_: java.io.IOException) {
            // Play still owns the verified file when there is no room for a private copy.
        } catch (error: QuranPackException) {
            return QuranPackState(QuranPackStatus.Failed, problem = error.problem)
        }
        val entry = revision.files.single()
        val sha = entry.sha256 ?: return invalid
        if (pack.name in removed.value || !file.isFile || file.length() != entry.bytes || file.lastModified() != modified) return invalid
        direct[pack.name] = VerifiedFile(file, entry.bytes, modified, sha)
        return readable
    }

    /** Private copies are immutable. Recheck a directly opened PAD file if a second copy couldn't fit. */
    private fun directDirectory(pack: QuranPack): File? {
        val cached = direct[pack.name] ?: return null
        val folder = play.directory(pack)
        val file = cached.file
        val same = folder != null && file == File(folder, pack.files.single().path) && file.isFile &&
            file.length() == cached.bytes && file.lastModified() == cached.modified &&
            try { QuranPackInstaller.sha256(file) == cached.sha256 } catch (_: java.io.IOException) { false }
        if (same && pack.name !in removed.value) return folder
        if (direct.remove(pack.name, cached)) refreshGeneration.update { it + 1 }
        return null
    }

    override fun directory(pack: QuranPack): File? {
        if (pack.name in removed.value) return null
        if (pack.name in padBooks) {
            if (cdnInstaller.isInstalled(pack)) return cdnInstaller.installedDirectory(pack)
            return directDirectory(pack) ?: cdnInstaller.readableDirectory(pack)
        }
        val local = { cdnInstaller.readableDirectory(pack) }
        return when {
            pack.name !in playNames -> local()
            pack.name in cdnNames.value -> local() ?: play.directory(pack)
            else -> play.directory(pack) ?: local()
        }
    }

    fun databaseRevision(pack: QuranPack): String? {
        val folder = directory(pack) ?: return null
        val file = direct[pack.name]
        return if (file != null && File(folder, pack.files.single().path) == file.file) file.sha256 else privateRevision(pack, folder)
    }

    override fun fetch(packs: List<QuranPack>, allowMetered: Boolean): Unit = synchronized(routeLock) {
        val names = packs.map { it.name }.toSet()
        removed.update { it - names }
        val (worker, pad) = packs.partition { it.name in cdnNames.value }
        if (worker.isNotEmpty()) {
            worker.forEach { asked.remove(it.name) }
            cdn.value.fetch(worker, allowMetered)
        }
        if (pad.isEmpty()) return@synchronized
        val oldPad = pad.filter { checked.value[it.name]?.previousVersion == true &&
            play.states.value[it.name]?.status == QuranPackStatus.Available }
        val directUpdates = oldPad.filter { it.name in direct.keys }
        val repair = pad.filter { checked.value[it.name]?.status == QuranPackStatus.Failed } + (oldPad - directUpdates.toSet())
        if (repair.isNotEmpty()) play.remove(repair)
        for (pack in pad) asked.merge(pack.name, allowMetered, Boolean::or)
        if (directUpdates.isNotEmpty() && play is PlayQuranPackSource) {
            play.refreshInstalledBooks(directUpdates) { name ->
                // A task can report the same completed pack without an intervening download event.
                if (asked.containsKey(name)) {
                    updatingDirect.add(name)
                    refreshGeneration.update { it + 1 }
                }
            }
            val ordinary = pad - directUpdates.toSet()
            if (ordinary.isNotEmpty()) play.fetch(ordinary, allowMetered)
        } else play.fetch(pad, allowMetered)
    }

    override fun confirmMobileData(packs: List<QuranPack>, launcher: ActivityResultLauncher<IntentSenderRequest>) {
        val (worker, pad) = packs.partition { it.name in cdnNames.value }
        if (worker.isNotEmpty()) cdn.value.confirmMobileData(worker, launcher)
        val waiting = pad.filter { states.value[it.name]?.status in WAITING }
        if (waiting.isNotEmpty()) play.confirmMobileData(waiting, launcher)
    }

    override fun remove(packs: List<QuranPack>): Unit = synchronized(routeLock) {
        val names = packs.map { it.name }.toSet()
        removed.update { it + names.intersect(padBooks.keys) }
        for (name in names) {
            asked.remove(name)
            updatingDirect.remove(name)
            direct.remove(name)
        }
        val (worker, pad) = packs.partition { it.name in cdnNames.value }
        if (worker.isNotEmpty()) cdn.value.remove(worker)
        val playCopies = packs.filter { it.name in playNames || (it.name in padBooks && play.states.value[it.name]?.available == true) }
        if (playCopies.isNotEmpty()) play.remove(playCopies)
        if (pad.isNotEmpty()) scope.launch(Dispatchers.IO) {
            pad.forEach(cdnInstaller::remove)
            scanRetained()
        }
    }

    override fun cancel(packs: List<QuranPack>): Unit = synchronized(routeLock) {
        for (pack in packs) {
            asked.remove(pack.name)
            updatingDirect.remove(pack.name)
        }
        val (worker, pad) = packs.partition { it.name in cdnNames.value }
        if (worker.isNotEmpty()) cdn.value.cancel(worker)
        if (pad.isNotEmpty()) play.cancel(pad)
    }

    override fun refresh() {
        if (playNames.any { it !in cdnNames.value } || padBooks.isNotEmpty()) play.refresh()
        if (cdnNames.value.isNotEmpty()) cdn.value.refresh()
        refreshGeneration.update { it + 1 }
        scanRetained()
    }

    private fun scanRetained() {
        scope.launch(Dispatchers.IO) {
            synchronized(scanLock) {
                retained.value = buildMap {
                    for (pack in layout.packs) {
                        if (pack.name !in playNames && pack.name !in padBooks) continue
                        if (cdnInstaller.isInstalled(pack)) put(pack.name, QuranPackState.Available)
                        else if (pack.kind == QuranPack.Kind.Tafsir && cdnInstaller.readableDirectory(pack) != null) {
                            put(pack.name, QuranPackState.Missing.copy(previousVersion = true))
                        }
                    }
                }
            }
        }
    }

    private fun packsUnavailable(names: Set<String>) {
        handOver(names.filterTo(mutableSetOf()) { name ->
            val pack = byName[name]
            pack?.kind == QuranPack.Kind.Tafsir && pack.cdnPublished && name in playNames
        })
    }

    private fun switchToCdn() = handOver(playNames)

    private fun handOver(names: Set<String>) {
        if (names.isEmpty()) return
        val moved = synchronized(routeLock) {
            val newlyMoved = names - cdnNames.value
            cdnNames.update { it + newlyMoved }
            newlyMoved
        }
        if (moved.isEmpty()) return
        scope.launch {
            val pad = ready.await()
            val worker = cdn.value
            worker.states.first { it.isNotEmpty() }
            synchronized(routeLock) {
                val transfers = moved.mapNotNull { name ->
                    val metered = asked.remove(name) ?: return@mapNotNull null
                    val pack = byName.getValue(name)
                    val current = if (name in padBooks) checked.value[name]?.status == QuranPackStatus.Available
                        else pad.states.value[name]?.status == QuranPackStatus.Available
                    if (current || worker.states.value[name]?.status == QuranPackStatus.Available) null else metered to pack
                }.groupBy({ it.first }, { it.second })
                transfers.forEach { (metered, packs) -> worker.fetch(packs, metered) }
            }
        }
    }

    private companion object {
        val WAITING = setOf(QuranPackStatus.WaitingForWifi, QuranPackStatus.NeedsConfirmation)
    }
}
