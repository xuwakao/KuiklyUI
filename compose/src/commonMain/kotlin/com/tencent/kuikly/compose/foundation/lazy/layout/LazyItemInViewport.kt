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

package com.tencent.kuikly.compose.foundation.lazy.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * Ronaq fork (CHANGES.md §56): whether the lazy list or grid item this is composed in lies within
 * its list's viewport, or within one line of it — false for an item the list has composed and
 * placed ahead of the reader (the grid's `beyondBoundsLineCount` lines, 3 by default) but that is
 * further from the glass than that.
 *
 * True outside any lazy list, and inside nested lists only while every enclosing item is in view
 * too. Content that loops (an equaliser, a pulse) reads this and holds still where nobody can see
 * it; everything else can ignore it.
 *
 * The one-line margin exists because on this renderer the native scroller moves first and Compose
 * places items after the fact (`ScrollableStateExtensions.kt`, `kuiklyOnScroll`): an answer taken
 * from Compose's own layout trails the glass, and the line entering next must already be moving
 * when it arrives.
 */
val LocalLazyItemInViewport = compositionLocalOf { true }

/**
 * Provides [LocalLazyItemInViewport] to one lazy item: its own answer from [inViewport], asked of
 * the list's last measure, ANDed with the answer of the item enclosing the list. Only readers of
 * the local recompose when the answer changes.
 */
@Composable
internal fun ProvideLazyItemInViewport(
    index: Int,
    inViewport: (Int) -> Boolean,
    content: @Composable () -> Unit,
) {
    val enclosing = LocalLazyItemInViewport.current
    val currentIndex by rememberUpdatedState(index)
    val own by remember(inViewport) { derivedStateOf { inViewport(currentIndex) } }
    CompositionLocalProvider(LocalLazyItemInViewport provides (enclosing && own), content = content)
}

/**
 * The indices of [items] (the items a list's last measure placed) that are within one line of the
 * viewport: an item whose main-axis extent, widened by its own size plus [spacing] on each side
 * (the line before and after it), overlaps `[viewportStart, viewportEnd)`. A pinned sticky header
 * is placed at the viewport's edge, so it counts. Null when nothing is placed yet or the viewport
 * has no extent: the list has not been measured, and every item then counts as in view.
 */
internal inline fun <T> indicesInViewport(
    items: List<T>,
    viewportStart: Int,
    viewportEnd: Int,
    spacing: Int,
    index: (T) -> Int,
    mainStart: (T) -> Int,
    mainSize: (T) -> Int,
): Set<Int>? {
    if (items.isEmpty() || viewportEnd <= viewportStart) return null
    val result = HashSet<Int>(items.size)
    for (item in items) {
        val start = mainStart(item)
        val size = mainSize(item)
        val margin = size + spacing
        if (start < viewportEnd + margin && start + size > viewportStart - margin) {
            result.add(index(item))
        }
    }
    return result
}

/**
 * One list state's answers, computed once per measure result and asked by every composed item.
 * A new result (identity) replaces the cached answer; items the result did not place are out of
 * view, since a list places everything it composed for the viewport and its beyond-bounds lines.
 */
internal class InViewportCache<R : Any>(private val compute: (R) -> Set<Int>?) {
    private var forResult: R? = null
    private var indices: Set<Int>? = null

    fun isInViewport(result: R, index: Int): Boolean {
        if (result !== forResult) {
            indices = compute(result)
            forResult = result
        }
        val known = indices ?: return true
        return index in known
    }
}
