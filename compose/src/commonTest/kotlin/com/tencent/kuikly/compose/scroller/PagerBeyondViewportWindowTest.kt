package com.tencent.kuikly.compose.scroller

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import com.tencent.kuikly.compose.foundation.pager.PagerBeyondViewportWindow
import com.tencent.kuikly.compose.foundation.pager.PagerState
import com.tencent.kuikly.compose.foundation.pager.MeasuredPage
import com.tencent.kuikly.compose.foundation.pager.createPagesBeforeList
import com.tencent.kuikly.compose.foundation.pager.createPagesAfterList
import com.tencent.kuikly.compose.foundation.gestures.Orientation
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.unit.IntOffset
import com.tencent.kuikly.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class PagerBeyondViewportWindowTest {
    private fun page(index: Int) = MeasuredPage(index, 320, emptyList(), IntOffset.Zero,
        index, Orientation.Horizontal, Alignment.CenterHorizontally,
        Alignment.CenterVertically, LayoutDirection.Ltr, false)

    @Test fun pinnedVisiblePageIsNotMeasuredTwiceWhenWindowIsBehindADistantTarget() {
        assertEquals(listOf(7), createPagesAfterList(5, 10, 1, 1, listOf(5, 7), ::page).map { it.index })
        assertEquals(listOf(0), createPagesBeforeList(2, 1, 8, listOf(0, 2), ::page).map { it.index })
    }

    @Test fun firstPixelDoesNotBuildTheHiddenThirdPageAndIdleRefillsNeighbours() {
        val window = PagerBeyondViewportWindow()
        assertEquals(0..1, window.update(0, 0, 3, 1, aligned = true))
        // Pages 0 and 1 overlap the viewport. Hidden page 2 must not be built mid-frame.
        assertEquals(0..1, window.update(0, 1, 3, 1, aligned = false))
        // The native animation may publish Compose's destination before visually settling.
        assertEquals(0..1, window.update(1, 1, 3, 1, aligned = false))
        assertEquals(0..2, window.update(1, 1, 3, 1, aligned = true))
        assertEquals(0..2, window.update(0, 1, 3, 1, aligned = false))
        // Returning to page 0 still disposes non-adjacent page 2; this is not keep-all.
        assertEquals(0..1, window.update(0, 0, 3, 1, aligned = true))
    }

    @Test fun reverseCancelledAndDistantTransitionsKeepABoundedWindow() {
        val window = PagerBeyondViewportWindow()
        assertEquals(8..10, window.update(9, 9, 20, 1, true))
        assertEquals(8..10, window.update(8, 9, 20, 1, false))
        assertEquals(8..10, window.update(9, 9, 20, 1, true))
        assertEquals(8..10, window.update(1, 2, 20, 1, false))
        assertEquals(0..2, window.update(1, 1, 20, 1, true))
        assertEquals(0..1, window.update(1, 1, 2, 1, false))
        assertEquals(1..1, window.update(1, 1, 2, 0, false))
    }

    @Test fun nativeSnapCompletionNotifiesMeasureEvenWithoutAnotherScrollEvent() {
        val state = PagerState { 3 }
        val observer = SnapshotStateObserver { it() }
        var changes = 0
        val measure = Any()
        val onChanged: (Any) -> Unit = { changes++ }
        observer.start()
        try {
            observer.observeReads(measure, onChanged) { state.isSnapAnimating }
            state.isSnapAnimating = true
            Snapshot.sendApplyNotifications()
            assertEquals(1, changes)
            observer.observeReads(measure, onChanged) { state.isSnapAnimating }
            state.clearSnapAnimationState()
            Snapshot.sendApplyNotifications()
            assertEquals(2, changes)
        } finally {
            observer.stop()
            observer.clear()
        }
    }
}
