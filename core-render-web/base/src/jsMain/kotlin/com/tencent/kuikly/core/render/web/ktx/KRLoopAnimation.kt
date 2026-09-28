package com.tencent.kuikly.core.render.web.ktx

import org.w3c.dom.HTMLElement

/**
 * Ronaq fork (CHANGES.md §61): the generic `loopAnimation` common prop on the web — a motion the browser
 * repeats by itself (`Element.animate`, infinite iterations) for as long as the prop is set, so Compose
 * writes it once and asks for no frame. Browsers throttle a hidden tab's animations themselves.
 *
 * The value is `"<rotate|scaleX|scaleY|opacity> <from> <to> <legMillis> <reverse 0|1> <linear|easeInOut>
 * <phase> <pivotX> <pivotY>"`; the empty value cancels the loop, and a malformed one is ignored.
 */
internal const val LOOP_ANIMATION = "loopAnimation"

private const val LOOP_STATE = "krLoopAnimation"

internal fun HTMLElement.setLoopAnimation(value: String?) {
    val self = asDynamic()
    val running = self[LOOP_STATE]
    if (running != null && running != undefined) {
        running.cancel()
        self[LOOP_STATE] = null
    }
    if (value.isNullOrEmpty()) return
    val p = value.split(' ')
    if (p.size != 9) return
    val numbers = listOf(p[1], p[2], p[3], p[6], p[7], p[8]).map { it.toDoubleOrNull() ?: return }
    val leg = numbers[2]
    if (leg <= 0.0) return
    val first = loopKeyframe(p[0], numbers[0]) ?: return
    val last = loopKeyframe(p[0], numbers[1]) ?: return
    val reverse = p[4] == "1"
    val cycle = if (reverse) 2 * leg else leg
    val options: dynamic = js("{}")
    options.duration = leg
    options.iterations = Double.POSITIVE_INFINITY
    options.direction = if (reverse) "alternate" else "normal"
    options.easing = if (p[5] == "easeInOut") "ease-in-out" else "linear"
    // A negative delay starts the loop part-way through its cycle: the phase.
    options.delay = -(numbers[3] * cycle)
    style.asDynamic().transformOrigin = "${numbers[4] * 100}% ${numbers[5] * 100}%"
    self[LOOP_STATE] = self.animate(arrayOf(first, last), options)
}

/** One keyframe of [property] at [value], or null for a property the prop does not know. */
private fun loopKeyframe(property: String, value: Double): dynamic {
    val frame: dynamic = js("{}")
    when (property) {
        "rotate" -> frame.transform = "rotate(${value}deg)"
        "scaleX" -> frame.transform = "scaleX($value)"
        "scaleY" -> frame.transform = "scaleY($value)"
        "opacity" -> frame.opacity = value.toString()
        else -> return null
    }
    return frame
}
