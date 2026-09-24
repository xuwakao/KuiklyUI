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
     * Ronaq fork (CHANGES.md §30): the host operations of the mirrored bridge. Production
     * drives the bound [scrollView]; `MirroredScrollAxisTest` puts a fake scroller here, so
     * the bridge functions below are the ones under test.
     */
    internal var mirroredHost: MirroredScrollHost = ScrollerMirroredHost(this)

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
            updateMirroredContentToRender(viewportChanged = false)
            return
        }
        val frame = createContentFrame()
        scrollView?.contentView?.setFrameToRenderView(frame)
    }

    /**
     * Ronaq fork (CHANGES.md §30, I6): a mirrored content view is never narrower than its
     * viewport. A short strip's mirrored items sit at the viewport's right end, and a
     * content view only C wide would leave them outside it (Android's FrameLayout clips).
     */
    private fun mirroredContentWidthDp(host: MirroredScrollHost): Float =
        max(currentContentSize, host.viewportPx) / host.density

    /**
     * [viewportChanged]: the re-anchor follows a change of the scroller's own frame
     * ([reanchorForViewport]) rather than of the content size.
     */
    private fun updateMirroredContentToRender(viewportChanged: Boolean) {
        val host = mirroredHost
        if (!host.bound || !host.hasContent) return
        val width = mirroredContentWidthDp(host)
        val plan = axis.planReanchor(currentContentSize, host.viewportPx)
        if (plan == null) {
            host.setContentWidth(width)
            return
        }
        val deltaDp = plan.delta / host.density
        val relative: Boolean
        if (plan.frameFirst) {
            host.setContentWidth(width)
            host.shiftChildren(deltaDp)
            relative = moveMirroredNative(plan, viewportChanged)
        } else {
            relative = moveMirroredNative(plan, viewportChanged)
            host.shiftChildren(deltaDp)
            host.setContentWidth(width)
        }
        axis.commitReanchor(plan, relative)
        // A pending echo of an earlier write moved with the host's coordinates.
        ignoreScrollOffset?.let { ignoreScrollOffset = IntOffset(it.x + plan.delta, it.y) }
    }

    /**
     * Move the host's native offset by a re-anchor's Δ.
     *
     * A content-size re-anchor fires from the scroll handler, so it lands mid-drag and
     * mid-fling (the +1500 dp growth, the collapse when the last item first shows). There the
     * host shifts its offset by Δ from wherever it is at that moment (`shiftContentOffset`):
     * an absolute write would stop an iOS deceleration dead (`-setContentOffset:animated:NO`)
     * and, on every host, would drop the motion the host made since the event Kotlin is
     * handling, a jerk back of a frame or two. Hosts without the method (HarmonyOS, macOS,
     * mini programs) keep the absolute write.
     *
     * A viewport re-anchor stays absolute: the host has just resized its own frame, and may
     * already have clamped its offset to the new range, so a relative shift would count
     * that clamp twice.
     *
     * So does a re-anchor made while Kotlin's idea of the offset is its own last absolute
     * write rather than something the host reported (`MirroredScrollAxis.hostReported`): the
     * binding's write and the first frame's, before the host has laid out or said anything.
     * Android may still be holding that write for its next layout pass and drop it there, and
     * a shift queued behind it would then land on the host's old offset. That is how the Mine
     * sub-tabs of the 2026-09-23 regression (751 px of content in 720) came to rest 31 px off:
     * the real size arrived at rest, before the host's first layout. An absolute write replaces
     * the one the host is holding. The host is at rest then, or has been written absolutely,
     * so there is no deceleration to keep. The regression is in Ronaq
     * `docs/evidence/regression-2026-09-23/rtl-language/android/mine-ar`.
     *
     * Either way the move runs with nested scrolling on SELF_ONLY, as `applyOffsetDelta`
     * has always guarded its programmatic moves. Returns whether the move was relative.
     */
    private fun moveMirroredNative(plan: MirroredScrollAxis.Reanchor, viewportChanged: Boolean): Boolean {
        val host = mirroredHost
        val restoreNested = host.holdNestedScrollSelfOnly()
        val relative = !viewportChanged && host.canShiftOffset && axis.hostReported
        if (relative) {
            host.shiftOffset(plan.delta / host.density)
        } else {
            writeMirroredNative(plan.toNative)
        }
        restoreNested?.invoke()
        return relative
    }

    /**
     * Write a native x offset (px) to the host, absolutely, and record it. Android keeps the
     * bridge's `-0.01dp` end write (`ScrollViewEx.applyOffsetDelta`): a mirrored list rests at
     * its physical end.
     */
    internal fun writeMirroredNative(nativePx: Float) {
        val host = mirroredHost
        if (!host.bound) return
        val density = host.density
        if (host.isAndroid) {
            host.writeOffset(max(0f, nativePx / density - 0.01f))
        } else {
            host.writeOffset(nativePx / density)
        }
        axis.noteWrite(nativePx)
    }

    /**
     * Ronaq fork (CHANGES.md §30): `applyOffsetDelta` for a mirrored scroller (a horizontal
     * lazy list or grid under Rtl). [delta] and the returned x are LOGICAL, like
     * `composeOffset`; the native offset is `nativeMax - logical`. [curYPx] is the host's
     * current y offset, passed through as the left-to-right path passes it.
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
    internal fun applyMirroredOffsetDelta(delta: Int, curYPx: Int): IntOffset {
        val host = mirroredHost
        val density = host.density
        val hostNative = axis.lastNative
        val newLogical = axis.toLogical(hostNative) + delta

        if (composeOffset.toInt() == newLogical) {
            return IntOffset(newLogical, curYPx)
        }

        // Grow the content (logical, as before); the re-anchor keeps the screen still.
        if (host.rendered) {
            if (newLogical + host.viewportPx > currentContentSize) {
                currentContentSize += (2000 * density + delta).toInt()
                updateContentSizeToRender()
            }
        }

        // Keep nested scrolling out of the programmatic move.
        val restoreNested = host.holdNestedScrollSelfOnly()

        host.shiftChildren(-delta / density)

        val newNative = axis.toNative(newLogical)
        if (abs(newNative - hostNative) >= 1f) {
            // Only wait for an echo the host will send: an offset left where the host already
            // is sends none.
            ignoreScrollOffset = IntOffset(newNative, curYPx)
        }
        writeMirroredNative(newNative.toFloat())

        restoreNested?.invoke()

        return IntOffset(newLogical, curYPx)
    }

    /**
     * Ronaq fork (CHANGES.md §30): the Compose-side offset of a host `scroll`, `dragEnd` or
     * `scrollEnd` event at ([offsetXPx], [offsetYPx]) (density-scaled), or null when a
     * mirrored scroller drops it as stale (produced before the host applied a re-anchor).
     * Vertical: `offsetY.toInt()`; horizontal and not mirrored: `offsetX.toInt()` — exactly
     * as the handlers always read it. Mirrored: `nativeMax - native`.
     */
    internal fun offsetFromHost(isVertical: Boolean, offsetXPx: Float, offsetYPx: Float): Int? {
        if (!axis.accept(offsetXPx)) return null
        return if (isVertical) offsetYPx.toInt() else axis.toLogical(offsetXPx)
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
        updateMirroredContentToRender(viewportChanged = true)
    }

    /**
     * Ronaq fork (CHANGES.md §30): rewrite the content frame and the native offset from the
     * logical values after the mirror was switched on or off (a layout-direction change).
     * Children follow at the relayout the direction change causes.
     */
    internal fun resyncNative() {
        val host = mirroredHost
        if (!host.bound || !host.rendered) return
        // The host still holds the offset of the mapping being left.
        val hostNative = if (axis.mirrored) contentOffset.toFloat() else axis.lastNative
        val width = if (axis.mirrored) mirroredContentWidthDp(host) else currentContentSize / host.density
        axis.rebase(currentContentSize, host.viewportPx)
        val native = axis.toNative(contentOffset)
        val frameFirst = width >= (host.contentWidthDp ?: 0f)
        if (frameFirst) host.setContentWidth(width)
        // Only wait for an echo the host will actually send: an unchanged offset sends none.
        ignoreScrollOffset = if (abs(hostNative - native) >= 1f) IntOffset(native, 0) else null
        if (axis.mirrored) {
            writeMirroredNative(native.toFloat())
        } else {
            host.writeOffset(native / host.density)
        }
        if (!frameFirst) host.setContentWidth(width)
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
            Frame(
                x = 0f,
                y = 0f,
                width = currentContentSize / getDensity(),
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