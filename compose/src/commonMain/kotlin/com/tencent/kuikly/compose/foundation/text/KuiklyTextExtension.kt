/*
 * Copyright 2021 The Android Open Source Project
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

package com.tencent.kuikly.compose.foundation.text

import com.tencent.kuikly.compose.extension.scaleToDensity
import com.tencent.kuikly.compose.material3.EmptyInlineContent
import com.tencent.kuikly.compose.resources.toKuiklyFontFamily
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.LinearGradient
import com.tencent.kuikly.compose.ui.graphics.Shadow
import com.tencent.kuikly.compose.ui.graphics.SolidColor
import com.tencent.kuikly.compose.ui.graphics.isSpecified
import com.tencent.kuikly.compose.ui.text.AnnotatedString
import com.tencent.kuikly.compose.ui.text.LinkAnnotation
import com.tencent.kuikly.compose.ui.text.SpanStyle
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontListFontFamily
import com.tencent.kuikly.compose.ui.text.font.FontStyle
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.font.GenericFontFamily
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextDecoration
import com.tencent.kuikly.compose.ui.text.style.TextIndent
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Density
import com.tencent.kuikly.compose.ui.unit.LayoutDirection
import com.tencent.kuikly.compose.ui.unit.TextUnit
import com.tencent.kuikly.compose.ui.unit.isSpecified
import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.Attr.StyleConst
import com.tencent.kuikly.core.views.ISpan
import com.tencent.kuikly.core.base.BoxShadow
import com.tencent.kuikly.core.collection.fastArrayListOf
import com.tencent.kuikly.core.collection.fastMutableSetOf
import com.tencent.kuikly.core.views.PlaceholderSpan
import com.tencent.kuikly.core.views.RichTextAttr
import com.tencent.kuikly.core.views.TextAttr
import com.tencent.kuikly.core.views.TextConst
import com.tencent.kuikly.core.views.TextSpan


// Returns platform-specific default font size
private fun TextAttr.defaultFontSize(): Float {
    val pagerData = getPager().pageData
    return when {
        pagerData.isIOS || pagerData.isMacOS || pagerData.isOhOs -> 15f
        pagerData.isAndroid -> 13f
        else -> 13f
    }
}

internal fun TextAttr.applyTextStyle(
    style: TextStyle,
    density: Density,
    // Ronaq: TextAlign.Start / End are relative to the layout direction; the caller
    // knows the node's direction, this function only maps.
    // Start / End 相对于布局方向，方向由调用方给出，此处只做映射。
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
) {
    // Font properties
    applyFontSize(style.fontSize, density)
    applyFontWeight(style.fontWeight)
    applyFontStyle(style.fontStyle)
    applyFontFamily(style.fontFamily)

    // Layout properties
    applyLetterSpacing(style.letterSpacing, density)
    applyLineHeight(style.lineHeight, density)
    applyTextAlign(style.textAlign, layoutDirection)
    applyTextIndent(style.textIndent)
}

// Handles font size with reuse optimization
internal fun TextAttr.applyFontSize(fontSize: TextUnit, density: Density) {
    if (fontSize.isSpecified) {
        val scaledFontSize = this.scaleToDensity(density, fontSize.value)
        if (!(fontSize.value == defaultFontSize() && getProp(TextConst.FONT_SIZE) == null)) {
            fontSize(scaledFontSize)
        }
    } else if (getProp(TextConst.FONT_SIZE) != null) {
        // Reset to default when changed to Unspecified
        fontSize(this.scaleToDensity(density, defaultFontSize()))
    }
}

// Handles letter spacing with reuse optimization
internal fun TextAttr.applyLetterSpacing(letterSpacing: TextUnit, density: Density) {
    if (letterSpacing.isSpecified) {
        val spacing = this.scaleToDensity(density, letterSpacing.value)
        if (!(spacing == 0f && getProp(TextConst.LETTER_SPACING) == null)) {
            letterSpacing(spacing)
        }
    } else if (getProp(TextConst.LETTER_SPACING) != null) {
        // Reset to 0 when changed to Unspecified
        letterSpacing(0f)
    }
}

// Handles line height with reuse optimization
internal fun TextAttr.applyLineHeight(lineHeight: TextUnit, density: Density) {
    if (lineHeight.isSpecified) {
        setProp(TextConst.LINE_HEIGHT, this.scaleToDensity(density, lineHeight.value))
    } else if (getProp(TextConst.LINE_HEIGHT) != null) {
        // Reset to 0 (auto) when changed to Unspecified
        setProp(TextConst.LINE_HEIGHT, 0f)
    }
}

// Handles text and background color with reuse optimization
internal fun TextAttr.applyStyleColor(style: SpanStyle) {
    when {
        style.brush is SolidColor -> {
            val color = (style.brush as SolidColor).value
            applyTextColorOptimized(color)
        }
        style.brush is LinearGradient -> {
            val linearGradient = style.brush as LinearGradient
            backgroundLinearGradient(
                linearGradient.direction,
                *linearGradient.resolveForText().colorStops.toTypedArray()
            )
        }
        else -> {
            if (style.color.isSpecified) {
                applyTextColorOptimized(style.color)
            } else if (getProp(TextConst.TEXT_COLOR) != null) {
                // Reset to black when changed to Unspecified
                applyTextColorOptimized(Color.Black)
            }
        }
    }

    if (style.background.isSpecified) {
        setProp(Attr.StyleConst.BACKGROUND_COLOR, style.background.toKuiklyColor().toString())
    }
}

// Skips setProp when color is black and has never been set
private fun TextAttr.applyTextColorOptimized(color: Color) {
    if (color == Color.Black && getProp(TextConst.TEXT_COLOR) == null) {
        return
    }
    setProp(TextConst.TEXT_COLOR, color.toKuiklyColor().toString())
}

// Handles font style with reuse optimization
internal fun TextAttr.applyFontStyle(fontStyle: FontStyle?) {
    val isDefault = (fontStyle == null || fontStyle == FontStyle.Normal)
    val value = if (fontStyle == FontStyle.Italic) "italic" else "normal"
    
    if (isDefault && getProp(TextConst.FONT_STYLE) == null) {
        return
    }
    setProp(TextConst.FONT_STYLE, value)
}

// Handles font weight with reuse optimization
internal fun TextAttr.applyFontWeight(fontWeight: FontWeight?) {
    val weightValue: String = fontWeight.toKuiklyFontWeight()

    if (weightValue == KUIKLY_FONT_WEIGHT_DEFAULT && getProp(TextConst.FONT_WEIGHT) == null) {
        return
    }
    setProp(TextConst.FONT_WEIGHT, weightValue)
}

/** The wire value every renderer's font-weight table is keyed on. 各渲染层字重表的键。 */
internal const val KUIKLY_FONT_WEIGHT_DEFAULT = "400"

/**
 * Ronaq: maps a Compose [FontWeight] onto the `fontWeight` prop the renderers read.
 * Ronaq：将 Compose 的 FontWeight 映射为各渲染层读取的 fontWeight 属性值。
 *
 * The previous table collapsed W700/W800/W900 onto "700" and W100 onto "300", so a
 * label declared `FontWeight.Black` was indistinguishable from `FontWeight.Bold` on
 * every platform at once. Nothing below this point required it: `TextAttr` already
 * offers `fontWeightExtraBold()` / `fontWeightBlack()` ("800" / "900"), iOS maps those
 * strings to `UIFontWeightHeavy` / `UIFontWeightBlack`, Android to its extra-bold and
 * black stroke widths, and the web renderer assigns them straight to CSS
 * `font-weight`. The cap lived only in this `when`.
 * 旧表把 W700/W800/W900 一并压成 "700"、把 W100 压成 "300"，于是 FontWeight.Black
 * 与 Bold 在三端同时不可分辨。下游从未要求如此：TextAttr 本就提供 "800"/"900"，
 * iOS 映射为 Heavy/Black，Android 映射为其加粗描边，Web 直接写入 CSS font-weight。
 * 上限只存在于这张表里。
 *
 * The value is snapped to the nearest hundred in 100..900 because that is the wire
 * contract: the iOS and Android lookup tables key on those exact strings and fall back
 * to regular for anything else, so an arbitrary `FontWeight(650)` must not be forwarded
 * verbatim — it would render lighter than the 600 it sits above.
 * 取值就近取整到 100..900：iOS/Android 的查表以这些字符串为键，其余一律回落常规字重，
 * 故 FontWeight(650) 不能原样下发 —— 那会比它上面的 600 还细。
 */
internal fun FontWeight?.toKuiklyFontWeight(): String {
    val weight = this?.weight ?: return KUIKLY_FONT_WEIGHT_DEFAULT
    val snapped = ((weight + 50) / 100 * 100).coerceIn(100, 900)
    return snapped.toString()
}

internal fun TextAttr.applyFontFamily(fontFamily: FontFamily?) {
    when (fontFamily) {
        is GenericFontFamily -> setProp(TextConst.FONT_FAMILY, fontFamily.name)
        is FontListFontFamily -> setProp(TextConst.FONT_FAMILY, fontFamily.fonts.toKuiklyFontFamily())
        else -> if (this.getProp(TextConst.FONT_FAMILY) != null) {
            setProp(TextConst.FONT_FAMILY, "")
        }
    }
}

// Handles text shadow with reuse optimization
internal fun TextAttr.applyShadow(shadow: Shadow?) {
    val isNoOpShadow = shadow == null ||
        (shadow.color == Color.Black && shadow.offset == Offset.Zero && shadow.blurRadius == 0f)
    
    if (isNoOpShadow && getProp(TextConst.TEXT_SHADOW) == null) {
        return
    }
    
    if (shadow == null) {
        setProp(TextConst.TEXT_SHADOW, "0.0 0.0 0.0 0")
    } else if (shadow.color != Color.Unspecified || shadow.offset != Offset.Zero || shadow.blurRadius > 0) {
        setProp(
            TextConst.TEXT_SHADOW,
            BoxShadow(
                shadow.offset.x,
                shadow.offset.y,
                shadow.blurRadius,
                shadow.color.toKuiklyColor()
            ).toString()
        )
    }
}

/**
 * Ronaq: resolve [TextAlign] against the layout direction before handing it to the
 * native text view, which only understands the absolute left / center / right.
 *
 * Upstream mapped `Start`, `End`, `Justify` and `Unspecified` all onto LEFT, so text
 * always hugged the left edge. In an RTL locale that leaves every label that fills its
 * box — a `weight(1f)` row label, a `fillMaxWidth` title — pinned to the wrong edge
 * while the row around it mirrors correctly. `End` was wrong even in LTR: it rendered
 * left. Compose's contract is that `Unspecified` resolves to `Start` and `Start` / `End`
 * resolve against the layout direction (see `resolveParagraphStyleDefaults`); this
 * restores it. Charter C-5.
 * 将 TextAlign 按布局方向解析后再交给原生文本视图（原生只认绝对的 left/center/right）。
 * 上游把 Start / End / Justify / Unspecified 一律映射为 LEFT，故文本恒贴左：RTL 下
 * 所有撑满自身盒子的标签（weight(1f) 行标签、fillMaxWidth 标题）都贴错边，
 * 而其外层 Row 却已正确镜像；End 连在 LTR 下都是错的（渲染成左对齐）。
 * Compose 的约定是 Unspecified → Start、Start/End 随布局方向解析，此处予以恢复。
 */
internal fun TextAttr.applyTextAlign(
    textAlign: TextAlign?,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
) {
    val rtl = layoutDirection == LayoutDirection.Rtl
    val start = if (rtl) {
        com.tencent.kuikly.core.views.TextAlign.RIGHT.value
    } else {
        com.tencent.kuikly.core.views.TextAlign.LEFT.value
    }
    val end = if (rtl) {
        com.tencent.kuikly.core.views.TextAlign.LEFT.value
    } else {
        com.tencent.kuikly.core.views.TextAlign.RIGHT.value
    }
    val align = when (textAlign) {
        TextAlign.Left -> com.tencent.kuikly.core.views.TextAlign.LEFT.value
        TextAlign.Right -> com.tencent.kuikly.core.views.TextAlign.RIGHT.value
        TextAlign.Center -> com.tencent.kuikly.core.views.TextAlign.CENTER.value
        TextAlign.End -> end
        // Start, Justify, Unspecified and null all lay out from the reading start edge.
        // Start / Justify / Unspecified / null 均自阅读起始边排布。
        else -> start
    }
    // Perf: skip when the resolved value is the native default (left) and was never set.
    // 性能：解析结果即原生默认值（left）且从未设置过时跳过。
    if (align == com.tencent.kuikly.core.views.TextAlign.LEFT.value &&
        getProp(TextConst.TEXT_ALIGN) == null
    ) {
        return
    }
    setProp(TextConst.TEXT_ALIGN, align)
}

internal fun TextAttr.applyTextIndent(textIndent: TextIndent?) {
    // Perf: skip when stays at native default headIndent = 0 and has never been set
    val value = if (textIndent != null && textIndent.firstLine.isSpecified) {
        textIndent.firstLine.value
    } else {
        0f
    }
    if (value == 0f && getProp(TextConst.HEAD_INDENT) == null) {
        return
    }
    setProp(TextConst.HEAD_INDENT, value)
}


// Handles text decoration with reuse optimization
internal fun TextAttr.applyTextDecoration(decoration: TextDecoration?) {
    val value = when (decoration) {
        TextDecoration.Underline -> "underline"
        TextDecoration.LineThrough -> "line-through"
        else -> "none"
    }
    
    if (value == "none" && getProp(TextConst.TEXT_DECORATION) == null) {
        return
    }
    setProp(TextConst.TEXT_DECORATION, value)
}

internal fun TextAttr.applySoftWrap(softWrap: Boolean) {
    val target = if (softWrap) "wordWrapping" else "clip"

    val current = getProp(TextConst.TEXT_OVERFLOW) as? String
    if (softWrap && (current == null || current == target) && getProp(TextConst.LINES) == null) {
        return
    }

    if (!softWrap) {
        if (current != target) {
            setProp(TextConst.TEXT_OVERFLOW, target)
        }
        if ((getProp(TextConst.LINES) as? Int) != 1) {
            setProp(TextConst.LINES, 1)
        }
    } else {
        if (current != null && current != target) {
            setProp(TextConst.TEXT_OVERFLOW, target)
        }
    }
}

internal fun TextAttr.applyOverflow(overflow: TextOverflow) {
    val mode = when (overflow) {
        TextOverflow.Clip -> "clip"
        TextOverflow.Ellipsis -> "tail"
        else -> "clip"
    }
    if (mode == "clip" && getProp(TextConst.TEXT_OVERFLOW) == null) {
        return
    }
    val current = getProp(TextConst.TEXT_OVERFLOW) as? String
    if (current == mode) return
    setProp(TextConst.TEXT_OVERFLOW, mode)
}

internal fun TextAttr.applyMaxLines(maxLines: Int) {
    if (maxLines == Int.MAX_VALUE && getProp(TextConst.LINES) == null) {
        return
    }
    val current = getProp(TextConst.LINES) as? Int
    if (current == maxLines) return
    setProp(TextConst.LINES, maxLines)
}

internal fun RichTextAttr.applyAnnotatedString(
    annoText: AnnotatedString,
    inlineContent: Map<String, InlineTextContent> = EmptyInlineContent,
    density: Density,
    // Ronaq: paragraph styles carry TextAlign too — resolve them against the same
    // direction as the plain-text path. 段落样式同样带 TextAlign，须按同一方向解析。
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
) {
    val spans = fastArrayListOf<ISpan>()

    // Collect all style change positions
    val positions = fastMutableSetOf<Int>()
    positions.add(0)
    positions.add(annoText.text.length)

    // Collect SpanStyle positions
    annoText.spanStyles.forEach { range ->
        positions.add(range.start)
        positions.add(range.end)
    }

    // Collect ParagraphStyle positions
    annoText.paragraphStyles.forEach { range ->
        positions.add(range.start)
        positions.add(range.end)
    }

    // Collect LinkAnnotation positions
    val linkAnnotations = annoText.getLinkAnnotations(0, annoText.length)
    linkAnnotations.forEach { range ->
        positions.add(range.start)
        positions.add(range.end)
    }

    // Collect placeholder info and positions
    val (placeholders, _) = if (annoText.hasInlineContent()) {
        annoText.resolveInlineContent(inlineContent)
    } else {
        Pair(null, null)
    }

    // Add placeholder positions
    placeholders?.forEach { range ->
        positions.add(range.start)
        positions.add(range.end)
    }

    val sortedPositions = positions.sorted()

    // Process segments by positions
    for (i in 0 until sortedPositions.size - 1) {
        val start = sortedPositions[i]
        val end = sortedPositions[i + 1]

        // Check if this range is a placeholder
        val isPlaceholder = placeholders?.any {
            it.start == start && it.end == end
        } ?: false

        if (isPlaceholder) {
            // Create PlaceholderSpan
            placeholders!!.find { it.start == start }?.let { placeholder ->
                spans.add(PlaceholderSpan().apply {
                    placeholderSize(
                        this@applyAnnotatedString.scaleToDensity(density, placeholder.item.width.value),
                        this@applyAnnotatedString.scaleToDensity(density, placeholder.item.height.value),
                    )
                })
            }
        } else if (start < end) {
            // Create TextSpan for normal text
            spans.add(TextSpan().apply {
                this.pagerId = this@applyAnnotatedString.pagerId
                text(annoText.text.substring(start, end))

                // Apply SpanStyle
                annoText.spanStyles
                    .filter { range -> !(end <= range.start || start >= range.end) }
                    .forEach { range -> applySpanStyle(range.item, density) }

                // Apply ParagraphStyle
                annoText.paragraphStyles
                    .filter { range -> !(end <= range.start || start >= range.end) }
                    .forEach { range ->
                        range.item.let { style ->
                            applyTextAlign(style.textAlign, layoutDirection)
                            setProp(TextConst.LINE_HEIGHT, style.lineHeight.value)
                            applyTextIndent(style.textIndent)
                        }
                    }

                // Handle LinkAnnotation for current range
                val linkAnnotation = linkAnnotations
                    .firstOrNull { range -> !(end <= range.start || start >= range.end) }

                // Apply LinkAnnotation styles if found
                linkAnnotation?.let { range ->
                    val spanStyle = range.item.styles?.style ?: SpanStyle()
                    applySpanStyle(spanStyle, density)

                    // Add click event handler
                    click { _ ->
                        range.item.linkInteractionListener?.onClick(range.item)
                    }

                    // Call applyLinkStyle for future extensions
                    applyLinkStyle(range.item)
                }
            })
        }
    }

    if (spans.isEmpty()) {
        spans.add(TextSpan().apply {
            pagerId = this@applyAnnotatedString.pagerId
            text(annoText.text)
        })
    }

    this.spans(ArrayList(spans))
}

internal fun TextSpan.applyLinkStyle(link: LinkAnnotation) {
    // TODO: Support pressed state
}

// Helper method to apply SpanStyle
internal fun TextSpan.applySpanStyle(spanStyle: SpanStyle, density: Density) {
    // Apply font styles
    if (spanStyle.fontSize.isSpecified) {
        fontSize(scaleToDensity(density, spanStyle.fontSize.value))
    }
    applyFontWeight(spanStyle.fontWeight)
    applyFontStyle(spanStyle.fontStyle)
    applyShadow(spanStyle.shadow)

    applyStyleColor(spanStyle)
    if (spanStyle.brush is SolidColor) {
        color((spanStyle.brush as SolidColor).value.toKuiklyColor())
    } else if (spanStyle.brush is LinearGradient) {
        val linearGradient = spanStyle.brush as LinearGradient
        backgroundLinearGradient(
            linearGradient.direction,
            *linearGradient.resolveForText().colorStops.toTypedArray()
        )
    } else {
        if (spanStyle.color.isSpecified) {
            color(spanStyle.color.toKuiklyColor())
        }
    }

    // Apply text decoration
    spanStyle.textDecoration?.let { applyTextDecoration(it) }

    // Apply letter spacing
    if (spanStyle.letterSpacing.isSpecified) {
        letterSpacing(spanStyle.letterSpacing.value)
    }
}
