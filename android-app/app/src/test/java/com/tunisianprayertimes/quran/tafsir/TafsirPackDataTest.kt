package com.tunisianprayertimes.quran.tafsir

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TafsirPackDataTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun aLargeSurahKeepsAllItsTextAndSupplementaryCharacters() {
        val file = folder.newFile("ashur.db")
        val payload = "س".repeat(65_535) + "𐐀" + "ح".repeat(1_100_000)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.version = 1
            db.execSQL("CREATE TABLE surahs(surah INTEGER PRIMARY KEY, payload TEXT NOT NULL)")
            db.execSQL("INSERT INTO surahs VALUES(2, ?)", arrayOf(payload))
        }
        assertEquals(payload, TafsirPackData.read(file, 2))
        assertNull(TafsirPackData.read(file, 1))
    }

    @Test
    fun namedSourcesReadSeparateInstalledFilesAndDeletionAffectsOnlyOneWork() {
        val ids = listOf("muyassar", "mukhtasar", "gharib", "juzayy", "ashur")
        val installed = ids.associateWith { id ->
            folder.newFile("$id.db").also { dbFile ->
                SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
                    db.version = 1
                    db.execSQL("CREATE TABLE surahs(surah INTEGER PRIMARY KEY, payload TEXT NOT NULL)")
                    db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                    db.execSQL("INSERT INTO meta VALUES('id', ?)", arrayOf(id))
                    db.execSQL("INSERT INTO surahs VALUES(1, ?)", arrayOf("chapter-$id"))
                }
            }
        }.toMutableMap()
        val access = TafsirPackData(installed::get)
        for (id in ids) {
            assertEquals(id, access.metadata(id)["id"])
            assertEquals("chapter-$id", access.read(id, 1))
        }
        assertNotEquals(access.revision("muyassar"), access.revision("mukhtasar"))
        installed.remove("gharib")
        assertNull(access.read("gharib", 1))
        assertEquals("chapter-muyassar", access.read("muyassar", 1))
    }
}
