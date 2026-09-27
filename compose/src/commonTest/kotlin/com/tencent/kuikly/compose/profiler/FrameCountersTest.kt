package com.tencent.kuikly.compose.profiler

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameCountersTest {

    @AfterTest
    fun tearDown() = FrameCounters.reset()

    @Test
    fun offCountsNothing() {
        FrameCounters.reset()
        FrameCounters.onTick()
        FrameCounters.onRenderStart(animation = true, invalidation = true, input = true)
        FrameCounters.onNodeDraw()
        FrameCounters.onNodeVisit()
        FrameCounters.onNodeSkip()
        FrameCounters.onSnapshotApply()
        FrameCounters.onInfiniteFrame()
        FrameCounters.onInfiniteRunning("eq", true)
        FrameCounters.onPress()
        FrameCounters.onRenderEnd()
        assertEquals(
            "ticks=0 renders=0 cause(anim=0,inval=0,input=0) nodes(draw=0,visit=0,skip=0)" +
                " applies=0 infFrames=0 infRunning=[] pressToRenderMs=[]",
            FrameCounters.drain(),
        )
    }

    @Test
    fun aWindowCountsAndTheNextStartsFromZero() {
        FrameCounters.reset()
        FrameCounters.enabled = true
        repeat(3) { FrameCounters.onTick() }
        FrameCounters.onRenderStart(animation = true, invalidation = false, input = false)
        FrameCounters.onRenderStart(animation = true, invalidation = true, input = false)
        repeat(4) { FrameCounters.onNodeDraw() }
        repeat(2) { FrameCounters.onNodeVisit() }
        FrameCounters.onNodeSkip()
        repeat(5) { FrameCounters.onSnapshotApply() }
        repeat(6) { FrameCounters.onInfiniteFrame() }
        assertEquals(
            "ticks=3 renders=2 cause(anim=2,inval=1,input=0) nodes(draw=4,visit=2,skip=1)" +
                " applies=5 infFrames=6 infRunning=[] pressToRenderMs=[]",
            FrameCounters.drain(),
        )
        assertEquals(
            "ticks=0 renders=0 cause(anim=0,inval=0,input=0) nodes(draw=0,visit=0,skip=0)" +
                " applies=0 infFrames=0 infRunning=[] pressToRenderMs=[]",
            FrameCounters.drain(),
        )
    }

    @Test
    fun runningTransitionsAreALevelThatCarriesOver() {
        FrameCounters.reset()
        FrameCounters.enabled = true
        FrameCounters.onInfiniteRunning("homeEq", true)
        FrameCounters.onInfiniteRunning("homeEq", true)
        FrameCounters.onInfiniteRunning("shimmer", true)
        assertTrue("infRunning=[homeEq:2,shimmer:1]" in FrameCounters.drain())
        FrameCounters.onInfiniteRunning("shimmer", false)
        assertTrue("infRunning=[homeEq:2]" in FrameCounters.drain())
        assertEquals(2, FrameCounters.runningInfinite("homeEq"))
        assertEquals(0, FrameCounters.runningInfinite("shimmer"))
    }

    @Test
    fun aPressIsTimedToTheEndOfTheNextRenderOnce() {
        FrameCounters.reset()
        FrameCounters.enabled = true
        FrameCounters.onPress()
        FrameCounters.onPress() // a second press before the render does not restart the clock
        FrameCounters.onRenderEnd()
        FrameCounters.onRenderEnd() // nothing pending any more
        val line = FrameCounters.drain()
        val delays = line.substringAfter("pressToRenderMs=[").substringBefore("]")
        assertEquals(1, delays.split(",").size, line)
        assertTrue(delays.toLong() >= 0L, line)
    }
}
