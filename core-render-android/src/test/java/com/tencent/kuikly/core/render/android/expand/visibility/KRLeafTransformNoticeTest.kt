package com.tencent.kuikly.core.render.android.expand.visibility

import android.content.ContextWrapper
import android.view.View
import android.widget.FrameLayout
import com.tencent.kuikly.core.render.android.css.animation.KRCSSTransform
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ronaq fork (CHANGES.md §57): a transform on a view with nothing under it that no observer watches
 * posts no visibility notice — Home's equaliser bars, sixty times a second — while a transform on a
 * watched view, on a view with children, or with an observer that does not say what it watches,
 * still does. The views are the mockable android.jar's; a child count is overridden where needed.
 */
class KRLeafTransformNoticeTest {

    private val queued = mutableListOf<Runnable>()
    private var watched: View? = null
    private var notices = 0
    private val observer = object : KRVisibility.Observer {
        override fun onViewTreeChanged(changes: Int) {
            if (changes and KRVisibility.CHANGE_VISIBILITY != 0) notices++
        }

        override fun watches(view: View): Boolean = view === watched
    }

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

    private fun turn() {
        while (queued.isNotEmpty()) queued.removeAt(0).run()
    }

    private fun move(view: View) {
        KRCSSTransform("0|1 0.5|0 0.25|0.5 0.5|0 0", view).applyTransform()
        turn()
    }

    @Test
    fun aLeafNobodyWatchesMovesWithoutANotice() {
        val bar = View(null)
        assertTrue(KRVisibility.isUnwatchedLeaf(bar))
        move(bar)
        assertEquals(0, notices)
    }

    @Test
    fun theWatchedViewAndAViewWithChildrenStillPostOne() {
        val image = View(null)
        watched = image
        assertFalse(KRVisibility.isUnwatchedLeaf(image))
        move(image)
        assertEquals(1, notices)
        // A mockable ViewGroup's child count is 0 whatever is added; this one says it has one.
        val page = object : FrameLayout(ContextWrapper(null)) {
            override fun getChildCount(): Int = 1
        }
        assertFalse(KRVisibility.isUnwatchedLeaf(page))
        move(page)
        assertEquals(2, notices)
    }

    @Test
    fun aCoverThatIsALeafStillPostsOneWhenItMoves() {
        // CHANGES.md §51 with §57: a moving cover changes what lies wholly under it, although nothing
        // is under it in the tree and no observer watches it — a sheet's solid body sliding away.
        val sheet = View(null)
        KRVisibility.setCover(sheet, true)
        turn()
        notices = 0
        try {
            assertFalse(KRVisibility.isUnwatchedLeaf(sheet))
            move(sheet)
            assertEquals(1, notices)
        } finally {
            KRVisibility.setCover(sheet, false)
            turn()
        }
    }

    @Test
    fun anObserverThatDoesNotSayWatchesEverything() {
        var heard = 0
        val quiet = KRVisibility.Observer { if (it and KRVisibility.CHANGE_VISIBILITY != 0) heard++ }
        KRVisibility.addObserver(quiet)
        try {
            val bar = View(null)
            assertFalse(KRVisibility.isUnwatchedLeaf(bar))
            move(bar)
            assertEquals(1, heard)
        } finally {
            KRVisibility.removeObserver(quiet)
        }
    }
}
