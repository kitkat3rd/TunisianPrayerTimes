package com.tunisianprayertimes.mosque

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.mosque.presence.GeoPoint
import com.tunisianprayertimes.mosque.presence.SilencePolygon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MosqueAreaDraftStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `cancelling or writing another draft leaves the original drawing intact`() {
        val original = AreaDraft(null, "Original", "36, 10\n36, 10.0001\n36.0001, 10", mode = BoundaryEditMode.DRAW)
        val other = AreaDraft(null, "Other", "")
        PrivateSilenceAreaStore.saveDraft(context, original)
        assertFalse(PrivateSilenceAreaStore.clearDraft(context, other.key))
        assertTrue(runCatching { PrivateSilenceAreaStore.saveDraft(context, other) }.isFailure)
        assertEquals(original, PrivateSilenceAreaStore.readDraft(context))
        assertTrue(PrivateSilenceAreaStore.clearDraft(context, original.key))
        assertNull(PrivateSilenceAreaStore.readDraft(context))
    }

    @Test
    fun `older drafts with no mode or key can still be resumed`() {
        File(context.noBackupFilesDir, "mosque-silence-draft.json")
            .writeText("""{"name":"Original","coordinates":"36, 10","id":null,"enabled":false}""")
        val draft = checkNotNull(PrivateSilenceAreaStore.readDraft(context))
        assertEquals("Original", draft.name)
        assertEquals("36, 10", draft.coordinates)
        assertEquals(BoundaryEditMode.VIEW, draft.mode)
        assertFalse(draft.enabled)
        PrivateSilenceAreaStore.saveDraft(context, draft.copy(mode = BoundaryEditMode.DRAW))
        assertEquals(draft.key, PrivateSilenceAreaStore.readDraft(context)?.key)
        assertEquals(BoundaryEditMode.DRAW, PrivateSilenceAreaStore.readDraft(context)?.mode)
    }

    @Test
    fun `catalogue geometry with a hole stays intact through draft and committed storage`() {
        val exterior = rectangle(36.0, 10.0, 0.001)
        val hole = rectangle(36.0002, 10.0002, 0.0002)
        val polygons = listOf(SilencePolygon(exterior, listOf(hole)))
        val draft = AreaDraft("catalogue:test", "Reviewed", "", enabled = false).withPolygons(polygons)
        PrivateSilenceAreaStore.saveDraft(context, draft)
        assertEquals(draft, PrivateSilenceAreaStore.readDraft(context))
        PrivateSilenceAreaStore.save(context, draft, polygons)
        assertEquals(polygons, PrivateSilenceAreaStore.read(context).areas.single().geometry.polygons)
        assertNull(PrivateSilenceAreaStore.readDraft(context))
    }

    private fun rectangle(lat: Double, lon: Double, span: Double) =
        listOf(GeoPoint(lat, lon), GeoPoint(lat, lon + span), GeoPoint(lat + span, lon + span), GeoPoint(lat + span, lon))
}
