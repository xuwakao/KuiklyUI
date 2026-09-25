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

package com.tencent.kuikly.core.render.android

import com.tencent.kuikly.core.render.android.expand.component.blur.CachedImageBlur

/**
 * Ronaq fork (CHANGES.md §38): the renderer's own caches answer memory pressure.
 *
 * The renderer keeps a bitmap cache of its own — the image blur's (`CachedImageBlur`, 2 MiB) —
 * that no image library accounts for and nothing trimmed. The host forwards its application's
 * `onTrimMemory` here, next to wherever it trims its image library; every level empties the
 * cache, the rule image libraries apply to their evictable entries. Views keep the pictures
 * they show: eviction never recycles a bitmap.
 */
object KuiklyRenderMemory {

    /** `ComponentCallbacks2.onTrimMemory` / `onLowMemory` (pass `TRIM_MEMORY_COMPLETE`). */
    fun onTrimMemory(@Suppress("UNUSED_PARAMETER") level: Int) {
        CachedImageBlur.evictAll()
    }

    /** Bytes held by the renderer's own bitmap caches, for a host's diagnostics. */
    fun cachedBitmapBytes(): Int = CachedImageBlur.sizeBytes()
}
