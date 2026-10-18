package com.tencent.kuikly.compose.ui.node

/** Snapshot-dependent drawing invalidates now and observes fresh reads on the next draw. */
internal interface SnapshotDrawScope : OwnerScope {
    fun invalidateSnapshotDraw()
}

// A callable reference created at the draw call site is a new function on Kotlin/JS. The
// observer keys its maps by callback identity, so the callback must live outside the draw.
private val onCommitAffectingDraw: (SnapshotDrawScope) -> Unit = { target ->
    if (target.isValidOwnerScope) target.invalidateSnapshotDraw()
}

internal fun OwnerSnapshotObserver.observeDrawReads(target: SnapshotDrawScope, block: () -> Unit) {
    observeReads(target, onCommitAffectingDraw, block)
}
