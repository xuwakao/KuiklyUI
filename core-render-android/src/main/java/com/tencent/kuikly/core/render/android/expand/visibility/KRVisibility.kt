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

package com.tencent.kuikly.core.render.android.expand.visibility

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.tencent.kuikly.core.render.android.const.KRCssConst
import com.tencent.kuikly.core.render.android.css.ktx.getViewData
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Ronaq fork (CHANGES.md §35): whether a view can be seen, and coalesced notices when the
 * framework changes something that could change the answer — the Android twin of the iOS
 * renderer's `UIView (KRVisibility)`, one predicate on both (Ronaq design image-pipeline INV-6).
 *
 * A view is effectively visible when it is attached, its window is shown (CHANGES.md §48: not once
 * the activity has stopped — the app in the background, the screen off), part of it is inside the
 * window (`getGlobalVisibleRect`), and neither it nor any ancestor is not `VISIBLE`, at alpha 0.01
 * or less, or marked `occluded` — the generic prop for a subtree that is laid out and attached but
 * not on the glass (a page under an overlay, a tab that is not in front). Since §51, "inside the
 * window" is the part of the view inside the window and every ancestor that clips its children
 * (what `getGlobalVisibleRect` returns), and a view all of whose visible part lies under an opaque
 * cover drawn above it — a view marked `occludes`, a sheet's solid body — is not visible either.
 *
 * The notices: the `visibility`, `opacity` and `occluded` props, a list's scroll, a transform
 * and a frame change post [CHANGE_VISIBILITY] (the last two since §43), and so does the root
 * render view's window being shown or hidden (§48); an enlarging transform
 * also posts [CHANGE_GEOMETRY]; however many come in one main-looper turn, observers hear one
 * call. Main thread only.
 */
object KRVisibility {

    const val CHANGE_VISIBILITY = 1
    const val CHANGE_GEOMETRY = 2

    /** Hears the coalesced notices. Held weakly. */
    fun interface Observer {
        fun onViewTreeChanged(changes: Int)
    }

    /** One layer of the chain from a view up to its root: itself, then each ancestor. */
    data class Layer(val shown: Boolean, val alpha: Float, val occluded: Boolean)

    private val observers = WeakHashMap<Observer, Boolean>()
    private var pending = 0

    /** How a delivery is scheduled; the main looper, or a test's queue. */
    internal var post: (Runnable) -> Unit = { mainHandler.post(it) }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun addObserver(observer: Observer) {
        observers[observer] = true
    }

    fun removeObserver(observer: Observer) {
        observers.remove(observer)
    }

    /**
     * The largest scale each view's transform has had since it was last reset (CHANGES.md §43).
     * Weak keys: a view that goes away takes its entry with it.
     */
    private val largestScales = WeakHashMap<View, Float>()

    /**
     * A transform with scale [scaleX] × [scaleY] was applied to [view] (CHANGES.md §43). It moves
     * what is under it on or off the glass — a pager page slid by its translation, an entrance —
     * so a visibility notice always follows; a geometry notice only when the scale is larger than
     * any this view has had since its transform was reset, so a pulse back to the same peak posts
     * it once, not on every frame.
     */
    fun noteTransform(view: View, scaleX: Float, scaleY: Float) {
        var changes = CHANGE_VISIBILITY
        val scale = maxOf(abs(scaleX), abs(scaleY))
        val largest = largestScales[view] ?: 1f
        if (scale > 1.001f && scale > largest + 0.001f) {
            largestScales[view] = scale
            changes = changes or CHANGE_GEOMETRY
        }
        noteChange(changes)
    }

    /** [view]'s transform was reset (a recycled view): its scale history starts again. */
    fun forgetTransform(view: View) {
        largestScales.remove(view)
        noteChange(CHANGE_VISIBILITY)
    }

    /** Posts [changes]; delivered once, on the next turn, with everything posted until then. */
    fun noteChange(changes: Int) {
        if (changes == 0) return
        val scheduled = pending != 0
        pending = pending or changes
        if (!scheduled) {
            post(Runnable { deliverPending() })
        }
    }

    fun deliverPending() {
        val changes = pending
        pending = 0
        if (changes == 0) return
        // A snapshot: an observer may register or leave while it is told.
        for (observer in observers.keys.toList()) {
            observer.onViewTreeChanged(changes)
        }
    }

    /** A rectangle in window pixels: left, top, right, bottom. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** A child's place in its parent's drawing order: its z, then its index. */
    data class Branch(val z: Float, val index: Int)

    /**
     * CHANGES.md §51: an opaque [cover] drawn above a view hides it when it covers ALL of what is
     * visible of it ([view], already clipped); a view only partly covered stays visible.
     */
    fun hiddenByCover(view: Box, cover: Box, coverAbove: Boolean): Boolean =
        coverAbove && cover.left <= view.left && cover.top <= view.top &&
            cover.right >= view.right && cover.bottom >= view.bottom

    /**
     * CHANGES.md §51: whether a cover's branch draws after a view's at their lowest common
     * ancestor — a higher z first (a `ViewGroup` orders its children by z), then a later index.
     */
    fun drawnAbove(cover: Branch, view: Branch): Boolean =
        cover.z > view.z || (cover.z == view.z && cover.index > view.index)

    /**
     * Views marked `occludes` (CHANGES.md §51): opaque covers of the rect they occupy — a sheet's
     * solid body. Weak keys: a cover that goes away takes its entry with it.
     */
    private val covers = WeakHashMap<View, Boolean>()

    /** [view] is, or is no longer, an opaque cover; a visibility notice follows a change. */
    fun setCover(view: View, cover: Boolean) {
        val was = covers.containsKey(view)
        if (cover) covers[view] = true else covers.remove(view)
        if (was != cover) noteChange(CHANGE_VISIBILITY)
    }

    /** Whether a registered cover drawn above [view] covers all of [visible], its visible rect. */
    private fun coveredByAnOccluder(view: View, visible: Rect): Boolean {
        if (covers.isEmpty()) return false
        val coverRect = Rect()
        for (cover in covers.keys.toList()) {
            if (cover === view || !cover.isAttachedToWindow) continue
            // A cover that cannot be seen itself hides nothing; covers are not checked for covers.
            if (!isEffectivelyVisible(cover, withCovers = false)) continue
            if (!cover.getGlobalVisibleRect(coverRect)) continue
            val above = drawnAbove(cover, view) ?: continue
            if (hiddenByCover(visible.box(), coverRect.box(), above)) return true
        }
        return false
    }

    /**
     * Whether [cover] draws above [view]: their branches compared at the lowest common ancestor.
     * Null when they share no ancestor, or one contains the other (a cover never hides its own
     * subtree, nor a view the cover's ancestor).
     */
    private fun drawnAbove(cover: View, view: View): Boolean? {
        val coverPath = generateSequence(cover) { it.parent as? View }.toList().asReversed()
        val viewPath = generateSequence(view) { it.parent as? View }.toList().asReversed()
        var depth = 0
        while (depth < coverPath.size && depth < viewPath.size && coverPath[depth] === viewPath[depth]) depth++
        if (depth == 0 || depth >= coverPath.size || depth >= viewPath.size) return null
        val parent = coverPath[depth - 1] as? ViewGroup ?: return null
        val c = coverPath[depth]
        val v = viewPath[depth]
        return drawnAbove(Branch(c.z, parent.indexOfChild(c)), Branch(v.z, parent.indexOfChild(v)))
    }

    private fun Rect.box() = Box(left, top, right, bottom)

    /**
     * The predicate, over plain values, for tests and for [isEffectivelyVisible]. [windowShown]:
     * the view's window is shown (`View.getWindowVisibility() == VISIBLE`), CHANGES.md §48.
     */
    fun isEffectivelyVisible(
        attached: Boolean,
        insideWindow: Boolean,
        chain: Sequence<Layer>,
        windowShown: Boolean = true,
    ): Boolean {
        if (!attached || !windowShown) return false
        for (layer in chain) {
            if (!layer.shown || layer.alpha <= 0.01f || layer.occluded) return false
        }
        return insideWindow
    }

    /**
     * [isEffectivelyVisible], or null while the answer is not known yet: an attached view that
     * has not been laid out, or has no area, cannot be placed against the window (CHANGES.md §41).
     * A detached view, or one whose window is not shown (§48), is not visible.
     */
    fun visibleOrUnknown(view: View): Boolean? {
        if (!view.isAttachedToWindow || view.windowVisibility != View.VISIBLE) return false
        if (!view.isLaidOut || view.width <= 0 || view.height <= 0) return null
        return isEffectivelyVisible(view)
    }

    fun isEffectivelyVisible(view: View): Boolean = isEffectivelyVisible(view, withCovers = true)

    private fun isEffectivelyVisible(view: View, withCovers: Boolean): Boolean {
        val chain = generateSequence(view) { it.parent as? View }.map {
            Layer(it.visibility == View.VISIBLE, it.alpha, it.isOccluded)
        }
        // The rect is the part of the view inside the window and every clipping ancestor
        // (`getGlobalVisibleRect` intersects the parents that clip their children).
        val visible = Rect()
        val insideWindow = view.isAttachedToWindow && view.getGlobalVisibleRect(visible)
        val shown = isEffectivelyVisible(
            attached = view.isAttachedToWindow,
            insideWindow = insideWindow,
            chain = chain,
            windowShown = view.windowVisibility == View.VISIBLE,
        )
        return shown && !(withCovers && coveredByAnOccluder(view, visible))
    }
}

/** The `occluded` common prop's value on this view. */
val View.isOccluded: Boolean
    get() = getViewData<Boolean>(KRCssConst.OCCLUDED) == true
