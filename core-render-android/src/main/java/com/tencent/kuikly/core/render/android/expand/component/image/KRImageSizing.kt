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

/**
 * Ronaq fork (CHANGES.md §43): the pixel size an image view last asked its adapter for against
 * the size of the picture it shows, and the rules that read them. View-free, for the JVM tests;
 * `KRImageView` owns one and tells it what happens to its loads. Main thread only.
 *
 * - **Ask again when grown** ([shouldUpgrade], §40): more than an eighth larger than the last
 *   request, never smaller — and never for an animated picture: a larger request of one is a new
 *   cache entry whose frames are the file's size again (an image library does not resize animated
 *   frames), so it would only restart the clip from its first frame (a one-shot replays) and hold
 *   its frames twice.
 * - **A request that never arrived does not count** ([abandoned]): an upgrade cancelled because
 *   the view left its window leaves the view comparing against the picture it shows, so its return
 *   asks again.
 * - **A failed re-ask keeps the picture** ([reportsFailure]): a view that already shows a picture
 *   of its source keeps it and reports no failure — a caller would swap a good picture for its
 *   error state (Ronaq's VIP tiles show ⊘).
 */
class KRImageSizing {

    /** The last request's size; 0×0 for a request without a size or none. */
    var issuedWidth = 0
        private set
    var issuedHeight = 0
        private set

    /** The size the picture on screen was asked for; 0×0 when none is shown or it was unsized. */
    private var shownWidth = 0
    private var shownHeight = 0

    /** Whether the last request had a size (only then does growth matter). */
    val sized: Boolean get() = issuedWidth > 0 && issuedHeight > 0

    /** A load was issued at [width] × [height] (0×0: without a size). */
    fun issue(width: Int, height: Int) {
        issuedWidth = width.coerceAtLeast(0)
        issuedHeight = height.coerceAtLeast(0)
    }

    /** The load issued last delivered its picture, which is now shown. */
    fun delivered() {
        shownWidth = issuedWidth
        shownHeight = issuedHeight
    }

    /** The load issued last was cancelled before it arrived: back to what is shown. */
    fun abandoned() {
        issuedWidth = shownWidth
        issuedHeight = shownHeight
    }

    /**
     * The load issued last failed. Its size stays the one asked for, so the view does not ask
     * again at the same size on every notice; it does once it grows past that.
     */
    fun failed() = Unit

    /** No picture and no load (a new source, a reset, destruction). */
    fun reset() {
        issuedWidth = 0
        issuedHeight = 0
        shownWidth = 0
        shownHeight = 0
    }

    /** Whether a view now covering [coveredWidth] × [coveredHeight] asks again. */
    fun shouldUpgrade(coveredWidth: Int, coveredHeight: Int, animated: Boolean): Boolean =
        !animated && sized && KRImagePixelSize.grewBeyondStep(issuedWidth, issuedHeight, coveredWidth, coveredHeight)

    companion object {
        /** Whether a failed load is reported: not when the view already shows a picture of its source. */
        fun reportsFailure(showsPicture: Boolean): Boolean = !showsPicture
    }
}
