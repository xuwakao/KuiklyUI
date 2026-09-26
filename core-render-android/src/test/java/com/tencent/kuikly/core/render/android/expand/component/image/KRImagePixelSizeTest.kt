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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ronaq fork (CHANGES.md §40): the pixels an image view covers, and when it asks again. */
class KRImagePixelSizeTest {

    @Test
    fun anEnlargingAncestorIsCounted() {
        // The seat headwear: a 168 px frame under scale(1/0.7).
        val scale = KRImagePixelSize.enlargingScale(sequenceOf(1f to 1f, (1 / 0.7f) to (1 / 0.7f), 1f to 1f))
        assertEquals(240 to 240, KRImagePixelSize.covered(168, 168, scale))
    }

    @Test
    fun scalesMultiplyAlongTheChainAndMirroringDoesNotShrink() {
        val scale = KRImagePixelSize.enlargingScale(sequenceOf(2f to 1f, -1.5f to 2f))
        assertEquals(3f to 2f, scale, "|-1.5|: an RTL mirror is a flip, not a shrink")
    }

    @Test
    fun aShrinkingTransformIsHeldAtOne() {
        // A gift icon mounting at 0.5 on its way to 1.2.
        assertEquals(1f to 1f, KRImagePixelSize.enlargingScale(sequenceOf(0.5f to 0.5f)))
        assertEquals(1.2f to 1f, KRImagePixelSize.enlargingScale(sequenceOf(0.5f to 0.5f, 2.4f to 1.5f)).let {
            (Math.round(it.first * 100) / 100f) to it.second
        }, "only the product is held, per axis")
    }

    @Test
    fun theCoveredSizeRoundsUpAndNeverBelowTheFrame() {
        assertEquals(101 to 50, KRImagePixelSize.covered(100, 50, 1.001f to 1f))
        assertEquals(0 to 0, KRImagePixelSize.covered(0, 0, 2f to 2f))
        assertEquals(100 to 50, KRImagePixelSize.covered(100, 50, 1f to 1f), "no float noise upwards")
    }

    @Test
    fun aViewAsksAgainOnlyWhenMoreThanAnEighthLarger() {
        assertFalse(KRImagePixelSize.grewBeyondStep(160, 160, 180, 180), "exactly 9/8")
        assertTrue(KRImagePixelSize.grewBeyondStep(160, 160, 181, 160))
        assertTrue(KRImagePixelSize.grewBeyondStep(160, 160, 160, 181))
        assertFalse(KRImagePixelSize.grewBeyondStep(160, 160, 80, 80), "never downwards")
        assertFalse(KRImagePixelSize.grewBeyondStep(0, 0, 500, 500), "an unsized load is not upgraded")
    }
}
