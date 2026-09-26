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

package com.tencent.kuikly.core.render.android.expand.component.image

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Ronaq fork (CHANGES.md §40): how many pixels an image view covers on screen — the size it asks
 * its adapter to decode at — and when it has grown enough to ask again. View-free, for the JVM
 * tests; `KRImageView` feeds it its frame and the scale of itself and every ancestor.
 *
 * A view's frame excludes the scale of an ancestor's transform (a `Modifier.scale` is a view's
 * `scaleX`/`scaleY`), so the seat headwear under `scale(1/0.7)` covers 240 px on a frame of 168.
 * Only enlarging scale is counted — the product is held at 1 or more per axis: a transform that
 * shrinks is usually the first frame of an entrance (a gift icon mounts at 0.5 and grows to 1.2),
 * and a decode taken then would be drawn upscaled for the rest of it; a view that stays shrunk
 * costs at most its unscaled frame, which is what it cost before.
 */
object KRImagePixelSize {

    /**
     * The product of the scale magnitudes along [chain] (the view's own, then each ancestor's),
     * each axis held at 1 or more.
     */
    fun enlargingScale(chain: Sequence<Pair<Float, Float>>): Pair<Float, Float> {
        var x = 1f
        var y = 1f
        for ((sx, sy) in chain) {
            x *= abs(sx)
            y *= abs(sy)
        }
        return Pair(x.coerceAtLeast(1f), y.coerceAtLeast(1f))
    }

    /** [frameWidth] × [frameHeight] under [scale], rounded up; 0 stays 0. */
    fun covered(frameWidth: Int, frameHeight: Int, scale: Pair<Float, Float>): Pair<Int, Int> =
        Pair(scaled(frameWidth, scale.first), scaled(frameHeight, scale.second))

    private fun scaled(px: Int, factor: Float): Int =
        if (px <= 0) px else ceil(px * factor.toDouble() - 1e-3).toInt().coerceAtLeast(px)

    /**
     * Whether a view that now covers [nowWidth] × [nowHeight] should ask again for a picture it
     * asked for at [issuedWidth] × [issuedHeight]: more than one eighth larger in either
     * dimension (iOS uses the same step, `docs/design/image-pipeline.md` §4.2.1). Never for a
     * smaller size: the current picture stays.
     */
    fun grewBeyondStep(issuedWidth: Int, issuedHeight: Int, nowWidth: Int, nowHeight: Int): Boolean {
        if (issuedWidth <= 0 || issuedHeight <= 0) return false
        return nowWidth * 8L > issuedWidth * 9L || nowHeight * 8L > issuedHeight * 9L
    }
}
