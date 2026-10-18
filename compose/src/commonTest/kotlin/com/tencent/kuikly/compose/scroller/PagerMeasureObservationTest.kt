package com.tencent.kuikly.compose.scroller

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import com.tencent.kuikly.compose.foundation.gestures.Orientation
import com.tencent.kuikly.compose.foundation.gestures.snapping.SnapPosition
import com.tencent.kuikly.compose.foundation.pager.MeasuredPage
import com.tencent.kuikly.compose.foundation.pager.PagerMeasureResult
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.layout.MeasureResult
import com.tencent.kuikly.compose.ui.layout.AlignmentLine
import com.tencent.kuikly.compose.ui.unit.IntOffset
import com.tencent.kuikly.compose.ui.unit.LayoutDirection
import com.tencent.kuikly.compose.foundation.pager.EmptyLayoutInfo
import com.tencent.kuikly.compose.foundation.pager.PagerState
import kotlin.test.Test
import kotlin.test.assertEquals

class PagerMeasureObservationTest {
    @Test fun publishingMeasuredLayoutDoesNotSubscribeMeasureToItsOwnOutput() {
        val state = PagerState { 3 }
        Snapshot.sendApplyNotifications()
        val observer = SnapshotStateObserver { it() }
        val measure = Any()
        var invalidations = 0
        val onChanged: (Any) -> Unit = { invalidations++ }
        observer.start()
        try {
            repeat(4) {
                observer.observeReads(measure, onChanged) {
                    state.applyMeasureResult(EmptyLayoutInfo)
                }
                Snapshot.sendApplyNotifications()
            }
            assertEquals(0, invalidations, "Pager output publication must not perpetuate measurement")
        } finally { observer.stop(); observer.clear() }
    }

    @Test fun sizedResultsStillPublishPagesOffsetsAndBounds() {
        for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            val state = PagerState { 3 }
            fun result(index: Int, size: Int, offset: Float): PagerMeasureResult {
                val page = MeasuredPage(index, size, emptyList(), IntOffset.Zero, "key$index",
                    Orientation.Horizontal, Alignment.CenterHorizontally,
                    Alignment.CenterVertically, direction, false)
                return PagerMeasureResult(listOf(page), size, 4, 0, Orientation.Horizontal,
                    0, size, listOf(page), false, 0, page, page, offset, 0, index < 2,
                    SnapPosition.Start, object : MeasureResult {
                        override val width = size
                        override val height = 100
                        override val alignmentLines = emptyMap<AlignmentLine, Int>()
                        override fun placeChildren() = Unit
                    }, false, coroutineScope = EmptyLayoutInfo.coroutineScope)
            }
            state.applyMeasureResult(result(1, 320, .25f))
            assertEquals(1, state.currentPage)
            assertEquals(.25f, state.currentPageOffsetFraction)
            assertEquals(true, state.canScrollForward)
            assertEquals(true, state.canScrollBackward)
            assertEquals(320, state.layoutInfo.pageSize)
            state.applyMeasureResult(result(1, 320, .1f), visibleItemsStayedTheSame = true)
            assertEquals(.1f, state.currentPageOffsetFraction)
            state.applyMeasureResult(result(2, 400, 0f))
            assertEquals(2, state.currentPage)
            assertEquals(400, state.layoutInfo.pageSize)
            assertEquals(false, state.canScrollForward)
        }
    }

    @Test fun realMeasureInputsAndExternalLayoutReadersRemainObserved() {
        val state = PagerState { 3 }
        val input = mutableStateOf(0)
        Snapshot.sendApplyNotifications()
        val observer = SnapshotStateObserver { it() }
        var measureChanges = 0
        var layoutChanges = 0
        val onMeasure: (Any) -> Unit = { measureChanges++ }
        val onLayout: (Any) -> Unit = { layoutChanges++ }
        observer.start()
        try {
            observer.observeReads(Any(), onMeasure) {
                input.value
                state.applyMeasureResult(EmptyLayoutInfo)
            }
            Snapshot.sendApplyNotifications()
            assertEquals(0, measureChanges)
            observer.observeReads(Any(), onLayout) { state.layoutInfo }
            state.applyMeasureResult(EmptyLayoutInfo)
            Snapshot.sendApplyNotifications()
            assertEquals(1, layoutChanges, "Publishing layout still notifies external consumers")
            assertEquals(0, measureChanges)
            input.value++
            Snapshot.sendApplyNotifications()
            assertEquals(1, measureChanges, "Real measure inputs remain observed")
        } finally { observer.stop(); observer.clear() }
    }
}
