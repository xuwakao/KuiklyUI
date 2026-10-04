package com.tencent.kuikly.compose.ui.node

import com.tencent.kuikly.compose.ui.graphics.Matrix
import com.tencent.kuikly.compose.ui.node.KNode.Companion.applyTransform
import com.tencent.kuikly.compose.ui.unit.IntSize
import com.tencent.kuikly.core.base.Attr
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KNodeReflectionTest {
    private val size = IntSize(300, 500)

    private fun wire(matrix: Matrix, attr: Attr = Attr()): List<String> {
        attr.applyTransform(size, matrix)
        return (attr.getProp(Attr.StyleConst.TRANSFORM) as String).split('|')
    }

    private fun reconstructed(matrix: Matrix): Matrix {
        val props = wire(matrix)
        val scales = props[1].split(' ').map(String::toFloat)
        val translation = props[2].split(' ').map(String::toFloat)
        return Matrix().apply {
            rotateZ(props[0].toFloat())
            scale(scales[0], scales[1])
            this[3, 0] = translation[0] * size.width
            this[3, 1] = translation[1] * size.height
        }
    }

    private fun assertBasis(expected: Matrix, actual: Matrix) {
        for (row in 0..3) for (column in 0..3) {
            assertEquals(expected[row, column], actual[row, column], 0.002f,
                "matrix[$row,$column]")
        }
    }

    @Test
    fun horizontalMirrorKeepsNegativeScaleWithoutRotatingImage() {
        val props = wire(Matrix().apply { scale(-1f, 1f) })
        assertEquals(0f, props[0].toFloat(), 0.001f)
        assertEquals(listOf(-1f, 1f), props[1].split(' ').map(String::toFloat))
    }

    @Test
    fun bothAxesAndRotatedMirrorsRoundTripThroughNativeWire() {
        for (angle in listOf(0f, 90f, 180f, 270f, 37f, -65f)) {
            for (sx in listOf(-1.7f, 1.7f)) for (sy in listOf(-0.8f, 0.8f)) {
                val matrix = Matrix().apply {
                    rotateZ(angle)
                    scale(sx, sy)
                    this[3, 0] = 91f
                    this[3, 1] = -27f
                }
                assertBasis(matrix, reconstructed(matrix))
            }
        }
        val random = Random(221)
        repeat(200) {
            val matrix = Matrix().apply {
                rotateZ(random.nextFloat() * 720f - 360f)
                scale(-(0.1f + random.nextFloat() * 3f), 0.1f + random.nextFloat() * 3f)
            }
            assertBasis(matrix, reconstructed(matrix))
        }
    }

    @Test
    fun repeatedFlipResetAndSingularScaleDoNotRetainOldReflection() {
        val attr = Attr()
        repeat(3) {
            val mirrored = Matrix().apply { scale(-1f, 1f) }
            assertEquals(listOf(-1f, 1f), wire(mirrored, attr)[1].split(' ').map(String::toFloat))
            val reset = wire(Matrix(), attr)
            assertEquals(listOf(1f, 1f), reset[1].split(' ').map(String::toFloat))
            assertEquals(0f, reset[0].toFloat(), 0.001f)
        }
        val singular = wire(Matrix().apply { scale(0f, 2f) }, attr)
        assertEquals(listOf(0f, 2f), singular[1].split(' ').map(String::toFloat))
        assertTrue(singular.joinToString().contains("NaN").not())
    }

    @Test
    fun threeDimensionalAndPerspectivePathsKeepTheirExistingScalePolicy() {
        val rotated = Matrix().apply { rotateX(45f); scale(-1f, 1f) }
        val rotatedProps = wire(rotated)
        rotatedProps[1].split(' ').map(String::toFloat).forEach {
            assertEquals(1f, it, 0.000001f)
        }
        assertEquals(-180f, rotatedProps[0].toFloat(), 0.001f)
        val rotations = rotatedProps[5].split(' ').map(String::toFloat)
        assertEquals(45f, rotations[0], 0.001f)
        assertEquals(0f, rotations[1], 0.001f)
        val perspective = Matrix().apply { scale(-1f, 1f); this[0, 3] = 0.001f }
        val perspectiveProps = wire(perspective)
        assertEquals(listOf(1f, 1f), perspectiveProps[1].split(' ').map(String::toFloat))
        assertEquals(-180f, perspectiveProps[0].toFloat(), 0.001f)
    }

    @Test
    fun reflectedShearRetainsItsExistingUnsupportedShearPolicy() {
        val matrix = Matrix().apply {
            scale(-1f, 1f)
            this[1, 0] = 0.25f
        }
        val props = wire(matrix)
        assertEquals(listOf(1f, 1f), props[1].split(' ').map(String::toFloat))
        assertEquals(-180f, props[0].toFloat(), 0.001f)
    }
}
