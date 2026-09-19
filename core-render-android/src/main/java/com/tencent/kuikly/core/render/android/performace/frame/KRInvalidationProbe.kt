package com.tencent.kuikly.core.render.android.performace.frame

import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import com.tencent.kuikly.core.render.android.const.KRCssConst
import com.tencent.kuikly.core.render.android.css.ktx.getViewData

/**
 * Diagnostic probe: per Choreographer frame, which native views the Kuikly side wrote a
 * prop to, which views were invalidated, whether a requestLayout reached the root, and
 * which views actually re-recorded their display list (draw() ran).
 *
 * Off by default; the host flips [enabled]. Every hook is a volatile read when off.
 * Output goes to logcat under tag "KRProbe", one line per frame that did anything.
 */
object KRInvalidationProbe {

    private const val TAG = "KRProbe"
    private const val STACK_EVERY_MS = 1000L
    private const val TOP_N = 12

    @Volatile
    var enabled = false
        set(value) {
            if (field == value) return
            field = value
            if (value) start() else stop()
        }

    private var frameNo = 0
    private var frameStartMs = 0L
    private val props = HashMap<String, Int>()
    private val propDetails = ArrayList<String>()
    private val invalidated = HashMap<String, Int>()
    private val drawn = HashMap<String, Int>()
    private var rootDraws = 0
    private var layoutRequests = 0
    private var lastPropStackMs = 0L
    private var lastLayoutStackMs = 0L
    private var lastInvalStackMs = 0L

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            flush()
            if (enabled) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun start() {
        frameNo = 0
        frameStartMs = SystemClock.uptimeMillis()
        Log.d(TAG, "probe on")
        Choreographer.getInstance().postFrameCallback(callback)
    }

    private fun stop() {
        Log.d(TAG, "probe off")
        Choreographer.getInstance().removeFrameCallback(callback)
    }

    /** Called at the start of every [setCommonProp]. */
    fun onProp(view: View, key: String, value: Any) {
        bump(props, key)
        if (key == KRCssConst.FRAME || key == KRCssConst.TRANSFORM || key == KRCssConst.OPACITY) {
            if (propDetails.size < TOP_N) {
                val old = if (key == KRCssConst.FRAME) " was=${view.layoutParams?.let { lp ->
                    (lp as? ViewGroup.MarginLayoutParams)?.let { "${it.leftMargin},${it.topMargin},${it.width},${it.height}" } }}" else ""
                propDetails.add("$key ${describe(view)}$old -> $value")
            }
            val now = SystemClock.uptimeMillis()
            if (key == KRCssConst.FRAME && now - lastPropStackMs > STACK_EVERY_MS) {
                lastPropStackMs = now
                Log.d(TAG, "frame-prop stack for ${describe(view)}:\n" + stack())
            }
        }
    }

    /** Called from the root's onDescendantInvalidated with the view that was invalidated. */
    fun onInvalidated(target: View) {
        bump(invalidated, describe(target))
        val now = SystemClock.uptimeMillis()
        if (now - lastInvalStackMs > STACK_EVERY_MS) {
            lastInvalStackMs = now
            Log.d(TAG, "invalidate stack for ${describe(target)}:\n" + stack())
        }
    }

    /** Called when a requestLayout reaches the root Kuikly view. */
    fun onLayoutRequested() {
        layoutRequests++
        val now = SystemClock.uptimeMillis()
        if (now - lastLayoutStackMs > STACK_EVERY_MS) {
            lastLayoutStackMs = now
            Log.d(TAG, "requestLayout stack:\n" + stack())
        }
    }

    /** Called from a view's draw(): the view is re-recording its display list. */
    fun onDraw(view: View) {
        bump(drawn, view.javaClass.simpleName)
    }

    fun onRootDraw() {
        rootDraws++
    }

    private fun flush() {
        frameNo++
        if (props.isEmpty() && invalidated.isEmpty() && drawn.isEmpty() && layoutRequests == 0 && rootDraws == 0) return
        val t = SystemClock.uptimeMillis() - frameStartMs
        val sb = StringBuilder()
        sb.append("f=").append(frameNo).append(" t=").append(t)
        sb.append(" layoutReq=").append(layoutRequests).append(" rootDraw=").append(rootDraws)
        sb.append(" props=").append(props.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}:${it.value}" })
        sb.append(" drawn=").append(drawn.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}:${it.value}" })
        sb.append(" inval=").append(invalidated.values.sum()).append("{")
        sb.append(invalidated.entries.sortedByDescending { it.value }.take(TOP_N).joinToString(",") { "${it.key}:${it.value}" })
        sb.append("}")
        Log.d(TAG, sb.toString())
        for (d in propDetails) Log.d(TAG, "  $d")
        props.clear(); propDetails.clear(); invalidated.clear(); drawn.clear()
        rootDraws = 0; layoutRequests = 0
    }

    private fun bump(map: HashMap<String, Int>, key: String) {
        map[key] = (map[key] ?: 0) + 1
    }

    private fun describe(view: View): String {
        val own = runCatching { view.getViewData<String>(KRCssConst.TEST_TAG) }.getOrNull()
        val sb = StringBuilder(view.javaClass.simpleName)
        if (own != null) {
            sb.append('[').append(own).append(']')
        } else {
            var p = view.parent
            var hops = 1
            while (p is View && hops <= 8) {
                val tag = runCatching { (p as View).getViewData<String>(KRCssConst.TEST_TAG) }.getOrNull()
                if (tag != null) { sb.append("(<").append(hops).append(' ').append(tag).append(')'); break }
                p = p.parent; hops++
            }
        }
        sb.append('#').append(Integer.toHexString(System.identityHashCode(view)))
        sb.append(' ').append(view.width).append('x').append(view.height)
        return sb.toString()
    }

    private fun stack(): String {
        val frames = Throwable().stackTrace
        val sb = StringBuilder()
        var n = 0
        for (f in frames) {
            val s = f.toString()
            if (s.contains("KRInvalidationProbe")) continue
            sb.append("    at ").append(s).append('\n')
            if (++n >= 70) break
        }
        return sb.toString()
    }
}
