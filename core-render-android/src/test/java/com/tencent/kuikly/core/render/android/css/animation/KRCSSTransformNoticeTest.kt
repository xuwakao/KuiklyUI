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

package com.tencent.kuikly.core.render.android.css.animation

import android.view.View
import com.tencent.kuikly.core.render.android.expand.visibility.KRVisibility
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ronaq fork (CHANGES.md §43): what a transform the renderer applies tells the view tree. A
 * translation moves a view on or off the glass — a pager page slid by `graphicsLayer.translationX`
 * — so it is a visibility notice; only a scale larger than any the view has had since its
 * transform was reset is a geometry notice, so a pulse does not post one on every frame.
 *
 * The views here are the mockable android.jar's (default values, no drawing): what is checked is
 * the notices, which is all the renderer adds.
 */
class KRCSSTransformNoticeTest {

    private val queued = mutableListOf<Runnable>()
    private val heard = mutableListOf<Int>()
    private val observer = KRVisibility.Observer { heard += it }

    @BeforeTest
    fun setUp() {
        KRVisibility.post = { queued += it }
        KRVisibility.deliverPending()
        queued.clear()
        KRVisibility.addObserver(observer)
    }

    @AfterTest
    fun tearDown() {
        KRVisibility.removeObserver(observer)
    }

    /** Runs the pending turn; the bits every observer heard in it. */
    private fun turn(): Int {
        val before = heard.size
        while (queued.isNotEmpty()) queued.removeAt(0).run()
        return heard.drop(before).fold(0) { all, changes -> all or changes }
    }

    /** Kuikly's transform wire: rotate | scale | translate (fractions of the frame) | anchor | skew. */
    private fun apply(view: View, scale: Float = 1f, translateX: Float = 0f) {
        KRCSSTransform("0|$scale $scale|$translateX 0|0.5 0.5|0 0", view).applyTransform()
    }

    @Test
    fun aPageSlidOffTheGlassAndBackByATranslationIsReCheckedBothTimes() {
        val page = View(null)
        apply(page, translateX = -1f)
        assertTrue(turn() and KRVisibility.CHANGE_VISIBILITY != 0, "slid off: its players must hear it")
        apply(page, translateX = 0f)
        assertTrue(turn() and KRVisibility.CHANGE_VISIBILITY != 0, "slid back: a paused preview must resume")
    }

    @Test
    fun aPulseBackToTheSameScalePostsOneGeometryNotice() {
        val heart = View(null)
        var geometry = 0
        repeat(6) {
            apply(heart, scale = 1.3f)
            if (turn() and KRVisibility.CHANGE_GEOMETRY != 0) geometry++
            apply(heart, scale = 1f)
            if (turn() and KRVisibility.CHANGE_GEOMETRY != 0) geometry++
        }
        assertEquals(1, geometry, "six beats to 1.3: one geometry notice, not one a frame")
        apply(heart, scale = 1.5f)
        assertTrue(turn() and KRVisibility.CHANGE_GEOMETRY != 0, "a new largest scale is news")
    }

    @Test
    fun aResetTransformStartsTheScaleHistoryAgain() {
        val reused = View(null)
        apply(reused, scale = 2f)
        assertTrue(turn() and KRVisibility.CHANGE_GEOMETRY != 0)
        KRCSSTransform(null, reused).resetTransform()
        turn()
        apply(reused, scale = 2f)
        assertTrue(turn() and KRVisibility.CHANGE_GEOMETRY != 0, "a recycled view's history does not carry over")
    }

    @Test
    fun aShrinkOrAPlainTranslationIsNeverGeometry() {
        val view = View(null)
        apply(view, scale = 0.5f, translateX = 0.2f)
        val changes = turn()
        assertTrue(changes and KRVisibility.CHANGE_VISIBILITY != 0)
        assertEquals(0, changes and KRVisibility.CHANGE_GEOMETRY)
    }
}
