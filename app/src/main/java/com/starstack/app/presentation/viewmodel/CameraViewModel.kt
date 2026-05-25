package com.starstack.app.presentation.viewmodel

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.starstack.app.data.model.CameraSettings
import com.starstack.app.data.repository.CameraRepository
import com.starstack.app.data.repository.CameraRepositoryImpl
import com.starstack.app.domain.usecase.CameraUseCase
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CameraViewModel(context: Context) : ViewModel() {

    private val cameraRepository = CameraRepositoryImpl(context)
    private val cameraUseCase = CameraUseCase(cameraRepository)

    val cameraState: StateFlow<CameraRepository.CameraState> = cameraUseCase.cameraState
    val cameraCharacteristics: StateFlow<CameraCharacteristics?> = cameraUseCase.cameraCharacteristics
    val currentCameraSettings: StateFlow<CameraSettings> = cameraUseCase.currentCameraSettings

    fun openCamera(cameraId: String, surface: Surface) {
        viewModelScope.launch {
            cameraUseCase.openCamera(cameraId, surface)
        }
    }

    fun closeCamera() {
        viewModelScope.launch {
            cameraUseCase.closeCamera()
        }
    }

    fun captureImage() {
        viewModelScope.launch {
            cameraUseCase.captureImage(currentCameraSettings.value) { imageProxy ->
                // Handle the captured image, e.g., save to disk or pass to stacking engine
                imageProxy.close()
            }
        }
    }

    fun setManualExposure(iso: Int, shutterSpeedNs: Long) {
        viewModelScope.launch {
            cameraUseCase.setManualExposure(iso, shutterSpeedNs)
        }
    }

    fun setManualFocus(distance: Float) {
        viewModelScope.launch {
            cameraUseCase.setManualFocus(distance)
        }
    }

    fun setWhiteBalance(mode: Int) {
        viewModelScope.launch {
            cameraUseCase.setWhiteBalance(mode)
        }
    }

    fun disableOIS() {
        viewModelScope.launch {
            cameraUseCase.disableOIS()
        }
    }

    fun getCameraCharacteristics(cameraId: String): CameraCharacteristics? {
        return cameraUseCase.getCameraCharacteristics(cameraId)
    }
}
