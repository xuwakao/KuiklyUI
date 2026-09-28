package com.tencent.kuikly.compose.extension

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `loopAnimation` prop's wire form (CHANGES.md §61): what every renderer parses, fixed here so
 * the three hosts and the Compose side agree.
 */
class LoopAnimationTest {

    @Test
    fun aTurnIsWrittenAsItsNineFields() {
        val turn = LoopAnimation(LoopProperty.ROTATE, from = 0f, to = 360f, legMillis = 2600)
        assertEquals("rotate 0 360 2600 0 linear 0 0.5 0.5", turn.toProp())
    }

    @Test
    fun aBottomAnchoredBarThereAndBack() {
        val bar = LoopAnimation(
            LoopProperty.SCALE_Y, from = 0.25f, to = 1f, legMillis = 450, reverse = true,
            easing = LoopEasing.EASE_IN_OUT, phase = 0.5f, pivotX = 0.5f, pivotY = 1f,
        )
        assertEquals("scaleY 0.25 1 450 1 easeInOut 0.5 0.5 1", bar.toProp())
    }

    @Test
    fun everyPropertyHasItsWireName() {
        assertEquals(listOf("rotate", "scaleX", "scaleY", "opacity"), LoopProperty.entries.map { it.wire })
        assertEquals(listOf("linear", "easeInOut"), LoopEasing.entries.map { it.wire })
    }

    /** Null removes the loop: an empty value, which every renderer reads as "stop and rest". */
    @Test
    fun noLoopIsTheEmptyValue() {
        assertEquals("", loopAnimationProp(null))
        assertEquals("opacity 1 0.5 800 1 linear 0 0.5 0.5",
            loopAnimationProp(LoopAnimation(LoopProperty.OPACITY, 1f, 0.5f, 800, reverse = true)))
    }
}
