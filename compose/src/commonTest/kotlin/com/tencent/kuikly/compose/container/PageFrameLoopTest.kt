package com.tencent.kuikly.compose.container

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ronaq fork (CHANGES.md §49): a Compose page produces no frames while it is not shown. Its scene is
 * paused and its vsync source stops when the page disappears (the app in the background, the screen
 * locked, another page over it), and both come back when it appears.
 */
class PageFrameLoopTest {

    private val events = mutableListOf<String>()
    private val conditions = VsyncTickConditions { paused -> events += if (paused) "paused" else "unpaused" }
    private val loop = PageFrameLoop(
        setAppActive = { conditions.isApplicationActive = it },
        startSource = { events += "start" },
        stopSource = { events += "stop" },
    )

    @Test
    fun aPageThatDisappearsPausesItsSceneAndStopsItsSource() {
        loop.started()
        events.clear()
        loop.disappeared()
        assertEquals(listOf("paused", "stop"), events)
        events.clear()
        conditions.needRedraw()
        assertEquals(listOf("paused"), events, "a redraw asked for meanwhile waits")
    }

    @Test
    fun itComesBackWhenThePageAppears() {
        loop.started()
        loop.disappeared()
        events.clear()
        loop.appeared()
        assertEquals("start", events.first(), "the source again")
        assertTrue(conditions.isApplicationActive)
        conditions.needRedraw()
        assertEquals("unpaused", events.last())
    }

    @Test
    fun oneStopPerDisappearanceAndNoSecondSourceOnAnAppearWhileShown() {
        loop.started()
        loop.appeared()
        loop.disappeared()
        loop.disappeared()
        loop.appeared()
        loop.appeared()
        assertEquals(1, events.count { it == "stop" })
        assertEquals(1, events.count { it == "start" })
    }

    @Test
    fun aSourceStartedWhileThePageIsNotShownStopsAtOnce() {
        loop.disappeared()
        loop.started()
        assertEquals(listOf("stop"), events.filter { it == "start" || it == "stop" })
        loop.appeared()
        assertEquals(listOf("stop", "start"), events.filter { it == "start" || it == "stop" })
    }

    @Test
    fun nothingAfterDestroy() {
        loop.started()
        loop.destroyed()
        events.clear()
        loop.disappeared()
        loop.appeared()
        assertEquals(emptyList(), events.filter { it == "start" || it == "stop" })
    }
}
