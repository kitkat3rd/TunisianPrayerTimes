package com.tunisianprayertimes.quran.assets

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class QuranPackInstallerTest {
    @get:Rule val folder = TemporaryFolder()

    // A minimal HTTP/1.1 server: the JDK's own is not on Android's unit-test classpath.
    private lateinit var server: ServerSocket
    private var served = ByteArray(0)
    private val archives = ConcurrentHashMap<String, ByteArray>()
    private var honoursRange = true
    private val requestedRanges = mutableListOf<String?>()

    @Before
    fun start() {
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { client ->
                    val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val requestPath = input.readLine()?.substringAfter(' ')?.substringBefore(' ') ?: return@use
                    val response = archives[requestPath] ?: served
                    val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                    val range = headers.firstOrNull { it.startsWith("Range:", ignoreCase = true) }?.substringAfter(':')?.trim()
                    requestedRanges += range
                    val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
                    val out = client.getOutputStream()
                    fun respond(status: String, body: ByteArray, extra: String = "") {
                        out.write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\n${extra}Connection: close\r\n\r\n".toByteArray())
                        out.write(body)
                        out.flush()
                    }
                    when {
                        start != null && honoursRange && start < response.size -> respond(
                            "206 Partial Content", response.copyOfRange(start, response.size),
                            "Content-Range: bytes $start-${response.size - 1}/${response.size}\r\n",
                        )
                        start != null && honoursRange -> respond("416 Range Not Satisfiable", ByteArray(0))
                        else -> respond("200 OK", response)
                    }
                }
            }
        }
    }

    @After
    fun stop() = server.close()

    private val url get() = "http://127.0.0.1:${server.localPort}/pack.zip"

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = content.size.toLong()
                    crc = CRC32().apply { update(content) }.value
                })
                out.write(content)
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val page1 = ByteArray(3_000) { (it % 7).toByte() }
    private val page2 = ByteArray(5_000) { (it % 11).toByte() }

    private fun pack(archive: ByteArray, files: List<Pair<String, Int>> = listOf("quran/pages/1.webp" to page1.size, "quran/pages/2.webp" to page2.size)) =
        QuranPack(
            "quran_pages", QuranPack.Kind.Pages, QuranPack.Delivery.Play, null, null, files.sumOf { it.second.toLong() },
            QuranPackArchive("v1/packs/quran_pages-${sha256(archive).take(12)}.zip", archive.size.toLong(), sha256(archive)),
            files.map { QuranPackFile(it.first, it.second.toLong()) },
        )

    @Test
    fun downloadsVerifiesAndInstallsInOneStep() {
        served = zip("quran/pages/1.webp" to page1, "quran/pages/2.webp" to page2)
        val pack = pack(served)
        val installer = QuranPackInstaller(folder.root)
        var lastProgress = 0L
        val installed = installer.install(installer.download(url, pack) { lastProgress = it }, pack)
        assertEquals(served.size.toLong(), lastProgress)
        assertTrue(installer.isInstalled(pack))
        assertEquals(installer.installedDirectory(pack), installed)
        assertArrayEquals(page2, File(installed, "quran/pages/2.webp").readBytes())
        // The archive is not kept next to the files it held.
        assertEquals(listOf(installed.name), folder.root.listFiles()!!.filter { it.isDirectory && !it.name.startsWith(".") }.map { it.name })
        assertTrue(File(folder.root, ".downloads").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun anInterruptedDownloadResumesWhereItStopped() {
        served = zip("quran/pages/1.webp" to page1, "quran/pages/2.webp" to page2)
        val pack = pack(served)
        val partial = File(folder.root, ".downloads/${pack.archive.key.substringAfterLast('/')}.part")
        partial.parentFile!!.mkdirs()
        partial.writeBytes(served.copyOf(4_000))
        val installer = QuranPackInstaller(folder.root)
        installer.install(installer.download(url, pack), pack)
        assertEquals(listOf("bytes=4000-"), requestedRanges)
        assertTrue(installer.isInstalled(pack))
    }

    @Test
    fun aServerThatCannotResumeStartsTheDownloadAgain() {
        served = zip("quran/pages/1.webp" to page1, "quran/pages/2.webp" to page2)
        honoursRange = false
        val pack = pack(served)
        val partial = File(folder.root, ".downloads/${pack.archive.key.substringAfterLast('/')}.part")
        partial.parentFile!!.mkdirs()
        partial.writeBytes(ByteArray(4_000) { 9 })  // Not even the right bytes.
        val installer = QuranPackInstaller(folder.root)
        installer.install(installer.download(url, pack), pack)
        assertArrayEquals(page1, File(installer.installedDirectory(pack), "quran/pages/1.webp").readBytes())
    }

    @Test
    fun aChangedArchiveIsRejectedAndForgotten() {
        val expected = zip("quran/pages/1.webp" to page1, "quran/pages/2.webp" to page2)
        served = zip("quran/pages/1.webp" to page2, "quran/pages/2.webp" to page1)
        val pack = pack(expected)
        val installer = QuranPackInstaller(folder.root)
        val error = assertThrows(QuranPackException::class.java) { installer.download(url, pack) }
        assertEquals(QuranDownloadProblem.Integrity, error.problem)
        assertFalse(installer.isInstalled(pack))
        assertTrue(File(folder.root, ".downloads").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun archivesWithOtherFilesThanTheLayoutAreNotUnpacked() {
        val installer = QuranPackInstaller(folder.root)
        val escaping = zip("quran/pages/1.webp" to page1, "../../shared_prefs/x.xml" to page2)
        val extra = zip("quran/pages/1.webp" to page1, "quran/pages/2.webp" to page2, "quran/pages/3.webp" to page1)
        val resized = zip("quran/pages/1.webp" to page1, "quran/pages/2.webp" to page1)
        val cases = listOf(
            escaping to listOf("quran/pages/1.webp" to page1.size, "../../shared_prefs/x.xml" to page2.size),
            extra to listOf("quran/pages/1.webp" to page1.size, "quran/pages/2.webp" to page2.size),
            resized to listOf("quran/pages/1.webp" to page1.size, "quran/pages/2.webp" to page2.size),
        )
        cases.forEach { (archive, files) ->
            val file = folder.newFile().apply { writeBytes(archive) }
            val error = assertThrows(QuranPackException::class.java) { installer.install(file, pack(archive, files)) }
            assertEquals(QuranDownloadProblem.Integrity, error.problem)
        }
        assertFalse(File(folder.root.parentFile, "shared_prefs/x.xml").exists())
        assertTrue(folder.root.listFiles()!!.none { it.isDirectory && it.name.startsWith("quran_pages") })
    }

    @Test
    fun earlierVersionsOfPacksAreDeleted() {
        served = zip("quran/pages/1.webp" to page1, "quran/pages/2.webp" to page2)
        val current = pack(served)
        val installer = QuranPackInstaller(folder.root)
        installer.install(installer.download(url, current), current)
        File(folder.root, "quran_pages-000000000000/quran/pages").mkdirs()
        File(folder.root, ".downloads/quran_hosary_99-0123456789ab.zip.part").apply { parentFile!!.mkdirs(); writeText("x") }
        installer.removeStale(QuranPackLayout("https://cdn.example/", listOf(current)))
        assertEquals(listOf(installer.installedDirectory(current).name),
            folder.root.listFiles()!!.filter { !it.name.startsWith(".") }.map { it.name })
        assertTrue(File(folder.root, ".downloads").listFiles().orEmpty().isEmpty())
        installer.remove(current)
        assertFalse(installer.isInstalled(current))
    }

    private val tafsirPath = "quran/tafsir/ashur.db"

    private fun tafsirPack(text: ByteArray, archive: ByteArray, id: String = "ashur"): QuranPack {
        val name = "quran_tafsir_$id"
        val hash = sha256(archive)
        return QuranPack(name, QuranPack.Kind.Tafsir, QuranPack.Delivery.Play, null, null, text.size.toLong(),
            QuranPackArchive("v1/packs/$name-${hash.take(12)}.zip", archive.size.toLong(), hash),
            listOf(QuranPackFile("quran/tafsir/$id.db", text.size.toLong(), sha256(text))))
    }

    @Test
    fun fiveConcurrentBookDownloadsHaveIndependentRevisionsAndRemoval() {
        val ids = listOf("muyassar", "mukhtasar", "juzayy", "ashur", "gharib")
        val installer = QuranPackInstaller(folder.root)
        val packs = ids.associateWith { id ->
            val db = id.toByteArray()
            val archive = zip("quran/tafsir/$id.db" to db)
            tafsirPack(db, archive, id).also { archives["/${it.archive.key}"] = archive }
        }
        val executor = Executors.newFixedThreadPool(ids.size)
        try {
            val jobs = packs.values.map { pack ->
                Callable {
                    val archive = installer.download("http://127.0.0.1:${server.localPort}/${pack.archive.key}", pack)
                    installer.install(archive, pack)
                }
            }
            executor.invokeAll(jobs).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        val updated = ids.associateWith { id ->
            val text = "edition 2 of $id".toByteArray()
            tafsirPack(text, zip("quran/tafsir/$id.db" to text), id)
        }
        installer.removeStale(QuranPackLayout("https://cdn.example/", updated.values.toList()))
        installer.remove(updated.getValue("mukhtasar"))
        assertTrue(installer.readableDirectory(updated.getValue("mukhtasar")) == null)
        for (id in ids - "mukhtasar") {
            val dir = installer.readableDirectory(updated.getValue(id))!!
            assertEquals(installer.installedDirectory(packs.getValue(id)), dir)
            assertArrayEquals(id.toByteArray(), File(dir, "quran/tafsir/$id.db").readBytes())
        }
    }

    @Test
    fun unpublishedBookMakesNoRequestAndAnArchiveWithTheWrongDatabaseIsRejected() {
        val installer = QuranPackInstaller(folder.root)
        val contents = zip(tafsirPath to ByteArray(page1.size))
        val pack = tafsirPack(page1, contents)
        val unavailable = assertThrows(QuranPackException::class.java) {
            installer.download(url, pack.copy(cdnPublished = false))
        }
        assertEquals(QuranDownloadProblem.Unpublished, unavailable.problem)
        assertTrue(requestedRanges.isEmpty())
        val archive = folder.newFile().apply { writeBytes(contents) }
        val invalid = assertThrows(QuranPackException::class.java) { installer.install(archive, pack) }
        assertEquals(QuranDownloadProblem.Integrity, invalid.problem)
        assertFalse(installer.isInstalled(pack))
    }

    @Test
    fun tafsirUpdateKeepsTheReadableEditionUntilItsSuccessorIsInstalled() {
        val installer = QuranPackInstaller(folder.root)
        served = zip(tafsirPath to page1)
        val old = tafsirPack(page1, served)
        installer.install(installer.download(url, old), old)
        val newArchive = zip(tafsirPath to page2)
        val next = tafsirPack(page2, newArchive)
        val layout = QuranPackLayout("https://cdn.example/", listOf(next))

        installer.removeStale(layout)
        assertFalse(installer.isInstalled(next))
        assertArrayEquals(page1, File(installer.readableDirectory(next)!!, tafsirPath).readBytes())
        // An incomplete or failed replacement and a retry leave the readable text alone.
        installer.discardDownload(next)
        assertEquals(installer.installedDirectory(old), installer.readableDirectory(next))
        served = newArchive
        installer.install(installer.download(url, next), next)
        assertArrayEquals(page2, File(installer.readableDirectory(next)!!, tafsirPath).readBytes())
        assertFalse(installer.isInstalled(old))
    }

    @Test
    fun playDeliveredTafsirRemainsReadableAfterPlayLosesItsCopyAndCanBeDeleted() {
        val installer = QuranPackInstaller(folder.root)
        val play = folder.newFolder()
        val file = File(play, tafsirPath).apply { parentFile!!.mkdirs(); writeBytes(page1) }
        val old = tafsirPack(page1, zip(tafsirPath to page1))
        val next = tafsirPack(page2, zip(tafsirPath to page2))
        installer.retainPlayTafsir(old, play)
        // A different file with the new edition's length cannot replace the readable old edition.
        file.writeBytes(ByteArray(page2.size))
        val error = assertThrows(QuranPackException::class.java) { installer.retainPlayTafsir(next, play) }
        assertEquals(QuranDownloadProblem.Integrity, error.problem)
        assertTrue(file.delete())
        installer.removeStale(QuranPackLayout("https://cdn.example/", listOf(next)))
        assertArrayEquals(page1, File(installer.readableDirectory(next)!!, tafsirPath).readBytes())
        installer.remove(next)
        assertFalse(installer.isInstalled(old))
        assertTrue(installer.readableDirectory(next) == null)
    }

    @Test
    fun migratedBundledAshurRequiresThePinnedBytesAndHonorsLaterDeletion() {
        val packs = folder.newFolder("packs")
        val cache = folder.newFolder("tafsir-database")
        val installer = QuranPackInstaller(packs)
        val book = tafsirPack(page1, zip(tafsirPath to page1))
        fun cached(name: String, bytes: ByteArray) = File(cache, name).apply { writeBytes(bytes) }
        val first = cached("ashur-12-${page1.size}.db", page1)
        val duplicate = cached("ashur-13-${page1.size}.db", page1)
        val altered = cached("ashur-14-${page1.size}.db", ByteArray(page1.size))
        val unrelated = cached("private.db", page1)
        val otherEdition = cached("ashur-15-4.db", ByteArray(4))

        val layout = QuranPackLayout("https://cdn.example/", listOf(book))
        val other = tafsirPack(page1, zip("quran/tafsir/gharib.db" to page1), "gharib")
        installer.migrateBundledTafsir(other, cache)
        assertFalse(installer.isInstalled(other))
        installer.migrateBundledTafsir(book, cache)
        assertArrayEquals(page1, File(installer.readableDirectory(book)!!, tafsirPath).readBytes())
        assertFalse(first.exists())
        assertFalse(duplicate.exists())
        assertTrue(listOf(altered, unrelated, otherEdition).all(File::isFile))

        // Even a cache copy left over or restored after the migration cannot undo explicit deletion.
        duplicate.writeBytes(page1)
        installer.remove(book)
        installer.removeStale(layout)
        val restarted = QuranPackInstaller(packs)
        restarted.migrateBundledTafsir(book, cache)
        assertFalse(restarted.isInstalled(book))
    }

    @Test
    fun interruptedLegacyCopyLeavesTheBundledCacheForALaterLaunch() {
        val packs = folder.newFolder("packs")
        // Simulate a filesystem that cannot create a staging directory.
        val occupiedStaging = File(packs, ".installs").apply { writeText("occupied") }
        val cache = folder.newFolder("tafsir-database")
        val source = File(cache, "ashur-12-${page1.size}.db").apply { writeBytes(page1) }
        val installer = QuranPackInstaller(packs)
        val book = tafsirPack(page1, zip(tafsirPath to page1))
        installer.migrateBundledTafsir(book, cache)
        assertFalse(installer.isInstalled(book))
        assertArrayEquals(page1, source.readBytes())
        assertTrue(occupiedStaging.delete())
        installer.migrateBundledTafsir(book, cache)
        assertTrue(installer.isInstalled(book))
        assertFalse(source.exists())
    }

    @Test
    fun playTafsirRequiresAnExactEditionAndAnUnrecognizedCacheCannotReplaceTheCurrentOne() {
        val packs = folder.newFolder("packs")
        val play = folder.newFolder("play")
        val db = File(play, tafsirPath).apply { parentFile!!.mkdirs(); writeBytes(page1) }
        val installer = QuranPackInstaller(packs)
        val current = tafsirPack(page1, zip(tafsirPath to page1))
        assertEquals(current, installer.authenticatedPlayRevision(current, db))
        installer.retainPlayTafsir(current, play)

        db.writeBytes(ByteArray(page1.size))
        assertTrue(installer.authenticatedPlayRevision(current, db) == null)
        assertFalse(installer.retainLegacyPlayAshur(current, play))
        assertArrayEquals(page1, File(installer.readableDirectory(current)!!, tafsirPath).readBytes())
    }

    @Test
    fun eachPlayBookAuthenticatesAndReplacesOnlyItsOwnEdition() {
        val ids = listOf("muyassar", "mukhtasar", "gharib", "juzayy", "ashur")
        val installer = QuranPackInstaller(folder.newFolder("packs"))
        val play = folder.newFolder("play")
        val editions = ids.mapIndexed { index, id ->
            val path = "quran/tafsir/$id.db"
            val oldBytes = ByteArray(128) { index.toByte() }
            val newBytes = ByteArray(oldBytes.size) { (index + 1).toByte() }
            val old = tafsirPack(oldBytes, zip(path to oldBytes), id)
            val next = tafsirPack(newBytes, zip(path to newBytes), id)
            val file = File(play, path).apply { parentFile!!.mkdirs(); writeBytes(oldBytes) }
            val stamp = file.lastModified()
            assertTrue(installer.retainPlayTafsir(old, play))
            // An update can reuse the filename and timestamp, and all five databases in this fixture have equal size.
            file.writeBytes(ByteArray(oldBytes.size) { (-1).toByte() })
            assertTrue(file.setLastModified(stamp))
            assertTrue(installer.authenticatedPlayRevision(next, file) == null)
            assertThrows(QuranPackException::class.java) { installer.retainPlayTafsir(next, play) }
            assertEquals(installer.installedDirectory(old), installer.readableDirectory(next))
            file.writeBytes(newBytes)
            assertTrue(file.setLastModified(stamp))
            assertEquals(sha256(newBytes), installer.authenticatedPlayRevision(next, file)?.files?.single()?.sha256)
            old to next
        }
        for ((old, next) in editions) {
            assertTrue(installer.retainPlayTafsir(next, play))
            assertTrue(installer.isInstalled(next))
            assertFalse(installer.isInstalled(old))
            for ((_, other) in editions) assertTrue(installer.readableDirectory(other) != null)
        }
    }
}
