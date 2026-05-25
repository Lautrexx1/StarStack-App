package com.starstack.app.processing

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Monitors hardware conditions (battery status, power-save modes, and thermal throttling status).
 * Dynamically requests CPU throttling (delay injections) or task suspension under extreme heat.
 */
class HardwareSafetyMonitor(private val context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    
    @Volatile
    private var currentThermalStatus: Int = 0 // 0 = THERMAL_STATUS_NONE

    companion object {
        private const val TAG = "HardwareSafetyMonitor"
        
        // Match Android OS constants (API >= 29)
        const val STATUS_NONE = 0
        const val STATUS_LIGHT = 1
        const val STATUS_MODERATE = 2
        const val STATUS_SEVERE = 3
        const val STATUS_CRITICAL = 4
        const val STATUS_EMERGENCY = 5
    }

    init {
        registerThermalListener()
    }

    private fun registerThermalListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            try {
                // Registers on the main thread executor
                powerManager.addThermalStatusListener(context.mainExecutor) { status ->
                    currentThermalStatus = status
                    Log.w(TAG, "System thermal status updated: $status")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register thermal status listener", e)
            }
        }
    }

    /**
     * Determines whether active high-load stacking should insert a dynamic sleep delay.
     * @return delay duration in milliseconds.
     */
    fun getThrottleDelay(): Long {
        // 1. Power Save Mode throttling to preserve remaining battery charge
        if (powerManager?.isPowerSaveMode == true) {
            Log.i(TAG, "Power Saving Mode detected. Throttling active processing loop.")
            return 300L 
        }

        // 2. Dynamic Thermal Throttling based on API 29+ levels
        return when (currentThermalStatus) {
            STATUS_SEVERE -> {
                Log.w(TAG, "Device experiencing SEVERE thermal stress. Injecting 500ms cooling delay.")
                500L
            }
            STATUS_CRITICAL -> {
                Log.w(TAG, "Device experiencing CRITICAL thermal stress. Injecting 1500ms cooling delay.")
                1500L
            }
            STATUS_EMERGENCY -> {
                Log.e(TAG, "Device experiencing EMERGENCY thermal stress. Injecting 3000ms cooling delay.")
                3000L
            }
            else -> 0L
        }
    }

    /**
     * Asserts whether thermal loads are too dangerous to continue processing.
     * @return true if hardware safety requires immediate suspension.
     */
    fun shouldSuspendProcessing(): Boolean {
        return currentThermalStatus >= STATUS_EMERGENCY
    }
}
