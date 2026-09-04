package com.tencent.kuikly.core.render.web.expand.components

import com.tencent.kuikly.core.render.web.const.KRCssConst
import com.tencent.kuikly.core.render.web.export.IKuiklyRenderViewExport
import com.tencent.kuikly.core.render.web.ktx.Frame
import com.tencent.kuikly.core.render.web.ktx.kuiklyDocument
import com.tencent.kuikly.core.render.web.ktx.pxToFloat
import com.tencent.kuikly.core.render.web.ktx.toPxF

import com.tencent.kuikly.core.render.web.runtime.dom.element.ElementType
import org.w3c.dom.Element

import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import kotlin.js.json

/**
 * Hover top view
 */
class KRHoverView : IKuiklyRenderViewExport {
    // div instance
    private val hover = kuiklyDocument.createElement(ElementType.DIV)

    // The scrolling ancestor this view hovers inside. Its scroll offset is what decides
    // whether the view rests in the content or is pinned to the top of it.
    private var scroller: HTMLElement? = null

    // Component's original top value
    private var top = 0f

    // Where the scroller itself sits on the page, so a pinned view can be placed against
    // the viewport. Taken lazily: at insertion time nothing in the chain has a frame yet.
    private var totalTop: Float? = null
    private var hoverViewMarginTop = 0f

    // Whether this view is currently pinned. Tracked here rather than read back off the
    // element, because the renderer rewrites `position` and `top` on every frame it applies
    // (see `Element.setFrame`) — so the element's own style says what the RENDERER last did,
    // which is not the same question. Null means the element must be synchronized even if
    // the next computed state has the same boolean value as it had before a reattachment.
    private var pinned: Boolean? = null

    // Kept as one function object so removal detaches the exact listener that was added.
    private val scrollListener: (Event) -> Unit = { updateHoverState() }

    override val ele: HTMLDivElement
        get() = hover.unsafeCast<HTMLDivElement>()

    /**
     * Set hover view's display layer
     */
    private fun setBringIndex(index: Any): Boolean {
        // Set display layer
        ele.style.zIndex = index.unsafeCast<Int>().toString()
        return true
    }

    /**
     * Get total top value
     */
    private fun getTotalTop(element: HTMLElement?): Float {
        var totalTop = 0f
        if (element == null) {
            return totalTop
        }
        // calculate current top value
        totalTop += element.style.top.pxToFloat()

        // plus all parent's top value
        var parent = element.parentElement
        while (parent !== null) {
            totalTop += parent.unsafeCast<HTMLElement>().style.top.pxToFloat()
            parent = parent.parentElement
        }

        return totalTop
    }

    /**
     * set hover margin top
     */
    private fun setHoverMarginTop(propValue: Any): Boolean {
        hoverViewMarginTop = propValue.unsafeCast<Float>()
        updateHoverState()
        return true
    }

    /**
     * When node is inserted into parent node, bind parent's scroll event and pass scroll parameters
     */
    override fun onAddToParent(parent: Element) {
        super.onAddToParent(parent)
        // Current node's parent element is listView's scroll content area scrollContentView.
        // Actual scroll view needs to get grandparent node
        scroller?.removeEventListener("scroll", scrollListener)
        scroller = null
        totalTop = null
        pinned = null
        val grandParent = parent.parentElement.unsafeCast<HTMLElement?>() ?: return
        scroller = grandParent
        // The hover carries a large `z-index` (the compose sticky headers give it 1000)
        // and, while pinned, `position: fixed`. Left alone, both join the PAGE's root
        // stacking context, because ordinary Kuikly views never establish one — so a
        // sticky header out-stacked every sibling screen drawn after its own: with a
        // room screen open over Home, Home's pinned category strip painted over the room
        // and took its taps. Android has no such escape; a child's z there only reorders
        // within its parent. Making the scroller a stacking context (`isolation` creates
        // one and nothing else — unlike `transform`, it does NOT become the containing
        // block for fixed descendants, so the pinning coordinates and `getTotalTop`'s
        // page-offset math are untouched) confines the hover to its own scroller's spot
        // in the paint order: above the content it hovers over, below everything a later
        // sibling — an overlay bar, another screen — draws on top. The property is left
        // on the scroller after removal: a stacking context on a scroll container is
        // inert on its own, and clearing it per-hover would need reference counting for
        // a container that can host several hovers.
        grandParent.style.setProperty(ISOLATION, ISOLATE)
        grandParent.addEventListener("scroll", scrollListener, json("passive" to true))
        updateHoverState()
    }

    override fun onRemoveFromParent(parent: Element) {
        scroller?.removeEventListener("scroll", scrollListener)
        scroller = null
        totalTop = null
        pinned = null
        super.onRemoveFromParent(parent)
    }

    override fun onDestroy() {
        // Normally removal runs first; this also covers a host that destroys directly.
        scroller?.removeEventListener("scroll", scrollListener)
        scroller = null
        totalTop = null
        pinned = null
        super.onDestroy()
    }

    override fun setProp(propKey: String, propValue: Any): Boolean {
        val handled = when (propKey) {
            MARGIN_TOP -> setHoverMarginTop(propValue)
            BRING_INDEX -> setBringIndex(propValue)
            else -> super.setProp(propKey, propValue)
        }
        if (propKey == KRCssConst.FRAME) {
            // Where this view RESTS, from the renderer's own value for it. It cannot be read
            // at `onAddToParent` — a node has no frame at the moment it is added, so
            // `style.top` is empty and reads as 0, and a view cached at 0 pins at the first
            // scrolled pixel instead of when the scroll reaches it. It cannot be read back
            // off `style.top` either once the view is pinned, because that property then
            // holds the pinned position.
            top = propValue.unsafeCast<Frame>().y.toFloat()
            // `setFrame` has just written `position:absolute` and this top unconditionally,
            // so the element is resting again whatever it was doing before. Re-decide from
            // scratch: a relayout while pinned — a banner delivered after the first paint —
            // would otherwise drop the pinned view back into the content mid-scroll.
            pinned = null
            updateHoverState()
        }
        return handled
    }

    /**
     * Pin the view to the top of its scroller once the scroll passes where it rests, and
     * hand it back to the renderer when the scroll returns above that.
     *
     * Only ever WRITES on a state change. The resting position belongs to the renderer,
     * which caches the frame it last applied and skips an update it believes is already
     * there; writing `top` on every scroll event races that and leaves the view parked at
     * an offset nothing will correct.
     */
    private fun updateHoverState() {
        val scroller = scroller ?: return
        val shouldPin = scroller.scrollTop > top - hoverViewMarginTop
        if (shouldPin == pinned) {
            return
        }
        pinned = shouldPin
        if (shouldPin) {
            // Read at the moment of pinning, not when the frame arrives: `getTotalTop`
            // walks the scroller's own ancestors, and those carry no `style.top` until
            // they have been laid out. Taking it here is the first point at which the
            // answer is certainly available, and it costs one walk per pin.
            // Re-read on every pin. The scroller can move between pins after a viewport
            // resize or a parent relayout; retaining its first page offset parks the
            // header at the old coordinate the next time it becomes sticky.
            totalTop = getTotalTop(scroller)
            ele.style.position = FIXED
            ele.style.top = "${(totalTop ?: 0f) + hoverViewMarginTop}px"
        } else {
            ele.style.position = ABSOLUTE
            ele.style.top = top.toPxF()
        }
    }

    companion object {
        const val VIEW_NAME = "KRHoverView"
        private const val FIXED = "fixed"
        private const val ABSOLUTE = "absolute"
        private const val ISOLATION = "isolation"
        private const val ISOLATE = "isolate"
        private const val BRING_INDEX = "bringIndex"
        private const val MARGIN_TOP = "hoverMarginTop"
    }
}
