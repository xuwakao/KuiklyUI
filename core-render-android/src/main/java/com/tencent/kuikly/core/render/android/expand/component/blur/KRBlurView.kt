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

package com.tencent.kuikly.core.render.android.expand.component.blur

import android.app.ActivityManager
import android.os.SystemClock
import com.tencent.kuikly.core.render.android.expand.component.KRView
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnPreDrawListener
import android.widget.FrameLayout
import com.tencent.kuikly.core.render.android.css.ktx.toNumberFloat
import com.tencent.kuikly.core.render.android.expand.component.blur.SizeScaler.Companion.ROUNDING_VALUE
import com.tencent.kuikly.core.render.android.export.IKuiklyRenderViewExport

/**
 * Created by kam on 2023/3/23.
 */
class KRBlurView(context: Context) : FrameLayout(context), IKuiklyRenderViewExport {

    private var capturedSource: KRView? = null
    private var capturedRevision = -1L
    private var capturedSourceX = 0
    private var capturedSourceY = 0
    private var capturedSourceWidth = 0
    private var capturedSourceHeight = 0
    private var capturedRadius = Float.NaN
    private val sourceLocation = IntArray(2)
    private var internalBitmap: Bitmap? = null
    private var internalCanvas: BlurViewCanvas? = null
    private var initialized = false
    private val rootLocation = IntArray(2)
    private val blurViewLocation = IntArray(2)
    private var blurRadius = 5f
    private val blur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        RenderEffectBlur(context)
    } else {
        RenderScriptBlur(context)
    }
    private var adaptiveRefresh = false
    private var lastCaptureMs = 0L
    private val baseCaptureInterval by lazy {
        val memory = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        when {
            memory.isLowRamDevice || memory.memoryClass <= 128 -> 100L
            Build.VERSION.SDK_INT < 31 || memory.memoryClass < 256 -> 66L
            else -> 33L
        }
    }
    private var captureInterval = 0L
    private var snapshotOnly = false
    private var geometryChangedAt = 0L
    private var captureX = Int.MIN_VALUE
    private var captureY = Int.MIN_VALUE
    private val currentLocation = IntArray(2)

    private var blurRootViewList = mutableListOf<View>()

    private var targetBlurViewTags = mutableListOf<Int>()

    private var blurOtherLayer = false
    private val otherLayerPaint by lazy(LazyThreadSafetyMode.NONE) {
        Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OVER)
        }
    }

    private val preDrawListener = OnPreDrawListener {
        if (isShown) {
            val now = SystemClock.uptimeMillis()
            getLocationOnScreen(currentLocation)
            if (currentLocation[0] != captureX || currentLocation[1] != captureY) {
                captureX = currentLocation[0]; captureY = currentLocation[1]
                capturedSource = null
                geometryChangedAt = now
                snapshotOnly = false
                if (adaptiveRefresh) postInvalidateDelayed(120L)
            }
            val settled = !adaptiveRefresh || now - geometryChangedAt >= 100L
            if (settled && !snapshotOnly && (!adaptiveRefresh || now - lastCaptureMs >= captureInterval)) {
                val start = SystemClock.elapsedRealtimeNanos()
                android.os.Trace.beginSection("KRBlurView.capture")
                try { updateBlurBitmap() } finally { android.os.Trace.endSection() }
                lastCaptureMs = now
                if (adaptiveRefresh) {
                    val costMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000L
                    // If software capture consumes half a 60Hz frame, cache this
                    // backdrop rather than inserting long frames throughout the sheet.
                    // Foreground keeps updating. A new attachment/geometry recaptures.
                    snapshotOnly = costMs > 8L
                    captureInterval = maxOf(baseCaptureInterval, costMs * 8).coerceAtMost(200L)
                }
            }
        }
        true
    }

    init {
        setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        snapshotOnly = false
        captureX = Int.MIN_VALUE
        captureY = Int.MIN_VALUE
        krRootView()?.viewTreeObserver?.also {
            it.removeOnPreDrawListener(preDrawListener)
            it.addOnPreDrawListener(preDrawListener)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        capturedSource = null
        krRootView()?.viewTreeObserver?.removeOnPreDrawListener(preDrawListener)
    }

    override fun setProp(propKey: String, propValue: Any): Boolean {
        return when (propKey) {
            "adaptiveRefresh" -> {
                adaptiveRefresh = propValue.toNumberFloat() != 0f
                captureInterval = if (adaptiveRefresh) baseCaptureInterval else 0L
                true
            }
            PROP_BLUR_RADIUS -> blurRadius(propValue)
            PROP_TARGET_BLUR_VIEW_NATIVE_REFS -> blurViewTag(propValue)
            PROP_BLUR_OTHER_LAYER -> blurOtherLayer(propValue)
            else ->super.setProp(propKey, propValue)
        }
    }

    override fun draw(canvas: Canvas) {
        // Stop at the actual backdrop boundary, including ancestors' later siblings.
        // Never toggle visibility: doing so requests a new measure/layout every capture.
        if (canvas is BlurViewCanvas && canvas.stopAt === this) throw StopBackdropCapture
        if (!initialized) {
            super.draw(canvas)
        } else
            if (canvas !is BlurViewCanvas) {
            internalBitmap?.also {
                val scaleFactorW = width.toFloat() / it.width
                val scaleFactorH = height.toFloat() / it.height
                canvas.save()
                canvas.scale(scaleFactorW, scaleFactorH)
                blur.draw(canvas, it)
                canvas.restore()
            }
            super.draw(canvas)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateBlurViewSize(w, h)
        snapshotOnly = false
        geometryChangedAt = SystemClock.uptimeMillis()
        if (adaptiveRefresh) postInvalidateDelayed(120L)
    }

    override fun onDestroy() {
        super.onDestroy()
        blur.destroy()
    }

    private fun blurRadius(propValue: Any): Boolean {
        blurRadius = propValue.toNumberFloat() * 2f
        return true
    }

    private fun blurViewTag(propValue: Any): Boolean {
        val tags = (propValue as String).split("|").mapNotNull { it.toIntOrNull() }.filter { it != -1 }
        if (tags == targetBlurViewTags) return true
        capturedSource = null
        targetBlurViewTags.clear()
        snapshotOnly = false
        lastCaptureMs = 0L
        if (adaptiveRefresh) postInvalidateDelayed(120L)
        targetBlurViewTags.addAll(tags)
        return true
    }

    private fun blurOtherLayer(propValue: Any): Boolean {
        blurOtherLayer = (propValue as Int) == 1
        return true
    }

    private fun updateBlurViewSize(width: Int, height: Int) {
        capturedSource = null
        val sizeScaler = SizeScaler(20f)
        if (sizeScaler.isZeroSized(width, height)) {
            return
        }

        val bitmapSize = sizeScaler.scale(width, height)
        val bitmap = Bitmap.createBitmap(bitmapSize.first, bitmapSize.second, Bitmap.Config.ARGB_8888)
        internalCanvas = BlurViewCanvas(bitmap)
        internalBitmap = bitmap
        initialized = true
    }

    private fun updateBlurBitmap() {
        if (!initialized) {
            return
        }

        val bitmap = internalBitmap ?: return
        val canvas = internalCanvas ?: return
        // Hardware capture is safe only for an explicit subtree that cannot contain
        // this effect. Recording an ancestor would create a recursive RenderNode graph.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isHardwareAccelerated &&
            adaptiveRefresh && targetBlurViewTags.size == 1 && !blurOtherLayer) {
            val source = kuiklyRenderContext?.getView(targetBlurViewTags.single())
            var ancestor: View? = this
            var recursive = false
            while (ancestor != null) {
                if (ancestor === source) recursive = true
                ancestor = ancestor.parent as? View
            }
            if (source != null && source.isShown && source.width > 0 && source.height > 0 && !recursive) {
                val tracked = source as? KRView
                tracked?.trackBackdropInvalidations = true
                source.getLocationOnScreen(sourceLocation)
                if (tracked != null && capturedSource === tracked &&
                    capturedRevision == tracked.backdropRevision && !source.isDirty &&
                    !source.hasTransientState() && capturedRadius == blurRadius &&
                    capturedSourceWidth == source.width && capturedSourceHeight == source.height &&
                    capturedSourceX == sourceLocation[0] && capturedSourceY == sourceLocation[1]) return
                val revisionBeforeDraw = tracked?.backdropRevision ?: -1L
                android.os.Trace.beginSection("KRBlurView.hardwareCapture")
                try {
                    (blur as RenderEffectBlur).capture(bitmap.width, bitmap.height, blurRadius) { recording ->
                        val checkpoint = recording.save()
                        try {
                            setupInternalCanvasMatrix(source, bitmap, recording)
                            source.draw(recording)
                        } finally { recording.restoreToCount(checkpoint) }
                    }
                } finally { android.os.Trace.endSection() }
                capturedSource = tracked
                capturedRevision = revisionBeforeDraw
                capturedSourceWidth = source.width
                capturedSourceHeight = source.height
                capturedSourceX = sourceLocation[0]
                capturedSourceY = sourceLocation[1]
                capturedRadius = blurRadius
                return
            }
        }
        capturedSource = null
        bitmap.eraseColor(Color.TRANSPARENT)
        android.os.Trace.beginSection("KRBlurView.captureContent")
        try {
            getBlurRootViewList().forEach { krRootView ->
                drawBlurContent(canvas, bitmap, krRootView)
            }
        } finally { android.os.Trace.endSection() }
        android.os.Trace.beginSection("KRBlurView.filter")
        try { internalBitmap = blur.blur(bitmap, blurRadius) }
        finally { android.os.Trace.endSection() }
    }

    private fun drawBlurContent(canvas: BlurViewCanvas, bitmap: Bitmap, rootView: View) {
        if (targetBlurViewTags.isNotEmpty()) {
            drawBlurContentWithTags(targetBlurViewTags, canvas, bitmap, rootView)
        } else {
            drawBlurContentWithRootView(canvas, bitmap, rootView)
        }
    }

    private fun drawBlurContentWithRootView(canvas: BlurViewCanvas, bitmap: Bitmap, rootView: View) {
        val checkpoint = canvas.save()
        canvas.stopAt = this
        try {
            setupInternalCanvasMatrix(rootView, bitmap, canvas)
            try { rootView.draw(canvas) } catch (_: StopBackdropCaptureException) { }
            tryDrawTextureView(rootView, canvas)
        } finally {
            canvas.stopAt = null
            canvas.restoreToCount(checkpoint)
        }
    }

    private fun tryDrawTextureView(rootView: View, canvas: Canvas) {
        if (!blurOtherLayer) {
            return
        }
        findTextureView(rootView)?.bitmap?.also {
            canvas.drawBitmap(it, 0f, 0f, otherLayerPaint)
        }
    }

    private fun findTextureView(view: View?): TextureView? {
        if (view is TextureView) {
            return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val tp = findTextureView(view.getChildAt(i))
                if (tp != null) {
                    return tp
                }
            }
        }
        return null
    }

    private fun drawBlurContentWithTags(
        tags: List<Int>,
        canvas: Canvas,
        bitmap: Bitmap,
        rootView: View
    ) {
        tags.forEach {
            findViewWithTag(it, rootView)?.also { blurView ->
                canvas.save()
                setupInternalCanvasMatrix(blurView, bitmap, canvas)
                blurView.draw(canvas)
                canvas.restore()
            }
        }
    }

    private fun findViewWithTag(tag: Int, rootView: View): View? {
        var view = kuiklyRenderContext?.getView(tag)
        if (view == null) {
            view = rootView.findViewWithTag<View>(tag)
        }
        return view
    }

    /**
     * Set up matrix to draw starting from blurView's position
     */
    private fun setupInternalCanvasMatrix(rootView: View, bitmap: Bitmap, canvas: Canvas) {
        rootView.getLocationOnScreen(rootLocation)
        getLocationOnScreen(blurViewLocation)
        val left = blurViewLocation[0] - rootLocation[0]
        val top = blurViewLocation[1] - rootLocation[1]

        val scaleFactorW = width.toFloat() / bitmap.width
        val scaleFactorH = height.toFloat() / bitmap.height
        val scaledLeftPosition = -left / scaleFactorW
        val scaledTopPosition = -top / scaleFactorH

        canvas.translate(scaledLeftPosition, scaledTopPosition)
        canvas.scale(1 / scaleFactorW, 1 / scaleFactorH)
    }

    private fun getBlurRootViewList(): List<View> {
        blurRootViewList.clear()
        val activityDecorView = getActivityDecorView()
        activityDecorView?.also {
            blurRootViewList.add(it)
        }
        // 获取Blur所处顶层View，一般情况是与Activity的DecorView是同一个
        // 但是如果在Dialog场景下, Dialog会有独立的DecorView, 此时两者不等
        val blurViewRootView = getBlurViewDecorView()
        if (blurViewRootView != null && blurViewRootView != activityDecorView) {
            blurRootViewList.add(blurViewRootView)
        }
        return blurRootViewList
    }

    private fun getActivityDecorView(): View? {
        return activity?.window?.decorView
    }

    private fun getBlurViewDecorView(): View? {
        return rootView
    }

    companion object {
        const val VIEW_NAME = "KRBlurView"
        private const val PROP_BLUR_RADIUS = "blurRadius"
        private const val PROP_TARGET_BLUR_VIEW_NATIVE_REFS = "targetBlurViewNativeRefs"
        private const val PROP_BLUR_OTHER_LAYER = "blurOtherLayer"
    }
}

/**
 * Scales width and height by [scaleFactor],
 * and then rounds the size proportionally so the width is divisible by [ROUNDING_VALUE]
 */
private class SizeScaler(private val scaleFactor: Float) {

    fun scale(width: Int, height: Int): Pair<Int, Int> {
        val nonRoundedScaledWidth = downscaleSize(width.toFloat())
        val scaledWidth = roundSize(nonRoundedScaledWidth)
        //Only width has to be aligned to ROUNDING_VALUE
        val roundingScaleFactor = width.toFloat() / scaledWidth
        //Ceiling because rounding or flooring might leave empty space on the View's bottom
        val scaledHeight = Math.ceil((height / roundingScaleFactor).toDouble()).toInt()
        return Pair(scaledWidth, scaledHeight)
    }

    fun isZeroSized(measuredWidth: Int, measuredHeight: Int): Boolean {
        return downscaleSize(measuredHeight.toFloat()) == 0 || downscaleSize(measuredWidth.toFloat()) == 0
    }

    /**
     * Rounds a value to the nearest divisible by [.ROUNDING_VALUE] to meet stride requirement
     */
    private fun roundSize(value: Int): Int {
        return if (value % ROUNDING_VALUE == 0) {
            value
        } else {
            value - value % ROUNDING_VALUE + ROUNDING_VALUE
        }
    }

    private fun downscaleSize(value: Float): Int {
        return Math.ceil((value / scaleFactor).toDouble()).toInt()
    }

    companion object {
        // Bitmap size should be divisible by ROUNDING_VALUE to meet stride requirement.
        // This will help avoiding an extra bitmap allocation when passing the bitmap to RenderScript for blur.
        // Usually it's 16, but on Samsung devices it's 64 for some reason.
        private const val ROUNDING_VALUE = 64
    }
}

private class BlurViewCanvas(bitmap: Bitmap) : Canvas(bitmap) {
    var stopAt: KRBlurView? = null
}
private class StopBackdropCaptureException : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}
private val StopBackdropCapture = StopBackdropCaptureException()