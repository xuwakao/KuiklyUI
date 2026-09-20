package com.tencent.kuikly.compose.ui.platform

import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.graphics.ReusableGraphicsLayerScope
import com.tencent.kuikly.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

class RenderNodeLayerTransformTest {
    private fun layer() = RenderNodeLayer({}, {}, null)

    private fun assertPoint(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 0.002f)
        assertEquals(expected.y, actual.y, 0.002f)
    }

    @Test
    fun inverseTracksTransformsAndResizeAfterItHasBeenRead() {
        val layer = layer()
        val point = Offset(13f, 29f)
        assertPoint(point, layer.mapOffset(point, true))
        val scope = ReusableGraphicsLayerScope().apply {
            translationX = 91f
            translationY = -27f
            rotationZ = 37f
            scaleX = 1.7f
            scaleY = 0.8f
        }
        layer.resize(IntSize(100, 200))
        layer.updateLayerProperties(scope)
        repeat(3) {
            assertPoint(point, layer.mapOffset(layer.mapOffset(point, false), true))
        }
        layer.resize(IntSize(320, 500))
        assertPoint(point, layer.mapOffset(layer.mapOffset(point, false), true))
        scope.rotationZ = -65f
        scope.translationX = -12f
        layer.updateLayerProperties(scope)
        assertPoint(point, layer.mapOffset(layer.mapOffset(point, false), true))
    }

    @Test
    fun singularTransformDoesNotReusePreviousInverse() {
        val layer = layer()
        val scope = ReusableGraphicsLayerScope().apply { translationX = 50f }
        layer.updateLayerProperties(scope)
        assertPoint(Offset(10f, 20f), layer.mapOffset(Offset(60f, 20f), true))
        scope.scaleX = 0f
        layer.updateLayerProperties(scope)
        val point = Offset(60f, 20f)
        assertPoint(point, layer.mapOffset(point, true))
        scope.reset()
        layer.updateLayerProperties(scope)
        assertPoint(point, layer.mapOffset(point, true))
    }
}
