package com.tencent.kuikly.compose.gestures

import androidx.compose.runtime.snapshots.Snapshot
import com.tencent.kuikly.compose.foundation.ExperimentalFoundationApi
import com.tencent.kuikly.compose.foundation.lazy.LazyListState
import com.tencent.kuikly.compose.foundation.lazy.grid.LazyGridState
import com.tencent.kuikly.compose.scroller.dragsBegun
import com.tencent.kuikly.compose.scroller.kuiklyOnDragBegin
import com.tencent.kuikly.core.views.ScrollParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ronaq fork (CHANGES.md §46): a scroller counts the drags its native view begins, observably, and
 * nothing else moves the count. The native event reaching [kuiklyOnDragBegin] is the scroll
 * bridge's (`SubcomposeLayout.kt`, `dragBegin`), exercised on a host by Ronaq's web e2e.
 */
@OptIn(ExperimentalFoundationApi::class)
class DragsBegunTest {

    @Test
    fun aNewStateHasBegunNoDrag() {
        assertEquals(0, LazyListState().dragsBegun)
        assertEquals(0, LazyGridState().dragsBegun)
    }

    @Test
    fun eachDragBeginCountsOnce() {
        val list = LazyListState()
        list.kuiklyOnDragBegin()
        list.kuiklyOnDragBegin()
        assertEquals(2, list.dragsBegun)
        val grid = LazyGridState()
        grid.kuiklyOnDragBegin()
        assertEquals(1, grid.dragsBegun)
    }

    @Test
    fun scrollEventsAndTheirEndDoNotCount() {
        // A fling's momentum reaches the bridge as scroll events and one scroll end, never as a
        // drag begin: the count must not move with them.
        val state = KuiklyScrollableState { it }
        state.kuiklyOnDragBegin()
        state.kuiklyOnScroll(40f)
        state.kuiklyOnScroll(25f)
        state.kuiklyOnScrollEnd(ScrollParams(0f, 65f, 400f, 1000f, 400f, 800f, isDragging = false))
        assertEquals(1, state.dragsBegun)
    }

    @Test
    fun theCountIsObservable() {
        val list = LazyListState()
        // A composition, a derivedStateOf or a snapshotFlow re-reads only what registered a read.
        val reads = mutableSetOf<Any>()
        Snapshot.observe(readObserver = { reads += it }) { list.dragsBegun }
        assertTrue(reads.isNotEmpty(), "reading dragsBegun registers no snapshot read")
    }
}
