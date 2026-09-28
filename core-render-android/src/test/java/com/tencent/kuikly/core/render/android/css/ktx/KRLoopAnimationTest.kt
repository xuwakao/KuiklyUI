package com.tencent.kuikly.core.render.android.css.ktx

import com.tencent.kuikly.core.render.android.css.ktx.LoopRunRule.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Ronaq fork (CHANGES.md §61): the `loopAnimation` prop on Android — its parse and when the view's
 * animator runs. The animator itself is checked on a device.
 */
class KRLoopAnimationTest {

    @Test
    fun theNineFieldsAreRead() {
        val spec = LoopSpec.parse("scaleY 0.25 1 450 1 easeInOut 0.5 0.5 1")
        assertEquals(LoopSpec(LoopSpec.Property.SCALE_Y, 0.25f, 1f, 450L, true, true, 0.5f, 0.5f, 1f), spec)
        assertEquals(900L, spec?.cycleMillis, "there and back is two legs")
        assertEquals(0L, LoopSpec.parse("rotate 0 360 2600 0 linear 0 0.5 0.5")?.let { it.cycleMillis - 2600L })
    }

    /** A malformed value or an unknown property is ignored: the view is left at rest. */
    @Test
    fun aMalformedValueIsNoLoop() {
        assertNull(LoopSpec.parse(""))
        assertNull(LoopSpec.parse("rotate 0 360"))
        assertNull(LoopSpec.parse("skew 0 1 100 0 linear 0 0.5 0.5"))
        assertNull(LoopSpec.parse("rotate a 360 2600 0 linear 0 0.5 0.5"))
        assertNull(LoopSpec.parse("rotate 0 360 0 0 linear 0 0.5 0.5"), "a leg of no length is no loop")
    }

    @Test
    fun aVisibleLoopStartsAndTheUnseenOnePauses() {
        assertEquals(Action.START, LoopRunRule.decide(removed = false, visible = true, started = false, paused = false))
        assertEquals(Action.NONE, LoopRunRule.decide(removed = false, visible = true, started = true, paused = false))
        assertEquals(Action.PAUSE, LoopRunRule.decide(removed = false, visible = false, started = true, paused = false))
        assertEquals(Action.NONE, LoopRunRule.decide(removed = false, visible = false, started = true, paused = true))
        assertEquals(Action.RESUME, LoopRunRule.decide(removed = false, visible = true, started = true, paused = true))
    }

    /** An unseen view does not start its loop; it starts when first seen, at its phase. */
    @Test
    fun anUnseenLoopWaitsToStart() {
        assertEquals(Action.NONE, LoopRunRule.decide(removed = false, visible = false, started = false, paused = false))
    }

    /** Removed, or the view reused: the loop stops and its property goes back to rest. */
    @Test
    fun aRemovedLoopStopsWhateverItWasDoing() {
        for (started in listOf(false, true)) for (paused in listOf(false, true)) for (visible in listOf(false, true)) {
            assertEquals(Action.STOP, LoopRunRule.decide(removed = true, visible = visible, started = started, paused = paused))
        }
    }

    @Test
    fun eachPropertyRestsAtItsIdentity() {
        assertEquals(0f, LoopSpec.Property.ROTATE.rest)
        assertEquals(1f, LoopSpec.Property.SCALE_X.rest)
        assertEquals(1f, LoopSpec.Property.SCALE_Y.rest)
        assertEquals(1f, LoopSpec.Property.OPACITY.rest)
    }
}
