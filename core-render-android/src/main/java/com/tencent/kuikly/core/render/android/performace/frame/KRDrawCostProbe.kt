package com.tencent.kuikly.core.render.android.performace.frame

import android.view.View

/** Explicit host-owned diagnostic only. Normal rendering reads a null observer. */
object KRDrawCostProbe {
    interface Observer {
        fun begin(view: View, phase: String): Int
        fun end(ticket: Int)
        fun failed(error: Throwable)
    }

    @Volatile var observer: Observer? = null

    inline fun measure(view: View, phase: String, body: () -> Unit) {
        val owner = observer
        if (owner == null) { body(); return }
        val ticket = begin(owner, view, phase)
        try { body() } finally { end(owner, ticket) }
    }

    fun begin(observer: Observer?, view: View, phase: String): Int =
        if (observer == null) -1 else try { observer.begin(view, phase) }
        catch (error: Throwable) { runCatching { observer.failed(error) }; -1 }

    fun end(observer: Observer?, ticket: Int) {
        if (observer == null || ticket < 0) return
        try { observer.end(ticket) }
        catch (error: Throwable) { runCatching { observer.failed(error) } }
    }
}
