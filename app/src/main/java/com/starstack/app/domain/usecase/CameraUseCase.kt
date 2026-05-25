package com.starstack.app.domain.usecase

import android.hardware.camera2.CameraCharacteristics
import android.view.Surface
import androidx.camera.core.ImageProxy
import com.starstack.app.data.model.CameraSettings
import com.starstack.app.data.repository.CameraRepository
import kotlinx.coroutines.flow.StateFlow

class CameraUseCase(private val cameraRepository: CameraRepository) {

    val cameraState: StateFlow<CameraRepository.CameraState> = cameraRepository.cameraState
    val cameraCharacteristics: StateFlow<CameraCharacteristics?> = cameraRepository.cameraCharacteristics
    val currentCameraSettings: StateFlow<CameraSettings> = cameraRepository.currentCameraSettings

    suspend fun openCamera(cameraId: String, surface: Surface) {
        cameraRepository.openCamera(cameraId, surface)
    }

    suspend fun closeCamera() {
        cameraRepository.closeCamera()
    }

    suspend fun captureImage(settings: CameraSettings, onImageCaptured: (ImageProxy) -> Unit) {
        cameraRepository.captureImage(settings, onImageCaptured)
    }

    suspend fun setManualExposure(iso: Int, shutterSpeedNs: Long) {
        cameraRepository.setManualExposure(iso, shutterSpeedNs)
    }

    suspend fun setManualFocus(distance: Float) {
        cameraRepository.setManualFocus(distance)
    }

    suspend fun setWhiteBalance(mode: Int) {
        cameraRepository.setWhiteBalance(mode)
    }

    suspend fun disableOIS() {
        cameraRepository.disableOIS()
    }

    fun getCameraCharacteristics(cameraId: String): CameraCharacteristics? {
        return cameraRepository.getCameraCharacteristics(cameraId)
    }
}
