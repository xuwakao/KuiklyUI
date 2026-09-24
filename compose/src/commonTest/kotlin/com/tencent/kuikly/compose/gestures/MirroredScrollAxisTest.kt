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
 * frame late, as they do through the context queue, or more frames late when the Kotlin
 * thread is busy.
 *
 * [AndroidListHost] adds what the Android list renderer (`KRRecyclerView`) does on top: a
 * content width takes effect at the next layout pass, a write the laid-out content has no room
 * for waits for that pass and is dropped there if it still does not fit, a shift waits behind
 * such a write, and dp become px the way `toPxI` rounds.
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

    @Test fun aLongLagOfStaleEventsIsFollowedNotCountedOut() {
        // A busy Kotlin thread: the host moved on 24 frames of 150 px before it applied a
        // +4500 re-anchor, far enough that its latest pre-shift events sit nearer the value
        // Kotlin wrote than the one it started from. Every one of them is still in the old
        // coordinates (regression 2026-09-23, rtl-language/home-ar-blank-strip).
        val axis = mirroredAxis(contentSize = 9000, viewport = 1290)
        assertTrue(axis.accept(2000f))
        axis.commitReanchor(assertNotNull(axis.planReanchor(13500, 1290)))  // 2000 -> 6500
        var host = 2000f
        repeat(24) {
            host += 150f
            assertFalse(axis.accept(host), "the pre-shift event at $host was taken")
        }
        // The shift landed where the host had got to; the next event is in the new coordinates.
        assertTrue(axis.accept(host + 150f + 4500f))
        assertEquals(host + 4650f, axis.lastNative)
    }

    @Test fun anAbsoluteWriteIsNotDraggedAlongByTheHost() {
        // A relative re-anchor, then applyOffsetDelta's absolute write in the same turn: the
        // host lands on the written value wherever it was, so the pre-write events moving on
        // must not move that reference with them.
        val axis = mirroredAxis(contentSize = 9000, viewport = 1290)
        assertTrue(axis.accept(5910f))
        axis.commitReanchor(assertNotNull(axis.planReanchor(13500, 1290)), relative = true)
        axis.noteWrite(9000f)
        for (host in listOf(6300f, 6700f, 7100f, 7500f)) assertFalse(axis.accept(host), "the pre-write event at $host was taken")
        assertTrue(axis.accept(9000f), "the written value")
    }

    @Test fun onlyTheHostSaysWhereItIs() {
        val axis = mirroredAxis(contentSize = 9000, viewport = 1290)
        assertFalse(axis.hostReported)
        assertTrue(axis.accept(7710f))
        assertTrue(axis.hostReported)
        // A relative re-anchor keeps it: the host lands Δ from where it said it was.
        axis.commitReanchor(assertNotNull(axis.planReanchor(13500, 1290)), relative = true)
        assertTrue(axis.hostReported)
        // Kotlin's own absolute write is an intention until the host answers.
        axis.noteWrite(12210f)
        assertFalse(axis.hostReported)
        assertTrue(axis.accept(12210f))
        assertTrue(axis.hostReported)
        axis.commitReanchor(assertNotNull(axis.planReanchor(9000, 1290)), relative = false)
        assertFalse(axis.hostReported)
        assertTrue(axis.accept(7710f))
        axis.rebase(9000, 1290)
        assertFalse(axis.hostReported)
        assertTrue(axis.accept(7710f))
        axis.reset()
        assertFalse(axis.hostReported)
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

    @Test fun aFlingHeardLateNeverRunsBackwards() {
        // The regression's blank Home strip (rtl-language/home-ar-blank-strip): after a forward
        // fling Compose had composed the first chips while the host showed the last ones. One
        // way there: a Kotlin thread busy for many frames at a re-anchor, so that more host
        // events in the old coordinates reach it than the stale filter allowed for, and the
        // rest read as a jump of the whole re-anchor. With the filter as it was, lag 12 ran
        // Compose back from 7934 to 1853 and the fling stopped short; lag 16 settled with
        // Compose at the end and the host 3754 px short of it, nothing left to correct it: the
        // chips Compose composes then sit off screen.
        for (lag in listOf(1, 4, 12, 16)) {
            for (rtl in listOf(false, true)) {
                val what = "lag $lag rtl=$rtl"
                val m = Model(rtl, host = FakeScroller(canShiftOffset = true, absoluteWriteStopsFling = false))
                    .also { it.settle() }
                m.fling(FLING, lag)
                if (rtl) assertTrue(m.grew > 0 && m.shrank > 0, "$what: grew ${m.grew} shrank ${m.shrank}")
                // Where Compose stood after each host event it heard: never behind where it was.
                m.trace.zipWithNext().forEachIndexed { i, (a, b) ->
                    assertTrue(b >= a, "$what: event ${i + 2} took Compose from $a back to $b")
                }
                assertEquals(m.strip.maxLogical, m.logical, "$what: Compose ends where the host ends")
                assertEquals(if (rtl) 0 else VIEWPORT - WIDTHS.last(), m.screenX(WIDTHS.lastIndex), what)
            }
        }
    }

    // ---- a host that applies writes at its next layout pass (Android) -----------------

    @Test fun aStripThatBarelyOverflowsOpensAtItsStartOnAndroid() {
        // The OPPO's Mine sub-tabs in Arabic (regression 2026-09-23, rtl-language/android/mine-ar
        // and superseded/android-mine-ar): 751 px of content in a 720 px viewport at 2.25 px/dp.
        // The first tab rested 31 or 5 px from the right edge instead of 36, and a forward
        // finger moved the strip 0 px. The list binds before it has a frame, gets its frame,
        // and learns its real size at rest from `LaunchedEffect(scrollViewSize)`, all before
        // the host's first layout pass — or the host lays out once in between.
        for (hostLaysOutFirst in listOf(false, true)) {
            val what = "host lays out first: $hostLaysOutFirst"
            val m = Model(rtl = true, host = AndroidListHost(MINE.density), strip = MINE)
            m.open(hostLaysOutFirst)
            assertEquals(MINE.viewport - MINE.pad - MINE.widths[0], m.screenX(0), "$what: the first tab at rest")
            // The host rests at its physical end, which is where Kotlin thinks it is.
            assertEquals(MINE.maxLogical, m.host.offset.roundToInt(), what)
            assertEquals(0, m.info.offsetFromHost(false, m.host.offset, 0f), what)
            // A forward finger (left to right in Arabic) moves every tab by the whole overflow,
            // and a backward one brings them back.
            val rest = m.screenXs()
            m.drag(360)
            m.assertAllMovedBy(rest, MINE.maxLogical, "$what: forward")
            m.drag(-360)
            m.assertAllMovedBy(rest, 0, "$what: back")
        }
    }

    @Test fun aReanchorShiftsOnlyFromAnOffsetTheHostReported() {
        // A shift moves the host from wherever it is, which is Kotlin's number only once the
        // host has said so. After Kotlin's own absolute write — which Android may still hold
        // for its next layout pass and drop there if it does not fit — a re-anchor is written
        // absolutely as well, so the last absolute write carries it.
        val host = FakeScroller(canShiftOffset = true)
        val m = Model(rtl = true, host = host).also { it.settle() }
        host.moves.clear()
        m.info.currentContentSize += EXPAND
        m.updateContentSizeToRender()
        assertEquals(listOf("set"), host.moves.map { it.kind }, "right after the binding's own writes")
        // Once the host reports where it is, a re-anchor is a shift from there.
        m.onScroll(host.offset)
        host.moves.clear()
        m.info.currentContentSize += EXPAND
        m.updateContentSizeToRender()
        assertEquals(listOf("shift"), host.moves.map { it.kind }, "after the host reported its offset")
        assertEquals(VIEWPORT - WIDTHS[0], m.screenX(0))
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

        /** The strip most tests drive: forty chips, no content padding, 3 px/dp. */
        val TAGS = Strip(WIDTHS, GAP, pad = 0, viewport = VIEWPORT, density = DENSITY)

        /**
         * Home's Mine sub-tabs in Arabic on the regression's OPPO (720 px at 2.25 px/dp, the
         * owner's density override): FOLLOWING, JOINED, RECENT as the dump measured them
         * (`rtl-language/android/mine-ar/record.json`), 8 dp apart, 16 dp of content padding
         * each side — 751 px of content, 31 px more than the viewport.
         */
        val MINE = Strip(listOf(228, 240, 175), gap = 18, pad = 36, viewport = 720, density = 2.25f)
    }

    /** A lazy row's geometry, px. */
    private class Strip(
        val widths: List<Int>,
        val gap: Int,
        /** `contentPadding` at each end. */
        val pad: Int,
        val viewport: Int,
        val density: Float,
    ) {
        val starts = widths.runningFold(0) { acc, w -> acc + w + gap }
        /** The whole content, both paddings included. */
        val total = pad + starts[widths.size] - gap + pad
        val maxLogical = max(0, total - viewport)
        /** DEFAULT_CONTENT_SIZE, DEFAULT_EXPAND_SIZE and CONTENT_SIZE_BUFFER at this density. */
        val defaultContent = (3000 * density).toInt()
        val expand = (1500 * density).toInt()
        val buffer = (2000 * density).toInt()
    }

    /** One offset move Kotlin made on the host. */
    private data class Move(val kind: String, val nested: String, val duringFling: Boolean)

    /**
     * A physically left-to-right host scroller behind the production seam, in px. [events]
     * holds the scroll events the host produced on its own account (a write it applied at a
     * layout pass) that Kotlin has not heard yet.
     */
    private abstract class TestHost(override val density: Float) : MirroredScrollHost {
        override val bound = true
        override val hasContent = true
        override val rendered = true

        abstract var viewport: Int
        override val viewportPx: Int get() = viewport

        var offset = 0f
        val children = HashMap<Int, Float>()
        var flinging = false
        var nested = SELF_FIRST
        val moves = mutableListOf<Move>()
        val events = ArrayDeque<Float>()

        /** How far the host lets its offset go. */
        abstract val maxOffset: Int

        /** The content width Kotlin last set, px: what `calculateContentSize` reads back. */
        abstract val frameWidthPx: Int

        /** The host's layout pass after Kotlin's turn; nothing to do for a host that applies at once. */
        open fun layoutPass() {}

        override fun shiftChildren(dxDp: Float) {
            for (k in children.keys) children[k] = children.getValue(k) + dxDp * density
        }

        override fun holdNestedScrollSelfOnly(): (() -> Unit)? {
            val origin = nested
            nested = SELF_ONLY
            return { nested = origin }
        }
    }

    /**
     * The strictest host: it clamps its offset to `[0, content - viewport]` on every write and
     * every resize (a browser's `scrollLeft`), which is what makes the write ORDER of a
     * re-anchor observable.
     */
    private class FakeScroller(
        override val isAndroid: Boolean = false,
        override val canShiftOffset: Boolean = true,
        /** UIKit: `-setContentOffset:animated:NO` ends a running deceleration. */
        val absoluteWriteStopsFling: Boolean = true,
    ) : TestHost(DENSITY) {
        override var viewport = VIEWPORT
            set(value) {
                field = value
                offset = clamp(offset)
            }

        var contentWidth = 0

        override val maxOffset get() = max(0, contentWidth - viewport)
        override val frameWidthPx get() = contentWidth
        private fun clamp(x: Float) = x.coerceIn(0f, maxOffset.toFloat())

        override val contentWidthDp: Float get() = contentWidth / density

        override fun setContentWidth(widthDp: Float) {
            contentWidth = (widthDp * density).roundToInt()
            offset = clamp(offset)
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
    }

    /**
     * The Android list renderer (`KRRecyclerView`), as far as the bridge can tell:
     * - a frame is layout params: the content width and the viewport take effect at the next
     *   [layoutPass];
     * - `contentOffset` lands at once when the laid-out content has room for it
     *   (`canScrollImmediately`), otherwise waits for the next layout pass, where it lands if it
     *   fits now and is dropped if it does not (`tryApplyPendingSetContentOffset`), and it
     *   drops a pending shift;
     * - `shiftContentOffset` waits behind a pending `contentOffset` and for room, then lands as
     *   a `scrollBy`, clamped to the content;
     * - the layout pass keeps the offset inside the content (`LinearLayoutManager`);
     * - dp become px as `toPxI` does, `(px + 0.5).toInt()`;
     * - every move of the offset is a scroll event, delivered to Kotlin after the pass.
     */
    private class AndroidListHost(density: Float) : TestHost(density) {
        override val isAndroid = true
        override val canShiftOffset = true

        override var viewport = 0
        private var laidOutViewport = 0
        private var requestedWidth = 0
        private var laidOutWidth = 0
        private var pendingAbsoluteDp: Float? = null
        private var pendingShift = 0

        override val maxOffset get() = max(0, laidOutWidth - laidOutViewport)
        override val frameWidthPx get() = requestedWidth
        override val contentWidthDp: Float get() = requestedWidth / density

        private fun toPxI(dp: Float) = (dp * density + 0.5f).toInt()
        private fun canScrollImmediately(x: Int) = x <= laidOutWidth - laidOutViewport

        private fun scrollTo(x: Int) {
            val target = x.coerceIn(0, maxOffset).toFloat()
            if (target != offset) {
                offset = target
                events.addLast(offset)
            }
        }

        override fun setContentWidth(widthDp: Float) {
            requestedWidth = toPxI(widthDp)
        }

        override fun writeOffset(xDp: Float) {
            moves += Move("set", nested, flinging)
            pendingShift = 0
            val x = toPxI(xDp)
            if (canScrollImmediately(x)) {
                pendingAbsoluteDp = null
                scrollTo(x)
            } else {
                pendingAbsoluteDp = xDp
            }
        }

        override fun shiftOffset(dxDp: Float) {
            moves += Move("shift", nested, flinging)
            pendingShift += toPxI(dxDp)
            tryApplyShift()
        }

        private fun tryApplyShift() {
            if (pendingShift == 0 || pendingAbsoluteDp != null) return
            if (!canScrollImmediately(offset.toInt() + pendingShift)) return
            val dx = pendingShift
            pendingShift = 0
            scrollTo(offset.toInt() + dx)
        }

        override fun layoutPass() {
            laidOutWidth = requestedWidth
            laidOutViewport = viewport
            scrollTo(offset.toInt())
            pendingAbsoluteDp?.let { dp ->
                pendingAbsoluteDp = null
                val x = toPxI(dp)
                if (canScrollImmediately(x)) scrollTo(x)
            }
            tryApplyShift()
        }
    }

    /**
     * The bridge between [host] and a lazy row, all in px. `logical` is the row's own scroll
     * position (LazyListState's); `info` is the production [KuiklyScrollInfo], whose
     * `composeOffset` / `contentOffset` / `currentContentSize` are the bridge's.
     */
    private class Model(val rtl: Boolean, val host: TestHost = FakeScroller(), val strip: Strip = TAGS) {
        val info = KuiklyScrollInfo().apply {
            orientation = Orientation.Horizontal
            axis.mirrored = rtl
            mirroredHost = host
            currentContentSize = strip.defaultContent
        }
        var logical = 0
        var grew = 0
        var shrank = 0
        var preShiftOffset = 0f
        /** [logical] after each host event a fling delivered. */
        val trace = mutableListOf<Int>()
        val maxLogical get() = strip.maxLogical
        private val viewport get() = strip.viewport

        /** Compose's placement: `placeRelative` mirrors inside the visible box under Rtl. */
        fun pos(i: Int): Int {
            val ltr = strip.pad + strip.starts[i] - logical
            return if (rtl) viewport - ltr - strip.widths[i] else ltr
        }

        fun screenX(i: Int): Int = (host.children.getValue(i) - host.offset).roundToInt()
        fun screenXs(): List<Int> = strip.widths.indices.map { screenX(it) }

        fun assertAllMovedBy(before: List<Int>, d: Int, what: String) {
            val after = screenXs()
            for (i in before.indices) assertEquals(before[i] + d, after[i], "$what: chip $i")
        }

        /** KNode.updateKuiklyViewFrame for every item (identity origin when not mirrored). */
        fun layout() {
            for (i in strip.widths.indices) host.children[i] = pos(i) + info.axis.frameOriginX(info.composeOffset)
        }

        /**
         * KuiklyScrollInfo.updateContentSizeToRender: the production re-anchor when mirrored;
         * the left-to-right path only writes the frame, which the model does itself.
         */
        fun updateContentSizeToRender() {
            if (!rtl) {
                host.setContentWidth(info.currentContentSize / strip.density)
                return
            }
            val before = info.axis.renderedNativeMax
            val offsetBefore = host.offset
            info.updateContentSizeToRender()
            val after = info.axis.renderedNativeMax
            if (after != before) preShiftOffset = offsetBefore
            if (after > before) grew += 1 else if (after < before) shrank += 1
        }

        /** restoreScrollerViewOnReuse before the scroller has a frame (W = 0). */
        private fun bind() {
            host.viewport = 0
            if (rtl) info.axis.rebase(info.currentContentSize, host.viewportPx)
            updateContentSizeToRender()
            if (rtl) info.writeMirroredNative(info.axis.toNative(info.contentOffset).toFloat())
            else host.writeOffset(info.contentOffset / strip.density)
            layout()
        }

        /** The frame arrives: KNode.updateFrame → reanchorForViewport. */
        private fun frame() {
            host.viewport = viewport
            if (rtl) info.reanchorForViewport()
            layout()
        }

        /** Binding, then the frame, on a host that applies writes at once. */
        fun settle() {
            bind()
            frame()
            host.moves.clear()
        }

        /**
         * A new strip on a host that lays out after Kotlin's turn (Android): the binding and the
         * frame, then the real size at rest (`LaunchedEffect(scrollViewSize)` →
         * `calculateAndUpdateContentSize`), then the host's layout pass and its events. With
         * [hostLaysOutFirst] the host lays out once between the frame and the size.
         */
        fun open(hostLaysOutFirst: Boolean) {
            bind()
            frame()
            if (hostLaysOutFirst) settleHost()
            calculateAndUpdateContentSize()
            layout()
            settleHost()
            host.moves.clear()
        }

        /** The host's layout pass after Kotlin's turn, and the events it produced, heard in order. */
        fun settleHost() {
            host.layoutPass()
            while (host.events.isNotEmpty()) onScroll(host.events.removeFirst())
        }

        /** calculateAndUpdateContentSize (ContentSizeExtensions.kt), for this row. */
        fun calculateAndUpdateContentSize() {
            val last = strip.widths.lastIndex
            val lastVisible = strip.pad + strip.starts[last] - logical < viewport
            info.realContentSize = if (lastVisible) (info.composeOffset + strip.total - logical).toInt() else null
            val computed = info.realContentSize ?: run {
                val frameWidth = host.frameWidthPx
                if (frameWidth - (info.composeOffset.toInt() + viewport) < strip.buffer) frameWidth + strip.expand else frameWidth
            }
            val old = info.currentContentSize
            info.currentContentSize = if (computed < old && info.composeOffset > max(0, computed - viewport)) {
                max(computed, info.composeOffset.toInt() + viewport)
            } else {
                computed
            }
            updateContentSizeToRender()
        }

        /** The finger: a physical scroller moves its offset against the finger, clamped. */
        fun drag(finger: Int) {
            host.offset = min(host.maxOffset.toFloat(), max(0f, host.offset - finger))
            onScroll(host.offset)
            settleHost()
        }

        /**
         * A forward fling: the host decelerates on its own, one frame at a time, and Kotlin
         * hears of each frame [lag] frames late (one, through the context queue; more when the
         * Kotlin thread is busy). What Kotlin writes lands before the next frame is drawn.
         * Returns every chip's screen x on every frame drawn, the settled state last.
         */
        fun fling(v0: Float, lag: Int = 1): List<List<Int>> {
            val frames = mutableListOf<List<Int>>()
            val sign = if (rtl) 1 else -1
            var v = v0
            val heard = ArrayDeque<Float>()
            host.flinging = true
            while (host.flinging && v >= 1f) {
                val next = host.offset - sign * v
                host.offset = next.coerceIn(0f, host.maxOffset.toFloat())
                if (host.offset != next) host.flinging = false   // an edge ends it
                // This frame is drawn with the host where it now is...
                frames += screenXs()
                // ...and its event joins the ones Kotlin has not handled yet.
                heard.addLast(host.offset)
                while (heard.size > lag) hear(heard.removeFirst())
                v *= DECAY
            }
            host.flinging = false
            while (heard.isNotEmpty()) hear(heard.removeFirst())
            frames += screenXs()
            return frames
        }

        private fun hear(native: Float) {
            onScroll(native)
            trace += logical
        }

        /** SubcomposeLayout's scroll handler, list path, with the production conversion. */
        fun onScroll(native: Float) {
            val offset = info.offsetFromHost(false, native, 0f) ?: return
            info.contentOffset = offset
            info.ignoreScrollOffset?.let { ignore ->
                val epsilon = 0.5 * strip.density
                if (abs(ignore.x - native) <= epsilon && abs(ignore.y - 0f) <= epsilon) {
                    info.ignoreScrollOffset = null
                }
                return
            }
            val delta = offset - info.composeOffset
            if (delta.toInt() == 0) return
            calculateAndUpdateContentSize()
            val toBottom = info.realContentSize?.let { it - viewport - info.composeOffset }
            if (offset < 0 && logical == 0) return
            if (toBottom != null && delta > toBottom) {
                if (toBottom.toInt() <= 0) return
                info.composeOffset += min(delta, toBottom)
            } else {
                info.composeOffset = max(0f, info.composeOffset + delta)
            }
            logical = (logical + delta.toInt()).coerceIn(0, strip.maxLogical)   // kuiklyOnScroll
            layout()
        }

        /** LazyListState.scrollToItem: the row jumps, the native offset does not. */
        fun scrollToItem(i: Int) {
            logical = strip.starts[i]
            layout()
        }

        /** tryExpandStartSizeNoScroll's first branch: move the logical native origin. */
        fun realign() {
            val minDelta = strip.defaultContent
            val delta = minDelta
            val maxDelta = info.currentContentSize - viewport - info.contentOffset
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
            if (newX + viewport > info.currentContentSize) {
                info.currentContentSize += (2000 * strip.density + delta).toInt()
                host.setContentWidth(info.currentContentSize / strip.density)
            }
            for (k in host.children.keys) host.children[k] = host.children.getValue(k) + delta
            host.writeOffset(newX / strip.density)
            return IntOffset(newX, 0)
        }
    }
}
