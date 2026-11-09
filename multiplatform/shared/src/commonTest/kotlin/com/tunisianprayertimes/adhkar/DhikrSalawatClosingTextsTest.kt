package com.tunisianprayertimes.adhkar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DhikrSalawatClosingTextsTest {
    private val benefits = DhikrSalawatClosingTexts.ALL

    @Test fun eightDistinctApprovedBenefitsHaveHeadingsTextsAndReferences() {
        assertEquals(8, benefits.size)
        assertEquals(8, benefits.map { it.id }.toSet().size)
        assertEquals(8, benefits.map { it.heading }.toSet().size)
        benefits.forEach { benefit ->
            assertEquals(DhikrClosingTexts.ClosingTheme.SALAWAT, benefit.theme)
            assertTrue(!benefit.heading.isNullOrBlank(), benefit.id)
            assertTrue(benefit.text.isNotBlank() && benefit.reference.isNotBlank(), benefit.id)
            assertFalse(benefit.id in DhikrClosingTexts.REWARDS.map { it.id }, benefit.id)
            val copy = benefit.heading + benefit.text + benefit.reference
            assertTrue(copy.none { it.code in 0x0660..0x0669 || it.code in 0x06F0..0x06F9 }, benefit.id)
            // "Sahih Muslim" is a collection name, not an added grading label.
            assertFalse(Regex("حسن|ضعيف|الألباني|إسناد|حكم الحديث|\\(صحيح\\)").containsMatchIn(copy), benefit.id)
            assertFalse("إبراهيم" in copy, benefit.id)
        }
    }

    @Test fun fridayBenefitIsEligibleOnlyOnFriday() {
        val friday = benefits.single { it.fridayOnly }
        assertEquals("salawat_abudawud_1531", friday.id)
        assertEquals(benefits, DhikrSalawatClosingTexts.eligible(isFriday = true))
        assertEquals(7, DhikrSalawatClosingTexts.eligible(isFriday = false).size)
        assertFalse(friday in DhikrSalawatClosingTexts.eligible(isFriday = false))
    }

    @Test fun quranUsesTheBundledQalounPassage() {
        val verse = benefits.single { it.quran }
        assertEquals("الأحزاب 56", verse.reference)
        assertNotNull(MushafPassages.text(verse.id))
        assertEquals("33:56:0-12", MushafPassages.SOURCES[verse.id])
    }

    @Test fun scholarlyReflectionIsNotAttributedToTheProphet() {
        val love = benefits.single { it.id == "salawat_love" }
        assertTrue(love.text.startsWith("قال ابن القيم"))
        assertTrue(love.reference.startsWith("ابن القيم، جلاء الأفهام"))
        assertFalse("قال رسول الله" in love.text)
        assertTrue(benefits.filterNot { it.quran || it == love }.all { it.text.startsWith("قال رسول الله ﷺ:") })
    }
}
