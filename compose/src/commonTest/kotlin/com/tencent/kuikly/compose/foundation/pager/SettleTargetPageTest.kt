package com.tencent.kuikly.compose.foundation.pager

import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ronaq fork (CHANGES.md §34): [PagerState.settleTargetPage] is the page a released drag is
 * settling to, observable, from the snap's start until its state is cleared.
 */
class SettleTargetPageTest {

    @Test
    fun noSettleMeansNoTarget() {
        assertEquals(-1, PagerState(currentPage = 0) { 3 }.settleTargetPage)
    }

    @Test
    fun theTargetIsSetWhenTheSnapStartsAndClearedWithIt() {
        val state = PagerState(currentPage = 0) { 3 }
        // Far from the current offset, so the snap has not reached its target on the spot.
        state.markSnapAnimationStarted(targetContentOffset = 10_000, targetPage = 1)
        assertEquals(1, state.settleTargetPage)
        state.clearSnapAnimationState()
        assertEquals(-1, state.settleTargetPage)
    }

    @Test
    fun theTargetIsObservable() {
        val state = PagerState(currentPage = 0) { 3 }
        // A composition, a derivedStateOf or a snapshotFlow re-reads only what registered a read.
        val reads = mutableSetOf<Any>()
        Snapshot.observe(readObserver = { reads += it }) { state.settleTargetPage }
        assertTrue(reads.isNotEmpty(), "reading settleTargetPage registers no snapshot read")
    }
}
