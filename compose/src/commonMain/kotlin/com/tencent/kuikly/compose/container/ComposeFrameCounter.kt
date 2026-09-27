package com.tencent.kuikly.compose.container

import kotlin.concurrent.Volatile

/**
 * Ronaq fork (CHANGES.md §47): what the Compose frame loop does, readable by a host without a
 * debugger. Process-wide totals since launch, summed over every Compose page:
 *
 *  - [vsyncTicks]: frame callbacks the page's vsync source delivered to the scene
 *    (`ComposeSceneMediator.renderFrame`, through [VsyncTickConditions.onDisplayLinkTick]),
 *    whether or not a frame was due. While this rises the source is running: the Kotlin side
 *    is woken once per display refresh.
 *  - [framesRendered]: frames the scene actually produced (recomposition, layout, effects and
 *    draw; `BaseComposeScene.render` past its pause check).
 *
 * Each counter has one writer, the thread the scene runs on, so a plain volatile increment
 * loses nothing; readers on other threads see a recent value. A reader takes differences
 * between two reads.
 */
object ComposeFrameCounter {

    @Volatile
    var vsyncTicks: Long = 0L
        private set

    @Volatile
    var framesRendered: Long = 0L
        private set

    @PublishedApi
    internal fun noteVsyncTick() {
        vsyncTicks += 1
    }

    internal fun noteFrameRendered() {
        framesRendered += 1
    }
}
