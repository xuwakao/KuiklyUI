package com.tencent.kuikly.core.render.android.scheduler

import java.util.concurrent.atomic.AtomicBoolean

/** A frame supersedes queued frames, while ordinary event callbacks remain lossless. */
internal class LatestFrameCallback(private val enqueue: (() -> Unit) -> Unit) {
    private val pending = AtomicBoolean(false)

    fun submit(frame: () -> Unit) {
        if (!pending.compareAndSet(false, true)) return
        try {
            enqueue {
                try { frame() } finally { pending.set(false) }
            }
        } catch (error: Throwable) {
            pending.set(false)
            throw error
        }
    }
}
