package com.tencent.kuikly.compose.container

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CHANGES.md §59: the owner of the native tick hears when the scene's paused state changes — and
 * only then — so it can stop the tick while nothing needs drawing and start it before the next draw.
 */
class VsyncTickConditionsTest {

    private val heard = mutableListOf<Boolean>()
    private var scenePaused: Boolean? = null
    private val conditions = VsyncTickConditions { scenePaused = it }.apply {
        onPausedChanged = { heard += it }
    }

    /** One vsync: the scene draws if a redraw is scheduled, as ComposeSceneMediator.renderFrame does. */
    private fun tick(): Boolean {
        var drew = false
        conditions.onDisplayLinkTick { drew = scenePaused == false }
        return drew
    }

    @Test
    fun aNewListenerHearsTheCurrentStateAtOnce() {
        assertEquals(listOf(true), heard, "nothing scheduled: paused")
    }

    @Test
    fun aRedrawResumesTheTickAndTwoFramesLaterItPausesOnce() {
        heard.clear()
        conditions.needRedraw()
        assertEquals(listOf(false), heard)
        assertEquals(true, tick())
        assertEquals(listOf(false), heard, "still one frame to go")
        tick()
        assertEquals(listOf(false, true), heard, "paused after the linger, told once")
        repeat(3) { tick() }
        assertEquals(listOf(false, true), heard, "no tick is expected while paused, and none would be told")
    }

    @Test
    fun anAnimationThatAsksEveryFrameKeepsTheTickWithoutNotices() {
        heard.clear()
        conditions.needRedraw()
        repeat(10) {
            tick()
            conditions.needRedraw() // the frame's awaiters invalidate again
        }
        assertEquals(listOf(false), heard)
    }

    @Test
    fun aHeldPointerKeepsTheTickAndItsReleasePausesIt() {
        heard.clear()
        conditions.needsToBeProactive = true
        assertEquals(listOf(false), heard)
        conditions.needsToBeProactive = false
        assertEquals(listOf(false, true), heard)
    }

    @Test
    fun anInactiveApplicationPausesWhateverIsScheduled() {
        conditions.needRedraw()
        heard.clear()
        conditions.isApplicationActive = false
        assertEquals(listOf(true), heard)
        conditions.isApplicationActive = true
        assertEquals(listOf(false), heard.drop(1))
    }
}
