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

/**
 * Ronaq fork (CHANGES.md §54): why the draw pass must come to a [KNode].
 *
 * Every `KNode` draws into its own native view, and a parent's view holds nothing of its
 * children's, so a node's own draw (reset its view, run its layers and draw modifiers, flush the
 * props) is needed only when something the node itself reads changed. The draw pass, however,
 * starts at the root and goes down only through nodes marked for it, so a mark has to be carried
 * up from a changed node to the root. Upstream `KNode` carried it by marking every ancestor as
 * changed itself, and each of them then redrew its own view for nothing — for an animated bar deep
 * in a list, every frame, the whole chain up to the root.
 *
 * Two marks keep the two meanings apart: [self], the node's own draw must run; [descendant], the
 * pass must come through here to reach a node below. [take] consumes them in the order the draw
 * pass needs.
 */
internal class DrawMarks {

    /** The node's own draw must run. New nodes start marked, as upstream's flag did. */
    var self: Boolean = true
        private set

    /** A node below must be drawn; the pass has to come through this one. */
    var descendant: Boolean = false
        private set

    /**
     * Marks the node itself. True when its parent must now be told that a descendant needs
     * drawing — that is, when the node was not already on a marked path. A node already marked
     * either way is reachable, or stuck exactly where upstream's single flag left it stuck (a
     * dirty child under a parent whose draw never reaches its children: an alpha-0 layer).
     */
    fun markSelf(): Boolean {
        val wasMarked = self || descendant
        self = true
        return !wasMarked
    }

    /** Marks the node as on the way to a changed descendant. True when its parent must be told too. */
    fun markDescendant(): Boolean {
        val wasMarked = self || descendant
        descendant = true
        return !wasMarked
    }

    /** What the draw pass does with this node now, clearing the marks it consumes. */
    fun take(): DrawAction = when {
        self -> {
            // The full draw goes on into the children itself.
            self = false
            descendant = false
            DrawAction.Draw
        }
        descendant -> {
            descendant = false
            DrawAction.Visit
        }
        else -> DrawAction.Skip
    }
}

internal enum class DrawAction {
    /** Run the node's own draw, which also goes on into its children. */
    Draw,

    /** Leave the node's view alone and go on into its children. */
    Visit,

    /** Nothing here or below has changed. */
    Skip,
}
