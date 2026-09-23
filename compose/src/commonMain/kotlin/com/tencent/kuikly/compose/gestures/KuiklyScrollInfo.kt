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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.gestures.Orientation
import com.tencent.kuikly.compose.ui.node.StickyHeaderCacheManager
import com.tencent.kuikly.compose.ui.unit.IntOffset
import com.tencent.kuikly.core.base.domChildren
import com.tencent.kuikly.core.layout.Frame
import com.tencent.kuikly.core.pager.PageData
import com.tencent.kuikly.core.views.ScrollerAttr
import com.tencent.kuikly.core.views.ScrollerEvent
import com.tencent.kuikly.core.views.ScrollerView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Scroll information management class, responsible for handling scroll-related state and calculations
 */
class KuiklyScrollInfo {
    companion object {
        private const val DEFAULT_CONTENT_SIZE = 3000
        private const val SCROLL_BOTTOM_THRESHOLD = 100
        private const val DEFAULT_DENSITY = 3f
    }

    /**
     * Scroll offset that needs to be ignored
     */
    var ignoreScrollOffset: IntOffset? = null

    /**
     * Scroll view instance
     */
    var scrollView: ScrollerView<ScrollerAttr, ScrollerEvent>? = null
        set(value) {
            field = value
            if (hasPullToRefresh && value != null) {
                value.setHasPullToRefresh(true)
            }
        }

    /**
     * Scroll orientation
     */
    var orientation: Orientation = Orientation.Vertical

    /**
     * Offset on the Compose side, does not exceed boundaries
     */
    var composeOffset = 0f

    /**
     * Temporary native-coordinate correction used while a Pager snap animation is running.
     * When items are inserted before the snap target, this keeps the target item's native frame
     * anchored to the original snap target offset until the snap settles.
     */
    var snapAnchorOffsetCorrection = 0

    /**
     * Current contentView size, used to expand the bottom boundary
     */
    var currentContentSize by mutableStateOf((DEFAULT_CONTENT_SIZE * getDensity()).toInt())

    /**
     * Real contentSize after scrolling to the bottom
     */
    var realContentSize: Int? = null

    /**
     * Whether the offset has deviation
     */
    var offsetDirty = false

    /**
     * ScrollView's scroll offset
     */
    var contentOffset: Int by mutableStateOf(0)

    /**
     * ScrollView is dragging
     */
    var isDragging: Boolean by mutableStateOf(false)

    /**
     * List height cache
     */
    internal var itemMainSpaceCache = hashMapOf<Any, Int>()

    /**
     * Used to track delayed execution of applyScrollViewOffsetDelta tasks
     */
    internal var appleScrollViewOffsetJob: Job? = null

    /**
     * Coroutine scope
     */
    internal var scope: CoroutineScope? = null

    /**
     * PageData related data
     */
    var pageData: PageData? = null

    /**
     * The key of the current sticky item, used to identify which item is in sticky state
     * In LazyList, when an item is set as sticky, its key will be stored here
     * KNode can determine if it's a sticky node by comparing its own slotId with this key
     */
    var stickyItemKey: Any? = null

    /**
     * Flag indicating whether the current list uses PullToRefresh
     * When PullToRefresh is used, the isAtTop judgment logic needs to be adjusted
     */
    var hasPullToRefresh: Boolean = false
        set(value) {
            field = value
            if (value) {
                scrollView?.setHasPullToRefresh(true)
            } else {
                scrollView?.setHasPullToRefresh(false)
            }
        }

    /**
     * Extra top inset on the pull-to-refresh lazy item in pixels,
     * from [com.tencent.kuikly.compose.material3.pullToRefreshItem.topInset].
     */
    var pullToRefreshTopInsetPx: Int = 0

    /**
     * Cached total number of items, used to detect changes in item count
     */
    var cachedTotalItems: Int = 0

    /**
     * When true, [tryExpandStartSize] is skipped. Used by [ScrollableTabRow] whose content
     * size is already exact via [ScrollState.maxValue] + viewport.
     */
    var skipExpandStartSize: Boolean = false

    /**
     * Sticky Header Position Cache Manager
     */
    val stickyHeaderCacheManager = StickyHeaderCacheManager()

    /**
     * Scroll to top event callback.
     * If set, the callback will be invoked instead of the default scroll to top behavior.
     * This aligns with iOS behavior where scrollToTop event can be intercepted.
     */
    var scrollToTopCallback: (() -> Unit)? = null

    /**
     * Ronaq fork (CHANGES.md §30): the logical/native mapping of a horizontal lazy list or
     * grid under Rtl. Identity for every other scroller.
     */
    internal val axis = MirroredScrollAxis()

    /**
     * Update content size to render view
     *
     * Ronaq fork (CHANGES.md §30): for a mirrored scroller this is also the one place where
     * `nativeMax` moves. A change of content size or viewport moves the logical start (the
     * host's physical right edge), so the content children and the native offset shift by
     * the same Δ in the same batch, and nothing moves on screen (invariant I2).
     */
    fun updateContentSizeToRender() {
        if (axis.mirrored) {
            updateMirroredContentToRender()
            return
        }
        val frame = createContentFrame()
        scrollView?.contentView?.setFrameToRenderView(frame)
    }

    private fun updateMirroredContentToRender() {
        val sv = scrollView ?: return
        val contentView = sv.contentView ?: return
        val frame = createContentFrame()
        val plan = axis.planReanchor(currentContentSize, viewportSize)
        if (plan == null) {
            contentView.setFrameToRenderView(frame)
            return
        }
        val density = getDensity()
        if (plan.frameFirst) {
            contentView.setFrameToRenderView(frame)
            shiftMirroredChildren(plan.delta / density)
            writeMirroredNative(plan.toNative)
        } else {
            writeMirroredNative(plan.toNative)
            shiftMirroredChildren(plan.delta / density)
            contentView.setFrameToRenderView(frame)
        }
        axis.commitReanchor(plan)
        // A pending echo of an earlier write moved with the host's coordinates.
        ignoreScrollOffset?.let { ignoreScrollOffset = IntOffset(it.x + plan.delta, it.y) }
    }

    /**
     * Move every content child by [dx] dp, from its Kotlin-side frame so that successive
     * shifts in one turn compose (as `applyOffsetDelta` moves them).
     */
    internal fun shiftMirroredChildren(dx: Float) {
        if (dx == 0f) return
        scrollView?.contentView?.domChildren()?.forEach { subview ->
            val cur = subview.renderView?.currentFrame ?: return@forEach
            subview.setFrameToRenderView(Frame(cur.x + dx, cur.y, cur.width, cur.height))
        }
    }

    /**
     * Write a native x offset (px) to the host, and record it. Android keeps the bridge's
     * `-0.01dp` end write (`ScrollViewEx.applyOffsetDelta`): a mirrored list rests at its
     * physical end.
     */
    internal fun writeMirroredNative(nativePx: Float) {
        val sv = scrollView ?: return
        val density = getDensity()
        if (pageData?.isAndroid == true) {
            sv.setContentOffset(max(0f, nativePx / density - 0.01f), 0f)
        } else {
            sv.setContentOffset(nativePx / density, 0f)
        }
        axis.noteWrite(nativePx)
    }

    /**
     * Ronaq fork (CHANGES.md §30): the native offset of the host view this info last
     * drove — [contentOffset] itself unless mirrored, where [contentOffset] is logical.
     */
    internal val nativeContentOffset: Int
        get() = if (axis.mirrored) axis.lastNative.roundToInt() else contentOffset

    /**
     * Ronaq fork (CHANGES.md §30): a mirrored scroller's own frame changed, which moves
     * `nativeMax = C - W` even though the content size did not.
     */
    internal fun reanchorForViewport() {
        if (!axis.mirrored) return
        updateContentSizeToRender()
    }

    /**
     * Ronaq fork (CHANGES.md §30): rewrite the content frame and the native offset from the
     * logical values after the mirror was switched on or off (a layout-direction change).
     * Children follow at the relayout the direction change causes.
     */
    internal fun resyncNative() {
        val sv = scrollView ?: return
        if (sv.renderView == null) return
        // The host still holds the offset of the mapping being left.
        val hostNative = if (axis.mirrored) contentOffset.toFloat() else axis.lastNative
        val frame = createContentFrame()
        axis.rebase(currentContentSize, viewportSize)
        val native = axis.toNative(contentOffset)
        val oldWidth = sv.contentView?.renderView?.currentFrame?.width ?: 0f
        val frameFirst = frame.width >= oldWidth
        if (frameFirst) sv.contentView?.setFrameToRenderView(frame)
        // Only wait for an echo the host will actually send: an unchanged offset sends none.
        ignoreScrollOffset = if (abs(hostNative - native) >= 1f) IntOffset(native, 0) else null
        if (axis.mirrored) {
            writeMirroredNative(native.toFloat())
        } else {
            sv.setContentOffset(native / getDensity(), 0f)
        }
        if (!frameFirst) sv.contentView?.setFrameToRenderView(frame)
    }

    /**
     * Reset scroll-related state when binding to a new ScrollView (e.g., when LazyColumn's key changes and causes rebuild)
     * Note: This depends on scrollView to get density, so it should be called after setting scrollView
     */
    fun resetForNewScrollView() {
        // Cancel and clear any pending tasks
        appleScrollViewOffsetJob?.cancel()
        appleScrollViewOffsetJob = null

        // Reset basic offset and scroll state
        ignoreScrollOffset = null
        composeOffset = 0f
        contentOffset = 0
        isDragging = false
        offsetDirty = false

        // Reset content size related (reinitialize based on current density)
        currentContentSize = (DEFAULT_CONTENT_SIZE * getDensity()).toInt()
        realContentSize = null

        // Clear list items and pagination caches
        itemMainSpaceCache.clear()
        stickyItemKey = null
        cachedTotalItems = 0
        pullToRefreshTopInsetPx = 0
        axis.reset()
    }

    /**
     * Create content Frame
     */
    private fun createContentFrame(): Frame {
        return if (isVertical()) {
            Frame(
                x = 0f,
                y = 0f,
                width = scrollView?.renderView?.currentFrame?.width ?: 0f,
                height = currentContentSize / getDensity()
            )
        } else {
            // Ronaq fork (CHANGES.md §30, I6): mirrored content is never narrower than its
            // viewport. A short strip's mirrored items sit at the viewport's right end, and
            // a content view only C wide would leave them outside it (Android's FrameLayout
            // clips children).
            val width = if (axis.mirrored) max(currentContentSize, viewportSize) else currentContentSize
            Frame(
                x = 0f,
                y = 0f,
                width = width / getDensity(),
                height = scrollView?.renderView?.currentFrame?.height ?: 0f
            )
        }
    }

    /**
     * Get viewport size
     */
    val viewportSize: Int
        get() {
            val size = if (isVertical()) {
                scrollView?.renderView?.currentFrame?.height ?: 0f
            } else {
                scrollView?.renderView?.currentFrame?.width ?: 0f
            }
            // Use roundToInt instead of toInt to avoid truncating the dp→px conversion.
            // A non-integer density (e.g. 2.625) makes the truncated viewportSize lose ~1px,
            // which keeps toButtomDelta at 1 instead of 0 and breaks the bottom overscroll
            // bounce handling (lastScrolledBackward wrongly set to true).
            return (size * getDensity()).roundToInt()
        }

    /**
     * Get density
     */
    fun getDensity(): Float {
        return scrollView?.getPager()?.pagerDensity() ?: DEFAULT_DENSITY
    }

    /**
     * Check if it's vertical scrolling
     */
    fun isVertical(): Boolean = orientation == Orientation.Vertical

    /**
     * Check if it's near the bottom of scrolling
     */
    fun nearScrollBottom(): Boolean {
        val threshold = SCROLL_BOTTOM_THRESHOLD * getDensity()
        return contentOffset + viewportSize + threshold > currentContentSize
    }
}