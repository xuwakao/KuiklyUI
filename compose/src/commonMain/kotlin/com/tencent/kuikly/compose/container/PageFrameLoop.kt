package com.tencent.kuikly.compose.container

/**
 * Ronaq fork (CHANGES.md §49): a Compose page's frame loop runs only while the page is shown.
 *
 * `ComposeContainer` moved only its lifecycle when its page disappeared — the app in the
 * background, the screen locked, a page over it — and the scene went on producing frames: the
 * vsync source (a 60 Hz GCD timer on iOS, a Choreographer callback re-posted every frame on
 * Android) woke the Kotlin side every display refresh, and every running animation recomposed,
 * laid out and drew until the page was destroyed. Now a disappearance pauses the scene through the
 * term the upstream frame conditions carry for it ([VsyncTickConditions.isApplicationActive]) and
 * stops the vsync source; an appearance starts the source again and resumes the scene, which draws
 * at once. While paused, recomposition, layout, drawing and frame-clock awaiters (animations) wait;
 * coroutines — `delay`, flows, view models — do not.
 *
 * View-free: the container hands in its scene's term and its source's start and stop.
 */
internal class PageFrameLoop(
    private val setAppActive: (Boolean) -> Unit,
    private val startSource: () -> Unit,
    private val stopSource: () -> Unit,
) {
    private var sourceStarted = false
    private var shown = true
    private var destroyed = false

    /** The container started its vsync source (with the page). */
    fun started() {
        if (destroyed) return
        sourceStarted = true
        if (!shown) stopSource()
    }

    /** The page appeared: the source again if it was stopped, and the scene resumed and redrawn. */
    fun appeared() {
        if (destroyed) return
        if (!shown) {
            shown = true
            if (sourceStarted) startSource()
        }
        setAppActive(true)
    }

    /** The page disappeared: the scene paused and the source stopped, once. */
    fun disappeared() {
        if (destroyed || !shown) return
        shown = false
        setAppActive(false)
        if (sourceStarted) stopSource()
    }

    /** The page is going: the container stops everything itself. */
    fun destroyed() {
        destroyed = true
    }
}
