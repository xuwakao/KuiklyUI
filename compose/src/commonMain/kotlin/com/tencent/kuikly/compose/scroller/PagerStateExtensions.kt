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

package com.tencent.kuikly.compose.scroller

import com.tencent.kuikly.compose.animation.core.AnimationSpec
import com.tencent.kuikly.compose.animation.core.AnimationVector1D
import com.tencent.kuikly.compose.animation.core.SpringSpec
import com.tencent.kuikly.compose.animation.core.TweenSpec
import com.tencent.kuikly.compose.animation.core.VectorConverter
import com.tencent.kuikly.compose.animation.core.VectorizedAnimationSpec
import com.tencent.kuikly.compose.animation.core.getDurationMillis
import com.tencent.kuikly.compose.foundation.gestures.Orientation
import com.tencent.kuikly.compose.foundation.pager.PagerMeasureResult
import com.tencent.kuikly.compose.foundation.pager.PagerSnapDistance
import com.tencent.kuikly.compose.foundation.pager.PagerState
import com.tencent.kuikly.compose.ui.util.fastFirstOrNull
import com.tencent.kuikly.core.views.SpringAnimation
import com.tencent.kuikly.core.views.WillEndDragParams

/**
 * Handle drag end event
 */
internal fun PagerState.kuiklyWillDragEnd(params: WillEndDragParams, orientation: Orientation) {
    val effectivePageSizePx = pageSize + pageSpacing
    if (effectivePageSizePx == 0) return

    val velocity = if (orientation == Orientation.Horizontal) -params.velocityX else -params.velocityY
    val startPage = if (velocity < 0) firstVisiblePage + 1 else firstVisiblePage
    val targetPage = calculateTargetPage(startPage, startPage.coerceIn(0, pageCount - 1), velocity)

    handleTargetPageScroll(targetPage, params, orientation)
}

private fun PagerState.calculateTargetPage(
    startPage: Int,
    targetPage: Int,
    velocity: Float
): Int {
    return if (velocity != 0f) {
        PagerSnapDistance.atMost(1).calculateTargetPage(
            startPage,
            targetPage,
            velocity,
            pageSize,
            pageSpacing
        ).coerceIn(0, pageCount - 1)
    } else {
        currentPage
    }
}

private fun PagerState.handleTargetPageScroll(
    targetPage: Int,
    params: WillEndDragParams,
    orientation: Orientation
) {
    val kuiklyInfo = this.kuiklyInfo
    (layoutInfo as? PagerMeasureResult)?.run {
        val nativeOffset = if (orientation == Orientation.Vertical) params.offsetY.toInt() else params.offsetX.toInt()

        // 检测 native offset 和 compose offset 是否同步
        // 如果 native offset 为负数，或者与预期偏移相差超过一个 page，说明不同步
        val pagerCurrentPage = this@handleTargetPageScroll.currentPage
        val expectedOffset = pagerCurrentPage * pageSizeWithSpacing
        val isDesync = nativeOffset < 0 ||
            (pageSizeWithSpacing > 0 && kotlin.math.abs(nativeOffset - expectedOffset) > pageSizeWithSpacing)

        val maxOffset = kuiklyInfo.currentContentSize - kuiklyInfo.viewportSize

        val targetOffset: Int
        if (isDesync) {
            // 偏移不同步，使用绝对位置计算
            targetOffset = (targetPage * pageSizeWithSpacing).coerceIn(0, maxOffset)
        } else {
            // 正常情况：使用相对计算
            val allResult = visiblePagesInfo + extraPagesAfter + extraPagesBefore
            val nextPage = allResult.fastFirstOrNull { it.index == targetPage }
            val rawTargetOffset = nextPage?.let { nativeOffset + it.offset }
                ?: (pageSizeWithSpacing * targetPage)
            targetOffset = rawTargetOffset.coerceIn(0, maxOffset)
        }

        if (targetOffset == nativeOffset) {
            return
        }

        val density = kuiklyInfo.getDensity()
        val springAnimation = SpringAnimation(
            ScrollableStateConstants.SPRING_ANIMATION_DURATION,
            ScrollableStateConstants.SPRING_ANIMATION_DAMPING,
            if (orientation == Orientation.Horizontal) params.velocityX else params.velocityY
        )

        if (orientation == Orientation.Horizontal) {
            kuiklyInfo.scrollView?.setContentOffset(
                (targetOffset - ScrollableStateConstants.OFFSET_CORRECTION) / density,
                0f,
                true,
                springAnimation
            )
        } else {
            kuiklyInfo.scrollView?.setContentOffset(
                0f,
                (targetOffset - ScrollableStateConstants.OFFSET_CORRECTION) / density,
                true,
                springAnimation
            )
        }
    }
}

/**
 * Converts AnimationSpec<Float> to SpringAnimation
 * This is a temporary solution that mainly supports animation duration and basic animation curves
 * 
 * @param animationSpec The animation spec to convert
 * @param initialValue Initial value (used for calculating SpringSpec duration)
 * @param targetValue Target value (used for calculating SpringSpec duration)
 * @return The converted SpringAnimation, or null if the type is not supported
 */
internal fun convertAnimationSpecToSpringAnimation(
    animationSpec: AnimationSpec<Float>,
    initialValue: Float = 0f,
    targetValue: Float = 0f
): SpringAnimation? {
    return when (animationSpec) {
        is TweenSpec<*> -> {
            // TweenSpec: Use durationMillis as durationMs
            // Note: Easing curve (animationSpec.easing) is not supported yet,
            // using default damping value instead
            SpringAnimation(
                durationMs = animationSpec.durationMillis,
                damping = 0.8f, // Default damping value (easing curve not supported)
                velocity = 0f
            )
        }
        is SpringSpec<*> -> {
            // SpringSpec: Use dampingRatio as damping, calculate duration via vectorize
            // SpringSpec is physics-based, so duration needs to be calculated from spring parameters
            // Note: getDurationMillis may involve complex calculations (Newton's method, etc.),
            // but it's only called once per animateScrollToPage, not per frame
            val vectorizedSpec: VectorizedAnimationSpec<AnimationVector1D> = 
                animationSpec.vectorize<AnimationVector1D>(Float.VectorConverter)
            val initialVector = AnimationVector1D(initialValue)
            val targetVector = AnimationVector1D(targetValue)
            val initialVelocityVector = AnimationVector1D(0f)
            val durationMs = vectorizedSpec.getDurationMillis(
                initialVector,
                targetVector,
                initialVelocityVector
            ).toInt().coerceAtLeast(1)
            SpringAnimation(
                durationMs = durationMs,
                damping = animationSpec.dampingRatio,
                velocity = 0f
            )
        }
        else -> {
            // Unrecognized type, return null
            null
        }
    }
} 