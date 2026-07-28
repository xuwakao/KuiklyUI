package com.tencent.kuikly.core.render.web.runtime.web.expand

import com.tencent.kuikly.core.render.web.const.KRCssConst
import com.tencent.kuikly.core.render.web.const.KRJsTypeConst
import com.tencent.kuikly.core.render.web.export.IKuiklyRenderViewExport
import com.tencent.kuikly.core.render.web.export.IKuiklyRenderViewPropExternalHandler
import org.w3c.dom.HTMLElement

/**
 * Default Handler: external prop handler for custom props.
 * Handles the "cssClass" attribute set via `fun Attr.cssClass(value: String)` in common code.
 */
internal class KRCustomPropsHandler : IKuiklyRenderViewPropExternalHandler {

    override fun setViewExternalProp(
        renderViewExport: IKuiklyRenderViewExport,
        propKey: String,
        propValue: Any
    ): Boolean {
        when (propKey) {
            KRCssConst.CSS_CLASS -> {
                val ele = renderViewExport.ele.unsafeCast<HTMLElement>()
                if (jsTypeOf(ele.asDynamic().classList) == KRJsTypeConst.UNDEFINED) return false

                removeTrackedCssClasses(ele)

                val cssClassValue = propValue.unsafeCast<String>().trim()
                cssClassValue
                    .split("\\s+".toRegex())
                    .filter { it.isNotEmpty() }
                    .forEach { className ->
                        ele.classList.add(className)
                    }
                ele.setAttribute(TRACKED_CSS_CLASS_ATTR, cssClassValue)
                return true
            }
            TEST_TAG -> {
                // `Modifier.testTag` / `Attr.testTag` already reaches the native views on Android
                // (KRConst.TEST_TAG) and iOS (css_testTag), but web had no handler, so the prop was
                // dropped silently and the tag was invisible to browser-driven tests. Surface it as
                // data-testid, which is what Playwright's getByTestId reads by default.
                val ele = renderViewExport.ele.unsafeCast<HTMLElement>()
                ele.setAttribute(TEST_ID_ATTR, propValue.unsafeCast<String>())
                return true
            }
            else -> {
                // Pass `data-*` props straight through to the element.
                //
                // Kuikly renders every control as a plain <div>, so state that a browser would
                // normally read from the element itself — disabled, checked, selected, busy — is
                // only expressed as styling and is invisible to anything driving the page from
                // outside. Letting callers emit their own data-* attributes gives that state a
                // machine-readable form without inventing a prop name per widget.
                if (!propKey.startsWith(DATA_ATTR_PREFIX)) return false
                val ele = renderViewExport.ele.unsafeCast<HTMLElement>()
                ele.setAttribute(propKey, propValue.toString())
                return true
            }
        }
    }

    override fun resetViewExternalProp(
        renderViewExport: IKuiklyRenderViewExport,
        propKey: String
    ): Boolean {
        return when (propKey) {
            KRCssConst.CSS_CLASS -> {
                val ele = renderViewExport.ele.unsafeCast<HTMLElement>()
                if (jsTypeOf(ele.asDynamic().classList) == KRJsTypeConst.UNDEFINED) return false

                removeTrackedCssClasses(ele)
                ele.removeAttribute(TRACKED_CSS_CLASS_ATTR)
                true
            }
            TEST_TAG -> {
                renderViewExport.ele.unsafeCast<HTMLElement>().removeAttribute(TEST_ID_ATTR)
                true
            }
            else -> {
                if (!propKey.startsWith(DATA_ATTR_PREFIX)) return false
                renderViewExport.ele.unsafeCast<HTMLElement>().removeAttribute(propKey)
                true
            }
        }
    }

    private fun removeTrackedCssClasses(ele: HTMLElement) {
        ele.getAttribute(TRACKED_CSS_CLASS_ATTR)
            ?.trim()
            ?.split("\\s+".toRegex())
            ?.filter { it.isNotEmpty() }
            ?.forEach { className ->
                ele.classList.remove(className)
            }
    }

    companion object {
        private const val TRACKED_CSS_CLASS_ATTR = "data-kuikly-css-class"

        /** Matches StyleConst.TEST_TAG in core / KRConst.TEST_TAG on Android. */
        private const val TEST_TAG = "testTag"
        private const val TEST_ID_ATTR = "data-testid"

        /** Props with this prefix are written to the element verbatim. */
        private const val DATA_ATTR_PREFIX = "data-"
    }
}
