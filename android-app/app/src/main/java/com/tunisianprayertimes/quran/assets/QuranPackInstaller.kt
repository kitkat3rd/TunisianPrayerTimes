package com.tunisianprayertimes.quran.assets

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.zip.ZipFile

class QuranPackException(val problem: QuranDownloadProblem, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Downloads a pack's archive from the quran-cdn Worker and unpacks it into [root], only after
 * its size and SHA-256 match the layout. An interrupted download resumes where it stopped.
 */
internal class QuranPackInstaller(private val root: File) {
    /** Named after the archive, so a pack whose files change is downloaded again. */
    fun installedDirectory(pack: QuranPack): File = File(root, "${pack.name}-${pack.archive.sha256.take(12)}")

    fun isInstalled(pack: QuranPack): Boolean = installedDirectory(pack).isDirectory

    /** Only complete, atomically installed revisions live directly under root. */
    private fun tafsirRevisions(pack: QuranPack): List<File> {
        if (pack.kind != QuranPack.Kind.Tafsir) return emptyList()
        val prefix = "${pack.name}-"
        return root.listFiles().orEmpty().filter { dir ->
            dir.isDirectory && dir.name.startsWith(prefix) && REVISION.matches(dir.name.removePrefix(prefix))
        }
    }

    fun readableDirectory(pack: QuranPack): File? {
        val current = installedDirectory(pack)
        if (current.isDirectory) return current
        return tafsirRevisions(pack)
            .filter { dir -> pack.files.all { File(dir, it.path).isFile } }
            .maxByOrNull { it.lastModified() }
    }

    /** Play may replace its own directories when the APK updates. Retain its delivered edition on IO. */
    fun retainPlayTafsir(pack: QuranPack, source: File): Boolean {
        if (pack.kind != QuranPack.Kind.Tafsir) return false
        val entry = pack.files.singleOrNull() ?: return false
        val file = File(source, entry.path)
        if (entry.sha256 == null || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        return retainTafsirDatabase(pack, entry, file)
    }

    /** The returned database digest identifies direct PAD reads even if the path, size and mtime are reused. */
    fun authenticatedPlayRevision(pack: QuranPack, file: File): QuranPack? {
        if (pack.kind != QuranPack.Kind.Tafsir || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        val candidates = buildList {
            add(pack)
            if (canAdoptLegacyAshur(pack)) add(LEGACY_ASHUR)
        }
        return identifyRevision(candidates, file)
    }

    private fun identifyRevision(revisions: List<QuranPack>, file: File): QuranPack? {
        val sized = revisions.filter { it.bytes == file.length() }
        if (sized.isEmpty()) return null
        val digest = sha256(file)
        return sized.firstOrNull { it.files.singleOrNull()?.sha256 == digest }
    }

    private fun hasLegacyAshur(pack: QuranPack): Boolean = pack.kind == QuranPack.Kind.Tafsir &&
        pack.name == LEGACY_ASHUR.name && pack.files.singleOrNull()?.path == LEGACY_ASHUR.files.single().path

    private fun canAdoptLegacyAshur(current: QuranPack): Boolean {
        if (!hasLegacyAshur(current) || current.files.single().sha256 == LEGACY_ASHUR.files.single().sha256) return false
        val readable = readableDirectory(current)
        if (readable != null) return readable == installedDirectory(LEGACY_ASHUR)
        return !File(root, LEGACY_ASHUR_HANDLED).exists()
    }

    /** Keep the earlier PAD edition in its own revision folder even when the catalog now names a newer one. */
    fun retainLegacyPlayAshur(current: QuranPack, directory: File): Boolean = synchronized(installLock) {
        if (!canAdoptLegacyAshur(current)) return@synchronized false
        if (isInstalled(LEGACY_ASHUR)) return@synchronized true
        val entry = LEGACY_ASHUR.files.single()
        val file = File(directory, entry.path)
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return@synchronized false
        retainTafsirDatabase(LEGACY_ASHUR, entry, file)
    }

    private fun retainTafsirDatabase(pack: QuranPack, entry: QuranPackFile, original: File): Boolean = synchronized(installLock) {
        if (isInstalled(pack)) return@synchronized true
        if (!original.isFile || original.length() != entry.bytes) return@synchronized false
        val target = installedDirectory(pack)
        val staging = File(root, "$INSTALLS/${target.name}.play")
        staging.deleteRecursively()
        try {
            val dest = File(staging, entry.path)
            dest.parentFile?.mkdirs()
            original.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            if (dest.length() != entry.bytes) return@synchronized false
            if (entry.sha256 != null && sha256(dest) != entry.sha256)
                throw QuranPackException(QuranDownloadProblem.Integrity, "Unexpected contents of ${entry.path}")
            if (!staging.renameTo(target)) return@synchronized false
            pruneReplacedTafsir(pack, target)
            true
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Earlier standalone APKs extracted their signed Ibn Ashur database into cacheDir/tafsir-database. */
    fun migrateBundledTafsir(pack: QuranPack, cacheDirectory: File) = synchronized(installLock) {
        if (!hasLegacyAshur(pack)) return@synchronized
        val done = File(root, LEGACY_ASHUR_HANDLED)
        if (done.exists() || !Files.isDirectory(cacheDirectory.toPath(), LinkOption.NOFOLLOW_LINKS)) return@synchronized
        val revisions = listOf(pack, LEGACY_ASHUR)
        try {
            val copies = cacheDirectory.listFiles().orEmpty().mapNotNull { file ->
                val size = LEGACY_CACHE_NAME.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()
                if (size == null || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || file.length() != size)
                    return@mapNotNull null
                identifyRevision(revisions, file)?.let { it to file }
            }
            if (copies.isEmpty()) return@synchronized
            val candidate = copies.firstOrNull { it.first == pack } ?: copies.first()
            if (isInstalled(pack)) {
                val installed = File(installedDirectory(pack), pack.files.single().path)
                if (!installed.isFile || identifyRevision(listOf(pack), installed) == null) return@synchronized
            } else {
                val (revision, file) = candidate
                if (revision != pack && !canAdoptLegacyAshur(pack)) return@synchronized
                if (!retainTafsirDatabase(revision, revision.files.single(), file)) return@synchronized
            }
            // Write the receipt before unlinking cache files. Even a leftover duplicate cannot reinstall a deleted book.
            if (done.createNewFile()) copies.forEach { (_, file) -> file.delete() }
        } catch (_: IOException) {
            // Full or evicted cache; keep the source and retry on a subsequent launch.
        } catch (_: QuranPackException) {
            // The cached file changed during copying. Its bytes are not published to the reader.
        }
    }

    private fun pruneReplacedTafsir(pack: QuranPack, current: File) {
        tafsirRevisions(pack).filterNot { it == current }.forEach { it.deleteRecursively() }
    }

    private fun partialFile(pack: QuranPack) = File(root, "$DOWNLOADS/${pack.archive.key.substringAfterLast('/')}.part")

    /** Returns the verified archive. [onProgress] receives the bytes downloaded so far. */
    fun download(url: String, pack: QuranPack, onProgress: (Long) -> Unit = {}): File {
        if (!pack.cdnPublished) {
            throw QuranPackException(QuranDownloadProblem.Unpublished, "No CDN archive for ${pack.name}")
        }
        val expected = pack.archive.bytes
        val part = partialFile(pack)
        part.parentFile?.mkdirs()
        if (part.length() > expected) part.delete()
        if (part.length() < expected) {
            if (root.usableSpace < (expected - part.length()) + pack.bytes) {
                throw QuranPackException(QuranDownloadProblem.Storage, "Not enough space for ${pack.name}")
            }
            transfer(url, part, expected, onProgress)
        }
        onProgress(part.length())
        if (part.length() != expected || sha256(part) != pack.archive.sha256) {
            part.delete()
            throw QuranPackException(QuranDownloadProblem.Integrity, "${pack.archive.key} does not match the layout")
        }
        return part
    }

    private fun transfer(url: String, part: File, expected: Long, onProgress: (Long) -> Unit, restarted: Boolean = false) {
        val offset = part.length()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val code = connection.responseCode
            val resumed = offset > 0L && code == HttpURLConnection.HTTP_PARTIAL &&
                connection.getHeaderField("Content-Range")?.startsWith("bytes $offset-") == true
            // Only the server's word that the partial file cannot be continued starts it over;
            // any other failure keeps it, so the next attempt resumes.
            val unresumable = offset > 0L && !resumed && (code == HTTP_RANGE_NOT_SATISFIABLE || code == HttpURLConnection.HTTP_PARTIAL)
            if (unresumable && !restarted) {
                connection.disconnect()
                part.delete()
                return transfer(url, part, expected, onProgress, restarted = true)
            }
            if (code != HttpURLConnection.HTTP_OK && !resumed) {
                throw QuranPackException(QuranDownloadProblem.Network, "HTTP $code for $url")
            }
            var written = if (resumed) offset else 0L
            connection.inputStream.use { input ->
                FileOutputStream(part, resumed).use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (written < expected) {
                        if (Thread.currentThread().isInterrupted) throw IOException("Download interrupted")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written)
                    }
                }
            }
        } catch (error: QuranPackException) {
            throw error
        } catch (error: IOException) {
            val problem = if (root.usableSpace < BUFFER) QuranDownloadProblem.Storage else QuranDownloadProblem.Network
            throw QuranPackException(problem, "Could not download $url", error)
        } finally {
            connection.disconnect()
        }
    }

    /** Unpacks exactly the files the layout lists, then moves them into place in one step. */
    fun install(archive: File, pack: QuranPack): File {
        val target = installedDirectory(pack)
        if (target.isDirectory) return target
        val staging = File(root, "$INSTALLS/${target.name}")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw QuranPackException(QuranDownloadProblem.Storage, "Cannot create $staging")
        val expected = pack.files.associateBy { it.path }
        val stagingPath = staging.canonicalPath + File.separator
        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries().toList().filterNot { it.isDirectory }
                if (entries.map { it.name }.sorted() != expected.keys.sorted()) {
                    throw QuranPackException(QuranDownloadProblem.Integrity, "${archive.name} holds other files than ${pack.name}")
                }
                entries.forEach { entry ->
                    val destination = File(staging, entry.name)
                    val file = expected.getValue(entry.name)
                    if (!destination.canonicalPath.startsWith(stagingPath) || entry.size != file.bytes) {
                        throw QuranPackException(QuranDownloadProblem.Integrity, "Unexpected entry ${entry.name} in ${archive.name}")
                    }
                    destination.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input -> destination.outputStream().use(input::copyTo) }
                    if (destination.length() != entry.size) {
                        throw QuranPackException(QuranDownloadProblem.Integrity, "Truncated entry ${entry.name}")
                    }
                    if (file.sha256 != null && sha256(destination) != file.sha256) {
                        throw QuranPackException(QuranDownloadProblem.Integrity, "Unexpected contents of ${entry.name}")
                    }
                }
            }
        } catch (error: QuranPackException) {
            staging.deleteRecursively()
            throw error
        } catch (error: IOException) {
            staging.deleteRecursively()
            throw QuranPackException(QuranDownloadProblem.Storage, "Could not unpack ${archive.name}", error)
        }
        // Whatever happened to the folder meanwhile, only a complete pack goes into place.
        if (pack.files.any { File(staging, it.path).length() != it.bytes }) {
            staging.deleteRecursively()
            throw QuranPackException(QuranDownloadProblem.Integrity, "Incomplete unpack of ${pack.name}")
        }
        synchronized(installLock) {
            if (target.isDirectory) staging.deleteRecursively()
            else if (!staging.renameTo(target)) {
                staging.deleteRecursively()
                throw QuranPackException(QuranDownloadProblem.Storage, "Could not move ${pack.name} into place")
            }
            pruneReplacedTafsir(pack, target)
        }
        archive.delete()
        return target
    }

    fun discardDownload(pack: QuranPack) {
        File(root, "$INSTALLS/${installedDirectory(pack).name}").deleteRecursively()
        partialFile(pack).delete()
    }

    /** A deletion by the reader also removes any retained edition. */
    fun remove(pack: QuranPack): Unit = synchronized(installLock) {
        installedDirectory(pack).deleteRecursively()
        tafsirRevisions(pack).forEach { it.deleteRecursively() }
        discardDownload(pack)
        if (pack.kind == QuranPack.Kind.Tafsir && pack.name == LEGACY_ASHUR_PACK) {
            root.mkdirs()
            File(root, LEGACY_ASHUR_HANDLED).createNewFile()
        }
    }

    /**
     * Deletes earlier versions of packs and downloads the current layout no longer lists. Work on a
     * current pack is left alone: a download worker may be writing or unpacking it right now.
     */
    fun removeStale(layout: QuranPackLayout) = synchronized(installLock) {
        val current = layout.packs.map { installedDirectory(it).name }.toSet()
        val retained = layout.packs.filter { it.kind == QuranPack.Kind.Tafsir }
            .mapNotNull { readableDirectory(it)?.name }.toSet()
        val archives = layout.packs.map { partialFile(it).name }.toSet()
        root.listFiles()?.forEach { file ->
            if (file.name != DOWNLOADS && file.name != INSTALLS && file.name != LEGACY_ASHUR_HANDLED &&
                file.name !in current && file.name !in retained)
                file.deleteRecursively()
        }
        File(root, INSTALLS).listFiles()?.forEach { if (it.name !in current) it.deleteRecursively() }
        File(root, DOWNLOADS).listFiles()?.forEach { if (it.name !in archives) it.delete() }
    }

    companion object {
        private val installLock = Any()
        private val REVISION = Regex("[0-9a-f]{12}")
        private const val LEGACY_ASHUR_PACK = "quran_tafsir_ashur"
        private const val LEGACY_ASHUR_HANDLED = ".legacy-ashur-handled"
        private val LEGACY_CACHE_NAME = Regex("ashur-[0-9]{1,10}-([0-9]+)\\.db")
        // The pre-five-book standalone APK and PAD pack shipped this exact database. It can be
        // adopted from the phone only, under its original archive revision and offline metadata.
        private val LEGACY_ASHUR = QuranPack(
            name = LEGACY_ASHUR_PACK, kind = QuranPack.Kind.Tafsir, delivery = QuranPack.Delivery.Play,
            reciterId = null, surahs = null, bytes = 28_008_448L,
            archive = QuranPackArchive("v1/packs/quran_tafsir_ashur-43c9580ed5ac.zip", 7_647_106L,
                "43c9580ed5acbfe32dcbdd19751caf8faf1cba6712650c0fd2bb65bb5eb6207a"),
            files = listOf(QuranPackFile("quran/tafsir/ashur.db", 28_008_448L,
                "ff6946d4f31e3920b935076a5fa64da414140a7c6a4831e403fccf9e30363e1c")),
            cdnPublished = false,
        )
        private const val DOWNLOADS = ".downloads"
        private const val INSTALLS = ".installs"
        private const val BUFFER = 64 * 1024
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
