package com.tunisianprayertimes.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class QuranOverlayScrollTest {
    @Test
    fun aPageAtItsTopCanScrollItsLastLineAboveThePopup() {
        assertEquals(-300f, offset(-1_000f, top = 0f), 0f)
        assertEquals(0f, offset(1_000f, top = 0f), 0f)
    }

    @Test
    fun anAutomaticallyLiftedPageCanScrollBackToItsFirstLine() {
        // The question near the page's foot has already lifted the page above the card.
        assertEquals(240f, offset(1_000f, top = -240f), 0f)
        assertEquals(-60f, offset(-1_000f, top = -240f), 0f)
    }

    @Test
    fun scrollingKeepsItsPlaceBetweenTheTwoEdges() {
        assertEquals(100f, offset(100f, top = -240f), 0f)
        assertEquals(-40f, offset(-40f, top = -240f), 0f)
    }

    @Test
    fun aPageThatFitsAboveThePopupDoesNotScrollIntoBlankSpace() {
        assertEquals(0f, quranOverlayScrollOffset(-1_000f, 40f, 200f, 0f, 300f), 0f)
        assertEquals(0f, quranOverlayScrollOffset(1_000f, 40f, 200f, 0f, 300f), 0f)
    }

    @Test
    fun theVisibleTopInsetIsRespected() {
        assertEquals(300f, quranOverlayScrollOffset(1_000f, -240f, 600f, 60f, 300f), 0f)
    }

    @Test
    fun theAutomaticPlacementAlwaysRemainsInRange() {
        assertEquals(0f, offset(0f, top = -240f), 0f)
        assertEquals(0f, offset(0f, top = 40f), 0f)
    }

    private fun offset(value: Float, top: Float) = quranOverlayScrollOffset(value, top, 600f, 0f, 300f)
}
