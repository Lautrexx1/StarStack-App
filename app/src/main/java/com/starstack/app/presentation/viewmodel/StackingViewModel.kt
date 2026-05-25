package com.starstack.app.presentation.viewmodel

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.starstack.app.data.model.Session
import com.starstack.app.processing.StackingEngine
import com.starstack.app.processing.StackingProgressCallback
import com.starstack.app.service.StackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * ViewModel that orchestrates the stacking pipeline for a given session.
 * Delegates heavy processing to the native StackingEngine running on a background thread.
 * Exposes observable state for the UI to react to progress and results.
 */
class StackingViewModel(private val context: Context) : ViewModel() {

    sealed class StackingState {
        object Idle : StackingState()
        data class Running(val progress: Float, val message: String) : StackingState()
        data class Completed(val outputPath: String) : StackingState()
        data class Failed(val errorCode: Int, val message: String) : StackingState()
        object Cancelled : StackingState()
    }

    private val _stackingState = MutableStateFlow<StackingState>(StackingState.Idle)
    val stackingState: StateFlow<StackingState> = _stackingState.asStateFlow()

    private val engine = StackingEngine()

    init {
        engine.initialize()
    }

    /**
     * Starts the stacking pipeline for the given session.
     * Light frames, dark frames, and flat frames are resolved from the session directory.
     * The output is written to the session root as "stacked_result.tiff".
     */
    fun startStacking(session: Session, stackMode: Int = 0) {
        if (_stackingState.value is StackingState.Running) return

        viewModelScope.launch(Dispatchers.IO) {
            // Start foreground service to keep processing alive
            val serviceIntent = Intent(context, StackingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            val lightPaths = collectFramePaths(session.framesDir)
            val darkPaths  = collectFramePaths(session.darksDir)
            val flatPaths  = collectFramePaths(session.flatsDir)
            val outputPath = File(session.rootPath, "stacked_result.tiff").absolutePath

            if (lightPaths.isEmpty()) {
                _stackingState.value = StackingState.Failed(-1, "No light frames found in session.")
                return@launch
            }

            val callback = object : StackingProgressCallback {
                override fun onProgress(progress: Float, statusMessage: String) {
                    _stackingState.value = StackingState.Running(progress, statusMessage)
                }
                override fun onError(errorCode: Int, errorMessage: String) {
                    _stackingState.value = StackingState.Failed(errorCode, errorMessage)
                    context.stopService(serviceIntent)
                }
                override fun onComplete(outputPath: String) {
                    _stackingState.value = StackingState.Completed(outputPath)
                    context.stopService(serviceIntent)
                }
            }

            engine.alignAndStack(context, lightPaths, darkPaths, flatPaths, outputPath, callback)
        }
    }

    fun cancelStacking() {
        engine.cancel()
        _stackingState.value = StackingState.Cancelled
    }

    fun resetState() {
        _stackingState.value = StackingState.Idle
    }

    private fun collectFramePaths(directory: File): Array<String> {
        if (!directory.exists() || !directory.isDirectory) return emptyArray()
        return directory.listFiles { f ->
            f.isFile && (f.extension.lowercase() in listOf("dng", "raw", "jpg", "jpeg", "png", "tiff", "tif"))
        }?.map { it.absolutePath }?.toTypedArray() ?: emptyArray()
    }

    override fun onCleared() {
        engine.release()
        super.onCleared()
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(StackingViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return StackingViewModel(context) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
