package com.tencent.kuikly.compose.profiler

import kotlin.concurrent.Volatile

/** Explicit host diagnostic. Null by default; never changes frame scheduling or scene work. */
object ComposeScenePhaseProbe {
    interface Observer {
        fun begin(phase: String)
        fun end()
        fun failed(error: Throwable)
    }

    @Volatile var observer: Observer? = null

    inline fun <T> measure(phase: String, body: () -> T): T {
        val owner = observer ?: return body()
        val begun = try { owner.begin(phase); true }
        catch (error: Throwable) { runCatching { owner.failed(error) }; false }
        try { return body() }
        finally {
            if (begun) try { owner.end() }
            catch (error: Throwable) { runCatching { owner.failed(error) } }
        }
    }
}
