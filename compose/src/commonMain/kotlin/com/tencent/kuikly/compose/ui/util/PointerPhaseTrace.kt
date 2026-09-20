package com.tencent.kuikly.compose.ui.util

/** Temporary opt-in diagnostic; no pointer values or user data are recorded. */
object PointerPhaseTrace {
    var observer: ((String, Boolean) -> Unit)? = null

    inline fun <T> section(name: String, block: () -> T): T {
        val listener = observer ?: return block()
        listener(name, true)
        return try { block() } finally { listener(name, false) }
    }
}
