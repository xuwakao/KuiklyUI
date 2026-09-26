package com.tencent.kuikly.compose.coil3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Ronaq fork (CHANGES.md §45): the source a painter is given while remote loads are held. */
class DeferredImageSourceTest {

    @Test
    fun aRemoteSourceIsHeldWhileLoadingIsOff() {
        assertNull(deferredImageSource("https://cdn.example/a.png", loading = false))
        assertNull(deferredImageSource("http://cdn.example/a.png", loading = false))
    }

    @Test
    fun bundledAndInlineSourcesKeepDrawingWhileLoadingIsOff() {
        assertEquals("assets://common/a.png", deferredImageSource("assets://common/a.png", loading = false))
        assertEquals("data:image/png;base64,AAAA", deferredImageSource("data:image/png;base64,AAAA", loading = false))
        assertNull(deferredImageSource(null, loading = false))
    }

    @Test
    fun everySourcePassesOnceLoadingIsOn() {
        assertEquals("https://cdn.example/a.png", deferredImageSource("https://cdn.example/a.png", loading = true))
        assertEquals("assets://common/a.png", deferredImageSource("assets://common/a.png", loading = true))
        assertNull(deferredImageSource(null, loading = true))
    }
}
