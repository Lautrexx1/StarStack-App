package com.starstack.app.processing

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Method

@RunWith(AndroidJUnit4::class)
class StackingEngineIntegrationTest {

    @Test
    fun testNativeLibraryLoads() {
        val engine = StackingEngine()
        assertNotNull(engine)
    }

    @Test
    fun testJniBoundaryMethodsExist() {
        val clazz = StackingEngine::class.java
        val methods = clazz.declaredMethods
        
        val expectedMethods = listOf(
            "nCreateSession",
            "nSetDarkFrame",
            "nSetFlatFrame",
            "nAddFrame",
            "nAddFrameBuffer",
            "nGetProgressivePreview",
            "nFinalizeStack",
            "nReleaseSession"
        )
        
        for (expected in expectedMethods) {
            val found = methods.any { it.name == expected }
            assertTrue("Native JNI method $expected was not found in StackingEngine class!", found)
        }
    }

    @Test
    fun testJniExecutionFlow() {
        val engine = StackingEngine()
        val clazz = StackingEngine::class.java
        
        val nCreateSession = clazz.getDeclaredMethod("nCreateSession", Int::class.java, Int::class.java, Int::class.java, Boolean::class.java).apply { isAccessible = true }
        val nSetDarkFrame = clazz.getDeclaredMethod("nSetDarkFrame", Long::class.java, ShortArray::class.java).apply { isAccessible = true }
        val nAddFrame = clazz.getDeclaredMethod("nAddFrame", Long::class.java, ByteArray::class.java).apply { isAccessible = true }
        val nFinalizeStack = clazz.getDeclaredMethod("nFinalizeStack", Long::class.java).apply { isAccessible = true }
        val nReleaseSession = clazz.getDeclaredMethod("nReleaseSession", Long::class.java).apply { isAccessible = true }

        val width = 3
        val height = 3
        val sessionHandle = nCreateSession.invoke(engine, width, height, 0, false) as Long
        assertNotEquals("Native session handle should not be zero", 0L, sessionHandle)

        // Set Dark Frame
        val darkData = ShortArray(width * height) { 10.toShort() }
        val darkSuccess = nSetDarkFrame.invoke(engine, sessionHandle, darkData) as Boolean
        assertTrue("Setting dark frame should succeed", darkSuccess)

        // Add 3 light frames (RGB interleaved = 27 bytes each)
        val frameData = ByteArray(width * height * 3) { 100.toByte() }
        for (i in 0 until 3) {
            val addSuccess = nAddFrame.invoke(engine, sessionHandle, frameData) as Boolean
            assertTrue("Adding frame $i should succeed", addSuccess)
        }

        // Finalise Stacking
        val finalData = nFinalizeStack.invoke(engine, sessionHandle) as ShortArray?
        assertNotNull("Finalized stacked data should not be null", finalData)
        assertEquals("Stacked image size should be width * height * 3 channels", (width * height * 3).toLong(), finalData!!.size.toLong())

        // Verify clean release
        nReleaseSession.invoke(engine, sessionHandle)
    }
}
