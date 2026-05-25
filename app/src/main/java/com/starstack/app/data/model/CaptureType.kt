package com.starstack.app.data.model

/**
 * Enumerates the type of capture the user is performing.
 * Determines which subdirectory the captured image is stored in.
 */
enum class CaptureType {
    /** Light frame: actual celestial exposure for stacking */
    FRAMES,
    /** Dark frame: lens cap on, same ISO/shutter, for noise calibration */
    DARKS,
    /** Flat frame: uniform illumination, for vignetting/dust correction */
    FLATS
}
