package com.starstack.app.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Kotlin-side boundary interface for the C++ Native Stacking Engine.
 * Responsible for memory-safe lifecycle management of the native handles and coordinating
 * the execution of raw frame alignment, calibration (darks/flats subtraction), and co-addition.
 */
class StackingEngine {

    // Address of the underlying native C++ engine structure (0 means uninitialized)
    private var nativeContextHandle: Long = 0

    @Volatile
    private var isProcessing = false

    @Volatile
    private var isCancelled = false

    companion object {
        private const val TAG = "StackingEngine"

        init {
            try {
                System.loadLibrary("starstack-native")
                Log.i(TAG, "Native starstack-native library loaded successfully.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library starstack-native. Ensure CMake/NDK build is correct.", e)
            }
        }
    }

    /**
     * Initializes the native stacking engine context placeholder.
     * Must be called before initiating any alignment or stacking operation.
     * @return true if initialized successfully.
     */
    synchronized fun initialize(): Boolean {
        Log.i(TAG, "StackingEngine initialized.")
        return true
    }

    /**
     * Terminate engine operations and release native memory resources.
     */
    synchronized fun release() {
        if (nativeContextHandle != 0L) {
            nReleaseSession(nativeContextHandle)
            nativeContextHandle = 0L
            Log.i(TAG, "Native StackingEngine context released.")
        }
    }

    /**
     * Executes the alignment and stacking algorithms asynchronously under native control.
     * Calibrates light frames using dark and flat frames.
     *
     * Features:
     * - Thread-safety guards
     * - Low-memory detection and adaptive resolution downsampling (OOM Protection)
     * - Immediate Bitmap recycling and Garbage Collection hint (Streamed Chunking)
     * - Graceful thread cancellation check
     * - Dynamic CPU thermal throttling and hardware battery safety monitoring
     * - Standard 8-bit output fallback or high-fidelity custom 16-bit uncompressed TIFF exporter
     */
    fun alignAndStack(
        context: Context?,
        lightPaths: Array<String>,
        darkPaths: Array<String>,
        flatPaths: Array<String>,
        outputPath: String,
        callback: StackingProgressCallback
    ): Boolean {
        synchronized(this) {
            if (isProcessing) {
                callback.onError(-3, "A stacking session is already in progress.")
                return false
            }
            isProcessing = true
            isCancelled = false
        }

        val safetyMonitor = context?.let { HardwareSafetyMonitor(it) }

        Thread {
            var sessionHandle = 0L
            try {
                if (lightPaths.isEmpty()) {
                    callback.onError(-2, "No light frames provided for stacking.")
                    finishProcessing()
                    return@Thread
                }

                // 1. Calculate adaptive sample size based on the first frame to avoid OOM
                val runtime = Runtime.getRuntime()
                val freeMemory = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
                val sampleSize = calculateAdaptiveSampleSize(lightPaths[0], freeMemory)
                
                // Get dimensions of first frame to create session
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(lightPaths[0], options)
                val width = options.outWidth / sampleSize
                val height = options.outHeight / sampleSize

                if (width <= 0 || height <= 0) {
                    callback.onError(-4, "Invalid frame dimensions detected.")
                    finishProcessing()
                    return@Thread
                }

                callback.onProgress(0.0f, "Initializing native stacking session (${width}x${height})...")
                
                // 2. Initialize native session (StackMode: 0 = MEAN, enableAlignment = true)
                sessionHandle = nCreateSession(width, height, 0, true)
                if (sessionHandle == 0L) {
                    callback.onError(-5, "Failed to create native stacking session.")
                    finishProcessing()
                    return@Thread
                }

                synchronized(this) {
                    nativeContextHandle = sessionHandle
                }

                // 3. Load and set Dark frame if provided
                if (darkPaths.isNotEmpty() && !isCancelled) {
                    callback.onProgress(0.05f, "Loading dark noise calibration frame...")
                    val darkBitmap = loadBitmapAdaptive(darkPaths[0], sampleSize)
                    if (darkBitmap != null) {
                        val darkData = extractDarkData(darkBitmap)
                        darkBitmap.recycle()
                        System.gc()
                        nSetDarkFrame(sessionHandle, darkData)
                    }
                }

                // 4. Load and set Flat frame if provided
                if (flatPaths.isNotEmpty() && !isCancelled) {
                    callback.onProgress(0.1f, "Loading flat field calibration frame...")
                    val flatBitmap = loadBitmapAdaptive(flatPaths[0], sampleSize)
                    if (flatBitmap != null) {
                        val flatData = extractFlatData(flatBitmap)
                        flatBitmap.recycle()
                        System.gc()
                        nSetFlatFrame(sessionHandle, flatData)
                    }
                }

                // 5. Feed Light frames one-by-one in a chunked manner
                val totalFrames = lightPaths.size
                var stackedCount = 0
                for (i in 0 until totalFrames) {
                    // Thermal safety suspend check
                    if (safetyMonitor?.shouldSuspendProcessing() == true) {
                        callback.onError(-10, "Critical thermal stress detected. Suspended high-load stacking process.")
                        nReleaseSession(sessionHandle)
                        synchronized(this) { nativeContextHandle = 0L }
                        finishProcessing()
                        return@Thread
                    }

                    if (isCancelled) {
                        callback.onError(-99, "Processing was cancelled.")
                        nReleaseSession(sessionHandle)
                        synchronized(this) { nativeContextHandle = 0L }
                        finishProcessing()
                        return@Thread
                    }

                    // Thermal and power safety throttle delay injection
                    val throttleDelay = safetyMonitor?.getThrottleDelay() ?: 0L
                    if (throttleDelay > 0) {
                        Log.i(TAG, "Throttling CPU: sleeping for ${throttleDelay}ms")
                        Thread.sleep(throttleDelay)
                    }

                    val path = lightPaths[i]
                    val progress = 0.1f + 0.7f * (i.toFloat() / totalFrames)
                    callback.onProgress(progress, "Processing light frame ${i + 1}/$totalFrames...")

                    val bitmap = loadBitmapAdaptive(path, sampleSize)
                    if (bitmap != null) {
                        val rgbBytes = extractRgbBytes(bitmap)
                        bitmap.recycle()
                        System.gc() // Immediate GC invocation

                        val success = nAddFrame(sessionHandle, rgbBytes)
                        if (success) {
                            stackedCount++
                        }
                    }
                }

                if (stackedCount == 0) {
                    callback.onError(-6, "No frames were successfully stacked.")
                    nReleaseSession(sessionHandle)
                    synchronized(this) { nativeContextHandle = 0L }
                    finishProcessing()
                    return@Thread
                }

                // 6. Finalise stack
                if (isCancelled) {
                    callback.onError(-99, "Processing was cancelled.")
                    nReleaseSession(sessionHandle)
                    synchronized(this) { nativeContextHandle = 0L }
                    finishProcessing()
                    return@Thread
                }

                callback.onProgress(0.9f, "Finalizing stacked high-fidelity image...")
                val finalizedData = nFinalizeStack(sessionHandle)
                if (finalizedData == null) {
                    callback.onError(-7, "Failed to finalize stacked image in native layer.")
                    nReleaseSession(sessionHandle)
                    synchronized(this) { nativeContextHandle = 0L }
                    finishProcessing()
                    return@Thread
                }

                // Write final image to file
                callback.onProgress(0.95f, "Saving finalized image to disk...")
                val saveSuccess = saveStackedImage(finalizedData, width, height, outputPath)
                if (saveSuccess) {
                    callback.onProgress(1.0f, "Stacking completed successfully.")
                    callback.onComplete(outputPath)
                } else {
                    callback.onError(-8, "Failed to write finalized image to output path: $outputPath")
                }

                nReleaseSession(sessionHandle)
                synchronized(this) { nativeContextHandle = 0L }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in background stacking thread", e)
                callback.onError(-9, "Unexpected error: ${e.message}")
                if (sessionHandle != 0L) {
                    nReleaseSession(sessionHandle)
                    synchronized(this) { nativeContextHandle = 0L }
                }
            } finally {
                finishProcessing()
            }
        }.start()

        return true
    }

    /**
     * Dynamic overload to support backward compatible signature.
     */
    fun alignAndStack(
        lightPaths: Array<String>,
        darkPaths: Array<String>,
        flatPaths: Array<String>,
        outputPath: String,
        callback: StackingProgressCallback
    ): Boolean {
        return alignAndStack(null, lightPaths, darkPaths, flatPaths, outputPath, callback)
    }

    /**
     * Request immediate cancellation of any ongoing stacking operation.
     */
    fun cancel() {
        synchronized(this) {
            isCancelled = true
            Log.i(TAG, "Cancellation requested for active stacking session.")
        }
    }

    private fun finishProcessing() {
        synchronized(this) {
            isProcessing = false
        }
    }

    // --- Helper Methods for Memory Safety & Calibration Extraction ---

    private fun calculateAdaptiveSampleSize(filePath: String, freeMemoryBytes: Long): Int {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(filePath, options)
        val width = options.outWidth
        val height = options.outHeight
        val estimatedBytes = width * height * 4L // RGBA estimated heap consumption

        var sampleSize = 1
        // If a single decoded image consumes > 20% of free memory, downscale
        while (estimatedBytes / (sampleSize * sampleSize) > freeMemoryBytes * 0.20) {
            sampleSize *= 2
        }
        if (sampleSize > 1) {
            Log.w(TAG, "Low memory detected (${freeMemoryBytes / 1024 / 1024}MB free). Adapting downsampling factor to: $sampleSize")
        }
        return sampleSize
    }

    private fun loadBitmapAdaptive(filePath: String, sampleSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            BitmapFactory.decodeFile(filePath, options)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM decoding: $filePath, retrying with increased downsampling", e)
            options.inSampleSize = sampleSize * 2
            try {
                BitmapFactory.decodeFile(filePath, options)
            } catch (e2: OutOfMemoryError) {
                Log.e(TAG, "Fatal OOM decoding: $filePath", e2)
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding file: $filePath", e)
            null
        }
    }

    private fun extractRgbBytes(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val rgbBytes = ByteArray(width * height * 3)
        var byteIdx = 0
        for (pixel in pixels) {
            rgbBytes[byteIdx++] = ((pixel shr 16) and 0xFF).toByte()
            rgbBytes[byteIdx++] = ((pixel shr 8) and 0xFF).toByte()
            rgbBytes[byteIdx++] = (pixel and 0xFF).toByte()
        }
        return rgbBytes
    }

    private fun extractDarkData(bitmap: Bitmap): ShortArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val darkData = ShortArray(width * height)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val gray = (r * 299 + g * 587 + b * 114) / 1000
            darkData[i] = gray.toShort()
        }
        return darkData
    }

    private fun extractFlatData(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val flatData = FloatArray(width * height)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val gray = (r * 299 + g * 587 + b * 114) / 1000
            flatData[i] = gray.toFloat() / 255.0f // Normalise flat to [0.0, 1.0]
        }
        return flatData
    }

    private fun saveStackedImage(
        shortArray: ShortArray,
        width: Int,
        height: Int,
        outputPath: String
    ): Boolean {
        val isTiff = outputPath.endsWith(".tif", ignoreCase = true) || outputPath.endsWith(".tiff", ignoreCase = true)
        if (isTiff) {
            return write16BitTiff(shortArray, width, height, outputPath)
        }

        // Standard 8-bit JPEG/PNG export fallback
        val pixels = IntArray(width * height)
        for (i in 0 until width * height) {
            val rVal = ((shortArray[i * 3 + 0].toInt() and 0xFFFF) shr 8).coerceIn(0, 255)
            val gVal = ((shortArray[i * 3 + 1].toInt() and 0xFFFF) shr 8).coerceIn(0, 255)
            val bVal = ((shortArray[i * 3 + 2].toInt() and 0xFFFF) shr 8).coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (rVal shl 16) or (gVal shl 8) or bVal
        }

        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val file = File(outputPath)
        file.parentFile?.mkdirs()
        
        return try {
            file.outputStream().use { out ->
                val format = if (outputPath.endsWith(".png", ignoreCase = true)) {
                    Bitmap.CompressFormat.PNG
                } else {
                    Bitmap.CompressFormat.JPEG
                }
                bitmap.compress(format, 95, out)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving final image to $outputPath", e)
            false
        } finally {
            bitmap.recycle()
        }
    }

    private fun write16BitTiff(
        shortArray: ShortArray,
        width: Int,
        height: Int,
        outputPath: String
    ): Boolean {
        val file = File(outputPath)
        file.parentFile?.mkdirs()
        try {
            file.outputStream().use { out ->
                // TIFF Header (Intel little-endian)
                out.write(byteArrayOf(0x49, 0x49)) 
                out.write(byteArrayOf(0x2A, 0x00)) 
                
                val ifdOffset = 8
                out.write(byteArrayOf(
                    (ifdOffset and 0xFF).toByte(),
                    ((ifdOffset shr 8) and 0xFF).toByte(),
                    ((ifdOffset shr 16) and 0xFF).toByte(),
                    ((ifdOffset shr 24) and 0xFF).toByte()
                ))

                val imageDataOffset = 200
                val totalPixelBytes = width * height * 6L 

                // IFD entry count (11 entries)
                out.write(byteArrayOf(11, 0))

                fun writeEntry(tag: Int, type: Int, count: Long, value: Long) {
                    out.write(byteArrayOf((tag and 0xFF).toByte(), ((tag shr 8) and 0xFF).toByte()))
                    out.write(byteArrayOf((type and 0xFF).toByte(), ((type shr 8) and 0xFF).toByte()))
                    out.write(byteArrayOf(
                        (count and 0xFF).toByte(),
                        ((count shr 16) and 0xFF).toByte(),
                        ((count shr 24) and 0xFF).toByte(),
                        ((count shr 32) and 0xFF).toByte()
                    ))
                    out.write(byteArrayOf(
                        (value and 0xFF).toByte(),
                        ((value shr 8) and 0xFF).toByte(),
                        ((value shr 16) and 0xFF).toByte(),
                        ((value shr 24) and 0xFF).toByte()
                    ))
                }

                writeEntry(0x0100, 4, 1, width.toLong())
                writeEntry(0x0101, 4, 1, height.toLong())
                writeEntry(0x0102, 3, 3, 150L) // BitsPerSample offset at 150
                writeEntry(0x0103, 3, 1, 1L)    // No compression
                writeEntry(0x0106, 3, 1, 2L)    // RGB model
                writeEntry(0x0111, 4, 1, imageDataOffset.toLong())
                writeEntry(0x0115, 3, 1, 3L)    // 3 samples per pixel
                writeEntry(0x0116, 4, 1, height.toLong())
                writeEntry(0x0117, 4, 1, totalPixelBytes)
                writeEntry(0x011A, 5, 1, 156L) // XResolution at 156
                writeEntry(0x011B, 5, 1, 164L) // YResolution at 164

                out.write(byteArrayOf(0, 0, 0, 0)) // Offset of next IFD

                // Extras:
                // At 150: BitsPerSample [16, 16, 16] -> 6 bytes
                out.write(byteArrayOf(16, 0, 16, 0, 16, 0))
                // At 156: XResolution (72/1) -> 8 bytes
                out.write(byteArrayOf(72, 0, 0, 0, 1, 0, 0, 0))
                // At 164: YResolution (72/1) -> 8 bytes
                out.write(byteArrayOf(72, 0, 0, 0, 1, 0, 0, 0))

                // Padding to imageDataOffset (200)
                val currentPos = 168
                for (p in currentPos until imageDataOffset) {
                    out.write(0)
                }

                // Image Data in little endian
                val tempBuffer = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
                for (i in shortArray.indices) {
                    if (!tempBuffer.hasRemaining()) {
                        out.write(tempBuffer.array())
                        tempBuffer.clear()
                    }
                    tempBuffer.putShort(shortArray[i])
                }
                if (tempBuffer.position() > 0) {
                    out.write(tempBuffer.array(), 0, tempBuffer.position())
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error writing 16-bit TIFF output", e)
            return false
        }
    }

    // --- Native JNI Interface Declarations ---
    private external fun nCreateSession(width: Int, height: Int, stackMode: Int, enableAlignment: Boolean): Long
    private external fun nSetDarkFrame(sessionHandle: Long, darkData: ShortArray): Boolean
    private external fun nSetFlatFrame(sessionHandle: Long, flatData: FloatArray): Boolean
    private external fun nAddFrame(sessionHandle: Long, frameData: ByteArray): Boolean
    private external fun nAddFrameBuffer(sessionHandle: Long, byteBuffer: ByteBuffer): Boolean
    private external fun nGetProgressivePreview(sessionHandle: Long, previewBuffer: ByteArray, previewWidth: Int, previewHeight: Int): Boolean
    private external fun nFinalizeStack(sessionHandle: Long): ShortArray?
    private external fun nReleaseSession(sessionHandle: Long)
}
