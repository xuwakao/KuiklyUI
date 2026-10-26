package com.tencent.kuikly.core.render.android.scheduler

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LatestFrameCallbackTest {
    @Test fun slowConsumerCollapsesQueuedTicksButAcceptsNextFrame() {
        val queue = mutableListOf<() -> Unit>()
        val gate = LatestFrameCallback { queue.add(it) }
        var frames = 0
        repeat(120) { gate.submit { frames++ } }
        assertEquals(1, queue.size)
        queue.removeAt(0)()
        assertEquals(1, frames)
        gate.submit { frames++ }
        queue.removeAt(0)()
        assertEquals(2, frames)
    }

    @Test fun callbackFailureDoesNotWedgeLaterFrames() {
        val queue = mutableListOf<() -> Unit>()
        val gate = LatestFrameCallback { queue.add(it) }
        gate.submit { error("callback failed") }
        runCatching { queue.removeAt(0)() }.onSuccess { fail("expected failure") }
        var frames = 0
        gate.submit { frames++ }
        queue.removeAt(0)()
        assertEquals(1, frames)
    }

    @Test fun rejectedEnqueueCanBeSubmittedAgain() {
        var reject = true
        var calls = 0
        val gate = LatestFrameCallback { if (reject) error("rejected") else it() }
        assertTrue(runCatching { gate.submit { calls++ } }.isFailure)
        reject = false
        gate.submit { calls++ }
        assertEquals(1, calls)
    }

    @Test fun independentRegistrationsDoNotSuppressEachOther() {
        val queue = mutableListOf<() -> Unit>()
        val first = LatestFrameCallback { queue.add(it) }
        val second = LatestFrameCallback { queue.add(it) }
        first.submit { }
        second.submit { }
        assertEquals(2, queue.size)
    }

    @Test fun runningFrameRejectsConcurrentTicks() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        var calls = 0
        val gate = LatestFrameCallback { executor.submit(it) }
        try {
            gate.submit {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                calls++
                done.countDown()
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(120) { gate.submit { fail("overlapping tick") } }
            release.countDown()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            executor.shutdown()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            assertEquals(1, calls)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
