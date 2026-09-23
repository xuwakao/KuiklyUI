package com.tencent.kuikly.compose.gestures

import com.tencent.kuikly.compose.foundation.gestures.Orientation
import com.tencent.kuikly.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ronaq fork (CHANGES.md §30). The mirror arithmetic on its own, then the bridge driven by
 * a simulated finger.
 *
 * The bridge half runs the PRODUCTION functions of [KuiklyScrollInfo] — the re-anchor in
 * `updateContentSizeToRender`, `writeMirroredNative`, `applyMirroredOffsetDelta`,
 * `offsetFromHost` (the scroll / dragEnd / scrollEnd conversion), `reanchorForViewport` —
 * against [FakeScroller], a [MirroredScrollHost] that behaves like the strictest host:
 * physically left to right, clamping its offset to `[0, content - viewport]` on every write
 * and every resize, as a browser clamps `scrollLeft`. What the test still models itself is
 * what has no host seam and does not change under the mirror: the lazy row's own placement
 * (`placeRelative` mirrors under Rtl), the handler's `composeOffset` bookkeeping,
 * `calculateAndUpdateContentSize`, and the whole left-to-right path, which stays the
 * reference the mirrored runs are compared with.
 *
 * Flings add one host property per platform: on iOS `-setContentOffset:animated:NO` ends a
 * deceleration ([FakeScroller.absoluteWriteStopsFling]); on every host an absolute write
 * drops whatever the host moved since Kotlin's last event. A fling's events reach Kotlin a
 * frame late, as they do through the context queue.
 *
 * A model of the hosts proves the sign and the bookkeeping, not the hosts. The browser check
 * (`scripts/lazyrow-direction-web.mjs`) and the device runs in the Ronaq issue record are
 * what show the real renderers agree.
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
        // Nothing to re-anchor: a left-to-right scroller's start never moves.
        assertNull(axis.planReanchor(12000, 1290))
        assertEquals("forward" to "backward", axis.hostNestedModes("forward", "backward"))
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

    @Test fun nestedScrollDirectionsSwapUnderTheMirror() {
        // forward / backward name the list's own directions (towards its end / its start);
        // a mirrored list moves towards its end by LOWERING the native offset.
        val axis = mirroredAxis(contentSize = 9000, viewport = 1290)
        assertEquals("backward" to "forward", axis.hostNestedModes("forward", "backward"))
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

    // ---- the bridge, driven by a finger ---------------------------------------------

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
        // The viewport arrives after the first write; the host clamps its offset to the new
        // range before Kotlin re-anchors, so this re-anchor must be an absolute write.
        val rtl = Model(rtl = true).also { it.settle() }
        assertEquals(VIEWPORT - WIDTHS[0], rtl.screenX(0))
        assertEquals(0, rtl.logical)
        // At rest the native offset is nativeMax, the host's physical end (I3).
        assertEquals(rtl.host.maxOffset, rtl.host.offset.roundToInt())
    }

    @Test fun androidRestsAHairShortOfItsEndAndStillReadsTheFirstChip() {
        val m = Model(rtl = true, host = FakeScroller(isAndroid = true)).also { it.settle() }
        // The -0.01 dp end write leaves the host 0.03 px short of nativeMax.
        assertTrue(m.host.offset < m.host.maxOffset)
        assertEquals(VIEWPORT - WIDTHS[0], m.screenX(0))
        assertEquals(0, m.info.offsetFromHost(false, m.host.offset, 0f))
        m.drag(FINGER)
        assertEquals(VIEWPORT - WIDTHS[0] + FINGER, m.screenX(0))
    }

    @Test fun aBackwardFingerAtTheStartMovesNothing() {
        val m = Model(rtl = true).also { it.settle() }
        val before = m.screenXs()
        m.drag(-FINGER)   // right to left in Arabic: already at the start
        assertEquals(before, m.screenXs())
    }

    @Test fun repeatedForwardDragsReachTheLastChipAndStop() {
        for (shifts in listOf(false, true)) {
            val m = Model(rtl = true, host = FakeScroller(canShiftOffset = shifts)).also { it.settle() }
            repeat(60) { m.drag(FINGER) }
            // The last chip is fully on screen at the far (left) edge, and no further.
            assertEquals(0, m.screenX(WIDTHS.lastIndex), "shifts=$shifts")
            val atEnd = m.screenXs()
            m.drag(FINGER)
            assertEquals(atEnd, m.screenXs(), "shifts=$shifts")
            // It grew past the default size at least once and collapsed at the end.
            assertTrue(m.grew > 0, "grew ${m.grew}")
            assertTrue(m.shrank > 0, "shrank ${m.shrank}")
        }
    }

    @Test fun noReanchorMovesAnythingOnScreen() {
        for (shifts in listOf(false, true)) {
            val m = Model(rtl = true, host = FakeScroller(canShiftOffset = shifts)).also { it.settle() }
            val step = 100
            var reanchors = 0
            repeat(200) {
                val before = m.screenXs()
                val count = m.grew + m.shrank
                val room = m.maxLogical - m.logical
                m.drag(step)
                if (m.grew + m.shrank > count) {
                    reanchors += 1
                    // Every chip moved by exactly the finger, however far the host re-anchored.
                    m.assertAllMovedBy(before, min(step, room), "shifts=$shifts re-anchor $reanchors")
                }
            }
            assertTrue(m.grew > 0 && m.shrank > 0, "grew ${m.grew} shrank ${m.shrank}")
        }
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
            // origin through applyOffsetDelta; nothing may move on screen, and its echo is
            // swallowed by ignoreScrollOffset.
            m.realign()
            assertEquals(placed, m.screenXs(), "rtl=$rtl realign")
            assertNull(m.info.ignoreScrollOffset, "rtl=$rtl the echo matched")
            // And the list can now be dragged back towards the first chip.
            val back = if (rtl) -FINGER else FINGER
            m.drag(back)
            m.assertAllMovedBy(placed, back, "rtl=$rtl back after realign")
        }
    }

    @Test fun reanchorsKeepNestedScrollingOutOfTheMove() {
        for (shifts in listOf(false, true)) {
            val host = FakeScroller(canShiftOffset = shifts)
            val m = Model(rtl = true, host = host).also { it.settle() }
            host.moves.clear()
            repeat(60) { m.drag(FINGER) }
            assertTrue(m.grew > 0 && m.shrank > 0)
            // Every offset move Kotlin made during the drags — the re-anchors included — ran
            // with the scroller's nested scrolling on SELF_ONLY, and the setting came back.
            assertTrue(host.moves.isNotEmpty())
            assertTrue(host.moves.all { it.nested == SELF_ONLY }, "shifts=$shifts ${host.moves}")
            assertEquals(SELF_FIRST, host.nested)
        }
    }

    // ---- flings: the host moves on its own, Kotlin hears a frame late ---------------

    @Test fun aFlingCarriesThroughEveryReanchorToTheLastChip() {
        // iOS semantics (an absolute write ends a deceleration) on a host that shifts.
        val m = Model(rtl = true, host = FakeScroller(canShiftOffset = true, absoluteWriteStopsFling = true))
            .also { it.settle() }
        m.fling(FLING)
        assertTrue(m.grew > 0 && m.shrank > 0, "grew ${m.grew} shrank ${m.shrank}")
        assertEquals(0, m.screenX(WIDTHS.lastIndex), "the fling ends with the last chip at the far edge")
        // Every re-anchor went to the host as a shift, none as an absolute write.
        assertTrue(m.host.moves.none { it.kind == "set" && it.duringFling }, "${m.host.moves}")
    }

    @Test fun controlAnAbsoluteReanchorWriteEndsAnIosFling() {
        // The same fling on a host without the shift: the first re-anchor ends it. This is
        // the defect the shift exists for, kept as a control that the model can see it.
        val m = Model(rtl = true, host = FakeScroller(canShiftOffset = false, absoluteWriteStopsFling = true))
            .also { it.settle() }
        m.fling(FLING)
        assertTrue(m.grew + m.shrank > 0)
        assertTrue(m.screenX(WIDTHS.lastIndex) < 0, "stopped short: last chip at ${m.screenX(WIDTHS.lastIndex)}")
    }

    @Test fun aFlingUnderTheMirrorIsTheMirrorImageOfTheLeftToRightFling() {
        // Frame by frame, every chip of the Arabic fling sits where the English fling's chip
        // sits, mirrored: the re-anchors add nothing on screen, not even for the frame in
        // which Kotlin, a frame behind the host, moves it. English re-anchors nothing (its
        // growth and collapse change the frame only), so it is the reference.
        for (stops in listOf(true, false)) {
            val ltr = Model(rtl = false, host = FakeScroller(absoluteWriteStopsFling = stops)).also { it.settle() }
            val rtl = Model(rtl = true, host = FakeScroller(canShiftOffset = true, absoluteWriteStopsFling = stops))
                .also { it.settle() }
            val expected = ltr.fling(FLING).map { frame -> frame.mirrored() }
            val actual = rtl.fling(FLING)
            assertTrue(rtl.grew > 0 && rtl.shrank > 0, "grew ${rtl.grew} shrank ${rtl.shrank}")
            assertEquals(expected.size, actual.size, "stops=$stops frames")
            for (f in expected.indices) assertMatches(expected[f], actual[f], "stops=$stops frame ${f + 1}")
        }
    }

    @Test fun controlAnAbsoluteReanchorWriteBreaksTheMirrorImage() {
        // Android / web semantics without the shift: the fling survives the absolute write,
        // but the write lands where Kotlin last heard the host was, a frame behind it.
        val ltr = Model(rtl = false, host = FakeScroller(absoluteWriteStopsFling = false)).also { it.settle() }
        val rtl = Model(rtl = true, host = FakeScroller(canShiftOffset = false, absoluteWriteStopsFling = false))
            .also { it.settle() }
        val expected = ltr.fling(FLING).map { frame -> frame.mirrored() }
        val actual = rtl.fling(FLING)
        val differs = expected.indices.any { f -> f >= actual.size || expected[f].indices.any { abs(expected[f][it] - actual[f][it]) > 1 } }
        assertTrue(differs, "the model sees the jerk of an absolute write")
    }

    private fun List<Int>.mirrored() = mapIndexed { i, x -> VIEWPORT - x - WIDTHS[i] }

    private fun assertMatches(expected: List<Int>, actual: List<Int>, what: String) {
        for (i in expected.indices) {
            assertTrue(abs(expected[i] - actual[i]) <= 1, "$what chip $i: expected ${expected[i]}, was ${actual[i]}")
        }
    }

    private fun mirroredAxis(contentSize: Int, viewport: Int) = MirroredScrollAxis().apply {
        mirrored = true
        rebase(contentSize, viewport)
    }

    private companion object {
        const val DENSITY = 3f
        const val VIEWPORT = 1290
        const val DEFAULT_CONTENT = 9000      // DEFAULT_CONTENT_SIZE 3000 dp at 3x
        const val EXPAND = 4500               // DEFAULT_EXPAND_SIZE 1500 dp
        const val BUFFER = 6000               // CONTENT_SIZE_BUFFER 2000 dp
        const val FINGER = 300
        /** A fling's first frame, px; it decays by [DECAY] a frame (about 15000 px in all). */
        const val FLING = 300f
        const val DECAY = 0.98f
        const val SELF_ONLY = "SELF_ONLY"
        const val SELF_FIRST = "SELF_FIRST"
        /** Forty server-ordered chips, 150..276 px wide. */
        val WIDTHS = List(40) { 150 + (it * 37) % 127 }
        const val GAP = 20
    }

    /** One offset move Kotlin made on the host. */
    private data class Move(val kind: String, val nested: String, val duringFling: Boolean)

    /**
     * A physically left-to-right host scroller, in px, behind the production seam. It clamps
     * its offset to `[0, content - viewport]` on every write and every resize (a browser's
     * `scrollLeft`), which is what makes the write ORDER of a re-anchor observable.
     */
    private class FakeScroller(
        override val isAndroid: Boolean = false,
        override val canShiftOffset: Boolean = true,
        /** UIKit: `-setContentOffset:animated:NO` ends a running deceleration. */
        val absoluteWriteStopsFling: Boolean = true,
    ) : MirroredScrollHost {
        override val bound = true
        override val hasContent = true
        override val rendered = true
        override val density = DENSITY

        var viewport = VIEWPORT
            set(value) {
                field = value
                offset = clamp(offset)
            }
        override val viewportPx: Int get() = viewport

        var contentWidth = 0
        var offset = 0f
        val children = HashMap<Int, Float>()
        var flinging = false
        var nested = SELF_FIRST
        val moves = mutableListOf<Move>()

        val maxOffset get() = max(0, contentWidth - viewport)
        private fun clamp(x: Float) = x.coerceIn(0f, maxOffset.toFloat())

        override val contentWidthDp: Float get() = contentWidth / density

        override fun setContentWidth(widthDp: Float) {
            contentWidth = (widthDp * density).roundToInt()
            offset = clamp(offset)
        }

        override fun shiftChildren(dxDp: Float) {
            for (k in children.keys) children[k] = children.getValue(k) + dxDp * density
        }

        override fun writeOffset(xDp: Float) {
            moves += Move("set", nested, flinging)
            offset = clamp(xDp * density)
            if (absoluteWriteStopsFling) flinging = false
        }

        override fun shiftOffset(dxDp: Float) {
            moves += Move("shift", nested, flinging)
            offset = clamp(offset + dxDp * density)
        }

        override fun holdNestedScrollSelfOnly(): (() -> Unit)? {
            val origin = nested
            nested = SELF_ONLY
            return { nested = origin }
        }
    }

    /**
     * The bridge between [host] and a lazy row, all in px. `logical` is the row's own scroll
     * position (LazyListState's); `info` is the production [KuiklyScrollInfo], whose
     * `composeOffset` / `contentOffset` / `currentContentSize` are the bridge's.
     */
    private class Model(val rtl: Boolean, val host: FakeScroller = FakeScroller()) {
        val info = KuiklyScrollInfo().apply {
            orientation = Orientation.Horizontal
            axis.mirrored = rtl
            mirroredHost = host
        }
        var logical = 0
        var grew = 0
        var shrank = 0
        var preShiftOffset = 0f
        private val starts = WIDTHS.runningFold(0) { acc, w -> acc + w + GAP }
        private val total = starts[WIDTHS.size] - GAP
        val maxLogical = max(0, total - VIEWPORT)

        /** Compose's placement: `placeRelative` mirrors inside the visible box under Rtl. */
        fun pos(i: Int): Int {
            val ltr = starts[i] - logical
            return if (rtl) VIEWPORT - ltr - WIDTHS[i] else ltr
        }

        fun screenX(i: Int): Int = (host.children.getValue(i) - host.offset).roundToInt()
        fun screenXs(): List<Int> = WIDTHS.indices.map { screenX(it) }

        fun assertAllMovedBy(before: List<Int>, d: Int, what: String) {
            val after = screenXs()
            for (i in before.indices) assertEquals(before[i] + d, after[i], "$what: chip $i")
        }

        /** KNode.updateKuiklyViewFrame for every item (identity origin when not mirrored). */
        fun layout() {
            for (i in WIDTHS.indices) host.children[i] = pos(i) + info.axis.frameOriginX(info.composeOffset)
        }

        /**
         * KuiklyScrollInfo.updateContentSizeToRender: the production re-anchor when mirrored;
         * the left-to-right path only writes the frame, which the model does itself.
         */
        fun updateContentSizeToRender() {
            if (!rtl) {
                host.setContentWidth(info.currentContentSize / DENSITY)
                return
            }
            val before = info.axis.renderedNativeMax
            val offsetBefore = host.offset
            info.updateContentSizeToRender()
            val after = info.axis.renderedNativeMax
            if (after != before) preShiftOffset = offsetBefore
            if (after > before) grew += 1 else if (after < before) shrank += 1
        }

        /**
         * restoreScrollerViewOnReuse before the scroller has a frame (W = 0), then the frame
         * arrives (KNode.updateFrame → reanchorForViewport).
         */
        fun settle() {
            host.viewport = 0
            if (rtl) info.axis.rebase(info.currentContentSize, host.viewportPx)
            updateContentSizeToRender()
            if (rtl) info.writeMirroredNative(info.axis.toNative(info.contentOffset).toFloat())
            else host.writeOffset(info.contentOffset / DENSITY)
            layout()
            host.viewport = VIEWPORT
            if (rtl) info.reanchorForViewport()
            layout()
            host.moves.clear()
        }

        /** calculateAndUpdateContentSize (ContentSizeExtensions.kt), for this row. */
        fun calculateAndUpdateContentSize() {
            val lastVisible = starts[WIDTHS.lastIndex] - logical < VIEWPORT
            info.realContentSize = if (lastVisible) (info.composeOffset + total - logical).toInt() else null
            val computed = info.realContentSize ?: run {
                val frameWidth = host.contentWidth
                if (frameWidth - (info.composeOffset.toInt() + VIEWPORT) < BUFFER) frameWidth + EXPAND else frameWidth
            }
            val old = info.currentContentSize
            info.currentContentSize = if (computed < old && info.composeOffset > max(0, computed - VIEWPORT)) {
                max(computed, info.composeOffset.toInt() + VIEWPORT)
            } else {
                computed
            }
            updateContentSizeToRender()
        }

        /** The finger: a physical scroller moves its offset against the finger, clamped. */
        fun drag(finger: Int) {
            host.offset = min(host.maxOffset.toFloat(), max(0f, host.offset - finger))
            onScroll(host.offset)
        }

        /**
         * A forward fling: the host decelerates on its own, one frame at a time, and Kotlin
         * hears of each frame one frame late. Returns every chip's screen x on every frame
         * drawn, the settled state last.
         */
        fun fling(v0: Float): List<List<Int>> {
            val frames = mutableListOf<List<Int>>()
            val sign = if (rtl) 1 else -1
            var v = v0
            var pending: Float? = null
            host.flinging = true
            while (host.flinging && v >= 1f) {
                val next = host.offset - sign * v
                host.offset = next.coerceIn(0f, host.maxOffset.toFloat())
                if (host.offset != next) host.flinging = false   // an edge ends it
                // This frame is drawn with the host where it now is...
                frames += screenXs()
                // ...its event reaches Kotlin a frame late, so Kotlin now handles the previous
                // frame's, and what it writes lands before the next frame is drawn.
                val produced = host.offset
                pending?.let { onScroll(it) }
                pending = produced
                v *= DECAY
            }
            host.flinging = false
            pending?.let { onScroll(it) }
            frames += screenXs()
            return frames
        }

        /** SubcomposeLayout's scroll handler, list path, with the production conversion. */
        fun onScroll(native: Float) {
            val offset = info.offsetFromHost(false, native, 0f) ?: return
            info.contentOffset = offset
            info.ignoreScrollOffset?.let { ignore ->
                val epsilon = 0.5 * DENSITY
                if (abs(ignore.x - native) <= epsilon && abs(ignore.y - 0f) <= epsilon) {
                    info.ignoreScrollOffset = null
                }
                return
            }
            val delta = offset - info.composeOffset
            if (delta.toInt() == 0) return
            calculateAndUpdateContentSize()
            val toBottom = info.realContentSize?.let { it - VIEWPORT - info.composeOffset }
            if (offset < 0 && logical == 0) return
            if (toBottom != null && delta > toBottom) {
                if (toBottom.toInt() <= 0) return
                info.composeOffset += min(delta, toBottom)
            } else {
                info.composeOffset = max(0f, info.composeOffset + delta)
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
            val maxDelta = info.currentContentSize - VIEWPORT - info.contentOffset
            if (delta > maxDelta) {
                info.currentContentSize += delta - maxDelta + minDelta
                updateContentSizeToRender()
            }
            applyScrollViewOffsetDelta(delta)
            onScroll(host.offset)   // the echo
        }

        /** applyScrollViewOffsetDelta → ScrollViewEx.applyOffsetDelta (production when mirrored). */
        fun applyScrollViewOffsetDelta(delta: Int) {
            val moved = if (rtl) info.applyMirroredOffsetDelta(delta, 0) else ltrApplyOffsetDelta(delta)
            info.composeOffset = moved.x.toFloat()
        }

        /** The left-to-right applyOffsetDelta (unchanged upstream code; no host seam). */
        private fun ltrApplyOffsetDelta(delta: Int): IntOffset {
            val newX = host.offset.toInt() + delta
            if (info.composeOffset.toInt() == newX) return IntOffset(newX, 0)
            info.ignoreScrollOffset = IntOffset(newX, 0)
            if (newX + VIEWPORT > info.currentContentSize) {
                info.currentContentSize += (2000 * DENSITY + delta).toInt()
                host.setContentWidth(info.currentContentSize / DENSITY)
            }
            for (k in host.children.keys) host.children[k] = host.children.getValue(k) + delta
            host.writeOffset(newX / DENSITY)
            return IntOffset(newX, 0)
        }
    }
}
