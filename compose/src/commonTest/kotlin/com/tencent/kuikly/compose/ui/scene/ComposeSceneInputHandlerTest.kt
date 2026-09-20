@file:OptIn(com.tencent.kuikly.compose.ui.ExperimentalComposeUiApi::class,
    com.tencent.kuikly.compose.ui.node.InternalCoreApi::class)

package com.tencent.kuikly.compose.ui.scene

import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.input.pointer.PointerEventType
import com.tencent.kuikly.compose.ui.input.pointer.PointerId
import com.tencent.kuikly.compose.ui.input.pointer.PointerType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComposeSceneInputHandlerTest {
    private fun pointer(id: Long, down: Boolean, type: PointerType = PointerType.Touch) =
        ComposeScenePointer(PointerId(id), Offset(id.toFloat(), 20f), down, type)

    @Test fun releasingOneTouchPreservesOtherTouchUntilItReleases() {
        val handler = ComposeSceneInputHandler({}, {})
        handler.onPointerEvent(PointerEventType.Press, listOf(pointer(1, true), pointer(2, true)), timeMillis = 1)
        handler.onPointerEvent(PointerEventType.Release, listOf(pointer(1, false)), timeMillis = 2)
        assertEquals(Offset(2f, 20f), handler.lastKnownPointerPosition)
        handler.onPointerEvent(PointerEventType.Release, listOf(pointer(2, false)), timeMillis = 3)
        assertNull(handler.lastKnownPointerPosition)
    }

    @Test fun mouseHoverPersistsThroughReleaseButExitRemovesEvenPressedMouse() {
        val handler = ComposeSceneInputHandler({}, {})
        handler.onPointerEvent(PointerEventType.Move, listOf(pointer(3, false, PointerType.Mouse)), timeMillis = 1)
        assertEquals(Offset(3f, 20f), handler.lastKnownPointerPosition)
        handler.onPointerEvent(PointerEventType.Release, listOf(pointer(3, false, PointerType.Mouse)), timeMillis = 2)
        assertEquals(Offset(3f, 20f), handler.lastKnownPointerPosition)
        handler.onPointerEvent(PointerEventType.Exit, listOf(pointer(3, true, PointerType.Mouse)), timeMillis = 3)
        assertNull(handler.lastKnownPointerPosition)
    }

    @Test fun releasedStylusDoesNotEraseUnreportedMouse() {
        val handler = ComposeSceneInputHandler({}, {})
        handler.onPointerEvent(PointerEventType.Move, listOf(pointer(4, false, PointerType.Mouse)), timeMillis = 1)
        handler.onPointerEvent(PointerEventType.Press, listOf(pointer(5, true, PointerType.Stylus)), timeMillis = 2)
        handler.onPointerEvent(PointerEventType.Release, listOf(pointer(5, false, PointerType.Stylus)), timeMillis = 3)
        assertEquals(Offset(4f, 20f), handler.lastKnownPointerPosition)
    }
}
