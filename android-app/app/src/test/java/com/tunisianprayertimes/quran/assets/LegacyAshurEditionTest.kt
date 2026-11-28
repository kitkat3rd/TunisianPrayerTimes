package com.tunisianprayertimes.quran.assets

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Uses the private publication artifacts when available. No licensed fixture belongs in git. */
class LegacyAshurEditionTest {
    @get:Rule val folder = TemporaryFolder()

    private val dbPath = "quran/tafsir/ashur.db"
    private val oldDatabase = "ff6946d4f31e3920b935076a5fa64da414140a7c6a4831e403fccf9e30363e1c"
    private val newDatabase = "2edba76962cb80fe3156652bd6ec5783f86432a8a80e8bbff5eab6efed2a0095"
    private val oldArchive = "43c9580ed5acbfe32dcbdd19751caf8faf1cba6712650c0fd2bb65bb5eb6207a"
    private val newArchive = "45b97f8fd34b6353b86479fb76216df4e3e0e7ac083a03dfa0f06e9626a8f944"
    private val size = 28_008_448L // Both revisions have exactly the same size.
    private val pack = QuranPack(
        "quran_tafsir_ashur", QuranPack.Kind.Tafsir, QuranPack.Delivery.Cdn, null, null, size,
        QuranPackArchive("v1/packs/quran_tafsir_ashur-${newArchive.take(12)}.zip", 7_648_071L, newArchive),
        listOf(QuranPackFile(dbPath, size, newDatabase)),
    )

    private fun privateArchive(digest: String): File {
        val relative = "android-app/quran-assets/build/quran_tafsir_ashur-${digest.take(12)}.zip"
        val archive = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .map { File(it, relative) }.firstOrNull(File::isFile)
        assumeTrue("Private edition archive is not available in this checkout", archive != null)
        return requireNotNull(archive).also { assertEquals(digest, QuranPackInstaller.sha256(it)) }
    }

    private fun legacyCache(archive: File, directory: File): File = ZipFile(archive).use { zip ->
        val entry = zip.getEntry(dbPath)
        assertEquals(size, entry.size)
        File(directory, "ashur-${entry.crc}-${entry.size}.db").also { file ->
            zip.getInputStream(entry).use { input -> file.outputStream().use(input::copyTo) }
            assertEquals(oldDatabase, QuranPackInstaller.sha256(file))
        }
    }

    @Test fun previousStandaloneStaysReadableOfflineThroughTheFirstCdnUpdate() {
        val original = privateArchive(oldArchive)
        val replacement = privateArchive(newArchive)
        val cache = folder.newFolder("tafsir-database")
        val root = folder.newFolder("quran-packs")
        val old = legacyCache(original, cache)
        val installer = QuranPackInstaller(root)
        installer.migrateBundledTafsir(pack, cache)
        assertFalse(installer.isInstalled(pack))
        assertFalse(old.exists())
        val layout = QuranPackLayout("https://example.invalid/", listOf(pack))
        // Run the same cleanup as on the first offline launch of the five-book APK.
        val reopened = QuranPackInstaller(root)
        reopened.removeStale(layout)
        val kept = requireNotNull(reopened.readableDirectory(pack))
        assertEquals("quran_tafsir_ashur-${oldArchive.take(12)}", kept.name)
        assertEquals(oldDatabase, QuranPackInstaller.sha256(File(kept, dbPath)))
        // Use a copy because the installer removes its consumed archive.
        reopened.install(replacement.copyTo(File(folder.root, "new.zip")), pack)
        assertTrue(reopened.isInstalled(pack))
        assertEquals(newDatabase, QuranPackInstaller.sha256(File(requireNotNull(reopened.readableDirectory(pack)), dbPath)))
        assertFalse(kept.exists())
        legacyCache(original, cache)
        reopened.migrateBundledTafsir(pack, cache)
        assertTrue(reopened.isInstalled(pack))
        reopened.remove(pack)
        // Even a recreated private cache from the old APK cannot resurrect the deleted download.
        QuranPackInstaller(root).migrateBundledTafsir(pack, cache)
        assertNull(reopened.readableDirectory(pack))
    }
}
