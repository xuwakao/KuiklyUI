package com.tencent.kuikly.compose.ui.node

/** Native Hover's displayed position minus its unchanged lazy-layout position, in pixels. */
internal fun stickyVisualDelta(
    viewportY: Float,
    contentY: Float,
    nativeScrollY: Float,
    marginY: Float,
): Float = maxOf(contentY - nativeScrollY, marginY) - viewportY
