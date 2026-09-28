package com.tencent.kuikly.compose.extension

import com.tencent.kuikly.compose.ui.Modifier

/** The generic common prop the renderers read (CHANGES.md §61). */
const val LOOP_ANIMATION_PROP = "loopAnimation"

/** What a [LoopAnimation] moves. */
enum class LoopProperty(internal val wire: String) {
    /** Degrees, about the view's centre. */
    ROTATE("rotate"),
    SCALE_X("scaleX"),
    SCALE_Y("scaleY"),
    OPACITY("opacity"),
}

enum class LoopEasing(internal val wire: String) {
    LINEAR("linear"),
    EASE_IN_OUT("easeInOut"),
}

/**
 * Ronaq fork (CHANGES.md §61): a motion the native renderer repeats by itself for as long as the prop
 * stays set. Compose writes it once and asks for no frame, so the scene can rest (§59) while the motion
 * runs: an infinite Compose transition asks for a frame on every vsync, and a small ornament kept the
 * whole scene rendering 60 times a second.
 *
 * [legMillis] is one leg, [from] to [to]; with [reverse] a cycle is two legs, there and back. [phase] is
 * the fraction of a cycle the loop starts at. [pivotX] and [pivotY] are fractions of the view's size
 * (rotation and scale). A new value replaces the loop and starts at its own phase.
 *
 * The loop owns its property on its node: the node must carry no transform of its own (ROTATE, SCALE_*)
 * or alpha (OPACITY), or the renderer's write would fight it. The renderers advance it only while the
 * view can be seen, and never on the Kotlin thread. Not for motion that must end or report; use a
 * Compose animation or a player for that.
 */
data class LoopAnimation(
    val property: LoopProperty,
    val from: Float,
    val to: Float,
    val legMillis: Int,
    val reverse: Boolean = false,
    val easing: LoopEasing = LoopEasing.LINEAR,
    val phase: Float = 0f,
    val pivotX: Float = 0.5f,
    val pivotY: Float = 0.5f,
) {
    /**
     * `"<property> <from> <to> <legMillis> <reverse 0|1> <easing> <phase> <pivotX> <pivotY>"`: what every
     * renderer parses. Whole numbers are written without a fraction, so every platform writes the same text.
     */
    fun toProp(): String =
        "${property.wire} ${wire(from)} ${wire(to)} $legMillis ${if (reverse) 1 else 0} ${easing.wire} " +
            "${wire(phase)} ${wire(pivotX)} ${wire(pivotY)}"

    private fun wire(value: Float): String {
        val whole = value.toInt()
        return if (whole.toFloat() == value) whole.toString() else value.toString()
    }
}

/** [animation]'s wire value, or the empty value that stops a loop. */
fun loopAnimationProp(animation: LoopAnimation?): String = animation?.toProp() ?: ""

/**
 * Runs [animation] on this node's native view (CHANGES.md §61); null stops it and puts the property back
 * at rest (rotation 0, scale 1, opacity 1). Keep the modifier on the node and pass null to stop: the prop
 * is written as the empty value, which every renderer reads as "stop".
 */
fun Modifier.loopAnimation(animation: LoopAnimation?): Modifier = setProp(LOOP_ANIMATION_PROP, loopAnimationProp(animation))
