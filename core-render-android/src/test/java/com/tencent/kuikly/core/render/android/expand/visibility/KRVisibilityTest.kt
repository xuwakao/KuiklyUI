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
