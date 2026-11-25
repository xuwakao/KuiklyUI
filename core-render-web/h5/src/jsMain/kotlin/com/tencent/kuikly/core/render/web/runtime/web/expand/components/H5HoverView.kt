package com.tencent.kuikly.core.render.web.runtime.web.expand.components

import com.tencent.kuikly.core.render.web.const.KRCssConst
import com.tencent.kuikly.core.render.web.export.IKuiklyRenderViewExport
import com.tencent.kuikly.core.render.web.ktx.Frame
import com.tencent.kuikly.core.render.web.ktx.kuiklyDocument
import com.tencent.kuikly.core.render.web.ktx.toPxF
import com.tencent.kuikly.core.render.web.runtime.dom.element.ElementType
import org.w3c.dom.Element
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement

/** Browser-owned pinning also works inside transformed paging containers. */
class H5HoverView : IKuiklyRenderViewExport {
    override val ele = kuiklyDocument.createElement(ElementType.DIV).unsafeCast<HTMLDivElement>()
    private var frame: Frame? = null
    private var marginTop = 0f

    override fun onAddToParent(parent: Element) {
        // Confine the header's z-index to its list, below sibling screens and overlays.
        parent.parentElement?.unsafeCast<HTMLElement>()?.style?.setProperty("isolation", "isolate")
        applyStickyFrame()
    }

    override fun setProp(propKey: String, propValue: Any): Boolean {
        val handled = when (propKey) {
            "bringIndex" -> { ele.style.zIndex = propValue.unsafeCast<Int>().toString(); true }
            "hoverMarginTop" -> { marginTop = propValue.unsafeCast<Float>(); true }
            else -> super.setProp(propKey, propValue)
        }
        if (propKey == KRCssConst.FRAME) frame = propValue.unsafeCast<Frame>()
        // The common FRAME setter writes absolute positioning; restore sticky after it.
        if (propKey == KRCssConst.FRAME || propKey == "hoverMarginTop") applyStickyFrame()
        return handled
    }

    private fun applyStickyFrame() {
        val frame = frame ?: return
        ele.style.position = "sticky"
        ele.style.top = marginTop.toPxF()
        // Lazy placement is absolute. Preserve its natural y without adding flow height:
        // several headers may share a content view whose other children are absolute.
        ele.style.marginTop = frame.y.toPxF()
        ele.style.marginBottom = (-frame.y - frame.height).toPxF()
    }
}
