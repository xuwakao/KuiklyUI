/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.kuikly.compose.ui.graphics

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.geometry.isFinite
import com.tencent.kuikly.compose.ui.geometry.isSpecified
import com.tencent.kuikly.compose.ui.text.style.modulate
import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.Direction
import kotlin.math.abs

@Immutable
sealed class Brush {

    /**
     * Return the intrinsic size of the [Brush].
     * If the there is no intrinsic size (i.e. filling bounds with an arbitrary color) return
     * [Size.Unspecified].
     * If there is no intrinsic size in a single dimension, return [Size] with
     * [Float.NaN] in the desired dimension.
     */
    open val intrinsicSize: Size = Size.Unspecified

    abstract fun applyTo(size: Size, p: Paint, alpha: Float)

    abstract fun applyTo(view: DeclarativeBaseView<*, *>, alpha: Float)

    /**
     * Creates a copy of this brush with the specified alpha value.
     * @param alpha The alpha value to apply to the brush, between 0.0 and 1.0
     * @return A new brush instance with the specified alpha value
     */
    abstract fun copy(alpha: Float): Brush

    companion object {

        /**
         * Creates a linear gradient with the provided colors along the given start and end
         * coordinates. The colors are dispersed at the provided offset defined in the
         * colorstop pair.
         *
         * ```
         *  Brush.linearGradient(
         *      0.0f to Color.Red,
         *      0.3f to Color.Green,
         *      1.0f to Color.Blue,
         *      start = Offset(0.0f, 50.0f),
         *      end = Offset(0.0f, 100.0f)
         * )
         * ```
         *
         * @sample androidx.compose.ui.graphics.samples.LinearGradientColorStopSample
         * @sample androidx.compose.ui.graphics.samples.GradientBrushSample
         *
         * @param colorStops Colors and their offset in the gradient area
         * @param start Starting position of the linear gradient. This can be set to
         * [Offset.Zero] to position at the far left and top of the drawing area
         * @param end Ending position of the linear gradient. This can be set to
         * [Offset.Infinite] to position at the far right and bottom of the drawing area
         * @param tileMode Determines the behavior for how the shader is to fill a region outside
         * its bounds. Defaults to [TileMode.Clamp] to repeat the edge pixels
         */
        @Stable
        fun linearGradient(
            vararg colorStops: Pair<Float, Color>,
            start: Offset = Offset.Zero,
            end: Offset = Offset.Infinite,
            tileMode: TileMode = TileMode.Clamp
        ): Brush = LinearGradient(
            colors = List<Color>(colorStops.size) { i -> colorStops[i].second },
            stops = List<Float>(colorStops.size) { i -> colorStops[i].first },
            start = start,
            end = end,
            tileMode = tileMode
        )

        /**
         * Creates a linear gradient with the provided colors along the given start and end coordinates.
         * The colors are
         *
         * ```
         *  Brush.linearGradient(
         *      listOf(Color.Red, Color.Green, Color.Blue),
         *      start = Offset(0.0f, 50.0f),
         *      end = Offset(0.0f, 100.0f)
         * )
         * ```
         *
         * @sample androidx.compose.ui.graphics.samples.LinearGradientSample
         * @sample androidx.compose.ui.graphics.samples.GradientBrushSample
         *
         * @param colors Colors to be rendered as part of the gradient
         * @param start Starting position of the linear gradient. This can be set to
         * [Offset.Zero] to position at the far left and top of the drawing area
         * @param end Ending position of the linear gradient. This can be set to
         * [Offset.Infinite] to position at the far right and bottom of the drawing area
         * @param tileMode Determines the behavior for how the shader is to fill a region outside
         * its bounds. Defaults to [TileMode.Clamp] to repeat the edge pixels
         */
        @Stable
        fun linearGradient(
            colors: List<Color>,
            start: Offset = Offset.Zero,
            end: Offset = Offset.Infinite,
            tileMode: TileMode = TileMode.Clamp
        ): Brush = LinearGradient(
            colors = colors,
            stops = null,
            start = start,
            end = end,
            tileMode = tileMode
        )

        /**
         * Creates a horizontal gradient with the given colors evenly dispersed within the gradient
         *
         * Ex:
         * ```
         *  Brush.horizontalGradient(
         *      listOf(Color.Red, Color.Green, Color.Blue),
         *      startX = 10.0f,
         *      endX = 20.0f
         * )
         * ```
         *
         * @sample androidx.compose.ui.graphics.samples.HorizontalGradientSample
         * @sample androidx.compose.ui.graphics.samples.GradientBrushSample
         *
         * @param colors colors Colors to be rendered as part of the gradient
         * @param startX Starting x position of the horizontal gradient. Defaults to 0 which
         * represents the left of the drawing area
         * @param endX Ending x position of the horizontal gradient.
         * Defaults to [Float.POSITIVE_INFINITY] which indicates the right of the specified
         * drawing area
         * @param tileMode Determines the behavior for how the shader is to fill a region outside
         * its bounds. Defaults to [TileMode.Clamp] to repeat the edge pixels
         */
        @Stable
        fun horizontalGradient(
            colors: List<Color>,
            startX: Float = 0.0f,
            endX: Float = Float.POSITIVE_INFINITY,
            tileMode: TileMode = TileMode.Clamp
        ): Brush = linearGradient(colors, Offset(startX, 0.0f), Offset(endX, 0.0f), tileMode)

        /**
         * Creates a horizontal gradient with the given colors dispersed at the provided offset
         * defined in the colorstop pair.
         *
         * Ex:
         * ```
         *  Brush.horizontalGradient(
         *      0.0f to Color.Red,
         *      0.3f to Color.Green,
         *      1.0f to Color.Blue,
         *      startX = 0.0f,
         *      endX = 100.0f
         * )
         * ```
         *
         * @sample androidx.compose.ui.graphics.samples.HorizontalGradientColorStopSample
         * @sample androidx.compose.ui.graphics.samples.GradientBrushSample
         *
         * @param colorStops Colors and offsets to determine how the colors are dispersed throughout
         * the vertical gradient
         * @param startX Starting x position of the horizontal gradient. Defaults to 0 which
         * represents the left of the drawing area
         * @param endX Ending x position of the horizontal gradient.
         * Defaults to [Float.POSITIVE_INFINITY] which indicates the right of the specified
         * drawing area
         * @param tileMode Determines the behavior for how the shader is to fill a region outside
         * its bounds. Defaults to [TileMode.Clamp] to repeat the edge pixels
         */
        @Stable
        fun horizontalGradient(
            vararg colorStops: Pair<Float, Color>,
            startX: Float = 0.0f,
            endX: Float = Float.POSITIVE_INFINITY,
            tileMode: TileMode = TileMode.Clamp
        ): Brush = linearGradient(
            *colorStops,
            start = Offset(startX, 0.0f),
            end = Offset(endX, 0.0f),
            tileMode = tileMode
        )

        /**
         * Creates a vertical gradient with the given colors evenly dispersed within the gradient
         * Ex:
         * ```
         *  Brush.verticalGradient(
         *      listOf(Color.Red, Color.Green, Color.Blue),
         *      startY = 0.0f,
         *      endY = 100.0f
         * )
         * ```
         *
         * @sample androidx.compose.ui.graphics.samples.VerticalGradientSample
         * @sample androidx.compose.ui.graphics.samples.GradientBrushSample
         *
         * @param colors colors Colors to be rendered as part of the gradient
         * @param startY Starting y position of the vertical gradient. Defaults to 0 which
         * represents the top of the drawing area
         * @param endY Ending y position of the vertical gradient.
         * Defaults to [Float.POSITIVE_INFINITY] which indicates the bottom of the specified
         * drawing area
         * @param tileMode Determines the behavior for how the shader is to fill a region outside
         * its bounds. Defaults to [TileMode.Clamp] to repeat the edge pixels
         */
        @Stable
        fun verticalGradient(
            colors: List<Color>,
            startY: Float = 0.0f,
            endY: Float = Float.POSITIVE_INFINITY,
            tileMode: TileMode = TileMode.Clamp
        ): Brush = linearGradient(colors, Offset(0.0f, startY), Offset(0.0f, endY), tileMode)

        /**
         * Creates a vertical gradient with the given colors at the provided offset defined
         * in the [Pair<Float, Color>]
         *
         * Ex:
         * ```
         *  Brush.verticalGradient(
         *      0.1f to Color.Red,
         *      0.3f to Color.Green,
         *      0.5f to Color.Blue,
         *      startY = 0.0f,
         *      endY = 100.0f
         * )
         * ```
         *
         * @sample androidx.compose.ui.graphics.samples.VerticalGradientColorStopSample
         * @sample androidx.compose.ui.graphics.samples.GradientBrushSample
         *
         * @param colorStops Colors and offsets to determine how the colors are dispersed throughout
         * the vertical gradient
         * @param startY Starting y position of the vertical gradient. Defaults to 0 which
         * represents the top of the drawing area
         * @param endY Ending y position of the vertical gradient.
         * Defaults to [Float.POSITIVE_INFINITY] which indicates the bottom of the specified
         * drawing area
         * @param tileMode Determines the behavior for how the shader is to fill a region outside
         * its bounds. Defaults to [TileMode.Clamp] to repeat the edge pixels
         */
        @Stable
        fun verticalGradient(
            vararg colorStops: Pair<Float, Color>,
            startY: Float = 0f,
            endY: Float = Float.POSITIVE_INFINITY,
            tileMode: TileMode = TileMode.Clamp
        ): Brush = linearGradient(
            *colorStops,
            start = Offset(0.0f, startY),
            end = Offset(0.0f, endY),
            tileMode = tileMode
        )

        /**
         * Creates a sweep (angular / conic) gradient: the colors are swept around
         * [center] rather than along a line.
         * 创建扫描（角度／锥形）渐变：颜色绕 [center] 旋转分布，而非沿直线分布。
         *
         * ```
         *  Brush.sweepGradient(
         *      0.00f to Color.Red,
         *      0.25f to Color.Red,     // a hard edge: repeat the color at both ends
         *      0.25f to Color.Blue,    // 硬分界：同一颜色在两端各写一次
         *      1.00f to Color.Blue,
         *  )
         * ```
         *
         * As in `androidx.compose.ui.graphics.Brush.sweepGradient`, offset `0` is at
         * three o'clock and the sweep runs clockwise. [startAngle] rotates the whole
         * sweep and is the one addition over the androidx signature: it exists because
         * a wheel or ring almost always wants its first boundary at twelve o'clock
         * (`startAngle = -90f`), which otherwise has to be folded into every stop.
         * 与 androidx 一致：偏移 0 在三点方向、顺时针扫描。[startAngle] 旋转整个扫描，
         * 是相对 androidx 签名唯一的增补 —— 转盘/圆环通常希望首个分界在十二点方向
         *（startAngle = -90f），否则每个色标都要自行折算。
         *
         * @param colorStops Colors and their offset (0..1) around the turn
         * @param center Centre of the sweep in pixels; [Offset.Unspecified] means the
         * centre of the drawing area
         * @param startAngle Degrees the sweep is rotated by; 0 keeps androidx behaviour
         */
        @Stable
        fun sweepGradient(
            vararg colorStops: Pair<Float, Color>,
            center: Offset = Offset.Unspecified,
            startAngle: Float = 0f
        ): Brush = SweepGradient(
            colors = List<Color>(colorStops.size) { i -> colorStops[i].second },
            stops = List<Float>(colorStops.size) { i -> colorStops[i].first },
            center = center,
            startAngle = startAngle
        )

        /**
         * Ronaq: creates a radial gradient — the shape the design states its page glows
         * with, and the one this fork could not draw.
         *
         * Without it the shared layer drew a glow as a stack of 56 stroked rings on a
         * full-screen Canvas: 290 ms per frame on a Pixel 2, re-issued every frame
         * because an animation elsewhere kept the frame loop running. As a view
         * background the same gradient costs nothing per frame.
         * Ronaq：径向渐变 —— 设计描述页面光晕所用的形状，此前本 fork 无法绘制。
         *
         * Centre and radius are FRACTIONS of the view rather than pixels: a background
         * outlives any one measurement, and a pixel radius would be wrong the moment the
         * view resized. [radius] is a fraction of the view's HEIGHT: the design's glows are
         * ellipses wider than the screen, so the vertical extent is what shapes them.
         *
         * @param colorStops Colors and their offset (0..1) from the centre outward
         * @param centerX 0..1 across the view
         * @param centerY 0..1 down the view
         * @param radius fraction of the view's HEIGHT
         */
        @Stable
        fun radialGradient(
            vararg colorStops: Pair<Float, Color>,
            centerX: Float = 0.5f,
            centerY: Float = 0.5f,
            radius: Float = 0.5f
        ): Brush = RadialGradient(
            colors = List(colorStops.size) { i -> colorStops[i].second },
            stops = List(colorStops.size) { i -> colorStops[i].first },
            centerX = centerX,
            centerY = centerY,
            radius = radius
        )

        /**
         * Creates a sweep (angular / conic) gradient with the colors evenly distributed
         * around the turn.
         * 创建颜色沿一周均匀分布的扫描（锥形）渐变。
         *
         * @param colors Colors to be swept around [center]
         * @param center Centre of the sweep in pixels; [Offset.Unspecified] means the
         * centre of the drawing area
         * @param startAngle Degrees the sweep is rotated by; 0 keeps androidx behaviour
         */
        @Stable
        fun sweepGradient(
            colors: List<Color>,
            center: Offset = Offset.Unspecified,
            startAngle: Float = 0f
        ): Brush = SweepGradient(
            colors = colors,
            stops = null,
            center = center,
            startAngle = startAngle
        )
    }
}

@Immutable
class SolidColor(val value: Color) : Brush() {
    override fun applyTo(size: Size, p: Paint, alpha: Float) {
        p.alpha = DefaultAlpha
        p.color = if (alpha != DefaultAlpha) {
            value.copy(alpha = value.alpha * alpha)
        } else {
            value
        }
        if (p.shader != null) p.shader = null
    }

    override fun applyTo(view: DeclarativeBaseView<*, *>, alpha: Float) {
        view.getViewAttr().backgroundColor(value.modulate(alpha).toKuiklyColor())
    }

    override fun copy(alpha: Float): Brush {
        return SolidColor(value.copy(alpha = value.alpha * alpha))
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SolidColor) return false
        if (value != other.value) return false

        return true
    }

    override fun hashCode(): Int {
        return value.hashCode()
    }

    override fun toString(): String {
        return "SolidColor(value=$value)"
    }
}

/**
 * Brush implementation used to apply a linear gradient on a given [Paint]
 */
@Immutable
class LinearGradient internal constructor(
    val colors: List<Color>,
    val stops: List<Float>? = null,
    val start: Offset,
    val end: Offset,
    private val tileMode: TileMode = TileMode.Clamp
) : Brush() {

    private val isFinite get() = start.isFinite && end.isFinite

    override val intrinsicSize: Size
        get() =
            Size(
                if (start.x.isFinite() && end.x.isFinite() && start.x != end.x) abs(start.x - end.x) else Float.NaN,
                if (start.y.isFinite() && end.y.isFinite() && start.x != end.x) abs(start.y - end.y) else Float.NaN
            )

    val colorStops: ArrayList<ColorStop> by lazy {
        val tStops = stops ?: computeEvenlyDistributedStops(colors.size)
        val res = arrayListOf<ColorStop>()
        colors.forEachIndexed { index, color ->
            val stop = tStops.getOrNull(index) ?: 1f
            res.add(ColorStop(color.toKuiklyColor(), stop))
        }
        res
    }

    override fun applyTo(size: Size, p: Paint, alpha: Float) {
        p.alpha = DefaultAlpha
        p.shader = if (isFinite && alpha == DefaultAlpha) {
            this
        } else {
            LinearGradient(
                colors = if (alpha == DefaultAlpha) colors else colors.map { it.modulate(alpha) },
                stops = stops,
                start = if (start.isFinite) start else Offset(
                    if (start.x.isFinite()) start.x else size.width,
                    if (start.y.isFinite()) start.y else size.height
                ),
                end = if (end.isFinite) end else Offset(
                    if (end.x.isFinite()) end.x else size.width,
                    if (end.y.isFinite()) end.y else size.height
                ),
                tileMode = tileMode
            )
        }
    }

    override fun applyTo(view: DeclarativeBaseView<*, *>, alpha: Float) {
        val brush = withAlpha(alpha).resolveForView(view)
        view.getViewAttr().backgroundLinearGradient(
            brush.direction,
            *brush.colorStops.toTypedArray()
        )
    }

    override fun copy(alpha: Float): LinearGradient {
        return LinearGradient(
            colors = colors.map { it.modulate(alpha) },
            stops = stops,
            start = start,
            end = end,
            tileMode = tileMode
        )
    }

    /**
     * 应用 alpha 值，如果不需要修改则返回 this
     * @param alpha 透明度值
     * @return 如果 alpha >= 1 或 NaN 则返回 this，否则返回应用了 alpha 的新对象
     */
    fun withAlpha(alpha: Float): LinearGradient {
        return if (alpha.isNaN() || alpha >= 1f) this else copy(alpha)
    }

    /**
     * 根据 View 的实际尺寸解析渐变
     * 将像素坐标的渐变转换为归一化坐标的渐变
     * @param view 目标 View，用于获取实际尺寸
     * @return 如果无需转换则返回 this，否则返回转换后的新对象
     */
    fun resolveForView(view: DeclarativeBaseView<*, *>?): LinearGradient {
        // 相对模式（start/end 不是有限值），无需转换
        if (!isFinite) return this
        
        // 优先使用 view 的实际尺寸
        view?.renderView?.currentFrame?.let { frame ->
            return resolveForSize(frame.width, frame.height)
        }
        
        // 使用坐标中的最大值作为参考尺寸
        return resolveForText()
    }

    /**
     * 根据指定尺寸解析渐变
     * 将像素坐标的渐变转换为归一化坐标的渐变
     * @param width 参考宽度
     * @param height 参考高度
     * @return 如果无需转换则返回 this，否则返回转换后的新对象
     */
    fun resolveForSize(width: Float, height: Float): LinearGradient {
        // 相对模式，无需转换
        if (!isFinite) return this
        
        val (startPos, endPos) = computePixelPositions(width, height)
        
        // 如果映射后与原始一致（0~1 范围），复用原对象
        if (startPos == 0f && endPos == 1f) return this
        
        return createMappedGradient(startPos, endPos)
    }

    /**
     * 使用坐标中的最大值作为参考尺寸来解析渐变
     * 主要用于 Text 渐变动画等没有 View 尺寸的场景
     * @return 如果无需转换则返回 this，否则返回转换后的新对象
     */
    fun resolveForText(): LinearGradient {
        val maxCoord = maxOf(
            abs(start.x),
            abs(start.y),
            abs(end.x),
            abs(end.y)
        )
        return if (maxCoord > 0f) resolveForSize(maxCoord, maxCoord) else this
    }

    /**
     * 创建映射后的 LinearGradient
     * 将原始 stops (0~1) 映射到 startPos~endPos 范围，并处理边界情况
     */
    private fun createMappedGradient(startPos: Float, endPos: Float): LinearGradient {
        val tStops = stops ?: computeEvenlyDistributedStops(colors.size)
        val (mappedColors, mappedStops) = mapColorsAndStopsToRange(tStops, startPos, endPos)
        
        return LinearGradient(
            colors = mappedColors,
            stops = mappedStops,
            start = Offset.Zero,
            end = Offset.Infinite,
            tileMode = tileMode
        )
    }

    /**
     * 将 colors 和 stops 映射到指定范围，处理边界情况
     * 确保输出的 stops 始终覆盖 0~1 范围
     * 
     * @param tStops 原始的 stops 列表 (0~1)
     * @param startPos 渐变起点在 view 中的归一化位置
     * @param endPos 渐变终点在 view 中的归一化位置
     * @return 映射后的 (colors, stops) 对
     */
    private fun mapColorsAndStopsToRange(
        tStops: List<Float>,
        startPos: Float,
        endPos: Float
    ): Pair<List<Color>, List<Float>> {
        val firstColor = colors.firstOrNull() ?: return Pair(emptyList(), emptyList())
        val lastColor = colors.lastOrNull() ?: firstColor
        
        // 处理反向渐变的情况
        val actualStartPos = minOf(startPos, endPos)
        val actualEndPos = maxOf(startPos, endPos)
        val isReversed = startPos > endPos
        
        // 情况1：渐变完全在 view 左侧 (endPos <= 0)
        if (actualEndPos <= 0f) {
            val solidColor = if (isReversed) firstColor else lastColor
            return Pair(listOf(solidColor, solidColor), listOf(0f, 1f))
        }
        
        // 情况2：渐变完全在 view 右侧 (startPos >= 1)
        if (actualStartPos >= 1f) {
            val solidColor = if (isReversed) lastColor else firstColor
            return Pair(listOf(solidColor, solidColor), listOf(0f, 1f))
        }
        
        // 情况3：渐变与 view 有交集
        val resultColors = mutableListOf<Color>()
        val resultStops = mutableListOf<Float>()
        
        // 添加起始边界颜色
        resultColors.add(firstColor)
        resultStops.add(0f)
        if (startPos > 0f && startPos < 1f) {
            resultColors.add(firstColor)
            resultStops.add(startPos)
        }
        
        // 添加中间颜色（跳过与边界重复的颜色）
        colors.forEachIndexed { index, color ->
            val originalStop = tStops.getOrNull(index) ?: 1f
            // 跳过首尾颜色，避免重复
            if (originalStop > 0f && originalStop < 1f) {
                val mappedStop = startPos + originalStop * (endPos - startPos)
                // 只添加在 view 范围内的颜色
                if (mappedStop > 0f && mappedStop < 1f) {
                    resultColors.add(color)
                    resultStops.add(mappedStop)
                }
            }
        }
        
        // 添加结束边界颜色
        if (endPos > 0f && endPos < 1f) {
            resultColors.add(lastColor)
            resultStops.add(endPos)
        }
        resultColors.add(lastColor)
        resultStops.add(1f)
        
        // 按位置排序
        val sortedPairs = resultColors.zip(resultStops).sortedBy { it.second }
        return Pair(sortedPairs.map { it.first }, sortedPairs.map { it.second })
    }

    val direction: Direction by lazy {
        getDirection(start, end)
    }

    /**
     * 计算 start 和 end 在参考尺寸中的归一化位置
     *
     * @param refWidth 参考宽度
     * @param refHeight 参考高度
     * @return (startPos, endPos) 归一化后的位置，范围通常在 0~1，但可以超出
     */
    private fun computePixelPositions(refWidth: Float, refHeight: Float): Pair<Float, Float> {
        return when {
            // 水平方向渐变
            start.y == end.y -> Pair(start.x / refWidth, end.x / refWidth)
            // 垂直方向渐变
            start.x == end.x -> Pair(start.y / refHeight, end.y / refHeight)
            // 对角线方向渐变
            else -> {
                // 计算参考对角线长度
                val refDiagonal = kotlin.math.sqrt(refWidth * refWidth + refHeight * refHeight)
                // 计算渐变方向上的投影距离
                // 使用渐变向量的方向来计算每个点在该方向上的投影
                val gradientDx = end.x - start.x
                val gradientDy = end.y - start.y
                val gradientLength = kotlin.math.sqrt(gradientDx * gradientDx + gradientDy * gradientDy)
                
                if (gradientLength > 0f) {
                    // 归一化渐变方向向量
                    val dirX = gradientDx / gradientLength
                    val dirY = gradientDy / gradientLength
                    // 计算 start 和 end 在渐变方向上的投影
                    val startProj = (start.x * dirX + start.y * dirY) / refDiagonal
                    val endProj = (end.x * dirX + end.y * dirY) / refDiagonal
                    Pair(startProj, endProj)
                } else {
                    // 如果渐变长度为 0，返回相同位置
                    val pos = start.x / refWidth
                    Pair(pos, pos)
                }
            }
        }
    }

    private fun computeEvenlyDistributedStops(colorCount: Int): List<Float> {
        val stopsList = mutableListOf<Float>()
        if (colorCount <= 1) {
            stopsList.add(0f)
            return stopsList
        }
        for (i in 0 until colorCount) {
            stopsList.add(i.toFloat() / (colorCount - 1))
        }
        return stopsList
    }


    private fun getDirection(start: Offset, end: Offset): Direction {
        return when {
            start.y > end.y && start.x == end.x -> Direction.TO_TOP
            start.y < end.y && start.x == end.x -> Direction.TO_BOTTOM
            start.y == end.y && start.x > end.x -> Direction.TO_LEFT
            start.y == end.y && start.x < end.x -> Direction.TO_RIGHT
            start.y > end.y && start.x > end.x -> Direction.TO_TOP_LEFT
            start.y > end.y && start.x < end.x -> Direction.TO_TOP_RIGHT
            start.y < end.y && start.x > end.x -> Direction.TO_BOTTOM_LEFT
            start.y < end.y && start.x < end.x -> Direction.TO_BOTTOM_RIGHT
            else -> throw IllegalArgumentException("Invalid start and end offsets")
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LinearGradient) return false

        if (colors != other.colors) return false
        if (stops != other.stops) return false
        if (start != other.start) return false
        if (end != other.end) return false
        if (tileMode != other.tileMode) return false

        return true
    }

    override fun hashCode(): Int {
        var result = colors.hashCode()
        result = 31 * result + (stops?.hashCode() ?: 0)
        result = 31 * result + start.hashCode()
        result = 31 * result + end.hashCode()
        result = 31 * result + tileMode.hashCode()
        return result
    }

    override fun toString(): String {
        val startValue = if (start.isFinite) "start=$start, " else ""
        val endValue = if (end.isFinite) "end=$end, " else ""
        return "LinearGradient(colors=$colors, " +
                "stops=$stops, " +
                startValue +
                endValue +
                "tileMode=$tileMode)"
    }
}
/**
 * Ronaq: brush implementation for a sweep (angular / conic) gradient.
 * Ronaq：扫描（角度／锥形）渐变画刷。
 *
 * ### Why this exists
 *
 * The kit had linear gradients and nothing else, so every design that reads as a *ring*
 * — a lucky wheel divided into wedges, an avatar frame, a rotating halo — could only be
 * approximated by a horizontal ramp. For a wheel the approximation loses the thing that
 * carries the meaning: the wedge boundaries are what make the pointer say anything, and
 * a smooth left-to-right ramp has none.
 * 套件此前只有线性渐变，因此一切「环形」表达 —— 分格转盘、头像框、旋转光晕 ——
 * 只能用横向渐变近似。对转盘而言，近似恰好丢掉了承载语义的部分：
 * 有了分格边界指针才有意义，而平滑的横向渐变一条边界也没有。
 *
 * ### How it reaches a renderer
 *
 * The core layer models a background gradient as one string under the `backgroundImage`
 * prop, `linear-gradient(<directionOrdinal>,<color> <stop>,...)`, which every renderer
 * parses positionally. A sweep is emitted in the same prop as
 * `sweep-gradient(<startAngleDeg> <centreXFraction> <centreYFraction>,<color> <stop>,...)`.
 * 核心层把背景渐变建模为 backgroundImage 属性下的一个字符串，各渲染层按位置解析；
 * 扫描渐变以同一属性、上述形状下发。
 *
 * **Only the web renderer parses that form today**, so this brush asks the pager which
 * renderer it is talking to and falls back to the horizontal ramp everywhere else —
 * i.e. exactly the approximation callers write by hand now, rather than a blank view.
 * The fallback is not a preference: `KRCSSBackgroundDrawable.parseBackgroundImage`
 * (Android) slices the string at a fixed `"linear-gradient(".length` and would parse a
 * differently-prefixed value into a `NumberFormatException`, and
 * `UIView+CSS.p_tryToParseWithLinearGradient` (iOS) requires the same prefix and draws
 * nothing without it. Giving those two, and OHOS, a real sweep is a change inside each
 * renderer and is listed in `CHANGES.md` as outstanding.
 * **目前仅 Web 渲染层解析该形式**，故此画刷询问 pager 当前渲染层，其余平台回落为横向渐变 ——
 * 即调用方现在手写的那种近似，而非空白视图。回落并非偏好：Android 的解析按
 * "linear-gradient(" 的固定长度切片，前缀不同会抛 NumberFormatException；
 * iOS 则要求同一前缀，否则不绘制。为这两端与 OHOS 补真正的扫描渐变属各渲染层内部改动，
 * 已在 CHANGES.md 记为未完成项。
 */
@Immutable
/**
 * Ronaq: a radial gradient, stated in fractions of the view it fills.
 *
 * Fractions rather than pixels because this is a BACKGROUND: it outlives any one
 * measurement, and each renderer wants a different unit anyway — pixels on Android,
 * a CSS string on the web.
 */
class RadialGradient internal constructor(
    val colors: List<Color>,
    val stops: List<Float>? = null,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val radius: Float = 0.5f
) : Brush() {

    val colorStops: ArrayList<ColorStop> by lazy {
        val tStops = stops ?: List(colors.size) { i ->
            if (colors.size <= 1) 0f else i.toFloat() / (colors.size - 1)
        }
        val res = arrayListOf<ColorStop>()
        colors.forEachIndexed { index, color ->
            res.add(ColorStop(color.toKuiklyColor(), tStops.getOrNull(index) ?: 1f))
        }
        res
    }

    /**
     * Sets itself as the paint's shader, as [SweepGradient] does, and for the same
     * reason: a Canvas draw resolves the shader to null and paints the flat colour
     * rather than a wrong gradient. This brush is meant for a background, where the
     * renderer draws it properly and it costs nothing per frame.
     * 与 SweepGradient 同：Canvas 绘制时安全解析为 null，以纯色而非错误渐变绘制。
     * 本笔刷用于背景，渲染层可正确绘制且每帧成本为零。
     */
    override fun applyTo(size: Size, p: Paint, alpha: Float) {
        p.alpha = DefaultAlpha
        p.shader = if (alpha == DefaultAlpha) this else copy(alpha)
    }

    override fun applyTo(view: DeclarativeBaseView<*, *>, alpha: Float) {
        val brush = if (alpha.isNaN() || alpha >= 1f) this else copy(alpha)
        if (view.getPager().pageData.isWeb) {
            view.getViewAttr().setProp(Attr.StyleConst.BACKGROUND_IMAGE, brush.toPropValue())
        } else {
            view.getViewAttr().backgroundRadialGradient(
                brush.centerX,
                brush.centerY,
                brush.radius,
                *brush.colorStops.toTypedArray()
            )
        }
    }

    /** The `backgroundImage` value for this gradient, in the wire's own form. */
    internal fun toPropValue(): String {
        val builder = StringBuilder(RADIAL_GRADIENT_PREFIX)
        builder.append(centerX).append(' ').append(centerY).append(' ').append(radius)
        colorStops.forEach { builder.append(',').append(it) }
        return builder.append(')').toString()
    }

    override fun copy(alpha: Float): RadialGradient = RadialGradient(
        colors = colors.map { it.modulate(alpha) },
        stops = stops,
        centerX = centerX,
        centerY = centerY,
        radius = radius
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RadialGradient) return false
        if (colors != other.colors) return false
        if (stops != other.stops) return false
        if (centerX != other.centerX) return false
        if (centerY != other.centerY) return false
        if (radius != other.radius) return false
        return true
    }

    override fun hashCode(): Int {
        var result = colors.hashCode()
        result = 31 * result + (stops?.hashCode() ?: 0)
        result = 31 * result + centerX.hashCode()
        result = 31 * result + centerY.hashCode()
        result = 31 * result + radius.hashCode()
        return result
    }

    override fun toString(): String = "RadialGradient(colors=$colors, stops=$stops, " +
        "centerX=$centerX, centerY=$centerY, radius=$radius)"

    private companion object {
        const val RADIAL_GRADIENT_PREFIX = "radial-gradient("
    }
}

class SweepGradient internal constructor(
    val colors: List<Color>,
    val stops: List<Float>? = null,
    val center: Offset = Offset.Unspecified,
    val startAngle: Float = 0f
) : Brush() {

    val colorStops: ArrayList<ColorStop> by lazy {
        val tStops = stops ?: evenlyDistributedStops(colors.size)
        val res = arrayListOf<ColorStop>()
        colors.forEachIndexed { index, color ->
            val stop = tStops.getOrNull(index) ?: 1f
            res.add(ColorStop(color.toKuiklyColor(), stop))
        }
        res
    }

    /**
     * Sets itself as the paint's shader, as [LinearGradient] does. A Canvas draw does
     * not honour it yet: `Paint.toKuiklyLinearGradient` casts the shader to
     * [LinearGradient] and a sweep safely resolves to null there, so the shape is
     * painted with the paint's flat colour rather than a wrong gradient.
     * 与 LinearGradient 一样把自身设为 shader。Canvas 绘制暂不支持：
     * Paint.toKuiklyLinearGradient 将 shader 安全转型为 LinearGradient，扫描渐变在
     * 那里解析为 null，于是以纯色而非错误的渐变绘制。
     */
    override fun applyTo(size: Size, p: Paint, alpha: Float) {
        p.alpha = DefaultAlpha
        p.shader = if (alpha == DefaultAlpha) this else copy(alpha)
    }

    override fun applyTo(view: DeclarativeBaseView<*, *>, alpha: Float) {
        val brush = if (alpha.isNaN() || alpha >= 1f) this else copy(alpha)
        if (view.getPager().pageData.isWeb) {
            view.getViewAttr().setProp(Attr.StyleConst.BACKGROUND_IMAGE, brush.toPropValue(view))
        } else {
            // Same stops, laid out along the closest line the renderer can draw.
            // 同样的色标，铺成该渲染层能画的最接近的直线渐变。
            view.getViewAttr().backgroundLinearGradient(
                Direction.TO_RIGHT,
                *brush.colorStops.toTypedArray()
            )
        }
    }

    /**
     * The `backgroundImage` value for this sweep, with the centre resolved against the
     * view's own frame when it has one.
     * 本扫描渐变对应的 backgroundImage 值；视图已有 frame 时据其解析圆心。
     */
    internal fun toPropValue(view: DeclarativeBaseView<*, *>?): String {
        var centreX = CENTRE_FRACTION
        var centreY = CENTRE_FRACTION
        if (center.isSpecified) {
            val frame = view?.renderView?.currentFrame
            if (frame != null && frame.width > 0f && frame.height > 0f) {
                centreX = center.x / frame.width
                centreY = center.y / frame.height
            }
        }
        val builder = StringBuilder(SWEEP_GRADIENT_PREFIX)
        builder.append(startAngle).append(' ').append(centreX).append(' ').append(centreY)
        colorStops.forEach { builder.append(',').append(it) }
        return builder.append(')').toString()
    }

    override fun copy(alpha: Float): SweepGradient = SweepGradient(
        colors = colors.map { it.modulate(alpha) },
        stops = stops,
        center = center,
        startAngle = startAngle
    )

    private fun evenlyDistributedStops(colorCount: Int): List<Float> {
        if (colorCount <= 1) return listOf(0f)
        return List(colorCount) { i -> i.toFloat() / (colorCount - 1) }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SweepGradient) return false

        if (colors != other.colors) return false
        if (stops != other.stops) return false
        if (center != other.center) return false
        if (startAngle != other.startAngle) return false

        return true
    }

    override fun hashCode(): Int {
        var result = colors.hashCode()
        result = 31 * result + (stops?.hashCode() ?: 0)
        result = 31 * result + center.hashCode()
        result = 31 * result + startAngle.hashCode()
        return result
    }

    override fun toString(): String =
        "SweepGradient(colors=$colors, stops=$stops, center=$center, startAngle=$startAngle)"
}

/** The prefix the web renderer keys a sweep gradient on. Web 渲染层据以识别扫描渐变的前缀。 */
private const val SWEEP_GRADIENT_PREFIX = "sweep-gradient("

/** Centre of the drawing area, used when no explicit centre is given. 默认圆心。 */
private const val CENTRE_FRACTION = 0.5f
