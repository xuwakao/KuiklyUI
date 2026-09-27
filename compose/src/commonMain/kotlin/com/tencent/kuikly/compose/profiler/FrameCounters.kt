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

package com.tencent.kuikly.compose.profiler

import com.tencent.kuikly.core.datetime.DateTime

/**
 * Ronaq fork (CHANGES.md §53): what the frame path did, counted, for an application that wants to
 * measure its own idle and start-up cost on a device.
 *
 * Off by default. Every hook reads [enabled] first and does nothing else when it is false, so a
 * product build pays one boolean read per tick, render, node draw and snapshot apply. Switched on
 * once, before the first frame, by the application (a launch argument); it is not meant to be
 * toggled while frames run.
 *
 * The counters accumulate until [drain] returns them as one line and starts the next window. The
 * caller decides the cadence (one line a second is the intended use) and where the line goes.
 * Single-threaded like the frame path itself (the Kuikly context thread), except [onSnapshotApply]
 * and [onTick], whose racy increments may lose a count under contention; a diagnostic tolerates
 * that.
 */
object FrameCounters {

    /** Whether the hooks count. Set once, before the first frame. */
    var enabled: Boolean = false

    /** Native frame ticks delivered to the scene (every vsync the host's timer fires). */
    var ticks = 0
        private set

    /** Scene renders that ran (a tick while the scene was paused does not render). */
    var renders = 0
        private set

    /** Why each render ran: frame-clock awaiters (an animation asked for the frame). */
    var rendersForAnimation = 0
        private set

    /** Why each render ran: a snapshot, layout or draw invalidation. */
    var rendersForInvalidation = 0
        private set

    /** Why each render ran: pending input, or a pointer held down (the scene is proactive). */
    var rendersForInput = 0
        private set

    /** Nodes whose own draw ran (reset, layers, modifiers, flush). */
    var nodeDraws = 0
        private set

    /** Nodes visited only to reach a dirty descendant; their own view was not touched. */
    var nodeVisits = 0
        private set

    /** Nodes reached by the draw pass with nothing to do. */
    var nodeSkips = 0
        private set

    /** Snapshot apply notifications sent (each runs every observer of the changed state). */
    var snapshotApplies = 0
        private set

    /** Frames delivered to running infinite transitions, all labels together. */
    var infiniteFrames = 0
        private set

    /** Running infinite transitions, by label. A transition counts while it is composed and running. */
    private val runningByLabel = HashMap<String, Int>()

    /** Press-to-render delays measured in this window, in milliseconds. */
    private val pressDelaysMs = ArrayList<Long>()

    /** When the press waiting for its first render arrived, or 0. */
    private var pendingPressNanos = 0L

    fun onTick() {
        if (enabled) ticks++
    }

    /**
     * A render is about to run. The three flags say what made it necessary; more than one may hold.
     */
    fun onRenderStart(animation: Boolean, invalidation: Boolean, input: Boolean) {
        if (!enabled) return
        renders++
        if (animation) rendersForAnimation++
        if (invalidation) rendersForInvalidation++
        if (input) rendersForInput++
    }

    /**
     * A render finished; closes a pending press-to-render measurement. A press older than
     * [PRESS_STALE_MS] caused no render of its own (it landed on something inert) and is dropped
     * rather than charged to whatever renders next.
     */
    fun onRenderEnd() {
        if (!enabled || pendingPressNanos == 0L) return
        val delayMs = (DateTime.nanoTime() - pendingPressNanos) / 1_000_000L
        if (delayMs <= PRESS_STALE_MS) pressDelaysMs.add(delayMs)
        pendingPressNanos = 0L
    }

    /** A press not followed by a render within this long rendered nothing. */
    private const val PRESS_STALE_MS = 1_000L

    /** A pointer went down; the next [onRenderEnd] measures how long it took to be drawn. */
    fun onPress() {
        if (!enabled) return
        val now = DateTime.nanoTime()
        // A pending press that is already stale is replaced, not kept.
        if (pendingPressNanos != 0L && (now - pendingPressNanos) / 1_000_000L <= PRESS_STALE_MS) return
        pendingPressNanos = now
    }

    fun onNodeDraw() {
        if (enabled) nodeDraws++
    }

    fun onNodeVisit() {
        if (enabled) nodeVisits++
    }

    fun onNodeSkip() {
        if (enabled) nodeSkips++
    }

    fun onSnapshotApply() {
        if (enabled) snapshotApplies++
    }

    fun onInfiniteFrame() {
        if (enabled) infiniteFrames++
    }

    /** An infinite transition labelled [label] started running (true) or stopped (false). */
    fun onInfiniteRunning(label: String, running: Boolean) {
        if (!enabled) return
        val now = (runningByLabel[label] ?: 0) + if (running) 1 else -1
        if (now <= 0) runningByLabel.remove(label) else runningByLabel[label] = now
    }

    /** How many infinite transitions labelled [label] are running now. */
    fun runningInfinite(label: String): Int = runningByLabel[label] ?: 0

    /**
     * The window's counts as one line, and a new window. Running transitions are a level, not a
     * count, so they carry over; everything else starts again from zero.
     */
    fun drain(): String {
        val running = runningByLabel.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }
        val presses = pressDelaysMs.joinToString(",")
        val line = "ticks=$ticks renders=$renders" +
            " cause(anim=$rendersForAnimation,inval=$rendersForInvalidation,input=$rendersForInput)" +
            " nodes(draw=$nodeDraws,visit=$nodeVisits,skip=$nodeSkips)" +
            " applies=$snapshotApplies infFrames=$infiniteFrames infRunning=[$running]" +
            " pressToRenderMs=[$presses]"
        ticks = 0
        renders = 0
        rendersForAnimation = 0
        rendersForInvalidation = 0
        rendersForInput = 0
        nodeDraws = 0
        nodeVisits = 0
        nodeSkips = 0
        snapshotApplies = 0
        infiniteFrames = 0
        pressDelaysMs.clear()
        return line
    }

    /** Back to the state of a fresh process (tests). */
    fun reset() {
        drain()
        runningByLabel.clear()
        pendingPressNanos = 0L
        enabled = false
    }
}
