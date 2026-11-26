package com.tunisianprayertimes.mosque

import com.tunisianprayertimes.mosque.presence.AdaptiveVisitDetector
import com.tunisianprayertimes.mosque.presence.GeoPoint
import com.tunisianprayertimes.mosque.presence.PresenceFix
import com.tunisianprayertimes.mosque.presence.SilenceArea
import com.tunisianprayertimes.mosque.presence.SilencePolygon
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MosqueEditorArrivalTest {
    private val rows = requireNotNull(javaClass.getResourceAsStream("/mosque-adaptive-megrine.txt"))
        .bufferedReader().useLines { lines -> lines.map { it.split(' ') }.toList() }.groupBy { it.first() }
    private fun point(x: Double, y: Double) = GeoPoint(36.76868 - y / 110_972.0, 10.22545 + x / 89_273.0)
    private val outline = SilencePolygon(rows.getValue("boundary").map { point(it[1].toDouble(), it[2].toDouble()) })
    private fun walk(name: String) = rows.getValue(name).map {
        PresenceFix(point(it[2].toDouble(), it[3].toDouble()), it[4].toDouble(), it[1].toLong() * 1000L)
    }

    private fun preview(readings: List<PresenceFix>, area: SilenceArea, since: Long = 0): List<EditorTestReading> = runBlocking {
        var time = since
        val reports = mutableListOf<EditorTestReading>()
        val source = flow { for (fix in readings) { time = maxOf(time, fix.elapsedRealtimeMillis); emit(fix) } }
        val confirmed = testDraftArrival(area, source, since, { time }) { reports += it }
        assertEquals(reports.lastOrNull()?.arrival != null, confirmed)
        reports
    }

    @Test fun previewShowsTheFirstReadingAndStopsWhenTheDraftQualifies() {
        val area = SilenceArea("draft", 0, listOf(outline))
        val reports = preview(walk("inside"), area)
        assertNull(reports.first().arrival)
        assertTrue(reports.first().distance < 0)
        assertEquals(4.0, reports.first().accuracy, 0.001)
        assertEquals(3, reports.size)
        assertEquals(AdaptiveVisitDetector.Arrival.INSIDE, reports.last().arrival)
        val movedOutline = SilencePolygon(outline.exterior.map { it.copy(latitude = it.latitude + 0.01) })
        val elsewhere = preview(walk("inside"), area.copy(polygons = listOf(movedOutline)))
        assertTrue(elsewhere.all { it.arrival == null && it.distance > 900 && it.clearlyAway })
    }

    @Test fun previewUsesTheSavedStricterRuleAndIgnoresStaleMockAndExpiredReadings() {
        val area = SilenceArea("draft", 0, listOf(outline))
        assertEquals(AdaptiveVisitDetector.Arrival.NEARBY, preview(walk("shifted"), area).last().arrival)
        assertNull(preview(walk("shifted"), area.copy(requireStrongerEvidence = true)).last().arrival)
        val fix = walk("inside").first()
        assertTrue(preview(listOf(fix, fix.copy(elapsedRealtimeMillis = 1500, isMock = true),
            fix.copy(elapsedRealtimeMillis = 121_000)), area, since = 1_000).isEmpty())
    }
}
