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

import com.tencent.kuikly.core.render.android.expand.visibility.KRUnseenPause.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ronaq fork (CHANGES.md §41): what an animation does when its view cannot be seen. */
class KRUnseenPauseTest {

    @Test
    fun aLoopingAnimationPausesWhenUnseenAndResumesWhenSeen() {
        val rule = KRUnseenPause()
        assertEquals(Action.PAUSE, rule.decide(visible = false, running = true, pausable = true))
        assertTrue(rule.pausedUnseen)
        assertEquals(Action.RESUME, rule.decide(visible = true, running = false, pausable = true))
        assertFalse(rule.pausedUnseen)
    }

    @Test
    fun aOneShotIsPausedAndResumedLikeALoop() {
        // CHANGES.md §50: nothing plays where nobody can see it; the caller holds a one-shot in place.
        val rule = KRUnseenPause()
        assertEquals(Action.PAUSE, rule.decide(visible = false, running = true, pausable = true))
        assertEquals(Action.RESUME, rule.decide(visible = true, running = false, pausable = true))
    }

    @Test
    fun anAnimationItsCallerCannotHoldIsNeverPaused() {
        val rule = KRUnseenPause()
        assertEquals(Action.NONE, rule.decide(visible = false, running = true, pausable = false))
        assertEquals(Action.NONE, rule.decide(visible = true, running = true, pausable = false))
    }

    @Test
    fun anAnimationThatStoppedOnItsOwnIsNotRestarted() {
        val rule = KRUnseenPause()
        assertEquals(Action.NONE, rule.decide(visible = false, running = false, pausable = true))
        assertEquals(Action.NONE, rule.decide(visible = true, running = false, pausable = true))
    }

    @Test
    fun anUnknownVisibilityChangesNothing() {
        val rule = KRUnseenPause()
        assertEquals(Action.NONE, rule.decide(visible = null, running = true, pausable = true))
        assertEquals(Action.PAUSE, rule.decide(visible = false, running = true, pausable = true))
        assertEquals(Action.NONE, rule.decide(visible = null, running = false, pausable = true),
            "not laid out yet: still paused, not resumed")
        assertTrue(rule.pausedUnseen)
    }

    @Test
    fun repeatedNoticesPauseAndResumeOnce() {
        val rule = KRUnseenPause()
        assertEquals(Action.PAUSE, rule.decide(false, true, true))
        assertEquals(Action.NONE, rule.decide(false, false, true))
        assertEquals(Action.NONE, rule.decide(false, true, true), "already paused by this rule")
        assertEquals(Action.RESUME, rule.decide(true, false, true))
        assertEquals(Action.NONE, rule.decide(true, true, true))
    }

    @Test
    fun newContentForgetsThePause() {
        val rule = KRUnseenPause()
        rule.decide(false, true, true)
        rule.reset()
        assertEquals(Action.NONE, rule.decide(true, false, true), "a new picture starts by itself")
    }
}
