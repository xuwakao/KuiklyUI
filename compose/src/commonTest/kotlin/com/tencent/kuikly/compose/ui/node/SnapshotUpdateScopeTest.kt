package com.tencent.kuikly.compose.ui.node

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class SnapshotUpdateScopeTest {
    private class Scope(private val observer: OwnerSnapshotObserver) : SnapshotUpdateScope {
        override var isValidOwnerScope = true
        var update: () -> Unit = {}
        var executions = 0
        override fun updateSnapshotReads() {
            if (!isValidOwnerScope) return
            observer.observeViewUpdate(this) { executions++; update() }
        }
    }

    @Test
    fun repeatedUpdatesDoNotMultiplyNotifications() {
        val observer = OwnerSnapshotObserver { it() }
        val state = mutableStateOf(0)
        val scopes = List(30) { Scope(observer).apply { update = { state.value } } }
        observer.startObserving()
        try {
            repeat(100) { scopes.forEach { it.updateSnapshotReads() } }
            scopes.forEach { it.executions = 0 }
            state.value++
            Snapshot.sendApplyNotifications()
            scopes.forEach { assertEquals(1, it.executions) }
        } finally { observer.stopObserving() }
    }

    @Test
    fun conditionalReadsAreTrackedAgainAndOldDependenciesAreRemoved() {
        val observer = OwnerSnapshotObserver { it() }
        val useFirst = mutableStateOf(true)
        val first = mutableStateOf(0)
        val second = mutableStateOf(0)
        val scope = Scope(observer).apply { update = { if (useFirst.value) first.value else second.value } }
        observer.startObserving()
        try {
            scope.updateSnapshotReads()
            useFirst.value = false
            Snapshot.sendApplyNotifications()
            assertEquals(2, scope.executions)
            first.value++
            Snapshot.sendApplyNotifications()
            assertEquals(2, scope.executions, "Old dependencies must not keep invalidating the view")
            second.value++
            Snapshot.sendApplyNotifications()
            assertEquals(3, scope.executions)
        } finally { observer.stopObserving() }
    }

    @Test
    fun replacedUpdateDetachedAndReattachedScopesRemainIndependent() {
        val observer = OwnerSnapshotObserver { it() }
        val first = mutableStateOf(0)
        val second = mutableStateOf(0)
        val a = Scope(observer).apply { update = { first.value } }
        val b = Scope(observer).apply { update = { second.value } }
        observer.startObserving()
        try {
            a.updateSnapshotReads(); b.updateSnapshotReads()
            // SnapshotStateObserver replaces reads across snapshot epochs.
            Snapshot.withMutableSnapshot { a.update = { second.value }; a.updateSnapshotReads() }
            first.value++; Snapshot.sendApplyNotifications()
            assertEquals(2, a.executions)
            assertEquals(1, b.executions)
            a.isValidOwnerScope = false; observer.clear(a)
            second.value++; Snapshot.sendApplyNotifications()
            assertEquals(2, a.executions)
            assertEquals(2, b.executions)
            a.isValidOwnerScope = true; a.updateSnapshotReads()
            second.value++; Snapshot.sendApplyNotifications()
            assertEquals(4, a.executions)
            assertEquals(3, b.executions)
        } finally { observer.stopObserving() }
    }
}
