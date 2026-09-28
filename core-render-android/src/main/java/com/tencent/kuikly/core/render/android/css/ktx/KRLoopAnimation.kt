package com.tencent.kuikly.core.render.android.css.ktx

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.util.Property
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import com.tencent.kuikly.core.render.android.adapter.KuiklyRenderLog
import com.tencent.kuikly.core.render.android.expand.visibility.KRVisibility

/**
 * Ronaq fork (CHANGES.md §61): the generic `loopAnimation` common prop on Android — a motion the view's
 * own property animator repeats for as long as the prop is set. Compose writes it once and asks for no
 * frame; each animator frame sets one RenderNode property (rotation, scale, alpha), which re-records
 * nothing, and the Kuikly context thread is not involved. It runs only while the view can be seen
 * (the renderer's predicate, [KRVisibility.visibleOrUnknown]) and watches only its own view, so the
 * saving on unwatched leaves (§57) is kept.
 */
internal const val LOOP_ANIMATION = "loopAnimation"

private const val LOOP_STATE = "loopAnimationState"
private const val TAG = "KRLoopAnimation"

/** One loop's parsed value (the wire form is [com.tencent.kuikly.compose.extension.LoopAnimation.toProp]'s). */
internal data class LoopSpec(
    val property: Property,
    val from: Float,
    val to: Float,
    val legMillis: Long,
    val reverse: Boolean,
    val easeInOut: Boolean,
    val phase: Float,
    val pivotX: Float,
    val pivotY: Float,
) {
    /** What the loop moves, and where that property rests when the loop is removed. */
    enum class Property(val wire: String, val rest: Float) {
        ROTATE("rotate", 0f),
        SCALE_X("scaleX", 1f),
        SCALE_Y("scaleY", 1f),
        OPACITY("opacity", 1f),
    }

    /** One cycle: a leg, or two for a loop that goes there and back. */
    val cycleMillis: Long get() = if (reverse) 2 * legMillis else legMillis

    companion object {
        /**
         * `"<property> <from> <to> <legMillis> <reverse 0|1> <linear|easeInOut> <phase> <pivotX> <pivotY>"`,
         * or null for the empty value, a malformed one, an unknown property or a leg of no length.
         */
        fun parse(value: String): LoopSpec? {
            val p = value.split(' ')
            if (p.size != 9) return null
            val property = Property.entries.firstOrNull { it.wire == p[0] } ?: return null
            val numbers = listOf(p[1], p[2], p[3], p[6], p[7], p[8]).map { it.toFloatOrNull() ?: return null }
            val leg = numbers[2].toLong()
            if (leg <= 0L) return null
            return LoopSpec(
                property, numbers[0], numbers[1], leg, p[4] == "1", p[5] == "easeInOut",
                numbers[3], numbers[4], numbers[5],
            )
        }
    }
}

/** What a loop's animator does next, from what the view is and what the animator is doing. */
internal object LoopRunRule {
    enum class Action { NONE, START, PAUSE, RESUME, STOP }

    /**
     * [removed]: the prop was removed or the view reused — stop, and put the property back at rest.
     * Otherwise a visible loop starts (at its phase) or resumes; an unseen one pauses where it is, and
     * one never started waits for its view to be seen.
     */
    fun decide(removed: Boolean, visible: Boolean, started: Boolean, paused: Boolean): Action = when {
        removed -> Action.STOP
        visible && !started -> Action.START
        visible && paused -> Action.RESUME
        !visible && started && !paused -> Action.PAUSE
        else -> Action.NONE
    }
}

/** A view's running loop: its animator and the listeners that keep it in step with the view. */
private class LoopState(
    val view: View,
    val spec: LoopSpec,
    val animator: ObjectAnimator,
) : KRVisibility.Observer, View.OnLayoutChangeListener, View.OnAttachStateChangeListener {

    override fun onViewTreeChanged(changes: Int) {
        if (changes and KRVisibility.CHANGE_VISIBILITY != 0) update()
    }

    override fun watches(view: View): Boolean = view === this.view

    override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
        applyPivot()
        update()
    }

    override fun onViewAttachedToWindow(v: View) = update()

    override fun onViewDetachedFromWindow(v: View) = update()

    fun applyPivot() {
        view.pivotX = spec.pivotX * view.width
        view.pivotY = spec.pivotY * view.height
    }

    fun update() {
        // A view not laid out yet is not known to be unseen: the loop starts, as it would on the glass.
        val visible = KRVisibility.visibleOrUnknown(view) != false
        when (LoopRunRule.decide(false, visible, animator.isStarted, animator.isPaused)) {
            LoopRunRule.Action.START -> {
                animator.start()
                animator.currentPlayTime = (spec.phase * spec.cycleMillis).toLong()
            }
            LoopRunRule.Action.RESUME -> animator.resume()
            LoopRunRule.Action.PAUSE -> animator.pause()
            else -> Unit
        }
    }

    fun stop() {
        animator.removeAllListeners()
        animator.cancel()
        KRVisibility.removeObserver(this)
        view.removeOnLayoutChangeListener(this)
        view.removeOnAttachStateChangeListener(this)
        view.propertyOf(spec.property).set(view, spec.property.rest)
    }
}

private fun View.propertyOf(property: LoopSpec.Property): Property<View, Float> = when (property) {
    LoopSpec.Property.ROTATE -> View.ROTATION
    LoopSpec.Property.SCALE_X -> View.SCALE_X
    LoopSpec.Property.SCALE_Y -> View.SCALE_Y
    LoopSpec.Property.OPACITY -> View.ALPHA
}

/**
 * Sets this view's loop to [value], or stops it (null or empty). A new value replaces the loop and
 * starts at its own phase; a malformed one leaves the view at rest, with one log line.
 */
internal fun View.setLoopAnimation(value: String?) {
    removeViewData<LoopState>(LOOP_STATE)?.stop()
    if (value.isNullOrEmpty()) return
    val spec = LoopSpec.parse(value) ?: run {
        KuiklyRenderLog.d(TAG, "loopAnimation ignored: $value")
        return
    }
    val animator = ObjectAnimator.ofFloat(this, propertyOf(spec.property), spec.from, spec.to).apply {
        duration = spec.legMillis
        repeatCount = ValueAnimator.INFINITE
        repeatMode = if (spec.reverse) ValueAnimator.REVERSE else ValueAnimator.RESTART
        interpolator = if (spec.easeInOut) AccelerateDecelerateInterpolator() else LinearInterpolator()
    }
    val state = LoopState(this, spec, animator)
    putViewData(LOOP_STATE, state)
    KRVisibility.addObserver(state)
    addOnLayoutChangeListener(state)
    addOnAttachStateChangeListener(state)
    state.applyPivot()
    state.update()
}
