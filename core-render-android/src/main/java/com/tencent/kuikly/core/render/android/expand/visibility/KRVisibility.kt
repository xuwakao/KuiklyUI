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
import com.tencent.kuikly.core.render.android.const.KRCssConst
import com.tencent.kuikly.core.render.android.css.ktx.getViewData
import java.util.WeakHashMap

/**
 * Ronaq fork (CHANGES.md §35): whether a view can be seen, and coalesced notices when the
 * framework changes something that could change the answer — the Android twin of the iOS
 * renderer's `UIView (KRVisibility)`, one predicate on both (Ronaq design image-pipeline INV-6).
 *
 * A view is effectively visible when it is attached, part of it is inside the window
 * (`getGlobalVisibleRect`), and neither it nor any ancestor is not `VISIBLE`, at alpha 0.01 or
 * less, or marked `occluded` — the generic prop for a subtree that is laid out and attached but
 * not on the glass (a page under an overlay, a tab that is not in front).
 *
 * The notices: the `visibility`, `opacity` and `occluded` props and a list's scroll post
 * [CHANGE_VISIBILITY]; however many come in one main-looper turn, observers hear one call.
 * Main thread only.
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

    /** The predicate, over plain values, for tests and for [isEffectivelyVisible]. */
    fun isEffectivelyVisible(attached: Boolean, insideWindow: Boolean, chain: Sequence<Layer>): Boolean {
        if (!attached) return false
        for (layer in chain) {
            if (!layer.shown || layer.alpha <= 0.01f || layer.occluded) return false
        }
        return insideWindow
    }

    /**
     * [isEffectivelyVisible], or null while the answer is not known yet: an attached view that
     * has not been laid out, or has no area, cannot be placed against the window (CHANGES.md §41).
     * A detached view is not visible.
     */
    fun visibleOrUnknown(view: View): Boolean? {
        if (!view.isAttachedToWindow) return false
        if (!view.isLaidOut || view.width <= 0 || view.height <= 0) return null
        return isEffectivelyVisible(view)
    }

    fun isEffectivelyVisible(view: View): Boolean {
        val chain = generateSequence(view) { it.parent as? View }.map {
            Layer(it.visibility == View.VISIBLE, it.alpha, it.isOccluded)
        }
        return isEffectivelyVisible(
            attached = view.isAttachedToWindow,
            insideWindow = view.isAttachedToWindow && view.getGlobalVisibleRect(Rect()),
            chain = chain,
        )
    }
}

/** The `occluded` common prop's value on this view. */
val View.isOccluded: Boolean
    get() = getViewData<Boolean>(KRCssConst.OCCLUDED) == true
