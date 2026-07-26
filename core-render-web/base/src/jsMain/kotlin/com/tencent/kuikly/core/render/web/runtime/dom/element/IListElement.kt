package com.tencent.kuikly.core.render.web.runtime.dom.element

import com.tencent.kuikly.core.render.web.ktx.KuiklyRenderCallback
import org.w3c.dom.HTMLElement

/**
 * Abstract ListView element interface
 */
interface IListElement {
    var ele: HTMLElement

    // Scroll callback
    var scrollEventCallback: KuiklyRenderCallback?

    // Drag begin callback
    var dragBeginEventCallback: KuiklyRenderCallback?

    // Drag end callback
    var dragEndEventCallback: KuiklyRenderCallback?

    // Will drag end callback
    var willDragEndEventCallback: KuiklyRenderCallback?

    // Scroll end drag
    var scrollEndEventCallback: KuiklyRenderCallback?

    // Click callback
    var clickEventCallback: KuiklyRenderCallback?

    // Double click callback
    var doubleClickEventCallback: KuiklyRenderCallback?

    /**
     * Whether this list has a pull-to-refresh child.
     * Set by upper layers (ScrollerView / Compose / DSL RefreshView).
     */
    var hasPullToRefresh: Boolean

    /**
     * Scroll element to specified position
     */
    fun setContentOffset(params: String?)

    /**
     * Set content margin with animation
     */
    fun setContentInset(params: String?)

    /**
     * Set padding when drag ends, i.e. translateX and Y values
     */
    fun setContentInsetWhenEndDrag(params: String?)

    /**
     * Bind scroll-related event handlers
     */
    fun setScrollEvent()

    /**
     * Bind scroll end event
     */
    fun setScrollEndEvent()

    /**
     * Set whether scrolling is enabled
     */
    fun setScrollEnable(params: Any): Boolean

    /**
     * Set whether to show scroll indicator
     */
    fun setShowScrollIndicator(params: Any): Boolean

    /**
     * Set scroll direction
     */
    fun setScrollDirection(params: Any): Boolean

    /**
     * Set whether to enable paging scroll
     */
    fun setPagingEnable(params: Any): Boolean

    /**
     * Compose pager hint (`isComposePager`) emitted by SubcomposeLayout.
     *
     * Compose implements its own snapping, so most renderers ignore this and return false
     * (unhandled -> falls through to the generic prop path, i.e. current behaviour).
     * H5 is the exception: its snapping lives in the render layer (H5ListPagingHelper), so
     * H5ListView overrides this to turn the hint into paging. Kept separate from
     * setPagingEnable so the compose hint never reaches native/mini-program paging, whose
     * semantics differ (mini-program switches to movable-area, native scrollviews snap by
     * viewport width) and would fight compose's own snap.
     */
    fun setComposePager(params: Any): Boolean = false

    /**
     * enable bounce effect
     */
    fun setBounceEnable(params: Any): Boolean

    /**
     * Set list nested scroll props
     */
    fun setNestedScroll(propValue: Any): Boolean

    /**
     * update offset
     */
    fun updateOffsetMap(offsetX: Float, offsetY: Float, isDragging: Int): MutableMap<String, Any>

    /**
     * Clear transient state for Compose DSL reuse (not the native reuse pool).
     *
     * After this is called, the next [setContentOffset] MUST asynchronously fire a scroll
     * event even when the underlying offset is unchanged. This is required so that the
     * upper-layer `ignoreScrollOffset` flag in SubcomposeLayout can be cleared; otherwise
     * web/miniapp scroll-view's native silent behavior on no-op scrollTo would block all
     * subsequent scroll events from reaching Compose.
     */
    fun prepareForComposeReuse()

    /**
     * Callback to be executed when component is destroyed
     */
    fun destroy()
}