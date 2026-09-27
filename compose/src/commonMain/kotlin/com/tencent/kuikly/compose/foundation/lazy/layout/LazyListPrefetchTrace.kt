/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 */

package com.tencent.kuikly.compose.foundation.lazy.layout

import com.tencent.kuikly.compose.foundation.ComposeFoundationFlags
import com.tencent.kuikly.compose.foundation.ExperimentalFoundationApi

/** Debug trace for LazyList prefetch pipeline (logcat tag: LazyListPrefetchTrace). */
@OptIn(ExperimentalFoundationApi::class)
internal object LazyListPrefetchTrace {
    const val LOG_TAG = "LazyListPrefetchTrace"

    /**
     * Ronaq fork (CHANGES.md §55): the message is built only when tracing is on. Callers pass a
     * lambda; `BaseComposeScene.render` alone used to format a ~150-character string every frame
     * with tracing off.
     */
    inline fun log(message: () -> String) {
        if (ComposeFoundationFlags.isLazyListPrefetchTraceEnabled) {
            val text = message()
            println("$LOG_TAG $text")
            ComposeFoundationFlags.lazyListPrefetchTraceListener?.invoke(text)
        }
    }
}
