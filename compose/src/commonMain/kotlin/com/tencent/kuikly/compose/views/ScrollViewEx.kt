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

package com.tencent.kuikly.compose.views

import com.tencent.kuikly.compose.ui.unit.IntOffset
import com.tencent.kuikly.compose.extension.toIntRect
import com.tencent.kuikly.compose.gestures.KuiklyScrollInfo
import com.tencent.kuikly.core.base.domChildren
import com.tencent.kuikly.core.layout.Frame
import com.tencent.kuikly.core.views.KRNestedScrollMode
import com.tencent.kuikly.core.views.ScrollerAttr
import com.tencent.kuikly.core.views.ScrollerAttr.Companion.NESTED_SCROLL
import com.tencent.kuikly.core.views.ScrollerEvent
import com.tencent.kuikly.core.views.ScrollerView
import kotlin.math.abs
import kotlin.math.max

internal val KuiklyInfoKey = "KuiklyInfoKey"

internal fun ScrollerView<ScrollerAttr, ScrollerEvent>.calNewOffset(curOffset: IntOffset, delta: Int, kuiklyInfo: KuiklyScrollInfo): IntOffset {
    // 注意不能够越界
    val newOffset = if (kuiklyInfo.isVertical()) {
        IntOffset(curOffset.x, curOffset.y + delta)
    } else {
        IntOffset(curOffset.x + delta, curOffset.y)
    }
    return newOffset
}

internal fun ScrollerView<ScrollerAttr, ScrollerEvent>.applyOffsetDelta(delta: Int, kuiklyInfo: KuiklyScrollInfo): IntOffset {
    if (kuiklyInfo.axis.mirrored) {
        return applyMirroredOffsetDelta(delta, kuiklyInfo)
    }
    val density = kuiklyInfo.getDensity()

    val curOffset = IntOffset(
        (curOffsetX * density).toInt(),
        (curOffsetY * density).toInt()
    )
    val newOffset = calNewOffset(curOffset, delta, kuiklyInfo)
    val newOriOffset = if (kuiklyInfo.isVertical()) newOffset.y else newOffset.x

    if (kuiklyInfo.composeOffset.toInt() == newOriOffset) {
        return newOffset
    }

    kuiklyInfo.ignoreScrollOffset = newOffset

    // 扩容
    renderView?.run {
        val viewportSize = kuiklyInfo.viewportSize
        if (newOriOffset + viewportSize > kuiklyInfo.currentContentSize) {
            kuiklyInfo.currentContentSize += (2000 * density + delta).toInt()
            kuiklyInfo.updateContentSizeToRender()
        }
    }

    // 避免嵌套滚动的影响
    val originNestSetting = getViewAttr().getProp(NESTED_SCROLL)
    if (originNestSetting != null) {
        getViewAttr().run {
            nestedScroll(KRNestedScrollMode.SELF_ONLY, KRNestedScrollMode.SELF_ONLY)
        }
    }

    // 然后更新子节点的offset
    contentView?.domChildren()?.forEach { subview ->
        if (subview.renderView == null) {
            return@forEach
        }
        val curRect = subview.renderView!!.currentFrame.toIntRect(density)
        val newChildOffset = calNewOffset(IntOffset(curRect.left, curRect.top), delta, kuiklyInfo)
        val newFrame = Frame(newChildOffset.x / density, newChildOffset.y / density,
            curRect.width / density, curRect.height / density)
        subview.setFrameToRenderView(newFrame)
    }
    // 更新offset
    if (contentView?.getPager()?.pageData?.isAndroid == true) {
        // 安卓有个bug，刚好滚到最末尾的时候，是不成功的，临时处理下
        setContentOffset(max(0f, newOffset.x / density - 0.01f), max(0f, newOffset.y / density - 0.01f))
    } else {
        setContentOffset(newOffset.x / density, newOffset.y / density)
    }

    // 恢复嵌套滚动设置
    originNestSetting?.run {
        getViewAttr().setProp(NESTED_SCROLL, originNestSetting)
    }

    return IntOffset(newOffset.x, newOffset.y)
}

/**
 * Ronaq fork (CHANGES.md §30): [applyOffsetDelta] for a mirrored scroller (a horizontal
 * lazy list or grid under Rtl). [delta] and the returned x are LOGICAL, like
 * `composeOffset`; the native offset is `nativeMax - logical`.
 *
 * The same steps as the left-to-right path, converted where a value crosses to the host:
 * 1. the current offset is read from the native one Kotlin last wrote or accepted
 *    (`curOffsetX` still holds the pre-shift value between a re-anchor and its echo);
 * 2. the content grows as before; `updateContentSizeToRender` now re-anchors, which moves
 *    the native offset and `nativeMax` together;
 * 3. the children move by `-delta`, because their native x is
 *    `pos.x + nativeMax - composeOffset` and `composeOffset` grows by `delta`;
 * 4. `nativeMax - newLogical` is written with the post-growth `nativeMax`, keeping the
 *    Android `-0.01dp` end write, and its echo is ignored in native units.
 */
private fun ScrollerView<ScrollerAttr, ScrollerEvent>.applyMirroredOffsetDelta(delta: Int, kuiklyInfo: KuiklyScrollInfo): IntOffset {
    val density = kuiklyInfo.getDensity()
    val axis = kuiklyInfo.axis

    val hostNative = axis.lastNative
    val curLogical = axis.toLogical(hostNative)
    val curY = (curOffsetY * density).toInt()
    val newLogical = curLogical + delta

    if (kuiklyInfo.composeOffset.toInt() == newLogical) {
        return IntOffset(newLogical, curY)
    }

    // Grow the content (logical, as before); the re-anchor keeps the screen still.
    renderView?.run {
        val viewportSize = kuiklyInfo.viewportSize
        if (newLogical + viewportSize > kuiklyInfo.currentContentSize) {
            kuiklyInfo.currentContentSize += (2000 * density + delta).toInt()
            kuiklyInfo.updateContentSizeToRender()
        }
    }

    // Keep nested scrolling out of the programmatic move.
    val originNestSetting = getViewAttr().getProp(NESTED_SCROLL)
    if (originNestSetting != null) {
        getViewAttr().run {
            nestedScroll(KRNestedScrollMode.SELF_ONLY, KRNestedScrollMode.SELF_ONLY)
        }
    }

    kuiklyInfo.shiftMirroredChildren(-delta / density)

    val newNative = axis.toNative(newLogical)
    if (abs(newNative - hostNative) >= 1f) {
        // Only wait for an echo the host will send: an offset left where the host already
        // is sends none.
        kuiklyInfo.ignoreScrollOffset = IntOffset(newNative, curY)
    }
    kuiklyInfo.writeMirroredNative(newNative.toFloat())

    // Restore the nested-scroll setting.
    originNestSetting?.run {
        getViewAttr().setProp(NESTED_SCROLL, originNestSetting)
    }

    return IntOffset(newLogical, curY)
}
