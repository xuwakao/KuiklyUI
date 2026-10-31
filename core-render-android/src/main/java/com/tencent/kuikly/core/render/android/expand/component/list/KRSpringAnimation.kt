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

package com.tencent.kuikly.core.render.android.expand.component.list

import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator

internal class KRSpringAnimation(
    startValue: Float,
    val endValue: Float,
    velocity: Float,
    val stiffness: Float,
    dampingRatio: Float
): KRScrollAnimation() {
    // Exact damped motion remains stable at the existing 64ms pause cap. Euler
    // integration at that step diverges for short-duration/stiff springs.
    private val motion = KRSpringMotion(startValue, endValue, velocity, stiffness, dampingRatio)
    private var lastTime = 0L

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 100000L // Long enough
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val currentTime = System.nanoTime()
            if (lastTime == 0L) {
                lastTime = currentTime
                return@addUpdateListener
            }
            val dt = ((currentTime - lastTime).coerceAtLeast(0L) / 1_000_000_000.0)
                .coerceAtMost(0.064)
            lastTime = currentTime
            motion.advance(dt)
            onUpdate(motion.value)

            // End condition: close enough and slow enough
            if (kotlin.math.abs(motion.value - endValue) < 0.5f && kotlin.math.abs(motion.velocity) < 10f) {
                cancel()
                onUpdate(endValue)
            }
        }
        addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationCancel(animation: android.animation.Animator) {
                onEnd()
            }

            override fun onAnimationEnd(animation: android.animation.Animator) {
                onEnd()
            }

            override fun onAnimationRepeat(animation: android.animation.Animator) {}
            override fun onAnimationStart(animation: android.animation.Animator) {}
        })
    }

    override var onUpdate: (Float) -> Unit = {}
    override var onEnd: () -> Unit = {}

    override fun start() {
        lastTime = 0L
        animator.start()
    }

    override fun cancel() {
        animator.cancel()
    }
}