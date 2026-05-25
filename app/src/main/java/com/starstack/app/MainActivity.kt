package com.starstack.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.starstack.app.data.model.CameraSettings
import com.starstack.app.data.model.Session
import com.starstack.app.data.repository.CameraRepositoryImpl
import com.starstack.app.data.repository.SessionRepositoryImpl
import com.starstack.app.presentation.ui.*
import com.starstack.app.presentation.viewmodel.CameraViewModel
import com.starstack.app.presentation.viewmodel.SessionViewModel
import com.starstack.app.presentation.viewmodel.StackingViewModel
import com.starstack.app.ui.theme.StarStackTheme

// Navigation destinations
sealed class Screen {
    object Sessions : Screen()
    object Camera : Screen()
    data class Result(val outputPath: String, val sessionName: String) : Screen()
}

class MainActivity : ComponentActivity() {

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_EXTERNAL_STORAGE
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Handle result */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMissingPermissions()

        setContent {
            StarStackTheme {
                val cameraViewModel: CameraViewModel = viewModel(
                    factory = CameraViewModelFactory(applicationContext)
                )
                val sessionViewModel: SessionViewModel = viewModel(
                    factory = SessionViewModelFactory(SessionRepositoryImpl())
                )
                val stackingViewModel: StackingViewModel = viewModel(
                    factory = StackingViewModel.Factory(applicationContext)
                )

                StarStackApp(
                    cameraViewModel = cameraViewModel,
                    sessionViewModel = sessionViewModel,
                    stackingViewModel = stackingViewModel
                )
            }
        }
    }

    private fun requestMissingPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }
}

@Composable
fun StarStackApp(
    cameraViewModel: CameraViewModel,
    sessionViewModel: SessionViewModel,
    stackingViewModel: StackingViewModel
) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Sessions) }
    var nightMode by remember { mutableStateOf(true) }
    var activeSession by remember { mutableStateOf<Session?>(null) }

    val sessions by sessionViewModel.sessions.collectAsState(initial = emptyList())
    val cameraSettings by cameraViewModel.currentCameraSettings.collectAsState()
    val stackingState by stackingViewModel.stackingState.collectAsState()

    // React to stacking completion
    LaunchedEffect(stackingState) {
        when (val state = stackingState) {
            is StackingViewModel.StackingState.Completed -> {
                currentScreen = Screen.Result(
                    outputPath = state.outputPath,
                    sessionName = activeSession?.targetName ?: "Session"
                )
            }
            else -> {}
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when (val screen = currentScreen) {
            is Screen.Sessions -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    SessionScreen(
                        sessions = sessions,
                        onCreateSession = { name ->
                            sessionViewModel.createSession(name)
                        },
                        onSelectSession = { session ->
                            activeSession = session
                            currentScreen = Screen.Camera
                        },
                        onDeleteSession = { session ->
                            sessionViewModel.deleteSession(session)
                        },
                        nightMode = nightMode
                    )
                }
            }

            is Screen.Camera -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    CameraScreen(
                        settings = cameraSettings,
                        onIsoChange = { iso ->
                            cameraViewModel.setManualExposure(iso, cameraSettings.shutterSpeedNanos)
                        },
                        onShutterChange = { shutterNs ->
                            cameraViewModel.setManualExposure(cameraSettings.iso, shutterNs)
                        },
                        onFocusChange = { dist ->
                            cameraViewModel.setManualFocus(dist)
                        },
                        onWhiteBalanceChange = { mode ->
                            cameraViewModel.setWhiteBalance(mode)
                        },
                        onCapture = {
                            cameraViewModel.captureImage()
                        },
                        onStartStacking = {
                            activeSession?.let { session ->
                                stackingViewModel.startStacking(session)
                            }
                        }
                    )

                    // Stacking progress overlay
                    val state = stackingState
                    if (state is StackingViewModel.StackingState.Running) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xAA000000)),
                            contentAlignment = Alignment.Center
                        ) {
                            StackingProgressPanel(
                                progress = state.progress,
                                nightMode = nightMode,
                                onDismiss = { stackingViewModel.cancelStacking() }
                            )
                        }
                    }
                }

                // Back button overlay
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp, start = 8.dp)
                ) {
                    IconButton(
                        onClick = { currentScreen = Screen.Sessions }
                    ) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = if (nightMode) StarStackColors.NightRedText else StarStackColors.TextPrimary
                        )
                    }
                }
            }

            is Screen.Result -> {
                ResultScreen(
                    outputPath = screen.outputPath,
                    sessionName = screen.sessionName,
                    nightMode = nightMode,
                    onBack = {
                        stackingViewModel.resetState()
                        currentScreen = Screen.Camera
                    }
                )
            }
        }

        // Global night mode toggle (bottom-right FAB)
        if (currentScreen is Screen.Sessions) {
            FloatingActionButton(
                onClick = { nightMode = !nightMode },
                containerColor = if (nightMode) Color(0xFF880000) else StarStackColors.AccentDim,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(48.dp)
            ) {
                Icon(
                    imageVector = if (nightMode) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = "Toggle Night Mode",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

// ─── ViewModel Factories ──────────────────────────────────────────────────────
class CameraViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CameraViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CameraViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class SessionViewModelFactory(private val repo: SessionRepositoryImpl) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SessionViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SessionViewModel(repo) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
