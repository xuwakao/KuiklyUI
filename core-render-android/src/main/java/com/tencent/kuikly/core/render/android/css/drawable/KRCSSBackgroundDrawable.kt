/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://github.com/Tencent-TDS/KuiklyUI/blob/main/LICENSE
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.kuikly.core.render.android.css.drawable

import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.SizeF
import android.view.View
import com.tencent.kuikly.core.render.android.IKuiklyRenderContext
import com.tencent.kuikly.core.render.android.const.KRCssConst
import com.tencent.kuikly.core.render.android.css.ktx.toColor
import com.tencent.kuikly.core.render.android.css.ktx.toPxF
import com.tencent.kuikly.core.render.android.css.ktx.toPxI

/**
 * 实现的样式包含:
 * 1.圆角
 * 2.渐变
 * 3.边框
 */
class KRCSSBackgroundDrawable : GradientDrawable() {

    /**
     * 是否为前景, android系统的前景Drawable没适配scrollX, scrollY场景，但是背景Drawable却有适配...
     */
    var isForeground = false
    var targetView: View? = null
    var kuiklyContext: IKuiklyRenderContext? = null


    private var borderRadiusF = BORDER_RADIUS_UNSET_VALUE
    private var borderRadii: FloatArray? = null
    var borderRadius: String = KRCssConst.EMPTY_STRING
        set(value) {
            if (field == value) {
                return
            }

            val borders = value.split(",")
            if (borders.size == BORDER_ELEMENT_SIZE) {
                val tl = kuiklyContext.toPxF(borders[BORDER_TOP_LEFT_INDEX].toFloat())
                val tr = kuiklyContext.toPxF(borders[BORDER_TOP_RIGHT_INDEX].toFloat())
                val bl = kuiklyContext.toPxF(borders[BORDER_BOTTOM_LEFT_INDEX].toFloat())
                val br = kuiklyContext.toPxF(borders[BORDER_BOTTOM_RIGHT_INDEX].toFloat())

                val raddi = floatArrayOf(
                    tl, tl,
                    tr, tr,
                    br, br,
                    bl, bl
                )
                if (isAllBorderRadiusEqual(raddi)) {
                    borderRadiusF = tl
                    borderRadii = null
                } else {
                    borderRadiusF = BORDER_RADIUS_UNSET_VALUE
                    borderRadii = raddi
                }
                updateBorderRadius()
                field = value
            }
        }

    private fun updateBorderRadius() {
        if (clipPath == null) {
            if (borderRadiusF != BORDER_RADIUS_UNSET_VALUE) {
                cornerRadius = borderRadiusF
            } else if (borderRadii != null) {
                cornerRadii = borderRadii
            }
        } else {
            cornerRadius = 0f
        }
    }

    private enum class LineStyle { SOLID, DASHED, DOTTED }
    private var lineWidth = BORDER_WIDTH_DEFAULT_VALUE
    private var lineStyle = LineStyle.SOLID
    private var lineColor = Color.TRANSPARENT
    private val linePaint by lazy(LazyThreadSafetyMode.NONE) { Paint() }
    val borderWidth get() = lineWidth
    var borderStyle: String = KRCssConst.EMPTY_STRING
        set(value) {
            if (field == value) {
                return
            }

            val borderStyles = value.split(KRCssConst.BLANK_SEPARATOR)
            if (borderStyles.size != BORDER_STYLE_ELEMENT_SIZE) {
                return
            }

            lineWidth = kuiklyContext.toPxI(borderStyles[BORDER_STYLE_WIDTH_INDEX].toFloat())
            lineStyle = when (borderStyles[BORDER_LINE_STYLE_INDEX]) {
                "dashed" -> LineStyle.DASHED
                "dotted" -> LineStyle.DOTTED
                else -> LineStyle.SOLID
            }
            lineColor = borderStyles[BORDER_STYLE_LINE_COLOR].toColor()
            updateBorderStyle()
            field = value
        }

    private fun updateBorderStyle() {
        if (lineWidth <= 0 || lineColor == Color.TRANSPARENT) {
            setStroke(0, Color.TRANSPARENT)
            return
        }
        if (clipPath == null) {
            when (lineStyle) {
                LineStyle.SOLID -> {
                    setStroke(lineWidth, ColorStateList.valueOf(lineColor))
                }
                LineStyle.DASHED -> {
                    setStroke(lineWidth,
                        ColorStateList.valueOf(lineColor),
                        lineWidth * BORDER_DASH_WIDTH,
                        lineWidth * BORDER_DASH_GAP
                    )
                }
                LineStyle.DOTTED -> {
                    setStroke(lineWidth,
                        ColorStateList.valueOf(lineColor),
                        lineWidth.toFloat(),
                        lineWidth.toFloat()
                    )
                }
            }
        } else {
            setStroke(0, Color.TRANSPARENT)
            linePaint.reset()
            linePaint.style = Paint.Style.STROKE
            linePaint.strokeWidth = lineWidth.toFloat()
            linePaint.color = lineColor
            when (lineStyle) {
                LineStyle.SOLID -> {
                    // do nothing
                }
                LineStyle.DASHED -> {
                    linePaint.pathEffect = DashPathEffect(
                        floatArrayOf(lineWidth * BORDER_DASH_WIDTH, lineWidth * BORDER_DASH_GAP),
                        0f
                    )
                }
                LineStyle.DOTTED -> {
                    linePaint.pathEffect = DashPathEffect(
                        floatArrayOf(lineWidth.toFloat(), lineWidth.toFloat()),
                        0f
                    )
                }
            }
        }
    }

    var backgroundImage: String = KRCssConst.EMPTY_STRING
        set(value) {
            if (field == value) {
                return
            }
            updateBackgroundImage(value)
            field = value
        }

    var clipPath: Path? = null
        set(value) {
            field = value
            updateBorderRadius()
            updateBorderStyle()
        }

    private fun updateBackgroundImage(backgroundImage: String) {
        if (backgroundImage == KRCssConst.EMPTY_STRING) {
            // Ronaq: a drawable is reused across views, so the gradient *type* has to be
            // put back as well as the colors — otherwise the next linear gradient on this
            // view would still be drawn as a sweep.
            // Ronaq: drawable 会跨视图复用，除颜色外还需复位渐变类型，
            // 否则该视图上的下一个线性渐变仍会按扫描渐变绘制。
            gradientType = GradientDrawable.LINEAR_GRADIENT
            colors = intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT) // 清除渐变背景
            return
        }
        val sweep = parseSweepGradient(backgroundImage)
        if (sweep != null) {
            applySweepGradient(sweep)
            return
        }
        val radial = parseRadialGradient(backgroundImage)
        if (radial != null) {
            applyRadialGradient(radial)
            return
        }
        // Ronaq: a view that carried a radial must forget it, for the same reason the
        // sweep reset above exists — the drawable is reused across views.
        pendingRadial = null
        gradientType = GradientDrawable.LINEAR_GRADIENT
        val backgroundImageTriple = parseBackgroundImage(backgroundImage)
        orientation = backgroundImageTriple.first
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setColors(backgroundImageTriple.second, backgroundImageTriple.third)
        } else {
            colors = backgroundImageTriple.second
        }
    }

    /**
     * Ronaq: draw a sweep (conic) gradient with the platform's own [SweepGradient].
     * Ronaq：以平台自身的 SweepGradient 绘制扫描（锥形）渐变。
     *
     * [GradientDrawable] can build a sweep shader — but only ever with `null` positions:
     * its `SWEEP_GRADIENT` branch passes `tempPositions`, which stays `null` unless
     * `mUseLevel` is set, so `setColors(colors, offsets)` has no effect on a sweep on any
     * API level (checked against platform sources for API 30, 35 and 36). The stops are
     * therefore resolved here into an evenly spaced color ramp, which is exactly what a
     * `null` position array means to the shader.
     * GradientDrawable 能构建扫描着色器，但其 SWEEP_GRADIENT 分支恒以 null 位置数组
     * 调用（除非 mUseLevel），即 setColors(colors, offsets) 对扫描渐变不生效
     * （已对照 API 30/35/36 平台源码确认）。故此处先把色标解析为等距色带，
     * 这正是 null 位置数组对着色器的含义。
     */
    /**
     * Ronaq: the radial gradient this drawable is currently painting, or null.
     *
     * Held because [GradientDrawable.gradientRadius] is a length in PIXELS while the
     * wire states a fraction of the view — the design writes `radial-gradient(50% 0%,
     * … 60%)` — so the radius cannot be resolved until the bounds are known and must be
     * resolved again whenever they change.
     * Ronaq：径向渐变的半径在平台 API 中是像素，而线上格式是视图的比例，
     * 故须在得到边界后解析，并在边界变化时重解析。
     */
    private var pendingRadial: KRCSSRadialGradient? = null

    /**
     * Ronaq: draw a radial gradient with the platform's own shader.
     * Ronaq：以平台自身的着色器绘制径向渐变。
     *
     * This is why it exists: the design states its page glow as a CSS radial gradient,
     * and with no renderer support the shared layer drew it as a stack of 56 stroked
     * rings on a full-screen Canvas — 290 ms per frame on a Pixel 2, re-issued on every
     * frame because a logo animation kept the frame loop running. Measured at 100%
     * janky, 350–400 ms; the same screen with the rings removed measured 9 ms.
     * 设计将页面光晕写为 CSS 径向渐变；渲染层此前不支持，共享层遂以 56 个描边圆环绘制，
     * 在 Pixel 2 上每帧 290ms，且因常驻动画而每帧重画。实测 100% 掉帧、350–400ms；
     * 移除圆环后同一屏为 9ms。
     */
    private fun applyRadialGradient(radial: KRCSSRadialGradient) {
        pendingRadial = radial
        gradientType = RADIAL_GRADIENT
        setGradientCenter(radial.centerX, radial.centerY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setColors(radial.colors, radial.offsets)
        } else {
            colors = radial.colors
        }
        resolveRadialRadius(bounds.width(), bounds.height())
    }

    /**
     * Ronaq: a radius of zero makes the shader throw, so an unmeasured view keeps the
     * last usable value rather than crashing on its first layout pass.
     * Ronaq：半径为 0 会使着色器抛错，故未测量的视图沿用上一个可用值而非崩溃。
     */
    private fun resolveRadialRadius(width: Int, height: Int) {
        val radial = pendingRadial ?: return
        // Ronaq: the HEIGHT, not the shorter side. The design's glows are ellipses whose
        // horizontal extent is wider than the screen in every skin, so a circle of the
        // vertical radius differs from the stated ellipse by less than the gradient's
        // own band width — while a circle of the WIDTH would be a different shape.
        // Ronaq：以高度为基准而非短边。设计的光晕为椭圆，其横向半径在各皮肤中均宽于屏幕，
        // 故取竖向半径的圆与所述椭圆之差小于渐变自身的带宽；取宽度则形状完全不同。
        if (height <= 0) return
        gradientRadius = radial.radiusFraction * height
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        resolveRadialRadius(bounds.width(), bounds.height())
    }

    private fun applySweepGradient(sweep: KRCSSSweepGradient) {
        gradientType = GradientDrawable.SWEEP_GRADIENT
        setGradientCenter(sweep.centerX, sweep.centerY)
        colors = sweep.samples
    }

    override fun draw(canvas: Canvas) {
        if (isForeground) {
            val scrollX = targetView?.scrollX?.toFloat() ?: 0f
            val scrollY = targetView?.scrollY?.toFloat() ?: 0f
            drawWithScrollXY(scrollX, scrollY, canvas)
        } else {
            drawWithClipPath(canvas)
        }
    }

    private fun drawWithScrollXY(scrollX: Float, scrollY: Float, canvas: Canvas) {
        if (scrollX == 0f && scrollY == 0f) {
            drawWithClipPath(canvas)
        } else {
            canvas.translate(scrollX, scrollY)
            drawWithClipPath(canvas)
            canvas.translate(-scrollX, -scrollY)
        }
    }

    private fun drawWithClipPath(canvas: Canvas) {
        if (clipPath == null) {
            super.draw(canvas)
        } else {
            val checkpoint = canvas.save()
            canvas.clipPath(clipPath!!)
            super.draw(canvas)
            canvas.restoreToCount(checkpoint)
            if (lineWidth > 0 && lineColor != Color.TRANSPARENT) {
                canvas.drawPath(clipPath!!, linePaint)
            }
        }
    }

    companion object {
        const val BORDER_ELEMENT_SIZE = 4
        const val BORDER_TOP_LEFT_INDEX = 0
        const val BORDER_TOP_RIGHT_INDEX = 1
        const val BORDER_BOTTOM_LEFT_INDEX = 2
        const val BORDER_BOTTOM_RIGHT_INDEX = 3

        private const val BORDER_RADII_TL = 0
        private const val BORDER_RADII_TR = 2
        private const val BORDER_RADII_BL = 4
        private const val BORDER_RADII_BR = 6

        private const val BORDER_STYLE_ELEMENT_SIZE = 3
        private const val BORDER_STYLE_WIDTH_INDEX = 0
        private const val BORDER_LINE_STYLE_INDEX = 1
        private const val BORDER_STYLE_LINE_COLOR = 2

        private const val BORDER_RADIUS_UNSET_VALUE = -1.0f
        private const val BORDER_WIDTH_DEFAULT_VALUE = 0

        private const val BORDER_DASH_GAP = 1.5f
        private const val BORDER_DASH_WIDTH = 3f

        private const val BACKGROUND_IMAGE_DIRECTION_INDEX = 0
        private const val BACKGROUND_IMAGE_DIRECTION_BOTTOM_TOP = 0
        private const val BACKGROUND_IMAGE_DIRECTION_TOP_BOTTOM = 1
        private const val BACKGROUND_IMAGE_DIRECTION_RIGHT_LEFT = 2
        private const val BACKGROUND_IMAGE_DIRECTION_LEFT_RIGHT = 3
        private const val BACKGROUND_IMAGE_DIRECTION_BR_TL = 4
        private const val BACKGROUND_IMAGE_DIRECTION_BL_TR = 5
        private const val BACKGROUND_IMAGE_DIRECTION_TR_BL = 6
        private const val BACKGROUND_IMAGE_DIRECTION_TL_BR = 7
        private const val BACKGROUND_IMAGE_COLORS_COLOR_INDEX = 0
        private const val BACKGROUND_IMAGE_COLORS_OFFSET_INDEX = 1

        // Ronaq: the two forms the `backgroundImage` prop can carry.
        // Ronaq：backgroundImage 属性可承载的两种形式。
        private const val LINEAR_GRADIENT_PREFIX = "linear-gradient("
        private const val SWEEP_GRADIENT_PREFIX = "sweep-gradient("
        private const val RADIAL_GRADIENT_PREFIX = "radial-gradient("

        private const val RADIAL_HEAD_CENTER_X_INDEX = 0
        private const val RADIAL_HEAD_CENTER_Y_INDEX = 1
        private const val RADIAL_HEAD_RADIUS_INDEX = 2
        private const val RADIAL_CENTER_DEFAULT = 0.5f
        private const val RADIAL_RADIUS_DEFAULT = 0.5f

        private const val SWEEP_HEAD_ANGLE_INDEX = 0
        private const val SWEEP_HEAD_CENTER_X_INDEX = 1
        private const val SWEEP_HEAD_CENTER_Y_INDEX = 2
        private const val SWEEP_CENTER_DEFAULT = 0.5f
        private const val DEGREES_PER_TURN = 360f

        /**
         * Ronaq: where the wire's offset `0` sits relative to [SweepGradient]'s own zero.
         * Ronaq：线上偏移 0 相对 SweepGradient 自身零点的位置。
         *
         * The wire measures `startAngle` from twelve o'clock and carries a further -90
         * (see the web renderer's `getCSSConicGradient`, whose CSS `from` angle is
         * `startAngle - 90`), while [SweepGradient] starts at three o'clock. Twelve
         * o'clock is a quarter turn *before* three o'clock, so the two conventions differ
         * by -90 - 90 = -180 degrees.
         * 线上 startAngle 自十二点起算并再偏 -90（见 Web 渲染层 getCSSConicGradient），
         * 而 SweepGradient 自三点起算；十二点比三点早四分之一圈，故两者相差 -180 度。
         */
        private const val SWEEP_WIRE_ZERO_DEG = -180f

        /**
         * Ronaq: how finely the turn is sampled for the platform's evenly spaced ramp.
         * Ronaq：为平台等距色带采样一圈的精度。
         *
         * A hard boundary (the same color written at two adjacent offsets, which is how a
         * wheel divides into wedges) survives as a blend one sample wide, here
         * `360 / 1023 = 0.35` degrees. On the design's 228dp wheel disc at 3x — 684px
         * across, 2149px around — that is a 2.1px blend, and the ramp costs one 4KB int
         * array per background change.
         * 硬分界（同色写在相邻两个偏移上，即转盘分格的做法）会退化为一个采样宽度的过渡，
         * 即 0.35 度；在设计的 228dp 转盘上（3 倍屏，直径 684px、周长 2149px）
         * 相当于 2.1 像素，代价是每次背景变更一个 4KB 数组。
         */
        private const val SWEEP_SAMPLE_COUNT = 1024

        fun isAllBorderRadiusEqual(radii: FloatArray): Boolean {
            val tl = radii[BORDER_RADII_TL]
            val tr = radii[BORDER_RADII_TR]
            val bl = radii[BORDER_RADII_BL]
            val br = radii[BORDER_RADII_BR]
            return tl == tr && tl == bl && tl == br
        }

        fun parseBackgroundImage(backgroundImage: String): Triple<Orientation, IntArray, FloatArray> {
            // Ronaq: a sweep gradient travels in the same prop, so a caller that can only
            // draw a line (a text foreground span, an image mask) reaches this function
            // with it. It used to slice at a fixed `"linear-gradient(".length` and then
            // call `toInt()` on whatever that produced — a NumberFormatException rather
            // than a fallback. Those callers now get the same stops laid along a line,
            // which is the approximation they would have had to write by hand.
            // Ronaq：扫描渐变走同一属性，只会画直线的调用方（文字前景 span、图片蒙版）
            // 也会走到这里。此前按 "linear-gradient(" 的固定长度切片再 toInt()，
            // 前缀不同即抛 NumberFormatException 而非回落；现改为把同一组色标铺成直线。
            if (isSweepGradient(backgroundImage)) {
                val stops = parseColorStops(splitGradient(backgroundImage, SWEEP_GRADIENT_PREFIX))
                    ?: return transparentGradient()
                return Triple(Orientation.LEFT_RIGHT, stops.colors, stops.offsets)
            }
            if (!backgroundImage.startsWith(LINEAR_GRADIENT_PREFIX)) {
                return transparentGradient()
            }
            val lg = backgroundImage.substring(LINEAR_GRADIENT_PREFIX.length, backgroundImage.length - 1)
            val splits = lg.split(",")

            // parse color
            val colors = IntArray(splits.size - 1) // 因为是从1开始遍历, 所以size要减1
            val offsets = FloatArray(splits.size - 1) // 因为是从1开始遍历, 所以size要减1
            for (i in 1 until splits.size) { // colors在splits数组中的index为1, 因此从1开始遍历
                val colorAndOffset = splits[i].trim().split(KRCssConst.BLANK_SEPARATOR)

                val color = colorAndOffset[BACKGROUND_IMAGE_COLORS_COLOR_INDEX]
                colors[i - 1] = color.toColor()

                offsets[i - 1] = colorAndOffset[BACKGROUND_IMAGE_COLORS_OFFSET_INDEX].toFloat()
            }

            // parse direction
            val direction = convertDirection(splits[BACKGROUND_IMAGE_DIRECTION_INDEX].toInt())

            return Triple(direction, colors, offsets)
        }

        /**
         * Ronaq: parse `radial-gradient(<cxFrac> <cyFrac> <rFrac>,<argb> <stop>,…)`.
         * Ronaq：解析上述径向渐变字符串。
         *
         * The head is a triple like the sweep's, and for the same reason: the wire
         * cannot carry CSS's own `at <position>` syntax without a parser on every
         * renderer, so the fractions are stated positionally and each renderer converts
         * to whatever its platform wants — pixels here, a CSS string on the web.
         *
         * Returns null for anything that is not a radial, so the caller carries on.
         */
        fun parseRadialGradient(backgroundImage: String): KRCSSRadialGradient? {
            if (!backgroundImage.startsWith(RADIAL_GRADIENT_PREFIX)) {
                return null
            }
            val parts = splitGradient(backgroundImage, RADIAL_GRADIENT_PREFIX)
            val head = parts.firstOrNull()?.trim()?.split(KRCssConst.BLANK_SEPARATOR).orEmpty()
            val stops = parseColorStops(parts) ?: return null
            return KRCSSRadialGradient(
                centerX = head.getOrNull(RADIAL_HEAD_CENTER_X_INDEX)?.toFloatOrNull()
                    ?: RADIAL_CENTER_DEFAULT,
                centerY = head.getOrNull(RADIAL_HEAD_CENTER_Y_INDEX)?.toFloatOrNull()
                    ?: RADIAL_CENTER_DEFAULT,
                radiusFraction = head.getOrNull(RADIAL_HEAD_RADIUS_INDEX)?.toFloatOrNull()
                    ?: RADIAL_RADIUS_DEFAULT,
                colors = stops.colors,
                offsets = stops.offsets,
            )
        }

        /** Ronaq: does this `backgroundImage` carry a sweep gradient? 是否为扫描渐变。 */
        fun isSweepGradient(backgroundImage: String): Boolean =
            backgroundImage.startsWith(SWEEP_GRADIENT_PREFIX)

        /**
         * Ronaq: parse `sweep-gradient(<startAngleDeg> <cxFrac> <cyFrac>,<argb> <stop>,…)`
         * into the center and the evenly spaced color ramp [SweepGradient] wants.
         * Ronaq：把上述扫描渐变字符串解析为圆心与 SweepGradient 所需的等距色带。
         *
         * Returns `null` for anything that is not a sweep, so the caller can carry on
         * down the linear path.
         * 非扫描渐变返回 null，调用方据此继续走线性路径。
         */
        fun parseSweepGradient(backgroundImage: String): KRCSSSweepGradient? {
            if (!isSweepGradient(backgroundImage)) {
                return null
            }
            val splits = splitGradient(backgroundImage, SWEEP_GRADIENT_PREFIX)
            val stops = parseColorStops(splits) ?: return null
            val head = splits.first().trim().split(KRCssConst.BLANK_SEPARATOR)
            val startAngle = head.getOrNull(SWEEP_HEAD_ANGLE_INDEX)?.toFloatOrNull() ?: 0f
            val centerX = head.getOrNull(SWEEP_HEAD_CENTER_X_INDEX)?.toFloatOrNull()
                ?: SWEEP_CENTER_DEFAULT
            val centerY = head.getOrNull(SWEEP_HEAD_CENTER_Y_INDEX)?.toFloatOrNull()
                ?: SWEEP_CENTER_DEFAULT
            val shift = wrapTurn((startAngle + SWEEP_WIRE_ZERO_DEG) / DEGREES_PER_TURN)
            return KRCSSSweepGradient(centerX, centerY, sampleTurn(stops, shift))
        }

        /**
         * Ronaq: the body of `<name>(…)`, split on commas. Head first, then the stops.
         * Ronaq：取 `<name>(…)` 的内容按逗号切分，首项为头部，其余为色标。
         */
        private fun splitGradient(value: String, prefix: String): List<String> {
            val end = if (value.endsWith(")")) value.length - 1 else value.length
            if (end <= prefix.length) {
                return emptyList()
            }
            return value.substring(prefix.length, end).split(",")
        }

        /**
         * Ronaq: the `<argb> <stop>` pairs that follow the head, in wire order.
         * Ronaq：头部之后的 `<argb> <stop>` 色标序列，保持线上顺序。
         */
        private fun parseColorStops(splits: List<String>): KRCSSGradientStops? {
            if (splits.size < 2) {
                return null
            }
            val colors = ArrayList<Int>(splits.size - 1)
            val offsets = ArrayList<Float>(splits.size - 1)
            for (i in 1 until splits.size) {
                val colorAndOffset = splits[i].trim().split(KRCssConst.BLANK_SEPARATOR)
                val color = colorAndOffset.getOrNull(BACKGROUND_IMAGE_COLORS_COLOR_INDEX)
                if (color.isNullOrEmpty()) {
                    continue
                }
                colors.add(color.toColor())
                offsets.add(
                    colorAndOffset.getOrNull(BACKGROUND_IMAGE_COLORS_OFFSET_INDEX)?.toFloatOrNull()
                        ?: 0f
                )
            }
            if (colors.isEmpty()) {
                return null
            }
            if (colors.size == 1) {
                // A shader needs two ends even when the design only names one color.
                // 即便设计只给一种颜色，着色器仍需两端。
                colors.add(colors[0])
                offsets[0] = 0f
                offsets.add(1f)
            }
            return KRCSSGradientStops(colors.toIntArray(), offsets.toFloatArray())
        }

        /**
         * Ronaq: resolve the stops into [SWEEP_SAMPLE_COUNT] evenly spaced colors, turned
         * by [shift] so the wire's offset `0` lands where the design puts it.
         * Ronaq：把色标解析为 SWEEP_SAMPLE_COUNT 个等距颜色，并按 shift 旋转，
         * 使线上偏移 0 落在设计规定的方位。
         *
         * Sample `i` is drawn at turn `i / (n - 1)` measured from three o'clock, and shows
         * the color the wire declares at `that - shift`, wrapped around the turn.
         * 第 i 个采样绘制于自三点起算的 i/(n-1) 圈处，取线上在 (该值 - shift) 处的颜色，
         * 越界则绕圈回卷。
         */
        private fun sampleTurn(stops: KRCSSGradientStops, shift: Float): IntArray {
            val last = SWEEP_SAMPLE_COUNT - 1
            return IntArray(SWEEP_SAMPLE_COUNT) { i ->
                rampColorAt(stops, wrapTurn(i / last.toFloat() - shift))
            }
        }

        /** Ronaq: fold any number of turns into `[0, 1)`. 将任意圈数折回 [0,1)。 */
        private fun wrapTurn(turn: Float): Float {
            val wrapped = turn % 1f
            return if (wrapped < 0f) wrapped + 1f else wrapped
        }

        /**
         * Ronaq: the color the stop list declares at [at], clamped outside its range —
         * the same reading CSS gives a `conic-gradient` whose stops do not span the turn.
         * Ronaq：色标列表在 at 处的颜色，超出范围则钳制 —— 与 CSS conic-gradient
         * 对未铺满整圈的色标的处理一致。
         */
        private fun rampColorAt(stops: KRCSSGradientStops, at: Float): Int {
            val offsets = stops.offsets
            val colors = stops.colors
            if (at <= offsets.first()) {
                return colors.first()
            }
            for (i in 0 until offsets.size - 1) {
                val from = offsets[i]
                val to = offsets[i + 1]
                if (at >= from && at < to) {
                    return lerpColor(colors[i], colors[i + 1], (at - from) / (to - from))
                }
            }
            return colors.last()
        }

        /** Ronaq: straight per-channel ARGB interpolation. 按 ARGB 各通道线性插值。 */
        private fun lerpColor(from: Int, to: Int, fraction: Float): Int {
            if (fraction <= 0f) {
                return from
            }
            if (fraction >= 1f) {
                return to
            }
            return Color.argb(
                lerpChannel(Color.alpha(from), Color.alpha(to), fraction),
                lerpChannel(Color.red(from), Color.red(to), fraction),
                lerpChannel(Color.green(from), Color.green(to), fraction),
                lerpChannel(Color.blue(from), Color.blue(to), fraction)
            )
        }

        private fun lerpChannel(from: Int, to: Int, fraction: Float): Int =
            (from + (to - from) * fraction + 0.5f).toInt().coerceIn(0, 255)

        /**
         * Ronaq: what an unreadable `backgroundImage` resolves to — nothing drawn, rather
         * than an exception thrown at whichever component happened to set it. A fresh
         * pair of arrays each time, because callers keep the ones they are given.
         * Ronaq：无法解析的 backgroundImage 的结果 —— 不绘制，而非向调用方抛异常。
         * 每次返回新数组，因为调用方会持有拿到的数组。
         */
        private fun transparentGradient(): Triple<Orientation, IntArray, FloatArray> = Triple(
            Orientation.BOTTOM_TOP,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT),
            floatArrayOf(0f, 1f)
        )

        fun parseLinearGradient(backgroundImage: String, size: SizeF, titleMode: Shader.TileMode): LinearGradient? {
            val backgroundImageParseTriple = parseBackgroundImage(backgroundImage)
            val x0: Float
            val x1: Float
            val y0: Float
            val y1: Float
            val r = RectF().apply {
                left = 0f
                top = 0f
                right = size.width
                bottom = size.height
            }

            when (backgroundImageParseTriple.first) {
                GradientDrawable.Orientation.TOP_BOTTOM -> {
                    x0 = r.left
                    y0 = r.top
                    x1 = x0
                    y1 = r.bottom
                }
                GradientDrawable.Orientation.TR_BL -> {
                    x0 = r.right
                    y0 = r.top
                    x1 = r.left
                    y1 = r.bottom
                }
                GradientDrawable.Orientation.RIGHT_LEFT -> {
                    x0 = r.right
                    y0 = r.top
                    x1 = r.left
                    y1 = y0
                }
                GradientDrawable.Orientation.BR_TL -> {
                    x0 = r.right
                    y0 = r.bottom
                    x1 = r.left
                    y1 = r.top
                }
                GradientDrawable.Orientation.BOTTOM_TOP -> {
                    x0 = r.left
                    y0 = r.bottom
                    x1 = x0
                    y1 = r.top
                }
                GradientDrawable.Orientation.BL_TR -> {
                    x0 = r.left
                    y0 = r.bottom
                    x1 = r.right
                    y1 = r.top
                }
                GradientDrawable.Orientation.LEFT_RIGHT -> {
                    x0 = r.left
                    y0 = r.top
                    x1 = r.right
                    y1 = y0
                }
                else -> {
                    x0 = r.left
                    y0 = r.top
                    x1 = r.right
                    y1 = r.bottom
                }
            }

            return LinearGradient(
                x0,
                y0,
                x1,
                y1,
                backgroundImageParseTriple.second,
                backgroundImageParseTriple.third,
                titleMode
            )
        }

        private fun convertDirection(direction: Int): Orientation {
            return when (direction) {
                BACKGROUND_IMAGE_DIRECTION_BOTTOM_TOP -> Orientation.BOTTOM_TOP
                BACKGROUND_IMAGE_DIRECTION_TOP_BOTTOM -> Orientation.TOP_BOTTOM
                BACKGROUND_IMAGE_DIRECTION_RIGHT_LEFT -> Orientation.RIGHT_LEFT
                BACKGROUND_IMAGE_DIRECTION_LEFT_RIGHT -> Orientation.LEFT_RIGHT
                BACKGROUND_IMAGE_DIRECTION_BR_TL -> Orientation.BR_TL
                BACKGROUND_IMAGE_DIRECTION_BL_TR -> Orientation.BL_TR
                BACKGROUND_IMAGE_DIRECTION_TR_BL -> Orientation.TR_BL
                BACKGROUND_IMAGE_DIRECTION_TL_BR -> Orientation.TL_BR
                else -> Orientation.BOTTOM_TOP
            }
        }
    }
}

/**
 * Ronaq: a sweep (conic) gradient resolved for [android.graphics.SweepGradient].
 * Ronaq：已解析为 android.graphics.SweepGradient 所需形式的扫描（锥形）渐变。
 *
 * [samples] is an evenly spaced color ramp rather than a stop list, because
 * [android.graphics.drawable.GradientDrawable] never hands a sweep its positions —
 * see `KRCSSBackgroundDrawable.applySweepGradient`.
 * samples 是等距色带而非色标列表：GradientDrawable 从不把位置数组交给扫描着色器，
 * 见 KRCSSBackgroundDrawable.applySweepGradient。
 */
/** Ronaq: a radial gradient as the wire states it — fractions of the view. */
class KRCSSRadialGradient(
    val centerX: Float,
    val centerY: Float,
    val radiusFraction: Float,
    val colors: IntArray,
    val offsets: FloatArray
)

class KRCSSSweepGradient(
    val centerX: Float,
    val centerY: Float,
    val samples: IntArray
)

/** Ronaq: the `<argb> <stop>` pairs of a gradient, in wire order. 线上顺序的渐变色标。 */
private class KRCSSGradientStops(
    val colors: IntArray,
    val offsets: FloatArray
)
