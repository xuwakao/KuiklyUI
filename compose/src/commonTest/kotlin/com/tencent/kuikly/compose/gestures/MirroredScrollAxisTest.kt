package com.tencent.kuikly.compose.gestures

import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ronaq fork (CHANGES.md §30). The mirror arithmetic on its own, then a model of the
 * whole bridge — a physically left-to-right host scroller, a lazy row that mirrors its
 * placement under Rtl, and the bridge steps of `SubcomposeLayout`'s scroll handler,
 * `KuiklyScrollInfo.updateContentSizeToRender` and `applyOffsetDelta` expressed through
 * [MirroredScrollAxis] — driven by a simulated finger.
 *
 * The model is a model: it proves the sign and the bookkeeping, not the hosts. The
 * browser check (`scripts/lazyrow-direction-web.mjs`) and the device runs in the Ronaq
 * issue record are what show the real renderers agree.
 */
class MirroredScrollAxisTest {

    // ---- the arithmetic -------------------------------------------------------------

    @Test fun everyFunctionIsTheIdentityWhenNotMirrored() {
        val axis = MirroredScrollAxis()
        axis.rebase(contentSize = 9000, viewport = 1290)
        assertEquals(0, axis.renderedNativeMax)
        assertEquals(1234, axis.toNative(1234))
        // Truncation, exactly as the bridge always read `offset.toInt()`.
        assertEquals(899, axis.toLogical(899.99994f))
        assertEquals(-30, axis.toLogical(-30.4f))
        assertEquals(417f, axis.frameOriginX(417f))
        assertNull(axis.planReanchor(12000, 1290)?.takeIf { axis.mirrored })
        // Never filters, whatever came before.
        assertTrue(axis.accept(5f))
        assertTrue(axis.accept(5000f))
    }

    @Test fun theMirrorIsItsOwnInverse() {
        val axis = mirroredAxis(contentSize = 9000, viewport = 1290)
        assertEquals(7710, axis.renderedNativeMax)
        for (logical in listOf(0, 1, 100, 3855, 7709, 7710)) {
            val native = axis.toNative(logical)
            assertEquals(7710 - logical, native)
            assertEquals(logical, axis.toLogical(native.toFloat()))
        }
        assertEquals(7710f - 250f, axis.frameOriginX(250f))
    }

    @Test fun aNativeOffsetAtRestReadsAsLogicalZero() {
        val axis = mirroredAxis(contentSize = 9000, viewport = 1290)
        // A host that reports its end a hair short (float dp round trip, or Android's
        // -0.01dp end write) must still read as the first item, not as logical 1.
        assertEquals(0, axis.toLogical(7709.99994f))
        assertEquals(0, axis.toLogical(7710f - 0.03f))
        assertEquals(0, axis.toLogical(7710.2f))
        // Past the start is a bounce: negative logical, as a left-to-right bounce is.
        assertEquals(-90, axis.toLogical(7800f))
    }

    @Test fun aShortStripHasNothingToScroll() {
        val axis = mirroredAxis(contentSize = 900, viewport = 1290)
        assertEquals(0, axis.renderedNativeMax)
        assertEquals(0, axis.toNative(0))
    }

    @Test fun growthWritesTheFrameFirstAndShiftsByTheGrowth() {
        val axis = mirroredAxis(contentSize = 9000, viewport = 1290)
        axis.noteWrite(7710f - 1800f)
        val plan = assertNotNull(axis.planReanchor(contentSize = 13500, viewport = 1290))
        assertEquals(4500, plan.delta)
        assertEquals(12210, plan.newNativeMax)
        assertEquals(5910f, plan.fromNative)
        assertEquals(10410f, plan.toNative)
        assertTrue(plan.frameFirst)
        axis.commitReanchor(plan)
        assertEquals(12210, axis.renderedNativeMax)
        // The logical offset does not change across a re-anchor (I2).
        assertEquals(1800, axis.toLogical(axis.lastNative))
    }

    @Test fun shrinkWritesTheOffsetFirst() {
        val axis = mirroredAxis(contentSize = 13500, viewport = 1290)
        axis.noteWrite(12210f - 2000f)
        val plan = assertNotNull(axis.planReanchor(contentSize = 9900, viewport = 1290))
        assertEquals(-3600, plan.delta)
        assertFalse(plan.frameFirst)
        assertEquals(6610f, plan.toNative)
        axis.commitReanchor(plan)
        assertEquals(2000, axis.toLogical(axis.lastNative))
    }

    @Test fun aViewportChangeReanchorsToo() {
        // First binding happens before the scroller has a frame: W = 0.
        val axis = mirroredAxis(contentSize = 9000, viewport = 0)
        axis.noteWrite(axis.toNative(0).toFloat())
        val plan = assertNotNull(axis.planReanchor(contentSize = 9000, viewport = 1290))
        assertEquals(-1290, plan.delta)
        axis.commitReanchor(plan)
        assertEquals(7710f, axis.lastNative)
        assertEquals(0, axis.toLogical(axis.lastNative))
    }

    @Test fun noReanchorWhenNativeMaxHoldsStill() {
        val axis = mirroredAxis(contentSize = 9000, viewport = 1290)
        assertNull(axis.planReanchor(9000, 1290))
        // Content narrower than the viewport both times: nativeMax stays 0.
        val short = mirroredAxis(contentSize = 600, viewport = 1290)
        assertNull(short.planReanchor(900, 1290))
    }

    @Test fun staleEventsAfterAReanchorAreDroppedAndTheEchoDisarms() {
        val axis = mirroredAxis(contentSize = 9000, viewport = 1290)
        axis.noteWrite(5910f)
        axis.commitReanchor(assertNotNull(axis.planReanchor(13500, 1290)))  // 5910 -> 10410
        // Finger motion the host produced before it applied the batch.
        assertFalse(axis.accept(5900f))
        assertFalse(axis.accept(5870f))
        assertEquals(10410f, axis.lastNative)
        // The echo, already moved on by the finger: taken, and the filter is off.
        assertTrue(axis.accept(10395f))
        assertEquals(10395f, axis.lastNative)
        // A later event near the old coordinates is no longer second-guessed.
        assertTrue(axis.accept(5900f))
    }

    @Test fun aLaterWriteIsWhatTheFilterComparesWith() {
        val axis = mirroredAxis(contentSize = 9000, viewport = 1290)
        axis.noteWrite(5910f)
        axis.commitReanchor(assertNotNull(axis.planReanchor(13500, 1290)))
        // applyOffsetDelta writes again in the same turn.
        axis.noteWrite(9000f)
        assertFalse(axis.accept(5915f))
        assertTrue(axis.accept(9003f))
    }

    @Test fun theFilterGivesUpAfterItsBudget() {
        val axis = mirroredAxis(contentSize = 9000, viewport = 1290)
        axis.noteWrite(5910f)
        axis.commitReanchor(assertNotNull(axis.planReanchor(13500, 1290)))
        repeat(MirroredScrollAxis.STALE_EVENT_BUDGET) { assertFalse(axis.accept(5910f)) }
        // A host that lost the write is followed again rather than ignored for ever.
        assertTrue(axis.accept(5910f))
    }

    @Test fun rebaseAndResetForgetThePendingFilter() {
        val axis = mirroredAxis(contentSize = 9000, viewport = 1290)
        axis.noteWrite(5910f)
        axis.commitReanchor(assertNotNull(axis.planReanchor(13500, 1290)))
        axis.rebase(13500, 1290)
        assertTrue(axis.accept(5910f))
        axis.reset()
        assertEquals(0, axis.renderedNativeMax)
        assertEquals(0f, axis.lastNative)
        assertTrue(axis.mirrored)
    }

    // ---- the model ------------------------------------------------------------------

    @Test fun aFingerMovesEveryChipWithItInBothDirections() {
        for (rtl in listOf(false, true)) {
            val m = Model(rtl).also { it.settle() }
            val forward = if (rtl) FINGER else -FINGER   // the finger that reveals later chips
            val before = m.screenXs()
            m.drag(forward)
            m.assertAllMovedBy(before, forward, "rtl=$rtl forward")
            val mid = m.screenXs()
            m.drag(-forward / 2)
            m.assertAllMovedBy(mid, -forward / 2, "rtl=$rtl back")
        }
    }

    @Test fun theFirstChipOpensAtTheStartEdge() {
        val ltr = Model(rtl = false).also { it.settle() }
        assertEquals(0, ltr.screenX(0))
        assertEquals(0, ltr.host.offset.toInt())
        val rtl = Model(rtl = true).also { it.settle() }
        assertEquals(VIEWPORT - WIDTHS[0], rtl.screenX(0))
        assertEquals(0, rtl.logical)
        // At rest the native offset is nativeMax, the host's physical end (I3).
        assertEquals(rtl.host.maxOffset, rtl.host.offset.toInt())
    }

    @Test fun aBackwardFingerAtTheStartMovesNothing() {
        val m = Model(rtl = true).also { it.settle() }
        val before = m.screenXs()
        m.drag(-FINGER)   // right to left in Arabic: already at the start
        assertEquals(before, m.screenXs())
    }

    @Test fun repeatedForwardDragsReachTheLastChipAndStop() {
        val m = Model(rtl = true).also { it.settle() }
        repeat(60) { m.drag(FINGER) }
        // The last chip is fully on screen at the far (left) edge, and no further.
        assertEquals(0, m.screenX(WIDTHS.lastIndex))
        val atEnd = m.screenXs()
        m.drag(FINGER)
        assertEquals(atEnd, m.screenXs())
        // It grew past the default size at least once and collapsed at the end.
        assertTrue(m.grew > 0, "grew ${m.grew}")
        assertTrue(m.shrank > 0, "shrank ${m.shrank}")
    }

    @Test fun theCollapseAtTheLastItemMovesNothingOnScreen() {
        val m = Model(rtl = true).also { it.settle() }
        val step = 100
        var sawCollapse = false
        repeat(200) {
            val before = m.screenXs()
            val shrankBefore = m.shrank
            val room = m.maxLogical - m.logical
            m.drag(step)
            if (m.shrank > shrankBefore) {
                sawCollapse = true
                // Every chip moved by exactly the finger, however far the host re-anchored.
                m.assertAllMovedBy(before, min(step, room), "collapse")
            }
        }
        assertTrue(sawCollapse)
    }

    @Test fun staleHostEventsAcrossAReanchorDoNotJump() {
        val m = Model(rtl = true).also { it.settle() }
        // Drag until one event has grown the content.
        while (m.grew == 0) m.drag(FINGER)
        val before = m.screenXs()
        // The host produced one more finger event before it applied the re-anchor batch.
        m.onScroll(m.preShiftOffset - 40f)
        assertEquals(before, m.screenXs())
        // Its echo, and the finger after it, are taken as usual.
        m.drag(FINGER)
        m.assertAllMovedBy(before, FINGER, "after the echo")
    }

    @Test fun scrollToItemThenRealignKeepsTheScreenStill() {
        for (rtl in listOf(false, true)) {
            val m = Model(rtl).also { it.settle() }
            m.scrollToItem(6)
            assertEquals(if (rtl) VIEWPORT - WIDTHS[6] else 0, m.screenX(6))
            val placed = m.screenXs()
            // The 150 ms realignment (`tryExpandStartSizeNoScroll`) moves the logical native
            // origin through applyOffsetDelta; nothing may move on screen.
            m.realign()
            assertEquals(placed, m.screenXs(), "rtl=$rtl realign")
            // And the list can now be dragged back towards the first chip.
            val back = if (rtl) -FINGER else FINGER
            m.drag(back)
            m.assertAllMovedBy(placed, back, "rtl=$rtl back after realign")
        }
    }

    private fun mirroredAxis(contentSize: Int, viewport: Int) = MirroredScrollAxis().apply {
        mirrored = true
        rebase(contentSize, viewport)
    }

    private companion object {
        const val VIEWPORT = 1290
        const val DEFAULT_CONTENT = 9000      // 3000 dp at 3x
        const val EXPAND = 4500               // DEFAULT_EXPAND_SIZE
        const val BUFFER = 6000               // CONTENT_SIZE_BUFFER
        const val FINGER = 300
        /** Forty server-ordered chips, 150..276 px wide. */
        val WIDTHS = List(40) { 150 + (it * 37) % 127 }
        const val GAP = 20
    }

    /**
     * A physically left-to-right host scroller, a lazy row, and the bridge between them,
     * all in px. `host.offset` is the native offset; `logical` is the row's own scroll
     * position (LazyListState's); `composeOffset` / `contentOffset` are the bridge's.
     */
    private class Model(val rtl: Boolean) {
        val host = Host()
        val axis = MirroredScrollAxis().apply { mirrored = rtl }
        var contentSize = DEFAULT_CONTENT
        var realContentSize: Int? = null
        var composeOffset = 0f
        var contentOffset = 0
        var logical = 0
        var grew = 0
        var shrank = 0
        var preShiftOffset = 0f
        private val starts = WIDTHS.runningFold(0) { acc, w -> acc + w + GAP }
        private val total = starts[WIDTHS.size] - GAP
        val maxLogical = max(0, total - VIEWPORT)

        class Host {
            var contentWidth = 0
            var offset = 0f
            val children = HashMap<Int, Float>()
            val maxOffset get() = max(0, contentWidth - VIEWPORT)
        }

        /** Compose's placement: `placeRelative` mirrors inside the visible box under Rtl. */
        fun pos(i: Int): Int {
            val ltr = starts[i] - logical
            return if (rtl) VIEWPORT - ltr - WIDTHS[i] else ltr
        }

        fun screenX(i: Int): Int = (host.children.getValue(i) - host.offset).toInt()
        fun screenXs(): List<Int> = WIDTHS.indices.map { screenX(it) }

        fun assertAllMovedBy(before: List<Int>, d: Int, what: String) {
            val after = screenXs()
            for (i in before.indices) assertEquals(before[i] + d, after[i], "$what: chip $i")
        }

        /** KNode.updateKuiklyViewFrame for every item. */
        fun layout() {
            for (i in WIDTHS.indices) host.children[i] = pos(i) + axis.frameOriginX(composeOffset)
        }

        fun write(native: Float) {
            host.offset = native
            axis.noteWrite(native)
        }

        /** restoreScrollerViewOnReuse before the scroller has a frame, then the frame. */
        fun settle() {
            axis.rebase(contentSize, 0)
            host.contentWidth = contentSize
            write(axis.toNative(contentOffset).toFloat())
            layout()
            updateContentSizeToRender()   // the viewport arrives: re-anchor by -W
            layout()
        }

        /** KuiklyScrollInfo.updateContentSizeToRender. */
        fun updateContentSizeToRender() {
            val width = if (rtl) max(contentSize, VIEWPORT) else contentSize
            val plan = if (rtl) axis.planReanchor(contentSize, VIEWPORT) else null
            if (plan == null) { host.contentWidth = width; return }
            if (plan.delta > 0) grew += 1 else shrank += 1
            preShiftOffset = host.offset
            host.contentWidth = width
            for (k in host.children.keys) host.children[k] = host.children.getValue(k) + plan.delta
            write(plan.toNative)
            axis.commitReanchor(plan)
        }

        /** calculateAndUpdateContentSize. */
        fun calculateAndUpdateContentSize() {
            val lastVisible = starts[WIDTHS.lastIndex] - logical < VIEWPORT
            realContentSize = if (lastVisible) (composeOffset + total - logical).toInt() else null
            val computed = realContentSize ?: run {
                val frameWidth = host.contentWidth
                if (frameWidth - (composeOffset.toInt() + VIEWPORT) < BUFFER) frameWidth + EXPAND else frameWidth
            }
            contentSize = if (computed < contentSize && composeOffset > computed - VIEWPORT) {
                max(computed, composeOffset.toInt() + VIEWPORT)
            } else computed
            updateContentSizeToRender()
        }

        /** The finger: a physical scroller moves its offset against the finger, clamped. */
        fun drag(finger: Int) {
            host.offset = min(host.maxOffset.toFloat(), max(0f, host.offset - finger))
            onScroll(host.offset)
        }

        /** SubcomposeLayout's scroll handler, list path. */
        fun onScroll(native: Float) {
            if (!axis.accept(native)) return
            val offset = axis.toLogical(native)
            contentOffset = offset
            val delta = offset - composeOffset
            if (delta.toInt() == 0) return
            calculateAndUpdateContentSize()
            val toBottom = realContentSize?.let { it - VIEWPORT - composeOffset }
            if (offset < 0 && logical == 0) return
            if (toBottom != null && delta > toBottom) {
                if (toBottom.toInt() <= 0) return
                composeOffset += min(delta, toBottom)
            } else {
                composeOffset = max(0f, composeOffset + delta)
            }
            logical = (logical + delta.toInt()).coerceIn(0, maxLogical)   // kuiklyOnScroll
            layout()
        }

        /** LazyListState.scrollToItem: the row jumps, the native offset does not. */
        fun scrollToItem(i: Int) {
            logical = starts[i]
            layout()
        }

        /** tryExpandStartSizeNoScroll's first branch: move the logical native origin. */
        fun realign() {
            val minDelta = DEFAULT_CONTENT
            val delta = minDelta
            val maxDelta = contentSize - VIEWPORT - contentOffset
            if (delta > maxDelta) {
                contentSize += delta - maxDelta + minDelta
                updateContentSizeToRender()
            }
            applyOffsetDelta(delta)
            onScroll(host.offset)   // the echo
        }

        /** ScrollViewEx.applyOffsetDelta (both paths) + applyScrollViewOffsetDelta. */
        fun applyOffsetDelta(delta: Int) {
            val cur = if (rtl) axis.toLogical(axis.lastNative) else host.offset.toInt()
            val newLogical = cur + delta
            if (composeOffset.toInt() == newLogical) return
            if (newLogical + VIEWPORT > contentSize) {
                contentSize += 6000 + delta
                updateContentSizeToRender()
            }
            val shift = if (rtl) -delta else delta
            for (k in host.children.keys) host.children[k] = host.children.getValue(k) + shift
            write(axis.toNative(newLogical).toFloat())
            composeOffset = newLogical.toFloat()
        }
    }
}
