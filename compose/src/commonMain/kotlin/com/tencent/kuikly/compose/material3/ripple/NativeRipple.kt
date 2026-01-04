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

import androidx.compose.runtime.Stable
import com.tencent.kuikly.compose.foundation.IndicationNodeFactory
import com.tencent.kuikly.compose.foundation.interaction.DragInteraction
import com.tencent.kuikly.compose.foundation.interaction.FocusInteraction
import com.tencent.kuikly.compose.foundation.interaction.HoverInteraction
import com.tencent.kuikly.compose.foundation.interaction.Interaction
import com.tencent.kuikly.compose.foundation.interaction.InteractionSource
import com.tencent.kuikly.compose.foundation.interaction.PressInteraction
import com.tencent.kuikly.compose.material3.LocalColorScheme
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.isSpecified
import com.tencent.kuikly.compose.ui.node.CompositionLocalConsumerModifierNode
import com.tencent.kuikly.compose.ui.node.DelegatableNode
import com.tencent.kuikly.compose.ui.node.KNode
import com.tencent.kuikly.compose.ui.node.LayoutAwareModifierNode
import com.tencent.kuikly.compose.ui.node.currentValueOf
import com.tencent.kuikly.compose.ui.node.requireLayoutNode
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.IntSize
import com.tencent.kuikly.compose.ui.unit.isUnspecified
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.coroutines.launch

/**
 * Creates a native ripple indication that leverages platform-native ripple implementations.
 *
 * On Android, this uses RippleDrawable.
 * On iOS, this uses CALayer animations.
 * On Web, this uses CSS transitions.
 *
 * @param bounded If true, ripples are clipped by the bounds of the target layout.
 * @param radius The radius for the ripple. If [Dp.Unspecified], the size will be calculated
 *   based on the target layout size.
 * @param color The color of the ripple. If [Color.Unspecified], onSurface from MaterialTheme will be used.
 */
@Stable
fun nativeRipple(
    bounded: Boolean = true,
    radius: Dp = Dp.Unspecified,
    color: Color = Color.Unspecified
): IndicationNodeFactory {
    return if (radius.isUnspecified && !color.isSpecified) {
        if (bounded) DefaultBoundedNativeRipple else DefaultUnboundedNativeRipple
    } else {
        NativeRippleNodeFactory(bounded, radius, color)
    }
}

/**
 * Default bounded native ripple singleton for better performance.
 */
private object DefaultBoundedNativeRipple : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode {
        return NativeRippleNode(
            interactionSource = interactionSource,
            bounded = true,
            radius = Dp.Unspecified,
            color = Color.Unspecified
        )
    }

    override fun hashCode(): Int = 1
    override fun equals(other: Any?) = other === this
}

/**
 * Default unbounded native ripple singleton for better performance.
 */
private object DefaultUnboundedNativeRipple : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode {
        return NativeRippleNode(
            interactionSource = interactionSource,
            bounded = false,
            radius = Dp.Unspecified,
            color = Color.Unspecified
        )
    }

    override fun hashCode(): Int = 2
    override fun equals(other: Any?) = other === this
}

/**
 * Factory for creating NativeRippleNode with custom configuration.
 */
@Stable
private class NativeRippleNodeFactory(
    private val bounded: Boolean,
    private val radius: Dp,
    private val color: Color
) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode {
        return NativeRippleNode(
            interactionSource = interactionSource,
            bounded = bounded,
            radius = radius,
            color = color
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NativeRippleNodeFactory) return false
        if (bounded != other.bounded) return false
        if (radius != other.radius) return false
        if (color != other.color) return false
        return true
    }

    override fun hashCode(): Int {
        var result = bounded.hashCode()
        result = 31 * result + radius.hashCode()
        result = 31 * result + color.hashCode()
        return result
    }
}

/**
 * The core Modifier.Node implementation that communicates with native platforms
 * to show ripple effects.
 */
private class NativeRippleNode(
    private val interactionSource: InteractionSource,
    private val bounded: Boolean,
    private val radius: Dp,
    private val color: Color
) : Modifier.Node(),
    LayoutAwareModifierNode,
    CompositionLocalConsumerModifierNode {

    private var currentSize: IntSize = IntSize.Zero
    private var rippleConfigSent = false

    /**
     * Get the actual ripple color.
     * Per Material Design 3 spec, pressed state layer should use onSurface color.
     * See: https://m3.material.io/components/buttons/specs
     */
    private val rippleColor: Color
        get() = if (color.isSpecified) {
            color
        } else {
            // Material 3: pressed state uses onSurface color for all components
            currentValueOf(LocalColorScheme).onSurface
        }

    override fun onAttach() {
        // Send ripple configuration to native
        sendRippleConfig()

        // Listen to interaction changes
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                handleInteraction(interaction)
            }
        }
    }

    override fun onDetach() {
        // Clean up: disable ripple on native side
        getView()?.getViewAttr()?.setProp(PROP_RIPPLE, buildDisabledConfig())
    }

    override fun onRemeasured(size: IntSize) {
        val sizeChanged = currentSize != size
        currentSize = size

        // Re-send config if size changed (radius might depend on size)
        if (sizeChanged && rippleConfigSent) {
            sendRippleConfig()
        }
    }

    /**
     * Send ripple configuration to the native layer.
     */
    private fun sendRippleConfig() {
        val view = getView() ?: return

        val config = buildRippleConfig()
        view.getViewAttr().setProp(PROP_RIPPLE, config)
        rippleConfigSent = true
    }

    /**
     * Build the ripple configuration JSON string.
     */
    private fun buildRippleConfig(): String {
        val actualColor = rippleColor
        val colorHex = colorToHex(actualColor)

        val alpha = RippleDefaults.RippleAlpha

        return JSONObject().apply {
            put("enabled", true)
            put("color", colorHex)
            put("bounded", bounded)
            put("radius", if (radius.isUnspecified) -1f else radius.value)
            put("pressedAlpha", alpha.pressedAlpha)
            put("hoveredAlpha", alpha.hoveredAlpha)
            put("focusedAlpha", alpha.focusedAlpha)
            put("draggedAlpha", alpha.draggedAlpha)
        }.toString()
    }

    /**
     * Build a disabled ripple configuration.
     */
    private fun buildDisabledConfig(): String {
        return JSONObject().apply {
            put("enabled", false)
        }.toString()
    }

    /**
     * Handle interaction state changes and notify native layer.
     */
    private fun handleInteraction(interaction: Interaction) {
        val view = getView() ?: return

        val stateJson = when (interaction) {
            is PressInteraction.Press -> {
                JSONObject().apply {
                    put("state", STATE_PRESSED)
                    put("x", interaction.pressPosition.x)
                    put("y", interaction.pressPosition.y)
                }.toString()
            }
            is PressInteraction.Release -> {
                JSONObject().apply {
                    put("state", STATE_RELEASED)
                }.toString()
            }
            is PressInteraction.Cancel -> {
                JSONObject().apply {
                    put("state", STATE_CANCELLED)
                }.toString()
            }
            is HoverInteraction.Enter -> {
                JSONObject().apply {
                    put("state", STATE_HOVERED)
                }.toString()
            }
            is HoverInteraction.Exit -> {
                JSONObject().apply {
                    put("state", STATE_UNHOVERED)
                }.toString()
            }
            is FocusInteraction.Focus -> {
                JSONObject().apply {
                    put("state", STATE_FOCUSED)
                }.toString()
            }
            is FocusInteraction.Unfocus -> {
                JSONObject().apply {
                    put("state", STATE_UNFOCUSED)
                }.toString()
            }
            is DragInteraction.Start -> {
                JSONObject().apply {
                    put("state", STATE_DRAGGED)
                }.toString()
            }
            is DragInteraction.Stop, is DragInteraction.Cancel -> {
                JSONObject().apply {
                    put("state", STATE_UNDRAGGED)
                }.toString()
            }
            else -> return
        }

        view.getViewAttr().setProp(PROP_RIPPLE_STATE, stateJson)
    }

    /**
     * Get the native view from the layout node.
     */
    private fun getView(): DeclarativeBaseView<*, *>? {
        return try {
            val layoutNode = requireLayoutNode()
            (layoutNode as? KNode<*>)?.view
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        // Prop keys for native communication
        private const val PROP_RIPPLE = "ripple"
        private const val PROP_RIPPLE_STATE = "rippleState"

        // State constants
        private const val STATE_PRESSED = "pressed"
        private const val STATE_RELEASED = "released"
        private const val STATE_CANCELLED = "cancelled"
        private const val STATE_HOVERED = "hovered"
        private const val STATE_UNHOVERED = "unhovered"
        private const val STATE_FOCUSED = "focused"
        private const val STATE_UNFOCUSED = "unfocused"
        private const val STATE_DRAGGED = "dragged"
        private const val STATE_UNDRAGGED = "undragged"

        /**
         * Convert Color to hex string format (#RRGGBB).
         */
        private fun colorToHex(color: Color): String {
            val r = (color.red * 255).toInt().coerceIn(0, 255)
            val g = (color.green * 255).toInt().coerceIn(0, 255)
            val b = (color.blue * 255).toInt().coerceIn(0, 255)
            return "#${r.toString(16).padStart(2, '0')}${g.toString(16).padStart(2, '0')}${b.toString(16).padStart(2, '0')}".uppercase()
        }
    }
}
