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

package com.tencent.kuikly.compose.material3.ripple

import androidx.compose.runtime.Immutable

/**
 * RippleAlpha defines the alpha values for different interaction states.
 * These values follow Material Design 3 specifications.
 *
 * @param pressedAlpha Alpha value when the component is pressed
 * @param focusedAlpha Alpha value when the component is focused
 * @param draggedAlpha Alpha value when the component is being dragged
 * @param hoveredAlpha Alpha value when the component is hovered
 */
@Immutable
class RippleAlpha(
    val pressedAlpha: Float,
    val focusedAlpha: Float,
    val draggedAlpha: Float,
    val hoveredAlpha: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RippleAlpha) return false

        if (pressedAlpha != other.pressedAlpha) return false
        if (focusedAlpha != other.focusedAlpha) return false
        if (draggedAlpha != other.draggedAlpha) return false
        if (hoveredAlpha != other.hoveredAlpha) return false

        return true
    }

    override fun hashCode(): Int {
        var result = pressedAlpha.hashCode()
        result = 31 * result + focusedAlpha.hashCode()
        result = 31 * result + draggedAlpha.hashCode()
        result = 31 * result + hoveredAlpha.hashCode()
        return result
    }

    override fun toString(): String {
        return "RippleAlpha(pressedAlpha=$pressedAlpha, focusedAlpha=$focusedAlpha, " +
                "draggedAlpha=$draggedAlpha, hoveredAlpha=$hoveredAlpha)"
    }
}

/**
 * Default RippleAlpha values following Material Design 3 specifications.
 */
object RippleDefaults {
    /**
     * Default alpha values for ripple effects.
     * These values are based on Material Design 3 state layer opacities.
     */
    val RippleAlpha = RippleAlpha(
        pressedAlpha = 0.20f,  // Increased for better visibility
        focusedAlpha = 0.12f,
        draggedAlpha = 0.16f,
        hoveredAlpha = 0.08f
    )
}
