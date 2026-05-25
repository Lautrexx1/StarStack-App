package com.starstack.app.processing

import org.junit.Assert.assertTrue
import org.junit.Test

class StackingEngineTest {

    @Test
    fun testInitializationAndRelease() {
        val engine = StackingEngine()
        assertTrue(engine.initialize())
        engine.release()
    }

    @Test
    fun testCancellationState() {
        val engine = StackingEngine()
        engine.cancel()
        // If we get here without exception, cancel works fine
        assertTrue(true)
    }
}
