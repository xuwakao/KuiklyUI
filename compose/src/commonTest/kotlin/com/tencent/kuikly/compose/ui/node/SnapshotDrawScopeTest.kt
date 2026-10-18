package com.tencent.kuikly.compose.ui.node

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class SnapshotDrawScopeTest {
    private class Scope(private val observer: OwnerSnapshotObserver) : SnapshotDrawScope {
        override var isValidOwnerScope = true
        var invalidations = 0
        var draw: () -> Unit = {}
        override fun invalidateSnapshotDraw() { invalidations++ }
        fun render() = observer.observeDrawReads(this, draw)
    }

    @Test
    fun repeatedFramesInvalidateEachTargetOnlyOnce() {
        val observer = OwnerSnapshotObserver { it() }
        val value = mutableStateOf(0)
        val scopes = List(30) { Scope(observer).apply { draw = { value.value } } }
        observer.startObserving()
        try {
            repeat(300) { scopes.forEach { it.render() } }
            value.value++
            Snapshot.sendApplyNotifications()
            scopes.forEach { assertEquals(1, it.invalidations) }
            // Invalidation schedules a later frame; it must not synchronously run drawing.
            scopes.forEach { it.render() }
            value.value++
            Snapshot.sendApplyNotifications()
            scopes.forEach { assertEquals(2, it.invalidations) }
        } finally { observer.stopObserving() }
    }

    @Test
    fun newDrawReadsReplaceOldOnesWithoutAffectingOtherTargets() {
        val observer = OwnerSnapshotObserver { it() }
        val first = mutableStateOf(0)
        val second = mutableStateOf(0)
        val a = Scope(observer).apply { draw = { first.value } }
        val b = Scope(observer).apply { draw = { first.value } }
        observer.startObserving()
        try {
            a.render(); b.render()
            // SnapshotStateObserver replaces reads across snapshot epochs.
            Snapshot.withMutableSnapshot { a.draw = { second.value }; a.render() }
            first.value++; Snapshot.sendApplyNotifications()
            assertEquals(0, a.invalidations)
            assertEquals(1, b.invalidations)
            second.value++; Snapshot.sendApplyNotifications()
            assertEquals(1, a.invalidations)
            assertEquals(1, b.invalidations)
            a.isValidOwnerScope = false; observer.clear(a)
            second.value++; Snapshot.sendApplyNotifications()
            assertEquals(1, a.invalidations)
            a.isValidOwnerScope = true; a.render()
            second.value++; Snapshot.sendApplyNotifications()
            assertEquals(2, a.invalidations)
            observer.stopObserving()
            first.value++; second.value++; Snapshot.sendApplyNotifications()
            assertEquals(2, a.invalidations)
            assertEquals(1, b.invalidations)
        } finally { observer.stopObserving() }
    }
}
