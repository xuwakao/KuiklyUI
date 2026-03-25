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

package com.tencent.kuikly.core.render.android.expand.component

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.tencent.kuikly.core.render.android.KuiklyRenderView
import com.tencent.kuikly.core.render.android.adapter.KuiklyRenderLog
import com.tencent.kuikly.core.render.android.const.KRViewConst
import com.tencent.kuikly.core.render.android.css.decoration.KRViewDecoration
import com.tencent.kuikly.core.render.android.css.ktx.touchConsumeByNative
import com.tencent.kuikly.core.render.android.css.ktx.drawCommonDecoration
import com.tencent.kuikly.core.render.android.css.ktx.drawCommonForegroundDecoration
import com.tencent.kuikly.core.render.android.css.ktx.nativeGestureViewHashCodeSet
import com.tencent.kuikly.core.render.android.css.ktx.toDpF
import com.tencent.kuikly.core.render.android.css.ktx.touchDownConsumeOnce
import com.tencent.kuikly.core.render.android.css.ktx.viewDecorator
import com.tencent.kuikly.core.render.android.export.IKuiklyRenderViewExport
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONObject

open class KRView(context: Context) : FrameLayout(context), IKuiklyRenderViewExport {

    private var touchDownCallback: KuiklyRenderCallback? = null
    private var touchMoveCallback: KuiklyRenderCallback? = null
    private var touchUpCallback: KuiklyRenderCallback? = null
    private var screenFrameCallback: ((Long) ->Unit)? = null
    private var screenFramePause: Boolean = false

    private var touchListenerProxy: View.OnTouchListener? = null
    private var currentActionState: Int = -1

    // Ripple effect related fields
    private var rippleOverlayView: View? = null
    private var rippleDrawable: RippleDrawable? = null
    private var rippleEnabled: Boolean = false
    private var rippleColor: Int = Color.BLACK
    private var ripplePressedAlpha: Float = 0.12f
    private var rippleBounded: Boolean = true

    override val reusable: Boolean
        get() = true

    /**
     * layout日志相关
     */
    private var requestLayoutLogCount = 0
    private var onLayoutLogCount = 0

    /**
     * 嵌套滚动相关
     */
    var nestedScrollDelegate: ViewGroup? = null

    init {
        setWillDraw()
    }

    override fun onStartNestedScroll(child: View, target: View, nestedScrollAxes: Int): Boolean =
        nestedScrollDelegate != null

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray) {
        nestedScrollDelegate?.onNestedPreScroll(target, dx, dy, consumed)
            ?: super.onNestedPreScroll(target, dx, dy, consumed)
    }

    override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float): Boolean {
        return nestedScrollDelegate?.onNestedPreFling(target, velocityX, velocityY) ?: super.onNestedPreFling(
            target,
            velocityX,
            velocityY
        )
    }

    private fun setScreenFramePause(propValue: Any) {
        val result = propValue == 1
        if (result != screenFramePause) {
            screenFramePause = result
            if (screenFramePause) {
                screenFrameCallback?.also {
                    Choreographer.getInstance().removeFrameCallback(it)
                }
            } else {
                screenFrameCallback?.also {
                    Choreographer.getInstance().postFrameCallback(it)
                }
            }
        }

    }

    private var superTouch: Boolean = false
    private var superTouchCanceled: Boolean = false

    private fun syncComposeRootTag() {
        krRootView()?.setTag(COMPOSE_ROOT_TAG_ID, superTouch)
    }

    override fun setProp(propKey: String, propValue: Any): Boolean {
        return when (propKey) {
            SCREEN_FRAME_PAUSE -> {
                setScreenFramePause(propValue)
                true
            }
            SUPER_TOUCH -> {
                superTouch = propValue as Boolean
                syncComposeRootTag()
                true
            }
            EVENT_TOUCH_DOWN -> {
                touchDownCallback = propValue as KuiklyRenderCallback
                true
            }
            EVENT_TOUCH_MOVE -> {
                touchMoveCallback = propValue as KuiklyRenderCallback
                true
            }
            EVENT_TOUCH_UP -> {
                touchUpCallback = propValue as KuiklyRenderCallback
                true
            }
            EVENT_SCREEN_FRAME -> {
                setScreenFrameCallback(propValue as? KuiklyRenderCallback)
                true
            }
            PROP_RIPPLE -> {
                setupRipple(propValue as String)
                true
            }
            PROP_RIPPLE_STATE -> {
                updateRippleState(propValue as String)
                true
            }
            else -> super.setProp(propKey, propValue)
        }
    }

    override fun resetProp(propKey: String): Boolean {
        nestedScrollDelegate = null
        touchListenerProxy = null
        currentActionState = -1
        return when (propKey) {
            EVENT_TOUCH_DOWN -> {
                touchDownCallback = null
                true
            }
            EVENT_TOUCH_MOVE -> {
                touchMoveCallback = null
                true
            }
            EVENT_TOUCH_UP -> {
                touchUpCallback = null
                true
            }
            EVENT_SCREEN_FRAME -> {
                setScreenFrameCallback(null)
                true
            }
            SCREEN_FRAME_PAUSE -> {
                screenFramePause = false
                true
            }
            PROP_RIPPLE -> {
                clearRipple()
                true
            }
            PROP_RIPPLE_STATE -> {
                // State is transient, just reset
                true
            }
            else -> super.resetProp(propKey)
        }
    }
    override fun setOnTouchListener(l: OnTouchListener?) {
        touchListenerProxy = object : OnTouchListener {
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                currentActionState = event.action
                tryFireTouchEvent(event)
                return l?.onTouch(v, event) ?: false
            }

        }
        super.setOnTouchListener(touchListenerProxy)
    }

    /*
    * 处理触摸事件分发逻辑，优先处理Compose事件，并在子View未消费时进行兜底处理[2,5](@ref)
    * @param event 触摸事件对象
    * @return 是否消费该事件
    */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!superTouch) {
            return super.dispatchTouchEvent(event)
        }

        // 以下是SuperTouch模式，意味着该View对应Compose的根节点，用于分发Touch事件给Compose
        tryFireTouchEvent(event)
        var handle = super.dispatchTouchEvent(event)
        if (handle) {
            // 子节点已经接接收了，后面的MOVE UP事件都能收到，不用兜底
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                touchDownConsumeOnce = false
            }
        } else {
            // 子节点未接收，需要在确保命中Compose可点击节点后确保消费Touch事件
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                if (touchDownConsumeOnce) {
                    // Compose有节点被点击，消费事件
                    handle = true
                    touchDownConsumeOnce = false
                } else {
                    // 不需要消费，则补一条Cancel取消Compose的点击态
                    tryFireCancelEvent(event)
                }
            } else {
                handle = true
            }
        }
        return handle
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val result = super.onTouchEvent(event)
        currentActionState = event.action
        val touchResult =
            if (superTouch) {
                false
            } else {
                tryFireTouchEvent(event)
            }
        return result || touchResult
    }

    private fun tryFireSuperTouchCanceled(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            superTouchCanceled = false
            nativeGestureViewHashCodeSet.clear()
            return false
        }
        var canceled = false
        if (superTouchCanceled) {
            canceled = true
        } else if (superTouch && touchConsumeByNative) {
            superTouchCanceled = true
            canceled = true
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            superTouchCanceled = false
            nativeGestureViewHashCodeSet.clear()
        }
        return canceled
    }

    private fun tryFireTouchEvent(event: MotionEvent): Boolean {
        tryFireSuperTouchCanceled(event)
        val action = event.actionMasked
        return when (action) {
            MotionEvent.ACTION_DOWN -> tryFireDownEvent(event)
            MotionEvent.ACTION_MOVE -> tryFireMoveEvent(event)
            MotionEvent.ACTION_UP -> tryFireUpEvent(event)
            MotionEvent.ACTION_POINTER_UP -> tryFireUpEvent(event)
            MotionEvent.ACTION_POINTER_DOWN -> tryFireDownEvent(event)
            MotionEvent.ACTION_CANCEL -> tryFireCancelEvent(event)
            else -> false
        }
    }
    override fun draw(canvas: Canvas) {
        val checkpoint: Int = if (hasCustomClipPath()) {
            canvas.save()
        } else {
            -1
        }
        drawCommonDecoration(canvas)
        super.draw(canvas)
        if (checkpoint != -1) {
            canvas.restoreToCount(checkpoint)
        }
        drawCommonForegroundDecoration(canvas)
    }

    private fun setWillDraw() {
        setWillNotDraw(false) // HRView有通用样式, 需要开启绘制
    }

    private fun tryFireDownEvent(motionEvent: MotionEvent): Boolean {
        if (touchDownCallback == null && touchMoveCallback == null &&
            touchUpCallback == null) {
            return false
        }
        touchDownCallback?.invoke(generateBaseParamsWithTouch(motionEvent, EVENT_TOUCH_DOWN))
        return true
    }

    private fun tryFireUpEvent(motionEvent: MotionEvent): Boolean {
        val upCallback = touchUpCallback ?: return false
        upCallback(generateBaseParamsWithTouch(motionEvent, EVENT_TOUCH_UP))
        return true
    }

    private fun tryFireCancelEvent(motionEvent: MotionEvent): Boolean {
        val upCallback = touchUpCallback ?: return false
        upCallback(generateBaseParamsWithTouch(motionEvent, EVENT_TOUCH_CANCEL))
        return true
    }

    private fun tryFireMoveEvent(motionEvent: MotionEvent): Boolean {
        val moveCallback = touchMoveCallback ?: return false
        moveCallback(generateBaseParamsWithTouch(motionEvent, EVENT_TOUCH_MOVE))
        return true
    }

    private fun generateBaseParamsWithTouch(motionEvent: MotionEvent, eventName: String): Map<String, Any> {
        val params = mapOf<String, Any>()
        val krRootView = krRootView() ?: return params
        val rootViewRect = IntArray(2)
        krRootView.getLocationInWindow(rootViewRect)
        val currentViewRect = IntArray(2)
        getLocationInWindow(currentViewRect)
        val x = motionEvent.x
        val y = motionEvent.y
        val touches = arrayListOf<Map<String, Any>>()
        // 遍历所有触摸点
        for (i in 0 until motionEvent.pointerCount) {
            // 获取触摸点的ID和坐标
            val pointerId = motionEvent.getPointerId(i)
            val x = motionEvent.getX(i)
            val y = motionEvent.getY(i)

            touches.add(mapOf(
                KRViewConst.X to kuiklyRenderContext.toDpF(x),
                KRViewConst.Y to kuiklyRenderContext.toDpF(y),
                PAGE_X to kuiklyRenderContext.toDpF((currentViewRect[0] - rootViewRect[0] + x)),
                PAGE_Y to kuiklyRenderContext.toDpF(currentViewRect[1] - rootViewRect[1] + y),
                POINTER_ID to pointerId
            ))
        }
        return (touches.first() ?: mapOf()).toMutableMap().apply {
            put(TOUCHES, touches)
            put(EVENT_ACTION, eventName)
            if (superTouch) {
                put(TIMESTAMP, motionEvent.eventTime)
                put(CONSUMED, if (touchConsumeByNative) {
                    1
                } else {
                    0
                })
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        setScreenFrameCallback(null)
    }

    private fun setScreenFrameCallback(callback: KuiklyRenderCallback?) {
        screenFrameCallback?.also {
            choreographer()?.removeFrameCallback(it)
        }
        if (callback != null) {
            screenFrameCallback = {
                if (!screenFramePause && this.screenFrameCallback != null) {
                    choreographer()?.postFrameCallback(this.screenFrameCallback)
                }
                callback.invoke(null)
            }
            choreographer()?.postFrameCallback(screenFrameCallback)
        } else {
            screenFrameCallback = null
        }
    }

    private fun choreographer(): Choreographer? {
        return try {
            Choreographer.getInstance()
        } catch (e: Throwable) {
            KuiklyRenderLog.e(VIEW_NAME, "get Choreographer.getInstance exception:${e}")
            null
        }
    }

    /**
     * Setup native ripple effect based on JSON configuration.
     * JSON format: {"enabled": true, "color": "#000000", "bounded": true, "pressedAlpha": 0.12, ...}
     */
    private fun setupRipple(configJson: String) {
        try {
            val config = JSONObject(configJson)
            val enabled = config.optBoolean("enabled", false)

            if (!enabled) {
                clearRipple()
                return
            }

            // Parse ripple configuration
            val colorStr = config.optString("color", "#000000")
            rippleColor = try {
                Color.parseColor(colorStr)
            } catch (e: Exception) {
                Color.BLACK
            }
            ripplePressedAlpha = config.optDouble("pressedAlpha", 0.12).toFloat()
            rippleBounded = config.optBoolean("bounded", true)
            rippleEnabled = true

            // Create RippleDrawable
            createRippleDrawable()
        } catch (e: Exception) {
            KuiklyRenderLog.e(VIEW_NAME, "setupRipple error: ${e.message}")
        }
    }

    /**
     * Create RippleDrawable and add it via an overlay View.
     * Using overlay View instead of foreground to avoid conflict with KRViewDecoration.
     */
    private fun createRippleDrawable() {
        // Remove existing overlay if any
        rippleOverlayView?.let { removeView(it) }

        // Calculate alpha-adjusted color
        val alpha = (ripplePressedAlpha * 255).toInt().coerceIn(0, 255)
        val alphaColor = Color.argb(
            alpha,
            Color.red(rippleColor),
            Color.green(rippleColor),
            Color.blue(rippleColor)
        )

        val colorStateList = ColorStateList.valueOf(alphaColor)

        // Create mask for bounded ripple
        val mask: ShapeDrawable? = if (rippleBounded) {
            val cornerRadius = getCornerRadius()
            val radii = FloatArray(8) { cornerRadius }
            val shape = RoundRectShape(radii, null, null)
            ShapeDrawable(shape).apply {
                paint.color = Color.WHITE
            }
        } else {
            null
        }

        rippleDrawable = RippleDrawable(colorStateList, null, mask)

        // Create overlay View for ripple
        val overlay = View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            background = rippleDrawable
            isClickable = false
            isFocusable = false
            // Disable touch interception so events pass through
            setOnTouchListener { _, _ -> false }
        }

        // Add overlay as the topmost child
        addView(overlay)
        rippleOverlayView = overlay
    }

    /**
     * Get corner radius from the view's background or CSS properties.
     * Returns the border radius if all corners are equal, otherwise returns 0.
     */
    private fun getCornerRadius(): Float {
        return try {
            val decorator = viewDecorator
            val radiusF = decorator?.borderRadiusF ?: KRViewDecoration.BORDER_RADIUS_UNSET_VALUE
            if (radiusF != KRViewDecoration.BORDER_RADIUS_UNSET_VALUE) {
                radiusF
            } else {
                0f
            }
        } catch (e: Exception) {
            0f
        }
    }

    /**
     * Update ripple state based on JSON state.
     * JSON format: {"state": "pressed|released|cancelled", "x": 100.0, "y": 50.0}
     */
    private fun updateRippleState(stateJson: String) {
        if (!rippleEnabled || rippleDrawable == null || rippleOverlayView == null) return

        try {
            val stateObj = JSONObject(stateJson)
            val state = stateObj.optString("state", "")

            when (state) {
                RIPPLE_STATE_PRESSED -> {
                    val x = stateObj.optDouble("x", 0.0).toFloat()
                    val y = stateObj.optDouble("y", 0.0).toFloat()

                    // Convert dp to px
                    val density = resources.displayMetrics.density
                    val pxX = x * density
                    val pxY = y * density

                    // Set hotspot for ripple origin
                    rippleDrawable?.setHotspot(pxX, pxY)

                    // Set pressed state to trigger ripple
                    rippleDrawable?.state = intArrayOf(
                        android.R.attr.state_pressed,
                        android.R.attr.state_enabled
                    )
                    rippleOverlayView?.invalidate()
                }
                RIPPLE_STATE_RELEASED, RIPPLE_STATE_CANCELLED -> {
                    // Release pressed state
                    rippleDrawable?.state = intArrayOf(android.R.attr.state_enabled)
                    rippleOverlayView?.invalidate()
                }
            }
        } catch (e: Exception) {
            KuiklyRenderLog.e(VIEW_NAME, "updateRippleState error: ${e.message}")
        }
    }

    /**
     * Clear and disable ripple effect.
     */
    private fun clearRipple() {
        rippleEnabled = false
        rippleDrawable = null
        rippleOverlayView?.let {
            removeView(it)
        }
        rippleOverlayView = null
    }

    override fun requestLayout() {
        super.requestLayout()
        logLayoutRequestIfNeeded()
    }


    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        logOnLayoutIfNeeded(changed)
    }


    private fun logLayoutRequestIfNeeded() {
        val parentView = parent as? KuiklyRenderView
        if (parentView != null && parentView.isDebugLogEnable() && requestLayoutLogCount < LAYOUT_MAX_LOG_COUNT && !parentView.requestedLayout) {
            var sb = "$this req=$isLayoutRequested\n"
            var viewParent = parent
            /**
             * flag 取值说明:
             * 0: 默认状态
             * 1: 存在不需要重新布局的View，且该View为根视图
             * 2: 存在不需要重新布局的View，且该View为子视图
             */
            var flag = 0
            while (viewParent != null) {
                val req = viewParent.isLayoutRequested
                if (req) {
                    sb += "${viewParent::class.simpleName} req=true\n"
                } else {
                    sb += "$viewParent req=false\n"
                    flag = if (viewParent.parent == null) 1 else 2
                }
                viewParent = viewParent.parent
            }
            KuiklyRenderLog.d("KuiklyRenderTracer", "root requestLayout $requestLayoutLogCount result=$sb flag=$flag ${Log.getStackTraceString(Throwable())}")
            requestLayoutLogCount++
            if (flag != 2) {
                parentView.requestedLayout = true
            }
        }
    }


    private fun logOnLayoutIfNeeded(changed: Boolean) {
        val parentView = parent as? KuiklyRenderView
        if (parentView != null && parentView.isDebugLogEnable() && onLayoutLogCount < LAYOUT_MAX_LOG_COUNT) {
            KuiklyRenderLog.d("KuiklyRenderTracer", "root onLayout $onLayoutLogCount, changed:$changed, left:$left, top:$top, right:$right, bottom:$bottom")
            onLayoutLogCount++
        }
        parentView?.requestedLayout = false
    }

    companion object {
        const val VIEW_NAME = "KRView"
        private const val COMPOSE_ROOT_TAG_ID = -0x7C03905E // random unique negative int
        private const val PAGE_X = "pageX"
        private const val PAGE_Y = "pageY"
        private const val TOUCHES = "touches"
        private const val POINTER_ID = "pointerId"
        private const val SCREEN_FRAME_PAUSE = "screenFramePause"
        private const val TIMESTAMP = "timestamp"
        private const val CONSUMED = "consumed"

        private const val EVENT_ACTION = "action"
        private const val SUPER_TOUCH = "superTouch"
        private const val EVENT_TOUCH_DOWN = "touchDown"
        private const val EVENT_TOUCH_MOVE = "touchMove"
        private const val EVENT_TOUCH_UP = "touchUp"
        private const val EVENT_TOUCH_CANCEL = "touchCancel"
        private const val EVENT_SCREEN_FRAME = "screenFrame"
        private const val LAYOUT_MAX_LOG_COUNT = 10

        // Ripple effect constants
        private const val PROP_RIPPLE = "ripple"
        private const val PROP_RIPPLE_STATE = "rippleState"
        private const val RIPPLE_STATE_PRESSED = "pressed"
        private const val RIPPLE_STATE_RELEASED = "released"
        private const val RIPPLE_STATE_CANCELLED = "cancelled"

        /**
         * Whether the given View is marked as a compose root (SuperTouch enabled).
         */
        internal fun isComposeRoot(view: View?): Boolean =
            view?.getTag(COMPOSE_ROOT_TAG_ID) == true
    }

}
