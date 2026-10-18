package com.tencent.kuikly.compose.profiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ComposeScenePhaseProbeTest {
    @Test fun originalFailureClosesPhaseAndIsNotReplacedByObserverFailure() {
        val previous=ComposeScenePhaseProbe.observer
        val original=IllegalArgumentException("body")
        var ended=0;var failures=0
        try {
            ComposeScenePhaseProbe.observer=object:ComposeScenePhaseProbe.Observer {
                override fun begin(phase:String) { assertEquals("layout",phase) }
                override fun end() { ended++;throw IllegalStateException("diagnostic") }
                override fun failed(error:Throwable) { failures++;throw IllegalStateException("report") }
            }
            val observed=assertFailsWith<IllegalArgumentException> {
                ComposeScenePhaseProbe.measure("layout") { throw original }
            }
            assertSame(original,observed);assertEquals(1,ended);assertEquals(1,failures)
        } finally { ComposeScenePhaseProbe.observer=previous }
    }

    @Test fun failedBeginStillRunsBodyExactlyOnceWithoutUnmatchedEnd() {
        val previous=ComposeScenePhaseProbe.observer
        var bodies=0;var ended=0
        try {
            ComposeScenePhaseProbe.observer=object:ComposeScenePhaseProbe.Observer {
                override fun begin(phase:String) { throw IllegalStateException("begin") }
                override fun end() { ended++ }
                override fun failed(error:Throwable) {}
            }
            assertEquals(17,ComposeScenePhaseProbe.measure("draw") { bodies++;17 })
            assertEquals(1,bodies);assertEquals(0,ended)
        } finally { ComposeScenePhaseProbe.observer=previous }
    }

    @Test fun nullObserverPreservesBodyReturn() {
        val previous=ComposeScenePhaseProbe.observer
        try {
            ComposeScenePhaseProbe.observer=null
            assertEquals("value",ComposeScenePhaseProbe.measure("draw") { "value" })
        } finally { ComposeScenePhaseProbe.observer=previous }
    }
}
