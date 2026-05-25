package com.starstack.app.presentation.ui

import android.hardware.camera2.CaptureRequest
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.starstack.app.data.model.CameraSettings
import com.starstack.app.data.model.CaptureType

// StarStack Color Palette - AMOLED black base
object StarStackColors {
    val Background    = Color(0xFF000000)
    val SurfaceDark   = Color(0xFF0A0A0A)
    val SurfaceMid    = Color(0xFF141414)
    val Accent        = Color(0xFF1E88E5)
    val AccentDim     = Color(0xFF0D47A1)
    val TextPrimary   = Color(0xFFE0E0E0)
    val TextSecondary = Color(0xFF757575)
    val Warning       = Color(0xFFFF6F00)
    val Error         = Color(0xFFD32F2F)
    val Success       = Color(0xFF2E7D32)
    val NightRed      = Color(0xCCCC0000)
    val NightRedDim   = Color(0x88880000)
    val NightRedText  = Color(0xFFFF3333)
    val ControlBorder = Color(0xFF2A2A2A)
    val SliderTrack   = Color(0xFF333333)
}

data class ShutterPreset(val label: String, val nanos: Long)

val SHUTTER_PRESETS = listOf(
    ShutterPreset("1/1000", 1_000_000L),
    ShutterPreset("1/500",  2_000_000L),
    ShutterPreset("1/250",  4_000_000L),
    ShutterPreset("1/100",  10_000_000L),
    ShutterPreset("1/50",   20_000_000L),
    ShutterPreset("1/25",   40_000_000L),
    ShutterPreset("1s",     1_000_000_000L),
    ShutterPreset("2s",     2_000_000_000L),
    ShutterPreset("4s",     4_000_000_000L),
    ShutterPreset("8s",     8_000_000_000L),
    ShutterPreset("15s",    15_000_000_000L),
    ShutterPreset("25s",    25_000_000_000L),
    ShutterPreset("30s",    30_000_000_000L)
)

data class WBPreset(val label: String, val mode: Int)

val WB_PRESETS = listOf(
    WBPreset("Auto",      CaptureRequest.CONTROL_AWB_MODE_AUTO),
    WBPreset("Daylight",  CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
    WBPreset("Cloudy",    CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    WBPreset("Tungsten",  CaptureRequest.CONTROL_AWB_MODE_TUNGSTEN),
    WBPreset("Fluor.",    CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT),
    WBPreset("Off",       CaptureRequest.CONTROL_AWB_MODE_OFF)
)

@Composable
fun CameraScreen(
    settings: CameraSettings = CameraSettings(),
    onIsoChange: (Int) -> Unit = {},
    onShutterChange: (Long) -> Unit = {},
    onFocusChange: (Float) -> Unit = {},
    onWhiteBalanceChange: (Int) -> Unit = {},
    onCapture: () -> Unit = {},
    onStartStacking: () -> Unit = {}
) {
    var nightModeEnabled by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(true) }
    var captureType by remember { mutableStateOf(CaptureType.FRAMES) }
    var isCapturing by remember { mutableStateOf(false) }
    var frameCount by remember { mutableStateOf(0) }
    var thermalWarning by remember { mutableStateOf(false) }
    var alignmentConfidence by remember { mutableStateOf(0f) }
    var stackingProgress by remember { mutableStateOf(0f) }
    var showStackingPanel by remember { mutableStateOf(false) }

    val nightOverlayAlpha by animateFloatAsState(
        targetValue = if (nightModeEnabled) 0.18f else 0f,
        label = "nightOverlay"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StarStackColors.Background)
    ) {
        // Camera Preview
        AndroidView(
            factory = { context ->
                PreviewView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Night-vision red overlay
        if (nightModeEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(StarStackColors.NightRed.copy(alpha = nightOverlayAlpha))
            )
        }

        // Top Status Bar
        TopStatusBar(
            nightMode = nightModeEnabled,
            captureType = captureType,
            frameCount = frameCount,
            thermalWarning = thermalWarning,
            alignmentConfidence = alignmentConfidence,
            onNightModeToggle = { nightModeEnabled = !nightModeEnabled },
            onToggleControls = { showControls = !showControls }
        )

        // Stacking Progress Overlay
        AnimatedVisibility(
            visible = showStackingPanel,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            StackingProgressPanel(
                progress = stackingProgress,
                nightMode = nightModeEnabled,
                onDismiss = { showStackingPanel = false }
            )
        }

        // Bottom Controls Panel
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            BottomControlsPanel(
                settings = settings,
                nightMode = nightModeEnabled,
                captureType = captureType,
                isCapturing = isCapturing,
                onCaptureTypeChange = { captureType = it },
                onIsoChange = onIsoChange,
                onShutterChange = onShutterChange,
                onFocusChange = onFocusChange,
                onWhiteBalanceChange = onWhiteBalanceChange,
                onCapture = {
                    isCapturing = true
                    frameCount++
                    onCapture()
                    isCapturing = false
                },
                onStartStacking = {
                    showStackingPanel = true
                    stackingProgress = 0f
                    onStartStacking()
                }
            )
        }
    }
}

@Composable
fun TopStatusBar(
    nightMode: Boolean,
    captureType: CaptureType,
    frameCount: Int,
    thermalWarning: Boolean,
    alignmentConfidence: Float,
    onNightModeToggle: () -> Unit,
    onToggleControls: () -> Unit
) {
    val textColor = if (nightMode) StarStackColors.NightRedText else StarStackColors.TextPrimary
    val bgColor   = if (nightMode) Color(0xCC1A0000) else Color(0xCC000000)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("★ StarStack", color = textColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)

        Row(verticalAlignment = Alignment.CenterVertically) {
            CaptureTypeBadge(captureType, nightMode)
            Spacer(Modifier.width(8.dp))
            Text("${frameCount}f", color = textColor, fontSize = 12.sp)
        }

        if (thermalWarning) {
            Icon(Icons.Default.Warning, contentDescription = "Thermal Warning",
                tint = StarStackColors.Warning, modifier = Modifier.size(18.dp))
        }

        if (alignmentConfidence > 0f) {
            AlignmentIndicator(confidence = alignmentConfidence, nightMode = nightMode)
        }

        IconButton(onClick = onNightModeToggle, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = if (nightMode) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = "Night Mode",
                tint = if (nightMode) StarStackColors.NightRedText else StarStackColors.TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }

        IconButton(onClick = onToggleControls, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Tune, contentDescription = "Toggle Controls",
                tint = textColor, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun CaptureTypeBadge(captureType: CaptureType, nightMode: Boolean) {
    val (label, color) = when (captureType) {
        CaptureType.FRAMES -> Pair("LIGHT", if (nightMode) StarStackColors.NightRedText else StarStackColors.Accent)
        CaptureType.DARKS  -> Pair("DARK",  Color(0xFF424242))
        CaptureType.FLATS  -> Pair("FLAT",  Color(0xFFAFB42B))
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.25f))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun AlignmentIndicator(confidence: Float, nightMode: Boolean) {
    val color = when {
        confidence >= 0.8f -> StarStackColors.Success
        confidence >= 0.5f -> StarStackColors.Warning
        else               -> StarStackColors.Error
    }
    val label = when {
        confidence >= 0.8f -> "ALN ✓"
        confidence >= 0.5f -> "ALN ~"
        else               -> "ALN ✗"
    }
    Text(
        text = label,
        color = if (nightMode) StarStackColors.NightRedText else color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
fun BottomControlsPanel(
    settings: CameraSettings,
    nightMode: Boolean,
    captureType: CaptureType,
    isCapturing: Boolean,
    onCaptureTypeChange: (CaptureType) -> Unit,
    onIsoChange: (Int) -> Unit,
    onShutterChange: (Long) -> Unit,
    onFocusChange: (Float) -> Unit,
    onWhiteBalanceChange: (Int) -> Unit,
    onCapture: () -> Unit,
    onStartStacking: () -> Unit
) {
    val bgColor = if (nightMode) Color(0xEE1A0000) else Color(0xEE0A0A0A)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        CaptureTypeSelector(selected = captureType, nightMode = nightMode, onSelect = onCaptureTypeChange)
        Spacer(Modifier.height(10.dp))

        AstroSliderRow(
            label = "ISO",
            value = settings.iso.toFloat(),
            valueRange = settings.isoRange.first.toFloat()..settings.isoRange.last.toFloat(),
            displayValue = "${settings.iso}",
            nightMode = nightMode,
            onValueChange = { onIsoChange(it.toInt()) }
        )

        ShutterSpeedRow(currentNanos = settings.shutterSpeedNanos, nightMode = nightMode, onSelect = onShutterChange)

        AstroSliderRow(
            label = "Focus",
            value = settings.focusDistance,
            valueRange = 0f..10f,
            displayValue = if (settings.focusDistance < 0.05f) "∞" else "%.2f".format(settings.focusDistance),
            nightMode = nightMode,
            onValueChange = { onFocusChange(it) }
        )

        WhiteBalanceRow(currentMode = settings.whiteBalance, nightMode = nightMode, onSelect = onWhiteBalanceChange)

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onStartStacking,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (nightMode) StarStackColors.NightRedText else StarStackColors.Accent
                ),
                modifier = Modifier.weight(1f).padding(end = 6.dp)
            ) {
                Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Stack", fontSize = 13.sp)
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(
                        if (isCapturing) StarStackColors.Error
                        else if (nightMode) Color(0xFF880000)
                        else StarStackColors.Accent
                    )
                    .clickable(enabled = !isCapturing) { onCapture() }
            ) {
                Icon(Icons.Default.Camera, contentDescription = "Capture",
                    tint = Color.White, modifier = Modifier.size(28.dp))
            }

            OutlinedButton(
                onClick = { /* Burst capture - future implementation */ },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (nightMode) StarStackColors.NightRedText else StarStackColors.TextPrimary
                ),
                modifier = Modifier.weight(1f).padding(start = 6.dp)
            ) {
                Icon(Icons.Default.BurstMode, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Burst", fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun CaptureTypeSelector(
    selected: CaptureType,
    nightMode: Boolean,
    onSelect: (CaptureType) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        CaptureType.entries.forEach { type ->
            val isSelected = type == selected
            val label = when (type) {
                CaptureType.FRAMES -> "Lights"
                CaptureType.DARKS  -> "Darks"
                CaptureType.FLATS  -> "Flats"
            }
            val activeColor = when (type) {
                CaptureType.FRAMES -> if (nightMode) StarStackColors.NightRedText else StarStackColors.Accent
                CaptureType.DARKS  -> Color(0xFF757575)
                CaptureType.FLATS  -> Color(0xFFAFB42B)
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) activeColor.copy(alpha = 0.2f) else Color.Transparent)
                    .border(
                        width = if (isSelected) 1.dp else 0.5.dp,
                        color = if (isSelected) activeColor else StarStackColors.ControlBorder,
                        shape = RoundedCornerShape(6.dp)
                    )
                    .clickable { onSelect(type) }
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    text = label,
                    color = if (isSelected) activeColor else StarStackColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun AstroSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    displayValue: String,
    nightMode: Boolean,
    onValueChange: (Float) -> Unit
) {
    val textColor   = if (nightMode) StarStackColors.NightRedText else StarStackColors.TextPrimary
    val accentColor = if (nightMode) StarStackColors.NightRedText else StarStackColors.Accent

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = StarStackColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.width(52.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = StarStackColors.SliderTrack
            ),
            modifier = Modifier.weight(1f)
        )
        Text(displayValue, color = textColor, fontSize = 11.sp,
            modifier = Modifier.width(52.dp), textAlign = TextAlign.End)
    }
}

@Composable
fun ShutterSpeedRow(currentNanos: Long, nightMode: Boolean, onSelect: (Long) -> Unit) {
    val textColor   = if (nightMode) StarStackColors.NightRedText else StarStackColors.TextPrimary
    val accentColor = if (nightMode) StarStackColors.NightRedText else StarStackColors.Accent

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Shutter", color = StarStackColors.TextSecondary, fontSize = 11.sp)
            Text(
                text = SHUTTER_PRESETS.find { it.nanos == currentNanos }?.label
                    ?: "%.1fs".format(currentNanos / 1_000_000_000.0),
                color = textColor, fontSize = 11.sp, fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(SHUTTER_PRESETS.size) { idx ->
                val preset = SHUTTER_PRESETS[idx]
                val isSelected = preset.nanos == currentNanos
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isSelected) accentColor.copy(alpha = 0.25f) else Color.Transparent)
                        .border(
                            width = if (isSelected) 1.dp else 0.5.dp,
                            color = if (isSelected) accentColor else StarStackColors.ControlBorder,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .clickable { onSelect(preset.nanos) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = preset.label,
                        color = if (isSelected) accentColor else StarStackColors.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
fun WhiteBalanceRow(currentMode: Int, nightMode: Boolean, onSelect: (Int) -> Unit) {
    val accentColor = if (nightMode) StarStackColors.NightRedText else StarStackColors.Accent

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("White Balance", color = StarStackColors.TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(WB_PRESETS.size) { idx ->
                val preset = WB_PRESETS[idx]
                val isSelected = preset.mode == currentMode
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isSelected) accentColor.copy(alpha = 0.25f) else Color.Transparent)
                        .border(
                            width = if (isSelected) 1.dp else 0.5.dp,
                            color = if (isSelected) accentColor else StarStackColors.ControlBorder,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .clickable { onSelect(preset.mode) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = preset.label,
                        color = if (isSelected) accentColor else StarStackColors.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
fun StackingProgressPanel(progress: Float, nightMode: Boolean, onDismiss: () -> Unit) {
    val bgColor   = if (nightMode) Color(0xEE1A0000) else Color(0xEE1A1A1A)
    val textColor = if (nightMode) StarStackColors.NightRedText else StarStackColors.TextPrimary
    val barColor  = if (nightMode) StarStackColors.NightRedText else StarStackColors.Accent

    Card(
        modifier = Modifier.fillMaxWidth(0.85f).wrapContentHeight(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Stacking Frames", color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = barColor,
                trackColor = StarStackColors.SliderTrack
            )
            Spacer(Modifier.height(8.dp))
            Text("${(progress * 100).toInt()}%", color = textColor, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = StarStackColors.Error)
            }
        }
    }
}
