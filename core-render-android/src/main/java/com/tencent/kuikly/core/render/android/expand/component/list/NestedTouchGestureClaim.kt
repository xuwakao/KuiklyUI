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

package com.tencent.kuikly.core.render.android.expand.component.list

/**
 * Ronaq fork (CHANGES.md §33): whether a list nested in a horizontal list keeps the current touch
 * gesture's horizontal motion from the lists it is nested in. `KRRecyclerView` holds one per list
 * and asks it at every nested move; it holds no view, so it is tested on the JVM
 * (`NestedTouchGestureClaimTest`).
 *
 * Decided once per gesture, at its first nested move the list does not give its parent first. A
 * list that could scroll that way then keeps the whole gesture: the finger past its end does not
 * move the list around it. A list that could not lets the parent have the gesture, as before.
 * That is what a scrollable child gets inside androidx `ViewPager`: the first move a nested view
 * can scroll sets `mIsUnableToDrag` for the rest of the gesture (`ViewPager.java:2080-2085`,
 * `:2046-2050`, viewpager 1.0.0), and the reference client's tag strips sit in one (lingoandroid
 * `fragment_party.xml:56`; `fragment_hot.xml:123-136`, `fragment_mine.xml:83-100`).
 */
internal class NestedTouchGestureClaim {

    private var claim = UNDECIDED

    /** A new gesture decides afresh (`ACTION_DOWN`). */
    fun reset() {
        claim = UNDECIDED
    }

    /**
     * Whether the list keeps the nested move [dx] (RecyclerView's sign: positive scrolls towards
     * the right end) to itself.
     *
     * @param isPager the list pages or does not fling (a Compose pager). A pager never keeps a
     *   gesture, so a pager nested in a pager hands over as §26 left it.
     * @param parentFirst the list declared PARENT_FIRST for [dx]'s direction.
     * @param canScroll whether the list could scroll by a given dx; asked only when deciding.
     */
    fun keeps(dx: Int, isPager: Boolean, parentFirst: Boolean, canScroll: (Int) -> Boolean): Boolean {
        if (isPager) {
            return false
        }
        // PARENT_FIRST asks the parent to move first on every move that way
        // (`KRRecyclerView.scrollParentIfNeeded`); a kept gesture does not override a declared
        // mode, and such a move decides nothing (review of 2026-09-23, fork finding F2).
        if (dx != 0 && parentFirst) {
            return false
        }
        if (claim == UNDECIDED && dx != 0) {
            claim = if (canScroll(dx)) KEPT else RELEASED
        }
        return claim == KEPT
    }

    private companion object {
        const val UNDECIDED = 0
        const val KEPT = 1
        const val RELEASED = 2
    }
}
