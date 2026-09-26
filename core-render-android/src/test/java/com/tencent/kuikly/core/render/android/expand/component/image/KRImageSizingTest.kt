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

/**
 * Ronaq fork (CHANGES.md §43): what an image view asked for against what it shows, and the
 * three rules that read it — ask again when it grew, never for an animated picture, and a failed
 * re-ask keeps the picture.
 */
class KRImageSizingTest {

    @Test
    fun aViewThatGrewPastAnEighthAsksAgain() {
        val sizing = KRImageSizing()
        sizing.issue(168, 168)
        sizing.delivered()
        assertFalse(sizing.shouldUpgrade(180, 180, animated = false), "layout noise")
        assertTrue(sizing.shouldUpgrade(240, 240, animated = false), "under scale(1/0.7)")
    }

    @Test
    fun anAnimatedPictureIsNeverUpgraded() {
        // A larger request of an animated picture is a new cache entry whose frames are the file's
        // size again (an image library does not resize animated frames): it would only restart the
        // clip from frame 0 — a one-shot replays — and hold its frames twice.
        val sizing = KRImageSizing()
        sizing.issue(168, 168)
        sizing.delivered()
        assertFalse(sizing.shouldUpgrade(240, 240, animated = true))
    }

    @Test
    fun anUpgradeCancelledBeforeItArrivedIsAskedForAgain() {
        // The view left the window with its larger picture still on the way; the load was given
        // back. On its return it compares against what it SHOWS, not against the size it never got.
        val sizing = KRImageSizing()
        sizing.issue(168, 168)
        sizing.delivered()
        sizing.issue(240, 240)
        sizing.abandoned()
        assertEquals(168, sizing.issuedWidth)
        assertTrue(sizing.shouldUpgrade(240, 240, animated = false))
    }

    @Test
    fun aFirstLoadCancelledLeavesNothingToUpgrade() {
        val sizing = KRImageSizing()
        sizing.issue(168, 168)
        sizing.abandoned()
        assertFalse(sizing.sized, "no picture: the view asks again as a first load, not as an upgrade")
        assertFalse(sizing.shouldUpgrade(240, 240, animated = false))
    }

    @Test
    fun aFailedUpgradeIsNotAskedForAgainUntilTheViewGrowsFurther() {
        val sizing = KRImageSizing()
        sizing.issue(168, 168)
        sizing.delivered()
        sizing.issue(240, 240)
        sizing.failed()
        assertFalse(sizing.shouldUpgrade(240, 240, animated = false), "no retry storm on every notice")
        assertTrue(sizing.shouldUpgrade(300, 300, animated = false))
    }

    @Test
    fun anUnsizedLoadNeverUpgrades() {
        val sizing = KRImageSizing()
        sizing.issue(0, 0)
        sizing.delivered()
        assertFalse(sizing.sized)
        assertFalse(sizing.shouldUpgrade(500, 500, animated = false))
    }

    @Test
    fun aFailureOverAPictureOfTheSameSourceIsNotReported() {
        assertFalse(KRImageSizing.reportsFailure(showsPicture = true),
            "a failed re-ask keeps the picture: the caller must not swap it for an error")
        assertTrue(KRImageSizing.reportsFailure(showsPicture = false))
    }

    @Test
    fun resetForgetsBoth() {
        val sizing = KRImageSizing()
        sizing.issue(168, 168)
        sizing.delivered()
        sizing.reset()
        assertFalse(sizing.sized)
        sizing.abandoned()
        assertFalse(sizing.sized)
    }
}
