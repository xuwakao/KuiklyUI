package com.tencent.kuikly.compose

import com.tencent.kuikly.lifecycle.Lifecycle.State.CREATED
import com.tencent.kuikly.lifecycle.Lifecycle.State.RESUMED
import com.tencent.kuikly.lifecycle.Lifecycle.State.STARTED
import kotlin.test.Test
import kotlin.test.assertEquals

/** CHANGES.md §58: the page container's lifecycle state from "shown" and "resumed". */
class HostLifecycleTest {

    @Test
    fun aLaunchBeforeAnyAppearEventIsVisible() {
        // The first composition runs in onCreatePager, before viewDidAppear.
        assertEquals(STARTED, HostLifecycle().state)
    }

    @Test
    fun pausedButShownIsStartedAndHiddenIsCreated() {
        val host = HostLifecycle()
        host.appeared()
        assertEquals(RESUMED, host.state)
        host.disappeared() // a system sheet, a permission dialog, iOS resign-active
        assertEquals(STARTED, host.state)
        host.hid() // the activity stopped, the app went to the background, the tab was hidden
        assertEquals(CREATED, host.state)
    }

    @Test
    fun theReturnEndsResumedInEitherEventOrder() {
        // Android: onResume (appear) arrives before the window reports itself visible again.
        val android = HostLifecycle().apply { appeared(); disappeared(); hid() }
        android.appeared()
        assertEquals(CREATED, android.state, "resumed while the window is still hidden")
        android.showed()
        assertEquals(RESUMED, android.state)
        // iOS: will-enter-foreground (show) arrives before did-become-active (appear).
        val ios = HostLifecycle().apply { appeared(); disappeared(); hid() }
        ios.showed()
        assertEquals(STARTED, ios.state)
        ios.appeared()
        assertEquals(RESUMED, ios.state)
    }

    @Test
    fun hidingWhileResumedStillHides() {
        val host = HostLifecycle().apply { appeared() }
        host.hid()
        assertEquals(CREATED, host.state)
        host.showed()
        assertEquals(RESUMED, host.state)
    }
}
