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

package com.tencent.kuikly.core.render.android.expand.visibility

/**
 * Ronaq fork (CHANGES.md §41): the rule an animated view follows when it cannot be seen — the
 * Android half of Ronaq `docs/design/image-pipeline.md` §4.4, the same rule as iOS's frame sources.
 *
 * A looping animation whose view is not effectively visible ([KRVisibility]) is paused, and
 * resumed when it is visible again — only if this rule paused it: an animation that stopped on its
 * own (a finite loop that ended) stays stopped. A one-shot play is never paused ([decide]'s
 * `pausable` false): a gift effect keeps its timing and its end. An unknown visibility (a view not
 * laid out yet) changes nothing. View-free, for the JVM tests. Main thread.
 */
class KRUnseenPause {

    enum class Action { NONE, PAUSE, RESUME }

    /** Whether the animation is paused because it could not be seen. */
    var pausedUnseen = false
        private set

    /**
     * What to do now. [visible]: the predicate's answer, or null when unknown; [running]: the
     * animation is advancing; [pausable]: it may be paused at all (a looping play).
     */
    fun decide(visible: Boolean?, running: Boolean, pausable: Boolean): Action {
        if (visible == null) return Action.NONE
        if (!visible) {
            if (pausable && running && !pausedUnseen) {
                pausedUnseen = true
                return Action.PAUSE
            }
            return Action.NONE
        }
        if (pausedUnseen) {
            pausedUnseen = false
            return Action.RESUME
        }
        return Action.NONE
    }

    /** New content: nothing of it was paused by this rule. */
    fun reset() {
        pausedUnseen = false
    }
}
