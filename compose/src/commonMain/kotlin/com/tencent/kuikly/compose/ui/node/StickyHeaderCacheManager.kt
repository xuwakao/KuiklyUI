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

package com.tencent.kuikly.compose.ui.node

import com.tencent.kuikly.compose.ui.geometry.Offset

/**
 * Sticky Header Cache Manager
 * Specifically responsible for managing position cache for multiple sticky headers
 * Used in conjunction with KuiklyScrollInfo
 */
class StickyHeaderCacheManager {

    /**
     * Cache data structure
     */
    private data class StickyHeaderCache(
        val position: Offset
    )

    /**
     * Cache mapping table, using lazyItemKey as identifier
     */
    private val stickyHeaderCacheMap = mutableMapOf<Any, StickyHeaderCache>()

    /**
     * Get cached position for sticky header
     *
     * The recorded position stands in while the lazy measure is reporting the moving offset
     * it gives a header that has been scrolled past — see the caller for why that offset
     * cannot be handed to the hover view. It is REPLACED, not merely filled, whenever the
     * measure reports the header's own position, so that anything inserted above the header
     * after the list first drew — a banner delivered late, a row that loads — moves it.
     * Supports multiple sticky headers, distinguished by lazyItemKey.
     *
     * @param positionIsItsOwn [currentPos] is where the header actually sits.
     */
    fun getCachedStickyPosition(itemKey: Any, currentPos: Offset, positionIsItsOwn: Boolean): Offset {
        val cachedData = stickyHeaderCacheMap[itemKey]

        return if (cachedData == null || positionIsItsOwn) {
            stickyHeaderCacheMap[itemKey] = StickyHeaderCache(currentPos)
            currentPos
        } else {
            cachedData.position
        }
    }
}