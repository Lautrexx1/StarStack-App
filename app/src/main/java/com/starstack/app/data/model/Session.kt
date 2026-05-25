package com.starstack.app.data.model

import java.io.File

/**
 * Represents an active or historical astrophotography capturing and stacking session.
 * Managed under rootPath/Sessions/TargetName_date/ which separates sub-directories for:
 * - Frames: Light frames (captures of the celestial body)
 * - Darks: Dark frames (noise reference at same ISO and shutter speed with lens cap on)
 * - Flats: Flat frames (vignetting/sensor dust reference under even illumination)
 */
data class Session(
    val id: String,
    val targetName: String,
    val timestamp: Long,
    val rootPath: String,
    val frameCount: Int = 0,
    val darkCount: Int = 0,
    val flatCount: Int = 0
) {
    val sessionDir: File get() = File(rootPath)
    val framesDir: File get() = File(rootPath, "Frames")
    val darksDir: File get() = File(rootPath, "Darks")
    val flatsDir: File get() = File(rootPath, "Flats")
}
