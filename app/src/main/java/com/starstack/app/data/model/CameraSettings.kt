package com.starstack.app.data.model

/**
 * Holds the current manual camera configuration state.
 * All values are nullable to represent "not supported / not set" on the device.
 */
data class CameraSettings(
    val iso: Int = 800,
    val shutterSpeedNanos: Long = 1_000_000_000L, // 1 second default
    val focusDistance: Float = 0.0f,               // 0.0 = infinity
    val whiteBalance: Int = 0,                     // CaptureRequest.CONTROL_AWB_MODE value
    val exposureCompensation: Int = 0,
    val captureType: CaptureType = CaptureType.FRAMES,

    // Device-reported ranges (populated during camera init)
    val isoRange: IntRange = 100..3200,
    val shutterRange: LongRange = 1_000_000L..32_000_000_000L, // 1ms to 32s
    val focusRange: ClosedFloatingPointRange<Float> = 0.0f..15.0f,
    val exposureCompRange: IntRange = -12..12,
    val availableWhiteBalanceModes: List<Int> = emptyList(),
    val hasVariableAperture: Boolean = false,
    val aperture: Float? = null
) {
    /** Alias used by Camera2 repository layer */
    val shutterSpeedNs: Long get() = shutterSpeedNanos

    /** Alias used by Camera2 repository layer */
    val whiteBalanceMode: Int get() = whiteBalance
}
