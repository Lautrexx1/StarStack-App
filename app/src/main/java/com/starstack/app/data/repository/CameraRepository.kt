package com.starstack.app.data.repository

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.view.Surface
import androidx.camera.core.ImageProxy
import com.starstack.app.data.model.CameraSettings
import kotlinx.coroutines.flow.StateFlow

interface CameraRepository {
    val cameraState: StateFlow<CameraState>
    val cameraCharacteristics: StateFlow<CameraCharacteristics?>
    val currentCameraSettings: StateFlow<CameraSettings>

    suspend fun openCamera(cameraId: String, surface: Surface)
    suspend fun closeCamera()
    suspend fun captureImage(settings: CameraSettings, onImageCaptured: (ImageProxy) -> Unit)
    suspend fun setManualExposure(iso: Int, shutterSpeedNs: Long)
    suspend fun setManualFocus(distance: Float)
    suspend fun setWhiteBalance(mode: Int)
    suspend fun disableOIS()
    fun getCameraCharacteristics(cameraId: String): CameraCharacteristics?

    sealed class CameraState {
        object Idle : CameraState()
        object Opening : CameraState()
        data class Open(val cameraDevice: CameraDevice) : CameraState()
        object Closed : CameraState()
        data class Error(val message: String) : CameraState()
    }
}
