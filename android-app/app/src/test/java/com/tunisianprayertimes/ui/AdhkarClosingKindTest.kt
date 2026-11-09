package com.tunisianprayertimes.ui

import com.tunisianprayertimes.adhkar.DhikrCatalog
import com.tunisianprayertimes.adhkar.DhikrCategory
import com.tunisianprayertimes.adhkar.DhikrSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdhkarClosingKindTest {
    private val salawat = DhikrSession(itemIds = listOf(DhikrCatalog.SALAWAT_ID))

    @Test fun manualAndScheduledSalawatRemindersUseTheirOwnBenefits() {
        assertEquals(DhikrClosingKind.SALAWAT, closingKind(salawat.copy(targetCountOverride = 100), fromReminder = true))
        assertEquals(DhikrClosingKind.SALAWAT, closingKind(salawat.copy(occurrenceId = "scheduled"), fromReminder = true))
    }

    @Test fun freeLibraryReadingStillHasNoClosingCard() {
        assertNull(closingKind(salawat, fromReminder = false))
    }

    @Test fun otherRemindersAndMixedReadingsKeepGenericRewards() {
        assertEquals(DhikrClosingKind.REMINDER,
            closingKind(DhikrSession(itemIds = listOf("subhanallah_bihamdih")), fromReminder = true))
        assertEquals(DhikrClosingKind.REMINDER,
            closingKind(salawat.copy(itemIds = salawat.itemIds + "subhanallah_bihamdih"), fromReminder = true))
    }

    @Test fun collectionCompletionIsNotReclassifiedByItsLastDhikr() {
        val collection = salawat.copy(category = DhikrCategory.MORNING, collectionPeriodKey = "2026-11-06")
        assertEquals(DhikrClosingKind.MORNING, closingKind(collection, fromReminder = true))
        assertEquals(DhikrClosingKind.MORNING, closingKind(collection, fromReminder = false))
    }
}
