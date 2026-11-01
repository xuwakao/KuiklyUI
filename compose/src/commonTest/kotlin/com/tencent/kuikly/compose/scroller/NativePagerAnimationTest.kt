package com.tencent.kuikly.compose.scroller

import com.tencent.kuikly.compose.animation.core.spring
import com.tencent.kuikly.compose.animation.core.tween
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NativePagerAnimationTest {
    @Test fun timedPageChangesNeverRequestAnUnderdampedNativeSpring() {
        for (duration in listOf(120,220,450)) for ((from,to) in listOf(0f to 1080f,2160f to 0f)) {
            val native = assertNotNull(convertAnimationSpecToSpringAnimation(tween(duration),from,to))
            assertEquals(1f,native.damping,"A timed page change must not cross and reverse at its target")
            assertEquals(duration,native.durationMs)
            assertEquals(0f,native.velocity)
        }
    }
    @Test fun an_explicit_bouncy_spring_remains_explicitly_bouncy() {
        val native = assertNotNull(convertAnimationSpecToSpringAnimation(spring(dampingRatio=.5f),0f,1080f))
        assertEquals(.5f,native.damping)
    }
}
