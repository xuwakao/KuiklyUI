package com.tencent.kuikly.compose.foundation.pager

/**
 * Keep speculative composition out of an in-flight page transition. As soon as a second page
 * becomes even one pixel visible, extending from its outer edge would synchronously build a
 * third, invisible page on the scrolling frame. Visible and pinned pages are measured separately.
 */
internal class PagerBeyondViewportWindow {
    private var range: IntRange? = null
    private var previousPageCount = -1
    private var previousBeyondCount = -1

    fun update(
        firstVisiblePage: Int,
        lastVisiblePage: Int,
        pageCount: Int,
        beyondViewportPageCount: Int,
        aligned: Boolean
    ): IntRange {
        if (range == null || aligned || pageCount != previousPageCount ||
            beyondViewportPageCount != previousBeyondCount
        ) {
            range = maxOf(0, firstVisiblePage - beyondViewportPageCount)..
                minOf(pageCount - 1, lastVisiblePage + beyondViewportPageCount)
            previousPageCount = pageCount
            previousBeyondCount = beyondViewportPageCount
        }
        return range!!
    }
}
