package com.starstack.app.processing

/**
 * Interface to receive progress and state updates from the native C++ Stacking Engine during
 * computational heavy stacking processes.
 */
interface StackingProgressCallback {
    /**
     * Called by the native layer to report progress.
     * @param progress value between 0.0 and 1.0 representing percentage complete.
     * @param statusMessage localization-friendly log or status string from C++.
     */
    fun onProgress(progress: Float, statusMessage: String)

    /**
     * Called when a fatal error occurs in native code (e.g., failed alignment, low memory).
     * @param errorCode unique identifier for error category.
     * @param errorMessage descriptive message.
     */
    fun onError(errorCode: Int, errorMessage: String)

    /**
     * Called when stacking completes successfully.
     * @param outputPath absolute path to the produced 16-bit TIFF or high-fidelity output.
     */
    fun onComplete(outputPath: String)
}
