package com.tunisianprayertimes.quran.tafsir

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TafsirCorrectionsTest {
    private val base = "a".repeat(64)
    private val book = "juzayy"
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private fun body(version: Long, text: String, baseId: String = base) =
        JSONObject().put("book", book).put("baseId", baseId).put("version", version)
            .put("units", JSONArray().put(JSONObject().put("id", "1:1").put("text", text)
                .put("date", "2026-11-01").put("reason", "Compared with the source printing")))
            .toString().toByteArray()

    private fun entry(version: Long, bytes: ByteArray) = TafsirCorrections.Entry(
        book, base, version, "corrections.json", bytes.size,
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    )

    @Test
    fun signedIndexRejectsModifiedPayloadAndPreviewCannotConfigureNetwork() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val row = JSONObject().put("book", book).put("baseId", base).put("version", 3)
            .put("path", "corrections.json").put("bytes", 150).put("sha256", "b".repeat(64))
        val payload = JSONObject().put("version", 1).put("files", JSONArray().put(row)).toString().toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(keys.private); update(payload) }.sign()
        val envelope = JSONObject().put("payload", Base64.getEncoder().encodeToString(payload))
            .put("signature", Base64.getEncoder().encodeToString(signature))
        assertEquals(3L, TafsirCorrections.verifiedIndex(envelope.toString().toByteArray(), keys.public).single().version)
        envelope.put("payload", Base64.getEncoder().encodeToString(payload + byteArrayOf(32)))
        assertThrows(IllegalArgumentException::class.java) {
            TafsirCorrections.verifiedIndex(envelope.toString().toByteArray(), keys.public)
        }
        val catalog = JSONObject().put("corrections", JSONObject().put("publicKey", Base64.getEncoder().encodeToString(keys.public.encoded))
            .put("indexUrl", "https://example.invalid/tafsir-corrections.json"))
        assertEquals("example.invalid", TafsirCorrections.config(catalog)?.index?.host)
        assertNull(TafsirCorrections.config(catalog.put("preview", true)))
        assertNull(TafsirCorrections.config(JSONObject()))
    }

    @Test
    fun rollbackCorruptionAndWrongEditionPreserveLastVerifiedText() {
        TafsirCorrections.schedule(context, JSONObject(), mapOf(book to base))
        val latest = body(2, "Corrected text")
        assertTrue(TafsirCorrections.install(context, entry(2, latest), latest))
        val old = body(1, "Older text")
        assertFalse(TafsirCorrections.install(context, entry(1, old), old))
        val corrupt = latest.copyOf().also { it[5] = 32 }
        assertThrows(IllegalArgumentException::class.java) { TafsirCorrections.install(context, entry(2, latest), corrupt) }
        val mismatched = body(3, "Different edition", "d".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { TafsirCorrections.install(context, entry(3, mismatched), mismatched) }
        assertEquals("Corrected text", TafsirCorrections.forBase(context, book, base)["1:1"]?.text)
        assertTrue(TafsirCorrections.forBase(context, book, "d".repeat(64)).isEmpty())
        TafsirCorrections.schedule(context, JSONObject(), emptyMap())
        assertTrue(TafsirCorrections.forBase(context, book, base).isEmpty())
        assertFalse(TafsirCorrections.install(context, entry(2, latest), latest))
    }

    @Test
    fun retainedPackAndBundledExcerptsKeepIndependentCorrectionsAcrossUpgradeAndDeletion() {
        val previous = "c".repeat(64)
        val bundled = "e".repeat(64)
        val prefs = context.getSharedPreferences("tafsir-corrections", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("bases", JSONObject().put(book, previous).toString()).remove("lastCheck").commit()
        assertEquals(setOf(previous), TafsirCorrections.beginCheck(context)?.get(book))
        val packText = body(8, "Retained full text", previous)
        val packEntry = entry(8, packText).copy(base = previous)
        assertTrue(TafsirCorrections.install(context, packEntry, packText))

        TafsirCorrections.scheduleEditions(context, JSONObject(), mapOf(book to setOf(previous, bundled)))
        val excerptText = body(1, "Bundled excerpt text", bundled)
        val excerptEntry = entry(1, excerptText).copy(base = bundled)
        assertTrue(TafsirCorrections.install(context, excerptEntry, excerptText))
        assertEquals("Retained full text", TafsirCorrections.forBase(context, book, previous)["1:1"]?.text)
        assertEquals("Bundled excerpt text", TafsirCorrections.forBase(context, book, bundled)["1:1"]?.text)
        TafsirCorrections.scheduleEditions(context, JSONObject(), mapOf(book to setOf(bundled)))
        assertTrue(TafsirCorrections.forBase(context, book, previous).isEmpty())
        assertEquals("Bundled excerpt text", TafsirCorrections.forBase(context, book, bundled)["1:1"]?.text)
        assertFalse(TafsirCorrections.install(context, packEntry, packText))
    }
}
