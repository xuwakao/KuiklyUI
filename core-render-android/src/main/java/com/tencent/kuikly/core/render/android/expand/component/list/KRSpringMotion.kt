package com.tencent.kuikly.core.render.android.expand.component.list

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Exact solution of x'' + 2*zeta*omega*x' + omega^2*x = 0, relative to the target.
 * No per-frame allocation or integration-step instability; preserves spring and initial velocity.
 */
internal class KRSpringMotion(
    start: Float,
    private val target: Float,
    initialVelocity: Float,
    stiffness: Float,
    dampingRatio: Float
) {
    private val omega = sqrt(stiffness.toDouble())
    private val zeta = dampingRatio.toDouble()
    private var displacement = start.toDouble() - target
    private var speed = initialVelocity.toDouble()
    val value: Float get() = (target + displacement).toFloat()
    val velocity: Float get() = speed.toFloat()

    fun advance(seconds: Double) {
        if (seconds <= 0.0) return
        val x = displacement
        val v = speed
        val damping = zeta * omega
        when {
            zeta < 1.0 -> {
                val frequency = omega * sqrt(1.0 - zeta * zeta)
                val decay = exp(-damping * seconds)
                val c = cos(frequency * seconds)
                val s = sin(frequency * seconds)
                displacement = decay * (x * c + (v + damping * x) / frequency * s)
                speed = decay * (v * c - (damping * v + omega * omega * x) / frequency * s)
            }
            zeta == 1.0 -> {
                val decay = exp(-omega * seconds)
                val b = v + omega * x
                displacement = decay * (x + b * seconds)
                speed = decay * (v - omega * b * seconds)
            }
            else -> {
                val root = omega * sqrt(zeta * zeta - 1.0)
                val r1 = -damping + root
                val r2 = -damping - root
                val a = (v - r2 * x) / (r1 - r2)
                val b = x - a
                val first = a * exp(r1 * seconds)
                val second = b * exp(r2 * seconds)
                displacement = first + second
                speed = r1 * first + r2 * second
            }
        }
    }
}
