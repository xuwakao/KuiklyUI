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

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Ronaq fork (CHANGES.md §30): how a horizontal lazy list's scroll offset maps onto its
 * native scroller under a right-to-left layout.
 *
 * Compose places a horizontal lazy item with `placeRelativeWithLayer`, which mirrors it
 * inside the list's visible box (`LazyListMeasuredItem.place`). Upstream Compose pairs
 * that with a reversed gesture (`scrollingContainer(reverseDirection = …)`). Here the
 * gesture belongs to a native scroller that is physically left-to-right on every host,
 * so the bridge has to do the reversal itself. It does so by treating the native offset
 * as the mirror image of the LOGICAL one — the offset the list would have in
 * left-to-right, where 0 is the first item:
 *
 * ```
 * nativeMax = max(0, C - W)          C: content size pushed to native, W: viewport (px)
 * native    = nativeMax - logical    (its own inverse)
 * frame.x   = pos.x + nativeMax - composeOffset
 * ```
 *
 * On screen an item then sits at `frame.x - native = pos.x`, which is where Compose
 * placed (and hit-tests) it, and a finger that lowers the native offset raises the
 * logical one, which moves the mirrored items WITH the finger.
 *
 * Every Kotlin quantity the bridge already keeps — `composeOffset`, `contentOffset`,
 * `currentContentSize` — stays logical; only values crossing to or from the host are
 * converted here. When [mirrored] is false every function is the identity, so vertical
 * scrollers, pagers and left-to-right lists take exactly the code path they took before.
 *
 * This class holds only arithmetic and bookkeeping, no host calls, so it can be tested
 * on the JVM (`MirroredScrollAxisTest`).
 */
internal class MirroredScrollAxis {

    /**
     * Whether the owning scroller maps its native offset through the mirror. Set by the
     * scroller composable from the orientation, `LocalLayoutDirection` and the state type.
     */
    var mirrored: Boolean = false

    /**
     * `nativeMax` as last pushed to the host: the content frame is this plus the viewport
     * wide. Frames and conversions always use THIS value, never a freshly computed one,
     * so that what Kotlin writes agrees with what the host holds (invariant I1).
     */
    var renderedNativeMax: Int = 0
        private set

    /**
     * The native offset (px) as Kotlin last knew it: the last value it wrote, or the last
     * one the host reported and [accept] took. Read instead of `ScrollerView.curOffsetX`,
     * which only follows host events and so still holds the pre-shift value between a
     * re-anchor and its echo.
     */
    var lastNative: Float = 0f
        private set

    /**
     * Whether [lastNative] is an offset the host itself reported ([accept]), or one Kotlin
     * reached from such an offset by relative moves only. False after Kotlin's own absolute
     * write until the host reports again: Android holds a write its laid-out content has no
     * room for until its next layout pass and drops it there if it still does not fit
     * (`KRRecyclerView.tryApplyPendingSetContentOffset`), so an absolute write is Kotlin's
     * intention, not the host's position. A relative move from an intention lands on wherever
     * the host really is (the Mine sub-tabs of the 2026-09-23 regression rested 31 px off),
     * so a re-anchor shifts only when this is true.
     */
    var hostReported: Boolean = false
        private set

    // Stale-event filter state, armed by a re-anchor (see [accept]).
    private var staleArmed = false
    private var staleFrom = 0f
    private var staleTo = 0f
    private var staleRelative = false
    private var staleBudget = 0

    /** Logical px to native px. Identity unless [mirrored]. */
    fun toNative(logical: Int): Int = if (mirrored) renderedNativeMax - logical else logical

    /**
     * Native px (as the host reported it, density-scaled, possibly fractional) to logical px.
     *
     * Not mirrored: truncates, exactly as the bridge always did (`offset.toInt()`).
     * Mirrored: rounds to the nearest pixel. A host that reports 899.99994 for a native
     * 900 would otherwise truncate to 899 and read back as logical 1 at rest, which is not
     * "at the start" to `isAtTop`, and the list would creep by a pixel.
     */
    fun toLogical(nativePx: Float): Int =
        if (mirrored) renderedNativeMax - nativePx.roundToInt() else nativePx.toInt()

    /**
     * The x an item's native frame adds to its placed position: `composeOffset` when not
     * mirrored (as before), `renderedNativeMax - composeOffset` when mirrored.
     */
    fun frameOriginX(composeOffset: Float): Float =
        if (mirrored) renderedNativeMax - composeOffset else composeOffset

    /**
     * The re-anchor a content-size or viewport change requires, or null when [renderedNativeMax]
     * does not move — which is always the case when not [mirrored]: a left-to-right
     * scroller's start is its native 0 whatever the content size.
     *
     * A mirrored list's logical start is the host's physical END, so a change of `C - W`
     * moves every item's native x and the native offset by the same Δ. Applying Δ to both
     * in one batch leaves every item where it was on screen and the logical offset unchanged
     * (invariant I2).
     */
    fun planReanchor(contentSize: Int, viewport: Int): Reanchor? {
        if (!mirrored) return null
        val newMax = nativeMaxFor(contentSize, viewport)
        val delta = newMax - renderedNativeMax
        if (delta == 0) return null
        return Reanchor(
            delta = delta,
            newNativeMax = newMax,
            fromNative = lastNative,
            toNative = lastNative + delta,
        )
    }

    /**
     * Record that [plan] was written to the host (content frame, children and offset), and
     * arm the stale-event filter: events the host produced before it applied the batch are
     * still in pre-shift coordinates. [relative]: the offset moved by `plan.delta` from wherever
     * the host was (`shiftContentOffset`), rather than to `plan.toNative`.
     */
    fun commitReanchor(plan: Reanchor, relative: Boolean = true) {
        renderedNativeMax = plan.newNativeMax
        lastNative = plan.toNative
        if (!staleArmed) {
            staleFrom = plan.fromNative
            staleTo = plan.toNative
            staleRelative = relative
        } else {
            // The filter is following a run of stale events, so [staleTo] is where the host will
            // land from where it has got to. A relative move adds to that; [lastNative] has not
            // followed the run, and `plan.toNative` would throw the run away (review of 2026-09-23,
            // fork finding F3). An absolute write lands where it says.
            staleTo = if (relative) staleTo + plan.delta else plan.toNative
            staleRelative = staleRelative && relative
        }
        staleArmed = true
        staleBudget = STALE_EVENT_BUDGET
        if (!relative) hostReported = false
    }

    /**
     * Re-base [renderedNativeMax] without shifting anything: for a full resync (first
     * binding, reuse, a direction flip), where the caller rewrites the frame and the
     * offset from the logical values anyway.
     */
    fun rebase(contentSize: Int, viewport: Int) {
        renderedNativeMax = if (mirrored) nativeMaxFor(contentSize, viewport) else 0
        hostReported = false
        clearStale()
    }

    /** Record a native offset Kotlin has just written to the host, absolutely. */
    fun noteWrite(nativePx: Float) {
        lastNative = nativePx
        hostReported = false
        if (staleArmed) {
            staleTo = nativePx
            // The host lands on this value, not on "wherever it was, plus Δ".
            staleRelative = false
        }
    }

    /**
     * Whether a host event at [nativePx] should be taken. Always true unless [mirrored].
     *
     * After a re-anchor, events the host produced before applying the batch arrive in
     * pre-shift coordinates; converted with the new `nativeMax` they would read as a jump
     * of Δ. An event is dropped when it lies within half the distance between the host's
     * position before Kotlin's writes ([Reanchor.fromNative]) and the position Kotlin wrote
     * last, measured from the former. The first event outside that disarms the filter,
     * since events arrive in order.
     *
     * Half the distance, not "nearer the one than the other": a host moving AWAY from the
     * written position further in one event than half of Δ (a fling back across a -1 px
     * re-anchor, which Android makes when it reads a content frame back a pixel short) is
     * nearer the old position on every event, in either coordinates, and every event was
     * dropped until the budget ran out, the fling's last event with it (review of 2026-09-23,
     * fork finding F1). Now such an event is taken: read in the wrong coordinates it is off
     * by Δ, so taking one wrongly costs at most |Δ| px, and only until the events the host
     * sends after the shift arrive.
     *
     * Unlike an exact-match `ignoreScrollOffset`, this never waits for one particular
     * value, which a host may never report once it coalesces the echo with finger motion.
     * The budget bounds it further: a host that lost the write stops being filtered after
     * [STALE_EVENT_BUDGET] events.
     *
     * The comparison follows the host. A dropped event is where the host had got to in the old
     * coordinates, so it becomes the reference for the next one; after a relative move the
     * host lands Δ from wherever it then is, so the other reference moves with it. A Kotlin
     * thread that falls many frames behind in a fling (the blank Home strip of the 2026-09-23
     * regression, `rtl-language/home-ar-blank-strip`) then hears a long run of old-coordinate
     * events, and neither the run's length nor the distance the host covered in it lets one
     * through as a jump of the whole re-anchor.
     */
    fun accept(nativePx: Float): Boolean {
        if (!mirrored) return true
        if (staleArmed) {
            val stale = abs(nativePx - staleFrom) < abs(staleTo - staleFrom) / 2f
            if (stale && staleBudget > 0) {
                staleBudget -= 1
                if (staleRelative) staleTo += nativePx - staleFrom
                staleFrom = nativePx
                return false
            }
            clearStale()
        }
        lastNative = nativePx
        hostReported = true
        return true
    }

    /**
     * The host's (forward, backward) nested-scroll modes for the list's own [forward] (towards
     * its end) and [backward] (towards its start). A host applies them physically — forward
     * is its native offset growing (`KRRecyclerView` `parentDx > 0`) — and a mirrored list
     * moves towards its end by LOWERING the native offset, so they swap. Identity otherwise.
     */
    fun <T> hostNestedModes(forward: T, backward: T): Pair<T, T> =
        if (mirrored) backward to forward else forward to backward

    /** Forget everything bound to a particular host view; keeps [mirrored]. */
    fun reset() {
        renderedNativeMax = 0
        lastNative = 0f
        hostReported = false
        clearStale()
    }

    private fun clearStale() {
        staleArmed = false
        staleBudget = 0
    }

    /**
     * One re-anchor step.
     *
     * @property delta how far every content child and the native offset move (px).
     * @property newNativeMax the `nativeMax` the host holds once the step is applied.
     * @property fromNative the native offset Kotlin believed before the step.
     * @property toNative the native offset to write.
     */
    class Reanchor(
        val delta: Int,
        val newNativeMax: Int,
        val fromNative: Float,
        val toNative: Float,
    ) {
        /**
         * Growing: write the wider content frame before the offset, or a browser clamps
         * `scrollLeft` to the old width and Android defers the write against it
         * (`KRRecyclerView.canScrollImmediately`). Shrinking: write the offset first, for
         * the same reason the other way round.
         */
        val frameFirst: Boolean get() = delta > 0
    }

    companion object {
        /**
         * How many events the stale filter may drop after one re-anchor: about a quarter to half
         * a second of a fling's events at 120 to 60 Hz. It only has to outlast the frames a busy
         * Kotlin thread falls behind by (8 did not, `accept`); a host that lost the write is
         * followed again after this many.
         */
        const val STALE_EVENT_BUDGET = 32

        fun nativeMaxFor(contentSize: Int, viewport: Int): Int = max(0, contentSize - viewport)
    }
}
