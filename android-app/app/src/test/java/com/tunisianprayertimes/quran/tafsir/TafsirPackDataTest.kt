package com.tunisianprayertimes.quran.tafsir

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
