package com.starstack.app.data.repository

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import androidx.camera.core.ImageProxy
import com.starstack.app.data.model.CameraSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraRepositoryImpl(private val context: Context) : CameraRepository {

    private val TAG = "CameraRepositoryImpl"

    private val cameraManager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val _cameraState = MutableStateFlow<CameraRepository.CameraState>(CameraRepository.CameraState.Idle)
    override val cameraState: StateFlow<CameraRepository.CameraState> = _cameraState.asStateFlow()

    private val _cameraCharacteristics = MutableStateFlow<CameraCharacteristics?>(null)
    override val cameraCharacteristics: StateFlow<CameraCharacteristics?> = _cameraCharacteristics.asStateFlow()

    private val _currentCameraSettings = MutableStateFlow(CameraSettings())
    override val currentCameraSettings: StateFlow<CameraSettings> = _currentCameraSettings.asStateFlow()

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var rawImageReader: ImageReader? = null
    private var jpegImageReader: ImageReader? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var captureRequest: CaptureRequest? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val cameraOpenCloseLock = Semaphore(1)

    init {
        startBackgroundThread()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    override suspend fun openCamera(cameraId: String, surface: Surface) = suspendCancellableCoroutine<Unit> {
        _cameraState.value = CameraRepository.CameraState.Opening
        previewSurface = surface

        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    _cameraState.value = CameraRepository.CameraState.Open(camera)
                    _cameraCharacteristics.value = cameraManager.getCameraCharacteristics(cameraId)
                    it.resume(Unit)
                    createCameraPreviewSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    _cameraState.value = CameraRepository.CameraState.Closed
                    it.resumeWithException(RuntimeException("Camera disconnected"))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    val errorMessage = when (error) {
                        ERROR_CAMERA_DEVICE -> "Fatal error on camera device"
                        ERROR_CAMERA_DISABLED -> "Camera disabled by policy"
                        ERROR_CAMERA_IN_USE -> "Camera in use by another app"
                        ERROR_MAX_CAMERAS_IN_USE -> "Too many cameras in use"
                        ERROR_CAMERA_SERVICE -> "Camera service encountered a fatal error"
                        else -> "Unknown camera error"
                    }
                    _cameraState.value = CameraRepository.CameraState.Error(errorMessage)
                    it.resumeWithException(RuntimeException(errorMessage))
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            cameraOpenCloseLock.release()
            _cameraState.value = CameraRepository.CameraState.Error("Failed to open camera: ${e.message}")
            it.resumeWithException(e)
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val camera = cameraDevice ?: return
            val surface = previewSurface ?: return

            // Determine optimal sizes for ImageReaders
            val characteristics = _cameraCharacteristics.value
            val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val largestJpeg = map?.getOutputSizes(android.graphics.ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
            val largestRaw = map?.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height }

            if (largestJpeg != null) {
                jpegImageReader = ImageReader.newInstance(largestJpeg.width, largestJpeg.height, android.graphics.ImageFormat.JPEG, 2)
                jpegImageReader?.setOnImageAvailableListener({ reader ->
                    // Handle JPEG image
                    val image = reader.acquireLatestImage()
                    // TODO: Process JPEG image
                    image.close()
                }, backgroundHandler)
            }

            if (largestRaw != null && isRawCaptureSupported()) {
                rawImageReader = ImageReader.newInstance(largestRaw.width, largestRaw.height, android.graphics.ImageFormat.RAW_SENSOR, 2)
                rawImageReader?.setOnImageAvailableListener({ reader ->
                    // Handle RAW image
                    val image = reader.acquireLatestImage()
                    // TODO: Process RAW image
                    image.close()
                }, backgroundHandler)
            }

            val surfaces = mutableListOf(surface)
            jpegImageReader?.surface?.let { surfaces.add(it) }
            rawImageReader?.surface?.let { surfaces.add(it) }

            val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder = captureRequestBuilder
            captureRequestBuilder.addTarget(surface)

            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        // Apply initial manual controls
                        applyManualCameraSettings(captureRequestBuilder, _currentCameraSettings.value)

                        // Start displaying the camera preview.
                        session.setRepeatingRequest(captureRequestBuilder.build(), previewCaptureCallback, backgroundHandler)
                    } catch (e: CameraAccessException) {
                        Log.e(TAG, "Failed to start camera preview: ${e.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Failed to configure camera capture session")
                    _cameraState.value = CameraRepository.CameraState.Error("Failed to configure camera capture session")
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to create camera preview session: ${e.message}")
            _cameraState.value = CameraRepository.CameraState.Error("Failed to create camera preview session: ${e.message}")
        }
    }

    override suspend fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            rawImageReader?.close()
            rawImageReader = null
            jpegImageReader?.close()
            jpegImageReader = null
            _cameraState.value = CameraRepository.CameraState.Closed
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
            stopBackgroundThread()
        }
    }

    override suspend fun captureImage(settings: CameraSettings, onImageCaptured: (ImageProxy) -> Unit) = suspendCancellableCoroutine<Unit> {
        val camera = cameraDevice ?: return@suspendCancellableCoroutine it.resumeWithException(RuntimeException("Camera not open"))
        val session = captureSession ?: return@suspendCancellableCoroutine it.resumeWithException(RuntimeException("Capture session not created"))

        try {
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            jpegImageReader?.surface?.let { captureBuilder.addTarget(it) }
            rawImageReader?.surface?.let { captureBuilder.addTarget(it) }

            applyManualCameraSettings(captureBuilder, settings)

            // Set up a capture callback to know when the capture is complete
            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result)
                    Log.d(TAG, "Still capture completed.")
                    it.resume(Unit)
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    super.onCaptureFailed(session, request, failure)
                    val errorMessage = "Still capture failed: ${failure.reason}"
                    Log.e(TAG, errorMessage)
                    it.resumeWithException(RuntimeException(errorMessage))
                }
            }
            session.stopRepeatingBurst()
            session.capture(captureBuilder.build(), captureCallback, backgroundHandler)
            session.setRepeatingRequest(previewRequestBuilder!!.build(), previewCaptureCallback, backgroundHandler)

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to capture image: ${e.message}")
            it.resumeWithException(e)
        }
    }

    override suspend fun setManualExposure(iso: Int, shutterSpeedNs: Long) {
        _currentCameraSettings.value = _currentCameraSettings.value.copy(iso = iso, shutterSpeedNanos = shutterSpeedNs)
        Log.d(TAG, "Set manual exposure: ISO=$iso, Shutter=${shutterSpeedNs}ns")
        previewRequestBuilder?.let { builder ->
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterSpeedNs)
            captureSession?.setRepeatingRequest(builder.build(), previewCaptureCallback, backgroundHandler)
        }
    }

    override suspend fun setManualFocus(distance: Float) {
        _currentCameraSettings.value = _currentCameraSettings.value.copy(focusDistance = distance)
        Log.d(TAG, "Set manual focus: Distance=$distance")
        previewRequestBuilder?.let { builder ->
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
            captureSession?.setRepeatingRequest(builder.build(), previewCaptureCallback, backgroundHandler)
        }
    }

    override suspend fun setWhiteBalance(mode: Int) {
        _currentCameraSettings.value = _currentCameraSettings.value.copy(whiteBalance = mode)
        Log.d(TAG, "Set white balance: Mode=$mode")
        previewRequestBuilder?.let { builder ->
            builder.set(CaptureRequest.CONTROL_AWB_MODE, mode)
            captureSession?.setRepeatingRequest(builder.build(), previewCaptureCallback, backgroundHandler)
        }
    }

    override suspend fun disableOIS() {
        Log.d(TAG, "Attempting to disable OIS")
        previewRequestBuilder?.let { builder ->
            val characteristics = _cameraCharacteristics.value
            val availableOisModes = characteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            if (availableOisModes != null && availableOisModes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)) {
                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                captureSession?.setRepeatingRequest(builder.build(), previewCaptureCallback, backgroundHandler)
                Log.d(TAG, "OIS disabled.")
            } else {
                Log.w(TAG, "OIS disabling not supported or not available.")
            }
        }
    }

    override fun getCameraCharacteristics(cameraId: String): CameraCharacteristics? {
        return try {
            cameraManager.getCameraCharacteristics(cameraId)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to get camera characteristics for $cameraId: ${e.message}")
            null
        }
    }

    // Helper to get available camera IDs
    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            super.onCaptureCompleted(session, request, result)
            // Optional: Handle capture completion, e.g., update UI with current frame metadata
        }
    }

    private fun applyManualCameraSettings(builder: CaptureRequest.Builder, settings: CameraSettings) {
        // Auto-focus should be set to infinity for astrophotography
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, settings.focusDistance) // Infinity is 0.0f

        // Manual Exposure
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, settings.iso)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, settings.shutterSpeedNanos)

        // White Balance
        builder.set(CaptureRequest.CONTROL_AWB_MODE, settings.whiteBalance)

        // OIS
        val characteristics = _cameraCharacteristics.value
        val availableOisModes = characteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        if (availableOisModes != null && availableOisModes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)) {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        }
    }

    fun getCameraIds(): Array<String> = cameraManager.cameraIdList

    // Helper to get a specific camera characteristic value
    fun <T> getCharacteristic(key: CameraCharacteristics.Key<T>): T? {
        return _cameraCharacteristics.value?.get(key)
    }

    // Helper to get supported ISO ranges
    fun getSupportedIsoRange(): Range<Int>? {
        return getCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    }

    // Helper to get supported exposure time ranges
    fun getSupportedShutterSpeedRange(): Range<Long>? {
        return getCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    }

    // Helper to get supported focus distance ranges
    fun getSupportedFocusDistanceRange(): Range<Float>? {
        return getCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.let { minFocus ->
            // For focus, 0.0f typically means infinity, so range might be [0.0f, minFocus]
            Range(0.0f, minFocus)
        }
    }

    // Helper to check for RAW capability
    fun isRawCaptureSupported(): Boolean {
        val capabilities = getCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        return capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
    }
}
