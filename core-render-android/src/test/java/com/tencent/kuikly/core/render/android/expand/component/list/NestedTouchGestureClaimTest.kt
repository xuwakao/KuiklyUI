package com.tencent.kuikly.core.render.android.expand.component.list

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ronaq fork (CHANGES.md §33): the decision `KRRecyclerView` asks at each nested move of a list
 * nested in a horizontal list. The wiring in `onNestedPreScroll` / `onNestedScroll` has no JVM
 * seam; the device check is Ronaq `scripts/lazyrow-direction-android.mjs`, `keeps-its-gesture`.
 */
class NestedTouchGestureClaimTest {

    /** A strip that can scroll towards its right end while it has [room], and always back. */
    private class Strip(var room: Int) {
        val asked = mutableListOf<Int>()
        fun canScroll(dx: Int): Boolean {
            asked += dx
            return if (dx > 0) room > 0 else true
        }
    }

    private fun NestedTouchGestureClaim.keeps(dx: Int, strip: Strip, parentFirst: Boolean = false) =
        keeps(dx, isPager = false, parentFirst = parentFirst, canScroll = strip::canScroll)

    @Test
    fun aListThatCanScrollAtTheFirstMoveKeepsTheWholeGesture() {
        val claim = NestedTouchGestureClaim()
        val strip = Strip(room = 40)
        assertTrue(claim.keeps(30, strip))
        // Past its end: still the list's, the parent does not move mid-gesture.
        strip.room = 0
        assertTrue(claim.keeps(30, strip))
        assertTrue(claim.keeps(-30, strip))
        assertEquals(listOf(30), strip.asked, "decided once, at the first move")
    }

    @Test
    fun aListAtItsEdgeAtTheFirstMoveLetsTheParentHaveTheGesture() {
        val claim = NestedTouchGestureClaim()
        val strip = Strip(room = 0)
        assertFalse(claim.keeps(30, strip))
        // Coming back does not take it back within the same gesture.
        assertFalse(claim.keeps(-30, strip))
    }

    @Test
    fun aNewGestureDecidesAfresh() {
        val claim = NestedTouchGestureClaim()
        val strip = Strip(room = 0)
        assertFalse(claim.keeps(30, strip))
        claim.reset()
        assertTrue(claim.keeps(-30, strip))
    }

    @Test
    fun aMoveOfNothingDecidesNothing() {
        val claim = NestedTouchGestureClaim()
        val strip = Strip(room = 0)
        assertFalse(claim.keeps(0, strip))
        assertTrue(strip.asked.isEmpty())
        assertTrue(claim.keeps(-30, strip))
    }

    @Test
    fun aPagerNeverKeepsAGesture() {
        val claim = NestedTouchGestureClaim()
        assertFalse(claim.keeps(30, isPager = true, parentFirst = false, canScroll = { true }))
        assertFalse(claim.keeps(-30, isPager = true, parentFirst = false, canScroll = { true }))
    }

    @Test
    fun aParentFirstDirectionIsTheParentsOnEveryMove() {
        // PARENT_FIRST asks the parent to move first on every move of that direction
        // (`scrollParentIfNeeded`); a kept gesture must not override it (review of 2026-09-23,
        // fork finding F2). The list can scroll both ways here.
        val claim = NestedTouchGestureClaim()
        val strip = Strip(room = 400)
        assertFalse(claim.keeps(30, strip, parentFirst = true), "the parent goes first")
        assertTrue(strip.asked.isEmpty(), "a move the parent goes first on decides nothing")
        // The other direction, SELF_FIRST, decides as usual and keeps the gesture...
        assertTrue(claim.keeps(-30, strip))
        // ...and the PARENT_FIRST direction is still the parent's within the kept gesture.
        assertFalse(claim.keeps(30, strip, parentFirst = true))
        assertTrue(claim.keeps(-30, strip))
    }
}
