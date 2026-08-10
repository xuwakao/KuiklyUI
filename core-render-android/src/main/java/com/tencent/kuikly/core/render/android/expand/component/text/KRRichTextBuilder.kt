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

package com.tencent.kuikly.core.render.android.expand.component.text

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.text.style.UpdateAppearance
import android.util.SizeF
import com.tencent.kuikly.core.render.android.IKuiklyRenderContext
import com.tencent.kuikly.core.render.android.const.KRCssConst
import com.tencent.kuikly.core.render.android.css.decoration.BoxShadow
import com.tencent.kuikly.core.render.android.css.drawable.KRCSSBackgroundDrawable
import com.tencent.kuikly.core.render.android.css.ktx.buildSpannedString
import com.tencent.kuikly.core.render.android.css.ktx.inSpans
import com.tencent.kuikly.core.render.android.css.ktx.spToPxI
import com.tencent.kuikly.core.render.android.css.ktx.toColor
import com.tencent.kuikly.core.render.android.css.ktx.toPxF
import com.tencent.kuikly.core.render.android.css.ktx.toPxI
import com.tencent.kuikly.core.render.android.expand.component.KRTextProps
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * 富文本构造器
 */
class KRRichTextBuilder(private val kuiklyContext: IKuiklyRenderContext?) {

    /**
     * 构建富文本
     *
     * @param textProps 文本属性，用作 TextSpan 解析的默认值
     * @param spanTextRanges 用于记录每个 Span 的文本范围
     * @param layoutSizeGetter 用于获取文本组件布局 Size
     */
    fun build(
        textProps: KRTextProps,
        spanTextRanges: MutableList<SpanTextRange>,
        layoutSizeGetter: () -> SizeF
    ): SpannableStringBuilder? {
        val spanValues = textProps.values
        if (spanValues == null || spanValues.length() == 0) {
            return null
        }
        val spannedBuilder = SpannableStringBuilder()
        for (index in 0 until spanValues.length()) {
            val isStart =
                spannedBuilder.isEmpty() || spannedBuilder[spannedBuilder.lastIndex] == '\n'
            val spanValue = spanValues.optJSONObject(index) ?: JSONObject()
            val spanProps = parseSpanProps(spanValue, textProps, isStart)
            val spans = createSpans(spanProps, index, layoutSizeGetter)
            if (spans.isNotEmpty()) {
                if (spanProps is TextSpanProps && spanProps.adjustNewline) {
                    // 对齐iOS、鸿蒙端表现，非空行的换行符不撑开行高
                    spannedBuilder.append(
                        "\n",
                        AbsoluteSizeSpan(1),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                spannedBuilder.append(buildSpannedString {
                    // 记录 Span 对应的文字范围
                    spanTextRanges.add(
                        SpanTextRange(
                            index,
                            spannedBuilder.length,
                            spannedBuilder.length + spanProps.text.length
                        )
                    )
                    inSpans(spans) {
                        append(spanProps.text)
                    }
                })

            }
        }
        if (textProps.richTextHeadIndent != 0) {
            spannedBuilder.setSpan(
                LeadingMarginSpan.Standard(textProps.richTextHeadIndent, 0),
                0,
                spannedBuilder.length,
                Spannable.SPAN_INCLUSIVE_EXCLUSIVE
            )
        }
        return spannedBuilder
    }

    /**
     * 解析 Span 参数
     */
    private fun parseSpanProps(
        spanValue: JSONObject,
        defaultTextProps: KRTextProps,
        isStart: Boolean
    ): SpanProps {
        if (isPlaceHolderSpan(spanValue)) {
            return PlaceholderSpanProps(spanValue, kuiklyContext)
        }
        return TextSpanProps(spanValue, defaultTextProps, isStart, kuiklyContext)
    }

    /**
     * 判断是否为 PlaceholderSpan
     */
    private fun isPlaceHolderSpan(spanValue: JSONObject): Boolean {
        return spanValue.opt(PlaceholderSpanProps.PROP_KEY_PLACEHOLDER_WIDTH) != null && spanValue.opt(
            PlaceholderSpanProps.PROP_KEY_PLACEHOLDER_HEIGHT
        ) != null
    }

    /**
     * 根据 span 类型创建对应的 span
     */
    private fun createSpans(
        spanProps: SpanProps,
        index: Int,
        layoutSizeGetter: () -> SizeF
    ): List<Any> {
        val spans = mutableListOf<Any>()
        when (spanProps) {
            is TextSpanProps -> {
                spans.addAll(createTextSpan(spanProps, index, layoutSizeGetter))
            }
            is PlaceholderSpanProps -> {
                spans.add(KRPlaceholderSpan(spanProps))
            }
        }
        return spans
    }

    /**
     * 创建富文本 span
     *
     * @param spanProps span 属性
     * @param index span 的位置
     * @param layoutSizeGetter 获取 TextLayout Size 的方法
     */
    private fun createTextSpan(
        spanProps: TextSpanProps,
        index: Int,
        layoutSizeGetter: () -> SizeF
    ): List<Any> {
        val textSpans = mutableListOf<Any>()

        // 字体相关
        if (spanProps.fontSize > 0) {
            textSpans.add(AbsoluteSizeSpan(if (spanProps.useDpFontSizeDim) kuiklyContext.toPxI(spanProps.fontSize) else {
                kuiklyContext.spToPxI(spanProps.fontSize)
            }))
        }
        // Ronaq: the family is resolved BEFORE the weight span is built, because whether
        // the host has a real face at this weight is what decides if the weight still
        // has to be faked with a widened stroke. The spans are still appended in their
        // original order.
        // Ronaq：先解析字体族再构造字重 span —— 宿主是否为该字重提供真实字面，
        // 决定了还需不需要用加粗描边伪造字重。span 的追加顺序保持不变。
        val familySpan = if (spanProps.fontFamily.isNotEmpty()) {
            FontFamilySpan(
                spanProps.fontFamily,
                kuiklyContext?.getTypeFaceLoader(),
                FontWeightSpan.parseWeight(spanProps.fontWeight)
            )
        } else {
            null
        }
        val fontWeightSpan =
            FontWeightSpan(spanProps.fontWeight, index, familySpan?.matchesWeight != true)
        textSpans.add(fontWeightSpan)
        textSpans.add(StyleSpan(spanProps.fontStyle))
        if (spanProps.fontVariant.isNotEmpty()) {
            textSpans.add(FontVariantSpan(spanProps.fontVariant))
        }
        familySpan?.also { textSpans.add(it) }

        // 修饰相关
        textSpans.add(ForegroundColorSpan(spanProps.color))
        if (spanProps.textDecoration.isNotEmpty()) {
            if (spanProps.textDecoration == KRTextProps.TEXT_DECORATION_LINE_THROUGH) {
                textSpans.add(StrikethroughSpan())
            } else {
                textSpans.add(UnderlineSpan())
            }
        }
        if (spanProps.backgroundImage.isNotEmpty()) {
            textSpans.add(LinearGradientForegroundSpan(spanProps.backgroundImage, layoutSizeGetter))
        }

        spanProps.textShadow?.let {
            if (!it.isEmpty()) {
                textSpans.add(
                    TextShadowSpan(
                        it.shadowOffsetX,
                        it.shadowOffsetY,
                        it.shadowRadius,
                        it.shadowColor
                    )
                )
            }
        }

        // 段落相关
        if (spanProps.letterSpacing != 0f) {
            textSpans.add(LetterSpacingSpan(spanProps.letterSpacing))
        }
        if (spanProps.lineHeight != KRTextProps.UNSET_LINE_HEIGHT) {
            textSpans.add(HRLineHeightSpan(spanProps.lineHeight.toInt()))
        }
        return textSpans
    }

}

abstract class SpanProps(spanValue: JSONObject) {
    protected val _text: String = spanValue.optString(KRTextProps.PROP_KEY_TEXT, "")
    open val text: String get() = _text
}

class TextSpanProps(
    spanValue: JSONObject,
    defaultProps: KRTextProps,
    isStart: Boolean,
    kuiklyContext: IKuiklyRenderContext?
) : SpanProps(spanValue) {

    val color: Int
    val fontSize: Float
    val fontFamily: String
    val fontWeight: String
    var fontVariant: String
    val fontStyle: Int
    val letterSpacing: Float
    val textDecoration: String
    val lineHeight: Float
    val backgroundImage: String
    var textShadow: BoxShadow? = null
    var useDpFontSizeDim = false

    val adjustNewline by lazy(LazyThreadSafetyMode.NONE) {
        !isStart && _text.isNotEmpty() && _text[0] == '\n'
    }
    private val _trimText by lazy(LazyThreadSafetyMode.NONE) {
        if (adjustNewline) {
            _text.substring(1)
        } else {
            _text
        }
    }

    override val text: String get() = _trimText

    init {
        color = spanValue.optString(KRTextProps.PROP_KEY_COLOR).let { colorStr ->
            if (colorStr.isNotEmpty()) {
                colorStr.toColor()
            } else {
                defaultProps.color
            }
        }
        fontSize = spanValue.optDouble(KRTextProps.PROP_KEY_FONT_SIZE, defaultProps.fontSize * 1.0).toFloat()
        fontFamily = spanValue.optString(KRTextProps.PROP_KEY_FONT_FAMILY, defaultProps.fontFamily)
        fontWeight = spanValue.optString(KRTextProps.PROP_KEY_FONT_WEIGHT, defaultProps.fontWeight)
        val fontStyleStr = spanValue.optString(KRTextProps.PROP_KEY_FONT_STYLE)
        fontStyle = if (fontStyleStr == KRTextProps.FONT_STYLE_ITALIC) {
            Typeface.ITALIC
        } else {
            defaultProps.fontStyle
        }
        fontVariant = spanValue.optString(KRTextProps.PROP_KEY_FONT_VARIANT)
        letterSpacing = if (spanValue.has(KRTextProps.PROP_KEY_LETTER_SPACING)) {
            spanValue.optDouble(KRTextProps.PROP_KEY_LETTER_SPACING).toFloat() / max(fontSize, 1f)
        } else {
            defaultProps.letterSpacing
        }
        textDecoration = spanValue.optString(KRTextProps.PROP_KEY_TEXT_DECORATION, defaultProps.textDecoration)
        lineHeight = if (spanValue.has(KRTextProps.PROP_KEY_LINE_HEIGHT)) {
            kuiklyContext.toPxF(spanValue.optDouble(KRTextProps.PROP_KEY_LINE_HEIGHT).toFloat())
        } else {
            defaultProps.lineHeight
        }
        backgroundImage = spanValue.optString(KRTextProps.PROP_KEY_BACKGROUND_IMAGE, defaultProps.backgroundImage)
        val textShadowStr = spanValue.optString(KRTextProps.PROP_KEY_TEXT_SHADOW, "")
        textShadow = BoxShadow(textShadowStr, kuiklyContext)
        useDpFontSizeDim = spanValue.optInt(KRTextProps.PROP_KEY_TEXT_USE_DP_FONT_SIZE_DIM) == 1
    }

}

class PlaceholderSpanProps(spanValue: JSONObject, private val kuiklyContext: IKuiklyRenderContext?) : SpanProps(spanValue) {

    companion object {
        const val PROP_KEY_PLACEHOLDER_WIDTH = "placeholderWidth"
        const val PROP_KEY_PLACEHOLDER_HEIGHT = "placeholderHeight"
    }

    val width: Int
    val height: Int

    init {
        width = kuiklyContext.toPxI(spanValue.optDouble(PROP_KEY_PLACEHOLDER_WIDTH, 0.0).toFloat())
        height = kuiklyContext.toPxI(spanValue.optDouble(PROP_KEY_PLACEHOLDER_HEIGHT, 0.0).toFloat())
    }

}

/**
 * 用于记录 DSL Span 对应的 Text Range
 */
data class SpanTextRange(val index: Int, val start: Int, val end: Int) {
    override fun toString(): String {
        return "{$index, $start, $end}"
    }
}

/**
 * 字重span
 * @param fontWeight 字重
 * @param index 标识是第几个span
 * @param synthesizeWeight Ronaq: whether the weight still has to be imitated by
 *        widening the paint stroke. False when the typeface already IS the requested
 *        weight, in which case stroking it would draw a real 800 face at roughly 1000.
 *        Ronaq：是否仍需以加粗描边模拟字重。当字面本身即为所请求的字重时为 false ——
 *        否则会把真正的 800 字面又描粗到近似 1000。
 */
class FontWeightSpan(
    fontWeight: String,
    val index: Int = -1,
    synthesizeWeight: Boolean = true
) : CharacterStyle() {

    private val strokeWidth = if (synthesizeWeight) getFontWeight(fontWeight) else 0f

    override fun updateDrawState(tp: TextPaint) {
        if (strokeWidth != 0f) {
            tp.style = Paint.Style.FILL_AND_STROKE
            tp.strokeWidth = strokeWidth * tp.textSize
        }
    }

    companion object {

        /**
         * Ronaq: no numeric weight was stated, so no face can be asked for by weight.
         * Ronaq：未给出数值字重，因而无法按字重索取字面。
         */
        const val FONT_WEIGHT_UNSPECIFIED = 0

        /**
         * Ronaq: the numeric weight a `fontWeight` prop carries, or
         * [FONT_WEIGHT_UNSPECIFIED] when it is absent or is not one of the nine CSS
         * weights. Anything off the hundreds is refused rather than snapped: the DSL
         * that produces this prop already snaps, and guessing here would let a face
         * be picked for a weight nothing declared.
         * Ronaq：fontWeight 属性所携带的数值字重；属性缺失或不是九档 CSS 字重之一时返回
         * FONT_WEIGHT_UNSPECIFIED。非整百值一律拒绝而不就近取整 ——
         * 产出该属性的 DSL 已做过取整，此处再猜会为无人声明的字重挑出字面。
         */
        fun parseWeight(fontWeight: String): Int {
            val weight = fontWeight.toIntOrNull() ?: return FONT_WEIGHT_UNSPECIFIED
            return if (weight in 100..900 && weight % 100 == 0) {
                weight
            } else {
                FONT_WEIGHT_UNSPECIFIED
            }
        }

        private const val FONT_WEIGHT_NORMAL = "400"
        private const val FONT_WEIGHT_MEDIUM = "500"
        private const val FONT_WEIGHT_MEDIUM_BOLD = "600"
        private const val FONT_WEIGHT_BOLD = "700"
        private const val FONT_WEIGHT_EXTRA_BOLD = "800"
        private const val FONT_WEIGHT_BLACK = "900"

        private const val SCALE = 50f
        private const val FONT_WEIGHT_NORMAL_VALUE = 0f
        private const val FONT_WEIGHT_MEDIUM_VALUE = 0.5f / SCALE
        private const val FONT_WEIGHT_MEDIUM_BOLD_VALUE = 1f / SCALE
        private const val FONT_WEIGHT_BOLD_VALUE = 1.5f / SCALE
        private const val FONT_WEIGHT_EXTRA_BOLD_VALUE = 2f / SCALE
        private const val FONT_WEIGHT_BLACK_VALUE = 2.5f / SCALE

        fun getFontWeight(fontWeight: String): Float {
            return when (fontWeight) {
                FONT_WEIGHT_NORMAL -> FONT_WEIGHT_NORMAL_VALUE
                FONT_WEIGHT_MEDIUM -> FONT_WEIGHT_MEDIUM_VALUE
                FONT_WEIGHT_MEDIUM_BOLD -> FONT_WEIGHT_MEDIUM_BOLD_VALUE
                FONT_WEIGHT_BOLD -> FONT_WEIGHT_BOLD_VALUE
                FONT_WEIGHT_EXTRA_BOLD -> FONT_WEIGHT_EXTRA_BOLD_VALUE
                FONT_WEIGHT_BLACK -> FONT_WEIGHT_BLACK_VALUE
                else -> FONT_WEIGHT_NORMAL_VALUE
            }
        }
    }
}

/**
 * 异形字体span
 */
class FontVariantSpan(private val fontVariant: String) : CharacterStyle() {

    override fun updateDrawState(tp: TextPaint) {
        tp.fontFeatureSettings = fontVariant
    }

}

/**
 * 字母间距span
 */
class LetterSpacingSpan(private val letterSpacing: Float) : MetricAffectingSpan() {

    override fun updateDrawState(tp: TextPaint) {
        apply(tp)
    }

    override fun updateMeasureState(textPaint: TextPaint) {
        apply(textPaint)
    }

    private fun apply(tp: TextPaint) {
        if (letterSpacing != 0f) {
            tp.letterSpacing = letterSpacing
        }
    }
}

/**
 * 字体span
 *
 * @param fontWeight Ronaq: the weight to prefer a real face for. Default keeps the
 *        pre-existing lookup, which asks only for the family name.
 *        Ronaq：优先索取真实字面的字重；默认值维持原有的「只按族名查找」。
 */
class FontFamilySpan(
    fontFamily: String,
    typeFaceLoader: TypeFaceLoader?,
    fontWeight: Int = FontWeightSpan.FONT_WEIGHT_UNSPECIFIED
) : TypefaceSpan(KRCssConst.EMPTY_STRING) {

    private var tfe: Typeface? = null

    /**
     * Ronaq: true when [tfe] is the host's own face for the requested weight, so the
     * caller must not widen the stroke on top of it.
     * Ronaq：当 tfe 即宿主为该字重提供的真实字面时为 true，调用方不应再叠加描边加粗。
     */
    val matchesWeight: Boolean

    init {
        // Italic is passed as false here as it always has been: [StyleSpan] carries the
        // slant and this span overwrites the typeface after it. Left untouched — it is a
        // separate defect from the weight one.
        // italic 一如既往传 false：倾斜由 StyleSpan 承担，而本 span 在其后覆盖 typeface。
        // 此处不动 —— 那与字重是两个独立缺陷。
        val resolved = typeFaceLoader?.resolve(fontFamily, false, fontWeight)
        tfe = resolved?.typeface
        matchesWeight = resolved?.matchesWeight == true
    }

    override fun updateDrawState(ds: TextPaint) {
        tfe?.also {
            applyCustomTypeFace(ds, it)
        } ?: also {
            super.updateDrawState(ds)
        }
    }

    override fun updateMeasureState(paint: TextPaint) {
        tfe?.also {
            applyCustomTypeFace(paint, it)
        } ?: also {
            super.updateMeasureState(paint)
        }
    }

    private fun applyCustomTypeFace(paint: Paint, tf: Typeface) {
        paint.typeface = tf
    }
}

class HRLineHeightSpan(internal val height: Int) : LineHeightSpan {

    override fun chooseHeight(
        text: CharSequence?,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fm: Paint.FontMetricsInt
    ) {
        val additional: Int = height - (-fm.top + fm.bottom)
        fm.top -= ceil((additional / 2.0f).toDouble()).toInt()
        fm.bottom += floor((additional / 2.0f).toDouble()).toInt()
        fm.ascent = fm.top
        fm.descent = fm.bottom
    }
}

class LinearGradientForegroundSpan(
    backgroundImage: String,
    private val sizeGetter: () -> SizeF
) : CharacterStyle(), UpdateAppearance {

    private val backgroundImageParseTriple = KRCSSBackgroundDrawable.parseBackgroundImage(backgroundImage)

    override fun updateDrawState(tp: TextPaint) {
        val x0: Float
        val x1: Float
        val y0: Float
        val y1: Float
        val r = RectF().apply {
            val sizeF = sizeGetter.invoke()
            left = 0f
            top = 0f
            right = sizeF.width
            bottom = sizeF.height
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

        tp.shader = LinearGradient(
            x0,
            y0,
            x1,
            y1,
            backgroundImageParseTriple.second,
            backgroundImageParseTriple.third,
            Shader.TileMode.REPEAT
        )
    }
}

/**
 * 字体阴影
 */
class TextShadowSpan(
    private val dx: Float,
    private val dy: Float,
    private val radius: Float,
    private val color: Int
) : CharacterStyle() {

    override fun updateDrawState(textPaint: TextPaint) {
        textPaint.setShadowLayer(radius, dx, dy, color)
    }

}

/**
 * PlaceHolderSpan，用于实现空白区域占位
 */
class KRPlaceholderSpan(private val spanProps: PlaceholderSpanProps): ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        if (fm != null) {
            val diff = spanProps.height - (fm.bottom - fm.top)
            fm.bottom = maxOf(0, fm.bottom + diff / 2)
            fm.top = fm.bottom - spanProps.height
            fm.ascent = fm.top
            fm.descent = fm.bottom
        }
        return spanProps.width
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {}

    fun width(): Int {
        return spanProps.width
    }

    fun height(): Int {
        return spanProps.height
    }

}