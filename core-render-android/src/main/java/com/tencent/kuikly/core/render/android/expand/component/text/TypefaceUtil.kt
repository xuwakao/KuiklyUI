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

package com.tencent.kuikly.core.render.android.expand.component.text

import android.graphics.Typeface
import android.util.LruCache
import com.tencent.kuikly.core.render.android.KuiklyContextParams
import com.tencent.kuikly.core.render.android.adapter.KuiklyRenderAdapterManager

/**
 * Ronaq: what a family chain resolved to, and whether that face really carries the
 * requested weight.
 * Ronaq：字体链的解析结果，以及该字面是否真的带有所请求的字重。
 *
 * [matchesWeight] is the only new fact. It exists so a caller can stop faking the
 * weight — every text path on Android widens the paint stroke to imitate a heavy face
 * ([FontWeightSpan]), which is the right thing to do only when no such face exists.
 * matchesWeight 是此处唯一新增的事实：调用方据此决定是否还需伪造字重 ——
 * Android 各文本路径均以加粗描边模拟重字面（见 FontWeightSpan），
 * 而那只在确实没有对应字面时才是对的做法。
 */
class ResolvedTypeface internal constructor(
    val typeface: Typeface,
    val matchesWeight: Boolean,
)

class TypeFaceLoader(private val contextParams: KuiklyContextParams? = null) {
    private class Key(val fontFamilyName: String, val italic: Boolean, val fontWeight: Int) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Key) return false
            return italic == other.italic &&
                fontWeight == other.fontWeight &&
                fontFamilyName == other.fontFamilyName
        }

        override fun hashCode(): Int {
            var result = fontFamilyName.hashCode()
            result = 31 * result + italic.hashCode()
            result = 31 * result + fontWeight
            return result
        }
    }

    private val sFontCache: LruCache<Key, ResolvedTypeface> = LruCache(FONT_CACHE_SIZE)

    fun getTypeface(fontFamilyName: String, italic: Boolean): Typeface =
        resolve(fontFamilyName, italic, FontWeightSpan.FONT_WEIGHT_UNSPECIFIED).typeface

    /**
     * Ronaq: resolves the family chain, preferring the host's own face for [fontWeight].
     * Ronaq：解析字体链，优先取宿主为该字重提供的真实字面。
     *
     * [fontWeight] is a CSS numeric weight (100..900) or
     * [FontWeightSpan.FONT_WEIGHT_UNSPECIFIED], which reproduces the pre-existing
     * behaviour exactly: no weight is asked for and [ResolvedTypeface.matchesWeight]
     * is false.
     * fontWeight 取 CSS 数值字重（100..900）或 FONT_WEIGHT_UNSPECIFIED；
     * 后者完全复现原有行为：不索取字重，matchesWeight 恒为 false。
     */
    fun resolve(fontFamilyName: String, italic: Boolean, fontWeight: Int): ResolvedTypeface {
        val key = Key(fontFamilyName, italic, fontWeight)
        return sFontCache.get(key) ?: createTypeface(key).also { sFontCache.put(key, it) }
    }

    private fun createTypeface(key: Key): ResolvedTypeface {
        val familyNameList: List<String> = if (key.fontFamilyName.indexOf(',') == -1) {
            listOf(key.fontFamilyName)
        } else {
            key.fontFamilyName.split(',')
        }
        val style = if (key.italic) Typeface.ITALIC else Typeface.NORMAL
        // Ronaq: the face name the host would have given this weight, e.g. `Bold` for
        // 700. Null when no weight was asked for, which skips the whole lookup.
        // Ronaq：宿主对该字重的惯用字面名（如 700 → Bold）；未索取字重时为 null，整个查找跳过。
        val faceName = postScriptFaceName(key.fontWeight, key.italic)
        var systemDefault: Typeface? = null
        for (splitName in familyNameList) {
            val familyName = splitName.trim()
            if (familyName.isEmpty()) {
                continue
            }
            val hostFace = hostTypeface(familyName)
            if (faceName != null) {
                val weighted = hostTypeface("$familyName-$faceName")
                // Only trust the weight-qualified answer when it is a DIFFERENT face
                // from the bare family's. An adapter that answers every name with one
                // typeface has not selected by weight, and its text still needs the
                // synthetic stroke — without this guard such a host would silently lose
                // every bold on screen.
                // 仅当带字重名解析出的字面与裸族名不同才采信：对任何名字都返回同一 typeface
                // 的适配器并未按字重挑选，其文本仍须依赖描边加粗 ——
                // 缺此判断会让这类宿主界面上的所有粗体无声地变细。
                if (weighted != null && weighted !== hostFace) {
                    return ResolvedTypeface(weighted, true)
                }
            }
            if (hostFace != null) {
                return ResolvedTypeface(hostFace, false)
            }
            if (systemDefault == null) {
                systemDefault = Typeface.defaultFromStyle(style)
            }
            // Deliberately NOT `Typeface.create(family, weight, italic)` (API 28+): that
            // would change the metrics of every existing app's text on the platform font
            // with no host opt-in. Only a face the host names is preferred here.
            // 此处刻意不使用 API 28 的 Typeface.create(family, weight, italic)：
            // 那会在宿主毫不知情的情况下改变既有应用系统字体下的全部文本度量。
            // 只有宿主自己命名的字面才被优先采用。
            val platformFace = Typeface.create(familyName, style)
            if (platformFace != null && platformFace != systemDefault) {
                return ResolvedTypeface(platformFace, false)
            }
        }
        return ResolvedTypeface(systemDefault ?: Typeface.defaultFromStyle(style), false)
    }

    /**
     * Ronaq: asks the host font adapter for one family name.
     * Ronaq：就单个族名询问宿主字体适配器。
     *
     * Returns null both when the host has no such face and when it hands back
     * [Typeface.DEFAULT] — which is how the loader has always read "not mine", and is
     * the contract that lets a chain like `Cairo,sans-serif` fall through to the
     * platform. Asking for a name the host does not own is therefore already a
     * supported query, which is what makes the weight-qualified lookup above safe.
     * 宿主没有该字面、或返回 Typeface.DEFAULT 时均返回 null —— 这正是本加载器一贯
     * 对「不是我的」的读法，也是 `Cairo,sans-serif` 之类字体链得以回落平台的前提。
     * 因此「询问宿主并不拥有的名字」本就是被支持的用法，上面的带字重查找据此成立。
     *
     * The result is read from a local rather than an outer variable, so an adapter that
     * never invokes the callback cannot leak the previous name's answer into this one.
     * 结果读自局部变量而非外层变量：适配器若未回调，也不会把上一个名字的结果串到这次。
     */
    private fun hostTypeface(familyName: String): Typeface? {
        var typeface: Typeface? = null
        KuiklyRenderAdapterManager.krFontAdapter?.getTypeface(familyName, contextParams) {
            typeface = it
        }
        return if (typeface != null && typeface != Typeface.DEFAULT) typeface else null
    }

    companion object {
        /**
         * Ronaq: raised from 10 because the cache key now carries a weight — a single
         * family used at five weights is five entries, so two families would have
         * thrashed the old bound. A Typeface is a thin handle onto a shared (usually
         * mmapped) native font, so the extra entries cost close to nothing.
         * Ronaq：由 10 上调 —— 缓存键现在含字重，一个族名用五个字重即占五条，
         * 两个族名就会击穿旧上限。Typeface 只是共享（通常 mmap）原生字体的轻量句柄，
         * 多留几条几乎不增加开销。
         */
        private const val FONT_CACHE_SIZE = 32

        /**
         * Ronaq: the face name a foundry gives this weight. This is the platform's own
         * convention, not a Kuikly one: these are the PostScript names
         * `[UIFont fontWithName:]` takes on iOS and the names a static `@font-face`
         * `src:` file carries on web, so a host that ships one file per weight has
         * already named them this way.
         * Ronaq：字重对应的惯用字面名。这是平台自身的约定而非 Kuikly 自造：
         * 这些正是 iOS 上 [UIFont fontWithName:] 所取的 PostScript 名，
         * 也是 Web 上静态 @font-face src 文件的名字 ——
         * 逐字重打包字体文件的宿主，本就是这样命名的。
         */
        private fun postScriptFaceName(fontWeight: Int, italic: Boolean): String? {
            val base = when (fontWeight) {
                100 -> "Thin"
                200 -> "ExtraLight"
                300 -> "Light"
                400 -> ""
                500 -> "Medium"
                600 -> "SemiBold"
                700 -> "Bold"
                800 -> "ExtraBold"
                900 -> "Black"
                else -> return null
            }
            return when {
                italic -> base + "Italic"
                base.isEmpty() -> "Regular"
                else -> base
            }
        }
    }
}
