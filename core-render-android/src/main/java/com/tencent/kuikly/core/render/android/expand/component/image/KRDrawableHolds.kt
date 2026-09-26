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

package com.tencent.kuikly.core.render.android.expand.component.image

import java.util.IdentityHashMap

/**
 * Ronaq fork (CHANGES.md §39): when a drawable an image adapter delivered may be given back
 * ([com.tencent.kuikly.core.render.android.adapter.IKRImageAdapter.releaseDrawable]).
 *
 * An adapter that lends a view its library's own bitmap (instead of a private copy) must learn
 * when nothing reads that bitmap any more, or the library will reuse its pixels for another
 * picture under a view still drawing it. What "nothing" means for an image view:
 *
 * - **origin** — the view keeps the delivered drawable (or a nine-patch built over its bitmap)
 *   as the source it re-tints, re-filters and re-blurs from;
 * - **shown** — what the view draws was made from it: the drawable itself, a tint or colour
 *   filter copy (`constantState.newDrawable()`, same bitmap), a nine-patch, or a blur result
 *   that fell back to the input;
 * - **tasks** — an off-thread task reads it (the blur).
 *
 * Once none of the three holds, the release waits for two frames ([afterFrames]): the render
 * thread may still be drawing the frame recorded before the view let go. It is then given back
 * exactly once. Anything this object was never told about ([track]) is never released — a
 * drawable a module cached, or one an adapter delivered without lending.
 *
 * View-free (generic over the drawable type) so the JVM tests drive it. Main thread only.
 */
class KRDrawableHolds<T : Any>(
    private val release: (T) -> Unit,
    private val afterFrames: (Runnable) -> Unit,
) {

    private class Entry {
        var tasks = 0
        var releasePending = false
    }

    private val entries = IdentityHashMap<T, Entry>()
    private var origin: T? = null
    private var shown: T? = null

    /** How many delivered drawables are tracked and not yet given back. */
    val trackedCount: Int get() = entries.size

    /** A drawable the adapter lent. Until something holds it, it is given back. */
    fun track(item: T) {
        if (!entries.containsKey(item)) entries[item] = Entry()
    }

    /** Whether [item] was lent and not yet given back. */
    fun isTracked(item: T): Boolean = entries.containsKey(item)

    /** The view's source is now [item] (a tracked drawable), or nothing it was lent. */
    fun origin(item: T?) {
        val previous = origin
        origin = item?.takeIf { entries.containsKey(it) }
        if (previous != null && previous !== origin) evaluate(previous)
    }

    /** What the view draws now was made from [item], or from nothing it was lent. */
    fun shown(item: T?) {
        val previous = shown
        shown = item?.takeIf { entries.containsKey(it) }
        if (previous != null && previous !== shown) evaluate(previous)
    }

    /** An off-thread task starts reading [item]; pair with [endTask]. */
    fun beginTask(item: T?) {
        val entry = item?.let { entries[it] } ?: return
        entry.tasks++
    }

    fun endTask(item: T?) {
        if (item == null) return
        val entry = entries[item] ?: return
        if (entry.tasks > 0) entry.tasks--
        evaluate(item)
    }

    /** A lent drawable the view did not take (a stale result): given back now, never drawn. */
    fun discard(item: T) {
        val entry = entries[item] ?: return
        if (isHeld(item, entry)) return
        entries.remove(item)
        release(item)
    }

    /** Gives [item] back if nothing holds it (after the frames). */
    fun settle(item: T) = evaluate(item)

    /** The view is going away: it keeps nothing. Running tasks still hold what they read. */
    fun clear() {
        origin(null)
        shown(null)
    }

    private fun isHeld(item: T, entry: Entry): Boolean =
        entry.tasks > 0 || origin === item || shown === item

    private fun evaluate(item: T) {
        val entry = entries[item] ?: return
        if (isHeld(item, entry) || entry.releasePending) return
        entry.releasePending = true
        afterFrames(Runnable {
            entry.releasePending = false
            // Held again in the meantime (it cannot be: a drawable is delivered once — but the
            // rule costs nothing): keep it, and the next let-go schedules again.
            if (entries[item] !== entry || isHeld(item, entry)) return@Runnable
            entries.remove(item)
            release(item)
        })
    }
}
