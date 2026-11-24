package com.tencent.kuikly.compose.ui.node

import com.tencent.kuikly.compose.gestures.KuiklyScrollInfo
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.unit.IntOffset
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.views.HoverView
import kotlin.test.Test
import kotlin.test.assertEquals

class StickyVisualCoordinatesTest {
    @Test fun naturalPlacementNeedsNoCorrectionUntilNativePinning() {
        assertEquals(0f, stickyVisualDelta(120f, 400f, 280f, 0f))
        assertEquals(0f, stickyVisualDelta(0f, 400f, 400f, 0f))
        // The retained lazy item is synthetic, far above the screen; native Hover is at zero.
        assertEquals(240f, stickyVisualDelta(-240f, 400f, 900f, 0f))
    }

    @Test fun marginOverscrollAndLateContentMoveUseDisplayedFrame() {
        assertEquals(264f, stickyVisualDelta(-240f, 400f, 900f, 24f))
        assertEquals(0f, stickyVisualDelta(460f, 400f, -60f, 0f))
        val cache = StickyHeaderCacheManager()
        cache.getCachedStickyPosition("header", Offset(0f, 400f), true)
        val moved = cache.getCachedStickyPosition("header", Offset(0f, 580f), true)
        assertEquals(0f, stickyVisualDelta(180f, moved.y, 400f, 0f))
        val retained = cache.getCachedStickyPosition("header", Offset(0f, 860f), false)
        assertEquals(240f, stickyVisualDelta(-240f, retained.y, 900f, 0f))
    }

    @Test fun coordinatorMapsDownMoveAndUpWithLiveNativeScrollAndReverses() {
        val node = KNode(HoverView())
        val info = KuiklyScrollInfo().apply { contentOffset = 520 }
        node.updateStickyCoordinateFrame(info, -180f, 400f, 0f)
        val coordinator = node.outerCoordinator
        for (point in listOf(Offset(32f, 202f), Offset(60f, 210f), Offset(70f, 220f))) {
            val local = coordinator.fromParentPosition(point)
            assertEquals(point - Offset(0f, 180f), local)
            assertEquals(point, coordinator.toParentPosition(local))
        }
        // Native scroll can change before the next Compose frame; no frozen correction.
        info.contentOffset = 350
        assertEquals(Offset(0f, 230f), node.stickyCoordinateOffset)
        assertEquals(IntOffset.Zero, coordinator.position, "Raw placement is renderer input")
        node.updateStickyCoordinateFrame(null, 0f, 0f, 0f)
        assertEquals(Offset(32f, 202f), coordinator.fromParentPosition(Offset(32f, 202f)))
    }

    @Test fun ordinaryCoordinatorRemainsUnchanged() {
        val coordinator = LayoutNode().outerCoordinator
        val point = Offset(71f, 83f)
        assertEquals(point, coordinator.fromParentPosition(point))
        assertEquals(point, coordinator.toParentPosition(point))
    }

    @Test fun layoutModifiersApplyVisualCorrectionOnlyAtOuterBoundary() {
        val node = KNode(HoverView())
        node.applyModifier(Modifier.offset(y = 1.dp).offset(y = 2.dp))
        node.updateStickyCoordinateFrame(KuiklyScrollInfo().apply { contentOffset = 900 },
            -180f, 400f, 0f)
        var coordinator: NodeCoordinator? = node.innerCoordinator
        var point = Offset(31f, 22f)
        val path = mutableListOf<NodeCoordinator>()
        while (coordinator != null) {
            path.add(coordinator)
            point = coordinator.toParentPosition(point)
            coordinator = coordinator.wrappedBy
        }
        assertEquals(Offset(31f, 202f), point)
        for (step in path.asReversed()) point = step.fromParentPosition(point)
        assertEquals(Offset(31f, 22f), point)
        kotlin.test.assertTrue(path.size >= 3, "Exercise real multiple layout coordinators")
    }
}
