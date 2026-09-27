package com.tencent.kuikly.compose.foundation.lazy.layout

import com.tencent.kuikly.compose.foundation.ComposeFoundationFlags
import com.tencent.kuikly.compose.foundation.ExperimentalFoundationApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** CHANGES.md §49: a trace message is built only when tracing is on. */
@OptIn(ExperimentalFoundationApi::class)
class LazyListPrefetchTraceTest {

    @AfterTest
    fun tearDown() {
        ComposeFoundationFlags.isLazyListPrefetchTraceEnabled = false
        ComposeFoundationFlags.lazyListPrefetchTraceListener = null
    }

    @Test
    fun offBuildsNoMessage() {
        ComposeFoundationFlags.isLazyListPrefetchTraceEnabled = false
        var built = 0
        repeat(3) { LazyListPrefetchTrace.log { built++; "frameEnd" } }
        assertEquals(0, built)
    }

    @Test
    fun onBuildsEachMessageOnceAndHandsItToTheListener() {
        val heard = mutableListOf<String>()
        ComposeFoundationFlags.isLazyListPrefetchTraceEnabled = true
        ComposeFoundationFlags.lazyListPrefetchTraceListener = { heard += it }
        var built = 0
        LazyListPrefetchTrace.log { built++; "frameEnd n=$built" }
        assertEquals(1, built)
        assertEquals(listOf("frameEnd n=1"), heard)
    }
}
