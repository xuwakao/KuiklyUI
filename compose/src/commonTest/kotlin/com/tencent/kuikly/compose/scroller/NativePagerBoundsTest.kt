package com.tencent.kuikly.compose.scroller

import com.tencent.kuikly.compose.foundation.pager.PagerState
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pixel: a fling beyond page2 of3 published target3 and crashed a page consumer. */
class NativePagerBoundsTest {
    @Test fun repeatedLastEdgeFlingsNeverPublishPageCount() {
        for (count in listOf(1,3,7)) {
            val last=count-1
            val state=PagerState(currentPage=last) { count }
            repeat(4) { assertEquals(last,state.calculateTargetPage(last,count,-1200f), "finite count=$count") }
        }
    }
    @Test fun firstEdgeStaysAtZero() {
        val state=PagerState(currentPage=0) { 3 }
        assertEquals(0,state.calculateTargetPage(0,-1,1200f))
    }
    @Test fun ordinaryFlingsStillMoveAtMostOnePage() {
        val state=PagerState(currentPage=2) { 7 }
        assertEquals(3,state.calculateTargetPage(2,99,-1200f))
        assertEquals(1,state.calculateTargetPage(2,-99,1200f))
    }
    @Test fun zeroVelocityKeepsCurrentPageWithinTheFiniteSet() {
        val state=PagerState(currentPage=2) { 3 }
        assertEquals(2,state.calculateTargetPage(2,0,0f))
    }
    @Test fun shrinkingDataCannotPublishTheRemovedPage() {
        var count=3
        val state=PagerState(currentPage=2) { count }
        count=1
        assertEquals(0,state.calculateTargetPage(2,3,-1200f))
        assertEquals(0,state.calculateTargetPage(2,3,0f))
    }
    @Test fun emptyPagerUsesTheExistingZeroSentinel() {
        val state=PagerState(currentPage=0) { 0 }
        assertEquals(0,state.calculateTargetPage(0,1,-1200f))
    }
}
