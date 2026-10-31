package com.tencent.kuikly.core.render.android.expand.component.list

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KRSpringMotionTest {
    private val stiffness = ((2 * PI / .220) * (2 * PI / .220)).toFloat()

    @Test fun shortSpringConvergesWithLongAndIrregularFrames() {
        for (frames in listOf(listOf(.016), listOf(.033), listOf(.048), listOf(.064), listOf(.016, .064, .033, .048))) {
            val spring = KRSpringMotion(0f, 1080f, 0f, stiffness, .8f)
            repeat(200) {
                spring.advance(frames[it % frames.size])
                assertTrue(spring.value.isFinite() && spring.velocity.isFinite())
                assertTrue(spring.value in -1f..1120f, "Bounded physical overshoot at $frames")
            }
            assertEquals(1080f, spring.value, .001f)
            assertEquals(0f, spring.velocity, .001f)
        }
    }

    @Test fun captured64msFrameDestabilizesOldEulerButNotExactMotion() {
        var x = 0f
        var v = 0f
        val damping = 2f * kotlin.math.sqrt(stiffness) * .8f
        repeat(12) {
            v += (-stiffness * (x - 1080f) - damping * v) * .064f
            x += v * .064f
        }
        assertTrue(abs(x) > 1_000_000f, "Regression must exercise the original instability")
        val spring = KRSpringMotion(0f, 1080f, 0f, stiffness, .8f)
        repeat(12) { spring.advance(.064) }
        assertEquals(1080f, spring.value, .01f)
    }

    @Test fun sameElapsedTimeIsIndependentOfFramePartitionForAllDampingBranches() {
        for (damping in listOf(0f, .8f, .9999f, 1f, 1.0001f, 2f)) {
            val whole = KRSpringMotion(40f, -1080f, 320f, stiffness, damping)
            val split = KRSpringMotion(40f, -1080f, 320f, stiffness, damping)
            whole.advance(.064)
            repeat(16) { split.advance(.004) }
            assertEquals(whole.value, split.value, .001f, "zeta=$damping")
            assertEquals(whole.velocity, split.velocity, .01f, "zeta=$damping")
        }
    }

    @Test fun velocityAndReverseDirectionRemainPhysical() {
        val forward = KRSpringMotion(0f, 1080f, 300f, stiffness, .8f)
        val backward = KRSpringMotion(1080f, 0f, -300f, stiffness, .8f)
        repeat(100) {
            forward.advance(.048)
            backward.advance(.048)
            assertEquals(1080f - forward.value, backward.value, .001f)
            assertEquals(-forward.velocity, backward.velocity, .001f)
        }
    }

    @Test fun stiffSpringsAndLongElapsedTimeStayFinite() {
        for (duration in listOf(.05, .22, .5)) for (damping in listOf(.2f, .8f, 1f, 2f)) {
            val k = ((2 * PI / duration) * (2 * PI / duration)).toFloat()
            val spring = KRSpringMotion(-5000f, 5000f, 8000f, k, damping)
            repeat(100) { spring.advance(.064) }
            assertTrue(spring.value.isFinite() && spring.velocity.isFinite())
            assertEquals(5000f, spring.value, .1f)
            spring.advance(60.0)
            assertEquals(5000f, spring.value, .001f)
        }
    }

    @Test fun nonPositiveElapsedTimeDoesNotMoveTheSpring() {
        val spring = KRSpringMotion(12f, 90f, 30f, stiffness, .8f)
        spring.advance(0.0)
        spring.advance(-.1)
        assertEquals(12f, spring.value)
        assertEquals(30f, spring.velocity)
    }
}
