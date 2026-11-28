package com.tunisianprayertimes.quran.assets

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipFile

/** Play installs: Play's packs go through Play, the rest through the quran-cdn Worker. */
class PlayOrCdnPackSourceTest {
    @get:Rule val folder = TemporaryFolder()

    private class FakeSource : QuranPackSource {
        override val states = MutableStateFlow<Map<String, QuranPackState>>(emptyMap())
        // The Worker takes over on another thread.
        val fetched = CopyOnWriteArrayList<Pair<List<String>, Boolean>>()
        val confirmed = CopyOnWriteArrayList<List<String>>()
        val removed = CopyOnWriteArrayList<List<String>>()
        val directories = java.util.concurrent.ConcurrentHashMap<String, File>()
        override fun directory(pack: QuranPack): File? = directories[pack.name]
        override fun fetch(packs: List<QuranPack>, allowMetered: Boolean) { fetched += packs.map { it.name } to allowMetered }
        override fun confirmMobileData(packs: List<QuranPack>, launcher: ActivityResultLauncher<IntentSenderRequest>) { confirmed += packs.map { it.name } }
        override fun remove(packs: List<QuranPack>) { removed += packs.map { it.name } }
        val cancelled = CopyOnWriteArrayList<List<String>>()
        override fun cancel(packs: List<QuranPack>) { cancelled += packs.map { it.name } }
        override fun refresh() {}
    }

    private val launcher = object : ActivityResultLauncher<IntentSenderRequest>() {
        override fun launch(input: IntentSenderRequest, options: ActivityOptionsCompat?) {}
        override fun unregister() {}
        override val contract: ActivityResultContract<IntentSenderRequest, *> = ActivityResultContracts.StartIntentSenderForResult()
    }

    private fun pack(name: String, delivery: String, surahs: IntRange, reciter: String) = JSONObject()
        .put("name", name).put("kind", "audio").put("delivery", delivery).put("reciterId", reciter)
        .put("surahs", JSONArray(listOf(surahs.first, surahs.last))).put("bytes", 10)
        .put("archive", JSONObject().put("key", "v1/packs/$name-0123456789ab.zip").put("bytes", 110).put("sha256", "a".repeat(64)))
        .put("files", JSONArray(surahs.map { JSONObject().put("path", "quran/audio/$reciter/%03d.mp3".format(it)).put("bytes", 10) }))

    private val layout = QuranPackLayout.parse(JSONObject().put("version", 1).put("cdn", "https://cdn.example/").put("packs", JSONArray(listOf(
        pack("quran_hosary_01", "play", 1..57, "hosary"),
        pack("quran_hosary_02", "play", 58..114, "hosary"),
        pack("quran_later_01", "cdn", 1..114, "later"),
    ))).toString())
    private val hosary1 = layout.packs[0]
    private val hosary2 = layout.packs[1]
    private val later = layout.packs[2]

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val play = FakeSource()
    private val worker = FakeSource()
    private var onUnavailable: () -> Unit = {}
    private val source by lazy {
        PlayOrCdnPackSource(layout, lazy { worker }, QuranPackInstaller(folder.root), scope) { unavailable ->
            onUnavailable = unavailable
            play
        }
    }

    @After
    fun stop() = scope.cancel()

    @Test
    fun initialAvailabilityIsUnknownUntilTheSourcesHaveAnswered() = runTest {
        val starting = PlayOrCdnPackSource(layout, lazy { worker }, QuranPackInstaller(folder.root), backgroundScope) { _ -> play }
        assertTrue(starting.states.value.isEmpty())
    }

    private fun awaitStates(condition: (Map<String, QuranPackState>) -> Boolean) = runBlocking {
        withTimeout(5_000) { source.states.first(condition) }
    }

    @Test
    fun eachPackGoesToTheSourceThatCarriesIt() {
        source.fetch(listOf(hosary1, later), allowMetered = false)
        assertEquals(listOf(listOf("quran_hosary_01") to false), play.fetched)
        assertEquals(listOf(listOf("quran_later_01") to false), worker.fetched)

        source.remove(listOf(hosary2, later))
        assertEquals(listOf(listOf("quran_hosary_02")), play.removed)
        assertEquals(listOf(listOf("quran_later_01")), worker.removed)

        // A download stopped goes to the source that carries it too.
        source.cancel(listOf(hosary1, later))
        assertEquals(listOf(listOf("quran_hosary_01")), play.cancelled)
        assertEquals(listOf(listOf("quran_later_01")), worker.cancelled)
    }

    @Test
    fun statesComeFromTheSourceThatCarriesEachPack() {
        play.states.value = mapOf("quran_hosary_01" to QuranPackState(QuranPackStatus.Downloading, 5, 10))
        // The Worker's view of a Play pack does not count while Play delivers it.
        worker.states.value = mapOf(
            "quran_later_01" to QuranPackState(QuranPackStatus.WaitingForWifi, 0, 110),
            "quran_hosary_02" to QuranPackState(QuranPackStatus.Failed, problem = QuranDownloadProblem.Network),
        )
        val states = awaitStates { it["quran_later_01"]?.status == QuranPackStatus.WaitingForWifi }
        assertEquals(QuranPackStatus.Downloading, states["quran_hosary_01"]?.status)
        assertTrue(states["quran_hosary_02"]?.status != QuranPackStatus.Failed)
    }

    @Test
    fun oldCommentaryRemainsReadableDuringPlayUpdateAndNewDeliveryIsRetained() = runBlocking {
        val old = edition('a', "old")
        val updated = edition('b', "new!")
        val installer = QuranPackInstaller(folder.newFolder())
        installer.retainPlayTafsir(old, playAssets("old"))
        val layout = QuranPackLayout("https://cdn.example/", listOf(updated))
        val source = PlayOrCdnPackSource(layout, lazy { worker }, installer, scope) { _ -> play }
        val name = updated.name
        val progress = QuranPackState(QuranPackStatus.Downloading, 10, 100)
        play.states.value = mapOf(name to progress)
        withTimeout(5_000) { source.states.first { it[name] == progress.copy(previousVersion = true) } }
        assertEquals("old", File(source.directory(updated)!!, db).readText())

        play.directories[name] = playAssets("new!")
        play.states.value = mapOf(name to QuranPackState.Available)
        withTimeout(5_000) { while (!installer.isInstalled(updated)) delay(10) }
        assertEquals("new!", File(installer.readableDirectory(updated)!!, db).readText())
        assertEquals("new!", File(source.directory(updated)!!, db).readText())

        // Play can still report its old directory briefly after the reader presses delete.
        source.remove(listOf(updated))
        withTimeout(5_000) { source.states.first { it[name] == QuranPackState.Missing } }
        assertNull(source.directory(updated))
        withTimeout(5_000) { while (installer.readableDirectory(updated) != null) delay(10) }
        assertEquals(listOf(listOf(name)), play.removed)
    }

    private val db = "quran/tafsir/ashur.db"

    private fun edition(hash: Char, body: String, book: String = "ashur", delivery: QuranPack.Delivery = QuranPack.Delivery.Play) = QuranPack(
        "quran_tafsir_$book", QuranPack.Kind.Tafsir, delivery, null, null, body.length.toLong(),
        QuranPackArchive("v1/packs/quran_tafsir_$book-${hash.toString().repeat(12)}.zip", 100, hash.toString().repeat(64)),
        listOf(QuranPackFile("quran/tafsir/$book.db", body.length.toLong(),
            MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) })),
    )

    private fun books(delivery: QuranPack.Delivery) = listOf("muyassar", "mukhtasar", "gharib", "juzayy", "ashur").map { book ->
        edition('b', book, book, delivery)
    }

    private fun playAssets(body: String) = folder.newFolder().apply {
        File(this, db).apply { parentFile!!.mkdirs(); writeText(body) }
    }

    private fun deliver(pack: QuranPack, contents: String) {
        val root = folder.newFolder()
        val database = File(root, pack.files.single().path)
        requireNotNull(database.parentFile).mkdirs()
        database.writeText(contents)
        play.directories[pack.name] = root
        play.states.value += pack.name to QuranPackState.Available
    }

    @Test
    fun fivePlayBooksKeepIndependentTransfersAndExposeOnlyVerifiedFiles() = runBlocking {
        val books = books(QuranPack.Delivery.Play)
        val (meaning, concise, glossary, juzayy, ashur) = books
        val installer = QuranPackInstaller(folder.newFolder())
        val local = PlayOrCdnPackSource(QuranPackLayout("https://cdn.example/", books), lazy { worker }, installer, scope) { _ -> play }
        books.forEach { local.fetch(listOf(it), allowMetered = true) }
        assertEquals(books.map { listOf(it.name) to true }, play.fetched)
        assertTrue(worker.fetched.isEmpty())

        val progress = QuranPackState(QuranPackStatus.Downloading, 32, 100)
        play.states.value = mapOf(juzayy.name to progress, ashur.name to QuranPackState(QuranPackStatus.NeedsConfirmation))
        deliver(meaning, "muyassar")
        deliver(concise, "mukhtasar")
        deliver(glossary, "broken") // Same length as "gharib", but a different hash.
        withTimeout(5_000) { local.states.first { states ->
            states[meaning.name] == QuranPackState.Available && states[concise.name] == QuranPackState.Available &&
                states[glossary.name]?.problem == QuranDownloadProblem.Integrity &&
                states[juzayy.name] == progress && states[ashur.name]?.status == QuranPackStatus.NeedsConfirmation
        } }
        assertTrue(installer.isInstalled(meaning) && installer.isInstalled(concise))
        assertEquals("mukhtasar", File(requireNotNull(local.directory(concise)), concise.files.single().path).readText())
        assertNull(local.directory(glossary))

        local.confirmMobileData(listOf(juzayy, ashur), launcher)
        local.cancel(listOf(juzayy))
        local.remove(listOf(meaning))
        withTimeout(5_000) { local.states.first { it[meaning.name] == QuranPackState.Missing } }
        assertNull(local.directory(meaning))
        assertTrue(installer.isInstalled(concise))
        assertEquals(listOf(listOf(ashur.name)), play.confirmed)
        assertEquals(listOf(listOf(juzayy.name)), play.cancelled)
        assertEquals(listOf(listOf(meaning.name)), play.removed)
        assertTrue(worker.confirmed.isEmpty() && worker.cancelled.isEmpty() && worker.removed.isEmpty())
    }

    @Test
    fun permanentPlayFailureHandsOnlyOutstandingBookRequestsToCdn() = runBlocking {
        val books = books(QuranPack.Delivery.Play)
        val (ready, invalid, pending, cancelled, untouched) = books
        val installer = QuranPackInstaller(folder.newFolder())
        var unavailable: () -> Unit = {}
        val local = PlayOrCdnPackSource(QuranPackLayout("https://cdn.example/", books), lazy { worker }, installer, scope) { failure ->
            unavailable = failure
            play
        }
        local.fetch(listOf(ready, invalid, pending, cancelled), allowMetered = false)
        local.fetch(listOf(pending), allowMetered = true)
        local.cancel(listOf(cancelled))
        deliver(ready, "muyassar")
        deliver(invalid, "xxxxxxxxx") // PAD reports completion, but these bytes cannot serve the book.
        play.states.value += pending.name to QuranPackState(QuranPackStatus.Pending)
        withTimeout(5_000) { local.states.first { states ->
            states[ready.name] == QuranPackState.Available && states[invalid.name]?.problem == QuranDownloadProblem.Integrity
        } }
        worker.states.value = books.associate { it.name to QuranPackState.Missing }
        unavailable()
        withTimeout(5_000) { while (worker.fetched.size < 2) delay(10) }
        assertEquals(setOf(listOf(invalid.name) to false, listOf(pending.name) to true), worker.fetched.toSet())
        assertEquals(2, worker.fetched.size)
        assertTrue(installer.isInstalled(ready))
        local.fetch(listOf(untouched), allowMetered = true)
        assertEquals(listOf(untouched.name) to true, worker.fetched.last())
        assertEquals(2, play.fetched.size)
    }

    @Test
    fun oneUnavailablePlayBookUsesItsPublishedCdnCopyWhileOtherBooksStayOnPlay() = runBlocking {
        val base = books(QuranPack.Delivery.Play)
        val meaning = base[0]
        val glossary = base[2]
        val unpublished = base[3].copy(cdnPublished = false)
        val books = listOf(meaning, glossary, unpublished)
        val installer = QuranPackInstaller(folder.newFolder())
        var packsUnavailable: (Set<String>) -> Unit = {}
        val local = PlayOrCdnPackSource(QuranPackLayout("https://cdn.example/", books), lazy { worker }, installer, scope) { _, missing ->
            packsUnavailable = missing
            play
        }
        val downloading = QuranPackState(QuranPackStatus.Downloading, 19, 100)
        val storeError = QuranPackState(QuranPackStatus.Failed, problem = QuranDownloadProblem.Store)
        play.states.value = mapOf(meaning.name to downloading, glossary.name to storeError, unpublished.name to storeError)
        worker.states.value = books.associate { it.name to QuranPackState.Missing }
        local.fetch(listOf(meaning, glossary, unpublished), allowMetered = false)
        packsUnavailable(setOf(glossary.name, unpublished.name))
        withTimeout(5_000) { while (worker.fetched.isEmpty()) delay(10) }
        assertEquals(listOf(listOf(glossary.name) to false), worker.fetched)
        val waiting = QuranPackState(QuranPackStatus.WaitingForWifi, 0, 100)
        worker.states.value += glossary.name to waiting
        withTimeout(5_000) { local.states.first { states ->
            states[meaning.name] == downloading && states[glossary.name] == waiting && states[unpublished.name] == storeError
        } }
        local.confirmMobileData(listOf(meaning, glossary), launcher)
        local.cancel(listOf(glossary))
        assertEquals(listOf(listOf(glossary.name)), worker.confirmed)
        assertEquals(listOf(listOf(glossary.name)), worker.cancelled)
        assertTrue(play.confirmed.isEmpty() && play.cancelled.isEmpty())
        local.fetch(listOf(meaning), allowMetered = true)
        assertEquals(listOf(meaning.name) to true, play.fetched.last())
        assertEquals(1, worker.fetched.size)
    }

    @Test
    fun invalidPlayRevisionOfTheSameSizeLeavesTheVerifiedEditionReadable() = runBlocking {
        val old = edition('a', "old")
        val updated = edition('b', "new")
        val installer = QuranPackInstaller(folder.newFolder())
        installer.retainPlayTafsir(old, playAssets("old"))
        val layout = QuranPackLayout("https://cdn.example/", listOf(updated))
        play.directories[updated.name] = playAssets("bad")
        play.states.value = mapOf(updated.name to QuranPackState.Available)
        val source = PlayOrCdnPackSource(layout, lazy { worker }, installer, scope) { _ -> play }
        withTimeout(5_000) { source.states.first { states ->
            states[updated.name]?.let { it.problem == QuranDownloadProblem.Integrity && it.previousVersion } == true
        } }
        assertEquals("old", File(source.directory(updated)!!, db).readText())
    }

    @Test
    fun fiveCommentaryPacksUseTheCdnIndependentlyOnPlayInstalls() = runBlocking {
        val books = books(QuranPack.Delivery.Cdn)
        val catalog = QuranPackLayout("https://cdn.example/", books)
        val source = PlayOrCdnPackSource(catalog, lazy { worker }, QuranPackInstaller(folder.newFolder()), scope) { _ -> play }
        books.forEach { source.fetch(listOf(it), allowMetered = true) }
        assertEquals(books.map { listOf(it.name) to true }, worker.fetched)
        assertTrue(play.fetched.isEmpty())

        val perBook = books.map { it.name }.zip(listOf(
            QuranPackState(QuranPackStatus.Downloading, 32, 100),
            QuranPackState.Available,
            QuranPackState(QuranPackStatus.WaitingForWifi),
            QuranPackState(QuranPackStatus.Failed, problem = QuranDownloadProblem.Network),
            QuranPackState.Missing,
        )).toMap()
        worker.states.value = perBook
        // A generic Play callback cannot make a CDN glossary appear downloaded.
        play.states.value = mapOf(books[2].name to QuranPackState.Available)
        withTimeout(5_000) { source.states.first { it == perBook } }

        source.cancel(listOf(books[0]))
        source.remove(listOf(books[1]))
        source.confirmMobileData(listOf(books[2]), launcher)
        assertEquals(listOf(listOf(books[0].name)), worker.cancelled)
        assertEquals(listOf(listOf(books[1].name)), worker.removed)
        assertEquals(listOf(listOf(books[2].name)), worker.confirmed)
        assertTrue(play.cancelled.isEmpty() && play.removed.isEmpty() && play.confirmed.isEmpty())
    }

    @Test
    fun previouslyInstalledPlayAshurIsReadableUnderTheNewCdnCatalog() = runBlocking {
        val books = books(QuranPack.Delivery.Cdn)
        val ashur = books.last()
        val installer = QuranPackInstaller(folder.newFolder())
        val catalog = QuranPackLayout("https://cdn.example/", books)
        worker.states.value = books.associate { it.name to QuranPackState.Missing }
        play.directories[ashur.name] = playAssets("ashur")
        play.states.value = mapOf(ashur.name to QuranPackState.Available)
        val source = PlayOrCdnPackSource(catalog, lazy { worker }, installer, scope) { _ -> play }

        withTimeout(5_000) { while (!installer.isInstalled(ashur)) delay(10) }
        val states = withTimeout(5_000) { source.states.first { it[ashur.name]?.available == true && it.keys.containsAll(books.map { book -> book.name }) } }
        assertTrue(books.dropLast(1).all { states[it.name] == QuranPackState.Missing })
        assertEquals("ashur", File(source.directory(ashur)!!, db).readText())
        assertTrue(play.fetched.isEmpty() && worker.fetched.isEmpty())

        source.remove(listOf(ashur))
        assertEquals(listOf(listOf(ashur.name)), worker.removed)
        assertEquals(listOf(listOf(ashur.name)), play.removed)
        source.fetch(listOf(ashur), allowMetered = true)
        assertEquals(listOf(listOf(ashur.name) to true), worker.fetched)
        assertTrue(play.fetched.isEmpty())
    }

    @Test
    fun signedEarlierPadEditionRemainsReadableWhenNoRoomForAPrivateCopy() = runBlocking {
        // The active PAD directory now contains the current edition on a release workstation.
        // Reconstruct the exact earlier PAD file from its archived publication when available.
        val relative = "android-app/quran-assets/build/quran_tafsir_ashur-43c9580ed5ac.zip"
        val repo = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, relative).isFile }
        assumeTrue("Legacy publication archive unavailable", repo != null)
        val archive = File(requireNotNull(repo), relative)
        assertEquals("43c9580ed5acbfe32dcbdd19751caf8faf1cba6712650c0fd2bb65bb5eb6207a", QuranPackInstaller.sha256(archive))
        val originalAssets = folder.newFolder()
        val original = File(originalAssets, db)
        requireNotNull(original.parentFile).mkdirs()
        ZipFile(archive).use { zip -> zip.getInputStream(zip.getEntry(db)).use { input ->
            original.outputStream().use(input::copyTo)
        } }
        assertEquals("ff6946d4f31e3920b935076a5fa64da414140a7c6a4831e403fccf9e30363e1c", QuranPackInstaller.sha256(original))
        val catalog = QuranPackLayout.parse(File(repo, "android-app/app/src/main/assets/quran/packs.json").readText())
        val current = requireNotNull(catalog.tafsir("ashur"))
        val books = catalog.packs
        val installer = QuranPackInstaller(folder.newFile()) // no directory available for a second copy
        play.directories[current.name] = originalAssets
        play.states.value = mapOf(current.name to QuranPackState.Available)
        worker.states.value = books.associate { it.name to QuranPackState.Missing }
        var unavailable = {}
        val source = PlayOrCdnPackSource(catalog, lazy { worker }, installer, scope) { failure ->
            unavailable = failure
            play
        }
        withTimeout(20_000) {
            source.states.first { it[current.name]?.previousVersion == true }
        }
        assertEquals(original, File(source.directory(current)!!, db))
        assertTrue(!installer.isInstalled(current))

        // Switching the delivery source must preserve the revision identity and CDN progress.
        unavailable()
        val updating = QuranPackState(QuranPackStatus.Downloading, 25, 100)
        worker.states.value = worker.states.value + (current.name to updating)
        withTimeout(5_000) {
            source.states.first { it[current.name] == updating.copy(previousVersion = true) }
        }
        source.remove(listOf(current))
        assertNull(source.directory(current))
        assertTrue(play.fetched.isEmpty())
    }

    @Test
    fun mobileDataIsAskedOfEachSourceForItsWaitingPacks() {
        play.states.value = mapOf(
            "quran_hosary_01" to QuranPackState(QuranPackStatus.NeedsConfirmation, 0, 10),
            "quran_hosary_02" to QuranPackState(QuranPackStatus.Downloading, 1, 10),
        )
        awaitStates { it["quran_hosary_01"]?.status == QuranPackStatus.NeedsConfirmation }
        source.confirmMobileData(listOf(hosary1, hosary2, later), launcher)
        assertEquals(listOf(listOf("quran_hosary_01")), play.confirmed)
        assertEquals(listOf(listOf("quran_later_01")), worker.confirmed)

        // Nothing of Play's waits: Play's dialog is not shown at all.
        play.confirmed.clear()
        source.confirmMobileData(listOf(hosary2, later), launcher)
        assertTrue(play.confirmed.isEmpty())
    }

    @Test
    fun whenPlayCannotServeThisInstallTheWorkerTakesOverWhatPlayWasAsked() {
        source.fetch(listOf(hosary1), allowMetered = true)
        worker.states.value = layout.packs.associate { it.name to QuranPackState.Missing }
        onUnavailable()
        runBlocking { withTimeout(5_000) { while (worker.fetched.none { it.first == listOf("quran_hosary_01") }) delay(10) } }
        assertEquals(true, worker.fetched.first { it.first == listOf("quran_hosary_01") }.second)

        // From now on every pack comes from the Worker.
        source.fetch(listOf(hosary2), allowMetered = false)
        assertEquals(listOf("quran_hosary_02") to false, worker.fetched.last())
        assertEquals(listOf(listOf("quran_hosary_01") to true), play.fetched)
    }
}
