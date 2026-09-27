package com.tencent.kuikly.compose.container

import com.tencent.kuikly.lifecycle.Lifecycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ronaq fork (CHANGES.md §60, which replaces §49's trigger): a page's Compose frames follow its host
 * lifecycle (§58). A page that is merely paused — a popup, a system dialog, Control Center over it —
 * is STARTED and keeps drawing; a page its host hides — the app in the background, the screen locked,
 * a full-screen page over it — is CREATED and holds its frames. §49 held them on every disappearance,
 * which froze the scene under a system dialog.
 */
class PageFrameLoopTest {

    private val events = mutableListOf<String>()
    private val conditions = VsyncTickConditions { paused -> events += if (paused) "paused" else "unpaused" }
    private val active = mutableListOf<Boolean>()
    private val loop = PageFrameLoop(setAppActive = {
        active += it
        conditions.isApplicationActive = it
    })

    @Test
    fun aPausedPageThatIsStillShownKeepsItsFrames() {
        loop.appeared()
        loop.disappeared()
        assertEquals(Lifecycle.State.STARTED, loop.state)
        assertTrue(conditions.isApplicationActive, "a popup or a system dialog over the page: the scene keeps moving")
        events.clear()
        conditions.needRedraw()
        assertEquals("unpaused", events.last(), "a redraw asked for while paused is drawn")
    }

    @Test
    fun aPageItsHostHidesHoldsItsFrames() {
        loop.appeared()
        loop.hid()
        assertEquals(Lifecycle.State.CREATED, loop.state)
        assertFalse(conditions.isApplicationActive)
        events.clear()
        conditions.needRedraw()
        assertEquals(listOf("paused"), events, "a redraw asked for while hidden waits")
    }

    @Test
    fun framesComeBackWhenTheHostShowsThePageAgainInEitherOrder() {
        // Android: the activity resumes before its window is reported visible.
        loop.appeared()
        loop.disappeared()
        loop.hid()
        loop.appeared()
        assertFalse(conditions.isApplicationActive, "resumed but still hidden: held")
        loop.showed()
        assertTrue(conditions.isApplicationActive)
        assertEquals(Lifecycle.State.RESUMED, loop.state)
        // iOS: the foreground first, then become-active.
        loop.disappeared()
        loop.hid()
        loop.showed()
        assertTrue(conditions.isApplicationActive, "shown again: frames run while the page is STARTED")
        loop.appeared()
        assertEquals(Lifecycle.State.RESUMED, loop.state)
        events.clear()
        conditions.needRedraw()
        assertEquals("unpaused", events.last())
    }

    @Test
    fun oneHoldPerHideAndAnAppearanceRedrawsWhileShown() {
        loop.hid()
        loop.hid()
        loop.disappeared()
        assertEquals(listOf(false), active, "held once")
        loop.showed()
        loop.appeared()
        assertEquals(listOf(false, true, true), active, "upstream redraws on every appearance; so does a shown page")
    }

    @Test
    fun nothingAfterDestroy() {
        loop.appeared()
        loop.destroyed()
        active.clear()
        loop.hid()
        loop.showed()
        loop.appeared()
        assertEquals(emptyList(), active)
    }
}
