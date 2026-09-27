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

package com.tencent.kuikly.compose

import com.tencent.kuikly.lifecycle.Lifecycle

/**
 * Ronaq fork (CHANGES.md §52): a page container's lifecycle state, from two facts about its host
 * rather than from the last event heard.
 *
 * - [shown]: the host shows the page at all. True until the host says it hid it
 *   (`hostDidHide`: Android window invisible, iOS background, web document hidden), and again
 *   on `hostDidShow`.
 * - [resumed]: the page has appeared and not disappeared since (`viewDidAppear` /
 *   `viewDidDisappear`: Android resume/pause, iOS appear and become-active / resign-active).
 *
 * | shown | resumed | state |
 * | --- | --- | --- |
 * | yes | yes | RESUMED |
 * | yes | no | STARTED — before the first appear event, or paused while still visible (a system sheet, a permission dialog, iOS Control Center) |
 * | no | either | CREATED |
 *
 * The androidx meaning of the states (ON_PAUSE → STARTED, ON_STOP → CREATED). Two flags, because the
 * events arrive in either order: Android reports the window visible again after `onResume`, and the
 * first composition runs before any appear event, when the page is being put on the glass.
 */
internal class HostLifecycle {
    var shown: Boolean = true
        private set
    var resumed: Boolean = false
        private set

    val state: Lifecycle.State
        get() = when {
            !shown -> Lifecycle.State.CREATED
            resumed -> Lifecycle.State.RESUMED
            else -> Lifecycle.State.STARTED
        }

    fun appeared() {
        resumed = true
    }

    fun disappeared() {
        resumed = false
    }

    fun hid() {
        shown = false
    }

    fun showed() {
        shown = true
    }
}
