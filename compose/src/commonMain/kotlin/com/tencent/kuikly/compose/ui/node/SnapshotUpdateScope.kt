package com.tencent.kuikly.compose.ui.node

/** A native-view update whose snapshot reads must be replaced on each execution. */
internal interface SnapshotUpdateScope : OwnerScope {
    fun updateSnapshotReads()
}

// One identity for every target. SnapshotStateObserver groups its read maps by this callback;
// allocating a captured callback on each update retains another map for the life of the observer.
private val onCommitAffectingUpdate: (SnapshotUpdateScope) -> Unit = { target ->
    if (target.isValidOwnerScope) target.updateSnapshotReads()
}

internal fun OwnerSnapshotObserver.observeViewUpdate(target: SnapshotUpdateScope, block: () -> Unit) {
    observeReads(target, onCommitAffectingUpdate, block)
}
