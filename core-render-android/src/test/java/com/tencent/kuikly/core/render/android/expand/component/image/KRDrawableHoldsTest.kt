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

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ronaq fork (CHANGES.md §39): the release rule of an image view's lent drawables — never while
 * the view keeps it, draws something made from it, or a task reads it; two frames after the
 * last of those; exactly once.
 */
class KRDrawableHoldsTest {

    private class Pic(val name: String) {
        override fun toString() = name
    }

    private val released = mutableListOf<Pic>()
    private val frames = mutableListOf<Runnable>()
    private lateinit var holds: KRDrawableHolds<Pic>

    @BeforeTest
    fun setUp() {
        holds = KRDrawableHolds(release = { released += it }, afterFrames = { frames += it })
    }

    /** Runs what waited for the two frames. */
    private fun twoFramesLater() {
        val due = frames.toList()
        frames.clear()
        due.forEach { it.run() }
    }

    /** A picture delivered and bound the way the view binds it: kept, and drawn. */
    private fun bind(pic: Pic) {
        holds.track(pic)
        holds.origin(pic)
        holds.shown(pic)
    }

    @Test
    fun aReplacedPictureIsGivenBackTwoFramesLaterNotBefore() {
        val a = Pic("a")
        val b = Pic("b")
        bind(a)
        bind(b)
        assertEquals(emptyList(), released, "the render thread may still draw a")
        twoFramesLater()
        assertEquals(listOf(a), released)
        assertEquals(1, holds.trackedCount, "b is still the view's")
    }

    @Test
    fun theBlurTaskHoldsThePictureUntilItHasRun() {
        val a = Pic("a")
        bind(a)
        holds.beginTask(a)
        holds.origin(null)
        holds.shown(null)
        twoFramesLater()
        assertEquals(emptyList(), released, "the blur still reads a's bitmap")
        holds.endTask(a)
        twoFramesLater()
        assertEquals(listOf(a), released)
    }

    @Test
    fun aBlurThatFinishesAfterTheViewMovedOnGivesTheOldPictureBack() {
        val a = Pic("a")
        val b = Pic("b")
        bind(a)
        holds.beginTask(a)
        bind(b)
        twoFramesLater()
        assertEquals(emptyList(), released)
        holds.endTask(a)
        twoFramesLater()
        assertEquals(listOf(a), released)
    }

    @Test
    fun destroyingTheViewGivesEverythingBack() {
        val a = Pic("a")
        bind(a)
        holds.clear()
        twoFramesLater()
        assertEquals(listOf(a), released)
        assertEquals(0, holds.trackedCount)
    }

    @Test
    fun aPictureIsNeverGivenBackTwice() {
        val a = Pic("a")
        bind(a)
        holds.clear()
        holds.clear()
        twoFramesLater()
        holds.origin(a)
        holds.shown(a)
        holds.clear()
        holds.settle(a)
        holds.discard(a)
        twoFramesLater()
        assertEquals(listOf(a), released, "once, and untracked after")
    }

    @Test
    fun aTintCopyReplacedByANewTintKeepsThePicture() {
        // A tint or colour filter draws `constantState.newDrawable()` — a new drawable over the
        // same bitmap. The view reports it as shown from the same delivered picture.
        val a = Pic("a")
        bind(a)
        holds.shown(a) // the first tint's copy
        holds.shown(a) // a new tint, a new copy
        twoFramesLater()
        assertEquals(emptyList(), released)
        holds.origin(null)
        holds.shown(null)
        twoFramesLater()
        assertEquals(listOf(a), released)
    }

    @Test
    fun aNinePatchBuiltOverThePictureHoldsItUntilANewSource() {
        // The view keeps the nine-patch as its source; its root is the delivered picture.
        val a = Pic("a")
        holds.track(a)
        holds.origin(a)
        holds.shown(a)
        holds.settle(a)
        twoFramesLater()
        assertEquals(emptyList(), released)
        holds.origin(null) // a new `src` clears the view first
        holds.shown(null)
        twoFramesLater()
        assertEquals(listOf(a), released)
    }

    @Test
    fun theOldDerivativeStaysDrawnUntilTheBlurOfTheNewOneArrives() {
        // With a blur radius, a new source is not drawn until its blur is ready: the old one's
        // copy is still on screen and still holds it.
        val a = Pic("a")
        val b = Pic("b")
        bind(a)
        holds.track(b)
        holds.origin(b)
        holds.beginTask(b)
        twoFramesLater()
        assertEquals(emptyList(), released, "a is still drawn")
        holds.shown(b) // the blur result arrives, shown from b
        holds.endTask(b)
        twoFramesLater()
        assertEquals(listOf(a), released)
    }

    @Test
    fun aStaleResultIsGivenBackAtOnceAndOnlyOnce() {
        val a = Pic("a")
        holds.track(a)
        holds.discard(a)
        holds.discard(a)
        assertEquals(listOf(a), released, "never drawn, so no frames to wait for")
        assertEquals(0, holds.trackedCount)
    }

    @Test
    fun aPictureTheViewWasNotLentIsNeverGivenBack() {
        val cached = Pic("from a module")
        holds.origin(cached)
        holds.shown(cached)
        holds.beginTask(cached)
        holds.endTask(cached)
        holds.clear()
        holds.discard(cached)
        twoFramesLater()
        assertEquals(emptyList(), released)
    }

    @Test
    fun heldAgainBeforeTheFramesRanMeansKept() {
        val a = Pic("a")
        bind(a)
        holds.shown(null)
        holds.origin(null)
        holds.origin(a) // taken back before the frames ran
        twoFramesLater()
        assertEquals(emptyList(), released)
        holds.origin(null)
        twoFramesLater()
        assertEquals(listOf(a), released)
        assertTrue(frames.isEmpty())
    }
}
