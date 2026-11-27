package com.tunisianprayertimes.ui

import com.tunisianprayertimes.quran.tafsir.TafsirBook
import org.junit.Assert.assertEquals
import org.junit.Test

class TafsirAttributionTest {
    private val publisher = "مجمع الملك فهد لطباعة المصحف الشريف"
    private val muyassar = TafsirBook(
        id = "muyassar", title = "التفسير الميسّر", source = "التفسير الميسر · $publisher",
        edition = "الإصدار 3", shareable = true, surahs = setOf(1),
    )

    @Test fun unvowelledCatalogCreditAlreadyContainsTheTitle() {
        assertEquals("التفسير الميسر · $publisher", muyassar.attribution())
        assertEquals("التفسير الميسر · $publisher · الإصدار 3", muyassar.attribution(withEdition = true))
    }

    @Test fun publisherOnlyCreditKeepsTheOfficialTitle() {
        assertEquals("التفسير الميسّر · $publisher", muyassar.copy(source = publisher).attribution())
    }

    @Test fun printReferencesRetainBothPagesWithoutRepeatingTheAuthorOrTitle() {
        val ashur = TafsirBook(
            id = "ashur", title = "التحرير والتنوير", source = "التحرير والتنوير · محمد الطاهر ابن عاشور",
            edition = "تونس 1984", shareable = true, surahs = setOf(67),
        )
        val citations = listOf(
            "التحرير والتنوير · ابن عاشور · ج 29، ص 24",
            "التحرير والتنوير · محمد الطاهر ابن عاشور · ج 29، ص 25",
        )
        assertEquals(
            "التحرير والتنوير · محمد الطاهر ابن عاشور · ج 29، ص 24 · ج 29، ص 25 · تونس 1984",
            ashur.attribution(citations, withEdition = true),
        )
    }
}
