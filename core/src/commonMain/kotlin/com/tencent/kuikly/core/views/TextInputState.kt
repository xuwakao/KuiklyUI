/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2026 Tencent. All rights reserved.
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

package com.tencent.kuikly.core.views

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * Internal cross-layer text input state payload for Core / Render communication.
 *
 * All indices are raw text offsets. [selectionStart] == [selectionEnd] means cursor position.
 * [compositionStart] and [compositionEnd] are -1 when the platform does not provide composing range.
 */
data class TextInputState(
    val text: String,
    val selectionStart: Int = text.length,
    val selectionEnd: Int = selectionStart,
    val compositionStart: Int = NO_COMPOSITION,
    val compositionEnd: Int = NO_COMPOSITION,
    val length: Int? = null,
    /**
     * Edit-generation handshake, optional. A renderer that counts user edits stamps
     * every reported state with its current generation; a state PUSHED to that
     * renderer carries the last generation the pusher had seen, so the renderer can
     * recognize a push composed before edits it has already applied and refuse to
     * roll them back. Renderers that do not count (Android, iOS) never stamp their
     * reports (`null`, which is never encoded, so their payloads are unchanged) and
     * ignore the key on a pushed state.
     * Not part of [hasSameEditingState]: it is transport metadata, not editing state.
     */
    val generation: Int? = null
) {
    fun toJSONObject(): JSONObject {
        return JSONObject().apply {
            put(KEY_TEXT, text)
            put(KEY_SELECTION_START, selectionStart)
            put(KEY_SELECTION_END, selectionEnd)
            put(KEY_COMPOSITION_START, compositionStart)
            put(KEY_COMPOSITION_END, compositionEnd)
            length?.let { put(KEY_LENGTH, it) }
            generation?.let { put(KEY_GENERATION, it) }
        }
    }

    fun encode(): String = toJSONObject().toString()

    fun hasSameEditingState(other: TextInputState): Boolean {
        return selectionStart == other.selectionStart &&
            selectionEnd == other.selectionEnd &&
            compositionStart == other.compositionStart &&
            compositionEnd == other.compositionEnd &&
            text == other.text
    }

    companion object {
        const val NO_COMPOSITION = -1

        const val KEY_TEXT = "text"
        const val KEY_SELECTION_START = "selectionStart"
        const val KEY_SELECTION_END = "selectionEnd"
        const val KEY_COMPOSITION_START = "compositionStart"
        const val KEY_COMPOSITION_END = "compositionEnd"
        const val KEY_LENGTH = "length"
        const val KEY_GENERATION = "generation"

        fun decode(params: JSONObject?): TextInputState {
            val json = params ?: JSONObject()
            val text = json.optString(KEY_TEXT)
            val selectionStart = if (json.has(KEY_SELECTION_START)) {
                json.optInt(KEY_SELECTION_START)
            } else {
                text.length
            }
            val selectionEnd = if (json.has(KEY_SELECTION_END)) {
                json.optInt(KEY_SELECTION_END)
            } else {
                selectionStart
            }
            val compositionStart = if (json.has(KEY_COMPOSITION_START)) {
                json.optInt(KEY_COMPOSITION_START)
            } else {
                NO_COMPOSITION
            }
            val compositionEnd = if (json.has(KEY_COMPOSITION_END)) {
                json.optInt(KEY_COMPOSITION_END)
            } else {
                NO_COMPOSITION
            }
            val length = if (json.has(KEY_LENGTH)) json.optInt(KEY_LENGTH) else null
            val generation = if (json.has(KEY_GENERATION)) json.optInt(KEY_GENERATION) else null
            return TextInputState(
                text = text,
                selectionStart = selectionStart,
                selectionEnd = selectionEnd,
                compositionStart = compositionStart,
                compositionEnd = compositionEnd,
                length = length,
                generation = generation
            )
        }
    }
}

typealias TextInputStateHandlerFn = (TextInputState) -> Unit
