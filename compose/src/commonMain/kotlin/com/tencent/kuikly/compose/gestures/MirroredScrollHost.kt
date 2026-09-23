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

package com.tencent.kuikly.compose.gestures

import com.tencent.kuikly.core.base.domChildren
import com.tencent.kuikly.core.layout.Frame
import com.tencent.kuikly.core.views.KRNestedScrollMode
import com.tencent.kuikly.core.views.ScrollerAttr.Companion.NESTED_SCROLL

/**
 * Ronaq fork (CHANGES.md §30): everything the mirrored bridge reads from its host scroller
 * and does to it, in one place.
 *
 * [KuiklyScrollInfo] drives [ScrollerMirroredHost], which is the real `ScrollerView`.
 * `MirroredScrollAxisTest` drives the same [KuiklyScrollInfo] functions against a fake
 * scroller, so the bridge steps the test checks are the ones that ship, not a copy of them.
 *
 * The bridge keeps px; every call here takes dp, as the host's own API does.
 */
internal interface MirroredScrollHost {
    /** A scroller is bound (`KuiklyScrollInfo.scrollView` is set). */
    val bound: Boolean

    /** The bound scroller has its content view. */
    val hasContent: Boolean

    /** The bound scroller has a render view (it has been created on the host). */
    val rendered: Boolean

    /** px per dp. */
    val density: Float

    /** The scroller's own width in px: W in `nativeMax = C - W`. */
    val viewportPx: Int

    /** The content view's current width in dp, or null before it has one. */
    val contentWidthDp: Float?

    /** The host is Android, whose absolute end write lands 0.01 dp short (`writeMirroredNative`). */
    val isAndroid: Boolean

    /**
     * The host's renderer implements `shiftContentOffset` (iOS, Android and web in this fork,
     * CHANGES.md §30): it moves its offset relative to wherever it is at that moment. Every
     * other host takes the absolute write.
     */
    val canShiftOffset: Boolean

    fun setContentWidth(widthDp: Float)

    /** Move every content child by [dxDp], from its own current frame. */
    fun shiftChildren(dxDp: Float)

    /**
     * Write an absolute x offset. On iOS this is `-setContentOffset:animated:NO`, which stops a
     * running deceleration; Android and web write `target - current`, dropping whatever the
     * host moved since Kotlin's last event.
     */
    fun writeOffset(xDp: Float)

    /**
     * Move the x offset by [dxDp] from wherever the host is now. A drag or a fling in progress
     * carries on from the new position, and the motion the host made since Kotlin's last
     * event is kept. Only called when [canShiftOffset].
     */
    fun shiftOffset(dxDp: Float)

    /**
     * Put the scroller's nested scrolling on SELF_ONLY for a programmatic move, as
     * `applyOffsetDelta` always has, and return what restores it (null when the scroller has
     * no nested-scroll setting to protect).
     */
    fun holdNestedScrollSelfOnly(): (() -> Unit)?
}

/** Ronaq fork (CHANGES.md §30): [MirroredScrollHost] over the bound `ScrollerView`. */
internal class ScrollerMirroredHost(private val info: KuiklyScrollInfo) : MirroredScrollHost {

    private val sv get() = info.scrollView

    override val bound: Boolean get() = sv != null
    override val hasContent: Boolean get() = sv?.contentView != null
    override val rendered: Boolean get() = sv?.renderView != null
    override val density: Float get() = info.getDensity()
    override val viewportPx: Int get() = info.viewportSize
    override val contentWidthDp: Float? get() = sv?.contentView?.renderView?.currentFrame?.width
    override val isAndroid: Boolean get() = info.pageData?.isAndroid == true
    override val canShiftOffset: Boolean
        get() = info.pageData?.let { it.isIOS || it.isAndroid || it.isWeb } == true

    override fun setContentWidth(widthDp: Float) {
        val sv = sv ?: return
        sv.contentView?.setFrameToRenderView(
            Frame(x = 0f, y = 0f, width = widthDp, height = sv.renderView?.currentFrame?.height ?: 0f)
        )
    }

    override fun shiftChildren(dxDp: Float) {
        if (dxDp == 0f) return
        sv?.contentView?.domChildren()?.forEach { subview ->
            val cur = subview.renderView?.currentFrame ?: return@forEach
            subview.setFrameToRenderView(Frame(cur.x + dxDp, cur.y, cur.width, cur.height))
        }
    }

    override fun writeOffset(xDp: Float) {
        sv?.setContentOffset(xDp, 0f)
    }

    override fun shiftOffset(dxDp: Float) {
        val sv = sv ?: return
        sv.performTaskWhenRenderViewDidLoad {
            // Keep the content view's record of the offset in step, as setContentOffset does.
            sv.contentView?.let { it.contentOffsetWillChanged(it.offsetX + dxDp, it.offsetY) }
            sv.renderView?.callMethod(SHIFT_CONTENT_OFFSET, "$dxDp 0")
        }
    }

    override fun holdNestedScrollSelfOnly(): (() -> Unit)? {
        val sv = sv ?: return null
        val origin = sv.getViewAttr().getProp(NESTED_SCROLL) ?: return null
        sv.getViewAttr().nestedScroll(KRNestedScrollMode.SELF_ONLY, KRNestedScrollMode.SELF_ONLY)
        return { sv.getViewAttr().setProp(NESTED_SCROLL, origin) }
    }

    companion object {
        /**
         * Render method: `"dx dy"` in dp; the host adds it to its current content offset
         * without stopping a drag or deceleration (CHANGES.md §30).
         */
        const val SHIFT_CONTENT_OFFSET = "shiftContentOffset"
    }
}
