package com.tencent.kuikly.compose.container

import com.tencent.kuikly.compose.HostLifecycle
import com.tencent.kuikly.lifecycle.Lifecycle

/**
 * Ronaq fork (CHANGES.md §60, which replaces §49's trigger): a Compose page's frames follow its host
 * lifecycle (§58). They are held only while the host hides the page — CREATED: the app in the
 * background, the screen locked, a full-screen page over it — and run while it is merely paused —
 * STARTED: a popup, a system dialog, Control Center over a page that is still on the glass.
 *
 * Held means the scene is paused through the term the upstream frame conditions carry for it
 * ([VsyncTickConditions.isApplicationActive]): recomposition, layout, drawing and frame-clock awaiters
 * (animations, `withFrameNanos`) wait; coroutines — `delay`, flows, view models — do not. The native
 * tick then stops through §59's paused listener, the one path that starts and stops it; this class
 * never touches the vsync source. Web, HarmonyOS and mini-app pages pause their scene the same way
 * (the web's timer stops through §59 too; the other two keep their timer).
 *
 * §49 held the frames on every disappearance, which also froze the scene under a system dialog; its
 * own stop of the vsync source is gone with it.
 *
 * View-free: the container hands in its scene's term and forwards its four host events.
 */
internal class PageFrameLoop(private val setAppActive: (Boolean) -> Unit) {
    private val host = HostLifecycle()
    private var held = false
    private var destroyed = false

    /** The page's lifecycle state, from whether its host shows it and whether it is resumed. */
    val state: Lifecycle.State get() = host.state

    /** The page appeared: resumed, and — as upstream did on every appearance — redrawn at once while shown. */
    fun appeared() {
        host.appeared()
        follow(redraw = true)
    }

    /** The page disappeared (a pause, a resign-active): STARTED while its host still shows it, frames running. */
    fun disappeared() {
        host.disappeared()
        follow()
    }

    /** The host stopped showing the page: CREATED, frames held once. */
    fun hid() {
        host.hid()
        follow()
    }

    /** The host shows the page again: frames run and draw at once. */
    fun showed() {
        host.showed()
        follow()
    }

    /** The page is going: the container stops everything itself. */
    fun destroyed() {
        destroyed = true
    }

    private fun follow(redraw: Boolean = false) {
        if (destroyed) return
        val hold = host.state == Lifecycle.State.CREATED
        if (hold != held) {
            held = hold
            setAppActive(!hold)
        } else if (redraw && !hold) {
            setAppActive(true)
        }
    }
}
