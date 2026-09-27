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

import android.view.View
import com.tencent.kuikly.core.render.android.expand.visibility.KRVisibility.Layer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ronaq fork (CHANGES.md §35): the effective-visibility predicate and the coalesced notices. */
class KRVisibilityTest {

    private val queued = mutableListOf<Runnable>()
    private val heard = mutableListOf<Int>()
    private val observer = KRVisibility.Observer { heard += it }

    @BeforeTest
    fun setUp() {
        KRVisibility.post = { queued += it }
        KRVisibility.deliverPending()
        KRVisibility.addObserver(observer)
    }

    @AfterTest
    fun tearDown() {
        KRVisibility.removeObserver(observer)
    }

    private val shown = Layer(shown = true, alpha = 1f, occluded = false)

    @Test
    fun anAttachedViewInsideTheWindowWithNothingHiddenIsVisible() {
        assertTrue(KRVisibility.isEffectivelyVisible(true, true, sequenceOf(shown, shown, shown)))
    }

    @Test
    fun aDetachedViewIsNotVisible() {
        assertFalse(KRVisibility.isEffectivelyVisible(false, true, sequenceOf(shown)))
    }

    @Test
    fun aViewOutsideTheWindowIsNotVisible() {
        assertFalse(KRVisibility.isEffectivelyVisible(true, false, sequenceOf(shown)))
    }

    @Test
    fun aHiddenTransparentOrOccludedAncestorHidesTheSubtree() {
        assertFalse(KRVisibility.isEffectivelyVisible(true, true, sequenceOf(shown, shown.copy(shown = false), shown)),
            "Kuikly hides a lazy slot's root, not the player")
        assertFalse(KRVisibility.isEffectivelyVisible(true, true, sequenceOf(shown, shown.copy(alpha = 0f))))
        assertFalse(KRVisibility.isEffectivelyVisible(true, true, sequenceOf(shown, shown.copy(occluded = true))),
            "a covered page")
        assertTrue(KRVisibility.isEffectivelyVisible(true, true, sequenceOf(shown, shown.copy(alpha = 0.5f))))
    }

    @Test
    fun aViewInAWindowThatIsNotShownIsNotVisible() {
        // CHANGES.md §48: the activity stopped — the app in the background, the screen off.
        assertFalse(KRVisibility.isEffectivelyVisible(true, true, sequenceOf(shown), windowShown = false))
        assertTrue(KRVisibility.isEffectivelyVisible(true, true, sequenceOf(shown), windowShown = true))
    }

    // CHANGES.md §51: an opaque cover drawn above a view hides it when it covers all of it.

    @Test
    fun aViewWhollyUnderACoverDrawnAboveItIsNotVisible() {
        val view = KRVisibility.Box(10, 10, 60, 60)
        assertTrue(KRVisibility.hiddenByCover(view, cover = KRVisibility.Box(0, 0, 100, 100), coverAbove = true))
    }

    @Test
    fun aViewPartlyUnderACoverStaysVisible() {
        val view = KRVisibility.Box(10, 10, 60, 60)
        assertFalse(KRVisibility.hiddenByCover(view, cover = KRVisibility.Box(0, 0, 100, 40), coverAbove = true))
    }

    @Test
    fun aCoverDrawnBelowTheViewHidesNothing() {
        val view = KRVisibility.Box(10, 10, 60, 60)
        assertFalse(KRVisibility.hiddenByCover(view, cover = KRVisibility.Box(0, 0, 100, 100), coverAbove = false))
    }

    @Test
    fun drawingOrderAtTheCommonAncestorIsZThenIndex() {
        assertTrue(KRVisibility.drawnAbove(cover = KRVisibility.Branch(0f, 3), view = KRVisibility.Branch(0f, 1)))
        assertFalse(KRVisibility.drawnAbove(cover = KRVisibility.Branch(0f, 1), view = KRVisibility.Branch(0f, 3)))
        assertTrue(KRVisibility.drawnAbove(cover = KRVisibility.Branch(20f, 0), view = KRVisibility.Branch(0f, 5)),
            "a higher z draws later whatever the index")
        assertFalse(KRVisibility.drawnAbove(cover = KRVisibility.Branch(0f, 5), view = KRVisibility.Branch(9f, 0)))
    }

    /** A view that keeps the attach listeners it is given (the mockable android.jar drops them). */
    private class ListenedView : View(null) {
        val listeners = mutableListOf<View.OnAttachStateChangeListener>()

        override fun addOnAttachStateChangeListener(listener: View.OnAttachStateChangeListener?) {
            listener?.let(listeners::add)
        }

        override fun removeOnAttachStateChangeListener(listener: View.OnAttachStateChangeListener?) {
            listeners.remove(listener)
        }
    }

    private fun drain(): Int {
        var changes = 0
        while (queued.isNotEmpty()) queued.removeAt(0).run()
        heard.forEach { changes = changes or it }
        heard.clear()
        return changes
    }

    @Test
    fun aCoverLeavingTheWindowPostsAVisibilityNotice() {
        // CHANGES.md §52: a sheet closes by removal, and the renderer posts nothing for a removed
        // view unless its reuse reset runs — which it skips once the reuse queue is full. Whatever
        // was wholly under the cover must still hear that it is gone.
        val cover = ListenedView()
        KRVisibility.setCover(cover, true)
        drain()
        assertEquals(1, cover.listeners.size, "a cover watches its own attachment")
        cover.listeners.toList().forEach { it.onViewDetachedFromWindow(cover) }
        assertEquals(KRVisibility.CHANGE_VISIBILITY, drain() and KRVisibility.CHANGE_VISIBILITY)
        cover.listeners.toList().forEach { it.onViewAttachedToWindow(cover) }
        assertEquals(KRVisibility.CHANGE_VISIBILITY, drain() and KRVisibility.CHANGE_VISIBILITY,
            "and coming back covers what is under it again")
        KRVisibility.setCover(cover, false)
        drain()
        assertTrue(cover.listeners.isEmpty(), "no longer a cover: no longer watched")
    }

    @Test
    fun noticesPostedInOneTurnAreHeardOnceTogether() {
        KRVisibility.noteChange(KRVisibility.CHANGE_VISIBILITY)
        KRVisibility.noteChange(KRVisibility.CHANGE_GEOMETRY)
        KRVisibility.noteChange(KRVisibility.CHANGE_VISIBILITY)
        assertEquals(0, heard.size, "delivered on the next turn")
        assertEquals(1, queued.size, "one delivery scheduled")
        queued.removeAt(0).run()
        assertEquals(listOf(KRVisibility.CHANGE_VISIBILITY or KRVisibility.CHANGE_GEOMETRY), heard)
    }
}
