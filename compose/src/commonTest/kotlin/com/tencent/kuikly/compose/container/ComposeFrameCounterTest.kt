package com.tencent.kuikly.compose.container

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ronaq fork (CHANGES.md §47): every vsync callback the scene is handed is counted, whether or
 * not a frame was due; only a draw the conditions let through is a frame.
 */
class ComposeFrameCounterTest {

    @Test
    fun everyTickCountsAndOnlyScheduledDrawsRun() {
        val conditions = VsyncTickConditions {}
        val ticksBefore = ComposeFrameCounter.vsyncTicks
        var draws = 0

        conditions.onDisplayLinkTick { draws++ } // nothing scheduled: a tick, no draw
        conditions.needRedraw()
        conditions.onDisplayLinkTick { draws++ }
        conditions.onDisplayLinkTick { draws++ }
        conditions.onDisplayLinkTick { draws++ } // the two scheduled draws are spent

        assertEquals(4L, ComposeFrameCounter.vsyncTicks - ticksBefore)
        assertEquals(VsyncTickConditions.FRAMES_COUNT_TO_SCHEDULE_ON_NEED_REDRAW, draws)
    }

    @Test
    fun aRenderedFrameCountsOnce() {
        val before = ComposeFrameCounter.framesRendered
        ComposeFrameCounter.noteFrameRendered()
        assertEquals(1L, ComposeFrameCounter.framesRendered - before)
    }
}
