package com.tunisianprayertimes

import java.io.File
import com.tunisianprayertimes.place.CityRef
import com.tunisianprayertimes.place.CountryResolver
import com.tunisianprayertimes.place.LatLngE5
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The picker's city search on the bundled catalogue (plan 6.8): every way of typing a city finds it, at once. */
class CitySearchTest {
    private companion object {
        val index: CitySearch.Index by lazy {
            val places = File("src/main/assets/places")
            val rows = listOf("europe-cities.tsv", "arab-cities.tsv")
                .flatMap { PlaceCatalogue.parse(places.resolve(it).readLines().asSequence()) }
            val regions = places.resolve("admin1-names.tsv").readLines().drop(1).associate {
                val (country, code, name) = it.split('\t')
                "$country.$code" to name
            }
            PlaceCatalogue.of(rows, regions).pickerIndex
        }
    }

    private fun first(query: String) = index.search(query).firstOrNull()?.name

    @Test
    fun everyShippedNamesakeHasDistinctAreaTextAndUniqueNamesHaveNone() {
        val groups = index.rows.groupBy { CountryResolver.normalize(it.ref.countryCode) to it.displayName }
        for ((name, rows) in groups) {
            val areas = rows.map { index.areaOf(it.ref.id) }
            if (rows.size == 1) assertTrue("$name", areas.single() == null)
            else assertTrue("$name: $areas", areas.none { it == null } && areas.distinct().size == rows.size)
        }
        assertEquals("الكرك", index.areaOf(250662L)?.region)
        assertEquals("معان", index.areaOf(7015781L)?.region)
    }

    @Test
    fun namesakesCloseToTheSameTownKeepTheirDistinctCoordinates() {
        fun row(id: Long, name: String, longitude: Double, population: Int) = PlaceCatalogue.Row(
            CityRef(id, LatLngE5.of(31.0, longitude), "JO", "Asia/Amman", population = population),
            name, name, "", emptyList(), null,
        )
        val east = row(1L, "Twin", 35.01, 300)
        val west = row(2L, "Twin", 34.99, 300)
        val anchor = row(3L, "Centre", 35.0, 35_000)
        val localIndex = PlaceCatalogue.of(listOf(east, west, anchor)).pickerIndex
        for (twin in listOf(east, west)) {
            assertEquals(CitySearch.Area(null, "Centre", twin.ref.point), localIndex.areaOf(twin.ref.id))
        }
    }

    @Test
    fun ivrySurSeineIsFoundHoweverItIsTyped() {
        // The owner typed «ivry sur seine» and found nothing (2026-10-18): the search folded all 26,600 cities' names
        // again on every key, a second or more on a phone, so the sheet showed the previous query's cities meanwhile.
        for (query in listOf("ivry sur seine", "Ivry-sur-Seine", "IVRY SUR SEINE", "ivry", "ivry sur", "إيفري سور سين", "إيفري")) {
            assertEquals(query, "Ivry-sur-Seine", first(query))
        }
        // From any word of the name, not only its first.
        assertTrue(index.search("sur seine").any { it.name == "Ivry-sur-Seine" })
        assertTrue(index.search("seine").any { it.name == "Ivry-sur-Seine" })
    }

    @Test
    fun aQueryScansTheFoldedNamesOnly() {
        val queries = listOf("i", "iv", "ivr", "ivry", "ivry s", "ivry sur", "ivry sur seine", "paris", "köln", "الرياض")
        index.search("warm up")
        val started = System.nanoTime()
        queries.forEach { index.search(it) }
        val ms = (System.nanoTime() - started) / 1_000_000
        // Folding every name on every key took about 1.4 s for these ten queries on the development computer.
        assertTrue("ten queries took $ms ms", ms < 500)
    }
}
