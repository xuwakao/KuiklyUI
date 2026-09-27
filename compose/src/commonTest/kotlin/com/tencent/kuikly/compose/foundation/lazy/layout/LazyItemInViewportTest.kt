package com.tencent.kuikly.compose.foundation.lazy.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CHANGES.md §56: which placed items count as within one line of the viewport. */
class LazyItemInViewportTest {

    private data class Placed(val index: Int, val start: Int, val size: Int)

    private fun inView(items: List<Placed>, start: Int, end: Int, spacing: Int = 10): Set<Int>? =
        indicesInViewport(items, start, end, spacing, { it.index }, { it.start }, { it.size })

    /** Lines of 100 px, 10 px apart, as a list scrolled so that [first] sits at [firstStart]. */
    private fun column(first: Int, count: Int, firstStart: Int) =
        (0 until count).map { Placed(first + it, firstStart + it * 110, 100) }

    @Test
    fun theViewportAndOneLineEitherSideAreInViewTwoLinesAreNot() {
        // Viewport 0..450: lines at 0, 110, 220, 330, 440 are on the glass (440 by 10 px).
        // Placed three lines beyond each side, as a grid's beyondBoundsLineCount = 3 does.
        val placed = column(first = 7, count = 11, firstStart = -330)
        // -330 (index 7), -220 (8): beyond; -110 (9): the line before; 0..440 (10..14): on the
        // glass; 550 (15): the line after; 660, 770 (16, 17): beyond.
        assertEquals((9..15).toSet(), inView(placed, 0, 450))
    }

    @Test
    fun contentPaddingWidensTheViewportTheListReports() {
        // A list reports viewportStart = -beforeContentPadding: content under the padding shows,
        // so the line before it is one line further up.
        val placed = listOf(Placed(0, -230, 100), Placed(1, -120, 100), Placed(2, -10, 100))
        assertEquals(setOf(1, 2), inView(placed, 0, 300))
        assertEquals(setOf(0, 1, 2), inView(placed, -60, 300))
    }

    @Test
    fun aPinnedHeaderIsInViewWhateverItsIndex() {
        val placed = column(first = 40, count = 8, firstStart = -330) + Placed(index = 4, start = 0, size = 48)
        val answer = inView(placed, 0, 450)!!
        assertTrue(4 in answer)
        assertFalse(40 in answer)
    }

    @Test
    fun nothingPlacedOrNoViewportMeansNotMeasuredAndEverythingCounts() {
        assertNull(inView(emptyList(), 0, 450))
        assertNull(inView(column(0, 3, 0), 0, 0))
        val cache = InViewportCache<List<Placed>> { inView(it, 0, 0) }
        assertTrue(cache.isInViewport(column(0, 3, 0), 99))
    }

    @Test
    fun theCacheAnswersPerResultAndAnItemNotPlacedIsOutOfView() {
        var computed = 0
        val cache = InViewportCache<List<Placed>> { computed++; inView(it, 0, 450) }
        val first = column(first = 0, count = 8, firstStart = 0)
        assertTrue(cache.isInViewport(first, 0))
        assertTrue(cache.isInViewport(first, 5))
        assertFalse(cache.isInViewport(first, 6))
        assertFalse(cache.isInViewport(first, 30)) // composed, not placed by this measure
        assertEquals(1, computed)
        val scrolled = column(first = 20, count = 8, firstStart = -110)
        assertFalse(cache.isInViewport(scrolled, 0))
        assertTrue(cache.isInViewport(scrolled, 20))
        assertEquals(2, computed)
    }
}
