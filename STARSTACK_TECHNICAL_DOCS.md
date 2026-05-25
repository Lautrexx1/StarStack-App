# StarStack — Documentación Técnica Completa

**Versión:** 1.0.0  
**Plataforma:** Android (API 28+)  
**Arquitectura:** MVVM + Clean Architecture  
**Lenguajes:** Kotlin, C++17 (NDK), Jetpack Compose  

---

## 1. Visión General

StarStack es una aplicación móvil Android de astrofotografía diseñada para capturar, calibrar y apilar (stack) secuencias de imágenes del cielo nocturno directamente en el dispositivo. El procesamiento de imágenes se ejecuta mediante un motor nativo C++ (NDK) para máximo rendimiento, con una interfaz AMOLED completamente oscura para minimizar la contaminación lumínica durante las sesiones de observación.

### Características Principales

| Módulo | Descripción |
|--------|-------------|
| Captura RAW | Control manual completo via Camera2 API (ISO, obturador, enfoque, balance de blancos) |
| Motor de Stacking | C++ nativo con Mean, Median y Sigma Clipping |
| Alineación Estelar | Detección de estrellas + RANSAC similarity transform |
| Calibración | Dark frames (ruido térmico) + Flat frames (viñeteo/polvo) |
| UI AMOLED | Fondo negro puro con modo nocturno rojo para preservar visión nocturna |
| Seguridad Térmica | Monitor de temperatura del dispositivo con throttling dinámico |
| Exportación | TIFF 16-bit sin pérdida o JPEG/PNG 8-bit |

---

## 2. Arquitectura del Proyecto

```
StarStack/
├── app/
│   ├── src/main/
│   │   ├── java/com/starstack/app/
│   │   │   ├── MainActivity.kt              # Entry point + navegación
│   │   │   ├── data/
│   │   │   │   ├── model/
│   │   │   │   │   ├── CameraSettings.kt    # Configuración de cámara (ISO, shutter, etc.)
│   │   │   │   │   ├── CaptureType.kt       # Enum: FRAMES / DARKS / FLATS
│   │   │   │   │   └── Session.kt           # Modelo de sesión de astrofotografía
│   │   │   │   └── repository/
│   │   │   │       ├── CameraRepository.kt          # Interfaz de cámara
│   │   │   │       ├── CameraRepositoryImpl.kt      # Camera2 API implementation
│   │   │   │       ├── SessionRepository.kt         # Interfaz de sesiones
│   │   │   │       ├── SessionRepositoryImpl.kt     # In-memory implementation
│   │   │   │       └── FileSessionRepositoryImpl.kt # Persistencia en disco (JSON)
│   │   │   ├── domain/usecase/
│   │   │   │   ├── CameraUseCase.kt         # Lógica de negocio de cámara
│   │   │   │   ├── CreateSessionUseCase.kt  # Crear sesión
│   │   │   │   ├── GetAllSessionsUseCase.kt # Listar sesiones
│   │   │   │   ├── GetSessionUseCase.kt     # Obtener sesión por ID
│   │   │   │   └── UpdateSessionUseCase.kt  # Actualizar sesión
│   │   │   ├── presentation/
│   │   │   │   ├── ui/
│   │   │   │   │   ├── CameraScreen.kt      # UI principal de captura
│   │   │   │   │   ├── SessionScreen.kt     # Gestión de sesiones
│   │   │   │   │   └── ResultScreen.kt      # Pantalla de resultado/exportación
│   │   │   │   └── viewmodel/
│   │   │   │       ├── CameraViewModel.kt   # Estado de cámara
│   │   │   │       ├── SessionViewModel.kt  # Estado de sesiones
│   │   │   │       └── StackingViewModel.kt # Orquestación del stacking
│   │   │   ├── processing/
│   │   │   │   ├── StackingEngine.kt        # Puente Kotlin → JNI
│   │   │   │   ├── StackingProgressCallback.kt # Interfaz de progreso
│   │   │   │   └── HardwareSafetyMonitor.kt # Monitor térmico/batería
│   │   │   ├── service/
│   │   │   │   └── StackingService.kt       # Foreground service para stacking
│   │   │   └── ui/theme/
│   │   │       └── StarStackTheme.kt        # Tema AMOLED Material3
│   │   ├── cpp/
│   │   │   ├── CMakeLists.txt               # Build nativo
│   │   │   ├── StackingCore.cpp/.h          # Motor de stacking (Mean/Median/Sigma)
│   │   │   ├── StarAlignment.cpp/.h         # Detección de estrellas + RANSAC
│   │   │   └── starstack-jni.cpp            # Puente JNI
│   │   ├── res/
│   │   │   ├── drawable/ic_launcher.xml     # Ícono vectorial
│   │   │   ├── values/strings.xml           # Cadenas de texto
│   │   │   ├── values/themes.xml            # Tema XML (AMOLED negro)
│   │   │   └── xml/file_provider_paths.xml  # Rutas para FileProvider
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
└── STARSTACK_TECHNICAL_DOCS.md
```

---

## 3. Módulo de Cámara (Camera2 API)

### CameraRepositoryImpl

Implementa control manual completo mediante la API Camera2 de Android:

- **ISO:** Rango dinámico leído desde `CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE`
- **Velocidad de obturador:** Presets de 1/1000s a 30s, con soporte para exposiciones largas de astrofotografía
- **Enfoque manual:** Control de distancia focal (`LENS_FOCUS_DISTANCE`) con valor 0.0 = infinito
- **Balance de blancos:** Modos AWB: Auto, Daylight, Cloudy, Tungsten, Fluorescent, Off
- **OIS desactivado:** Se desactiva la estabilización óptica para evitar movimientos durante el stacking
- **Captura RAW:** Solicita formato `ImageFormat.RAW_SENSOR` cuando el hardware lo soporta

### CameraSettings

```kotlin
data class CameraSettings(
    val iso: Int = 800,
    val shutterSpeedNanos: Long = 4_000_000_000L, // 4 segundos
    val focusDistance: Float = 0.0f,               // 0.0 = infinito
    val whiteBalance: Int = CONTROL_AWB_MODE_AUTO,
    val isoRange: IntRange = 100..6400,
    val shutterRange: LongRange = 1_000_000L..30_000_000_000L
)
```

---

## 4. Motor de Stacking Nativo (C++)

### StackingCore

El motor de stacking opera sobre buffers de píxeles RGB en memoria:

#### Modos de Stacking

| Modo | Descripción | Uso Recomendado |
|------|-------------|-----------------|
| **MEAN** | Promedio aritmético de todos los frames | Cielos estables, pocas tramas |
| **MEDIAN** | Mediana exacta por píxel | Eliminación de satélites/aviones |
| **SIGMA_CLIPPING** | Media con rechazo de outliers (σ configurable) | Máxima calidad, muchas tramas |

#### Pipeline de Calibración

1. **Dark Subtraction:** Resta el frame oscuro (ruido térmico) de cada frame de luz
2. **Flat Division:** Divide por el frame plano normalizado (corrige viñeteo y polvo del sensor)
3. **Alignment:** Transforma cada frame al sistema de referencia del primero
4. **Accumulation:** Acumula en buffers de 32-bit float para evitar saturación

#### Salida

- **16-bit TIFF sin pérdida:** Formato preferido para post-procesamiento en PixInsight/Siril
- **8-bit JPEG/PNG:** Fallback para visualización rápida

### StarAlignment

Implementa alineación sub-pixel mediante:

1. **Detección de estrellas:** Umbral adaptativo por bloques + supresión de no-máximos
2. **Centroide sub-pixel:** Centro de masa ponderado por intensidad (precisión ~0.1px)
3. **Matching de triángulos:** Descriptores invariantes a escala/rotación
4. **RANSAC:** Estimación robusta de la transformación de similitud (rotación + traslación + escala uniforme)
5. **Transformación:** Interpolación bilineal para aplicar la transformación al frame

---

## 5. Interfaz de Usuario (Jetpack Compose)

### Paleta de Colores AMOLED

```kotlin
object StarStackColors {
    val Background    = Color(0xFF000000)  // Negro puro AMOLED
    val SurfaceDark   = Color(0xFF0A0A0A)  // Superficie casi negra
    val Accent        = Color(0xFF1E88E5)  // Azul estelar
    val NightRedText  = Color(0xFFFF3333)  // Rojo modo nocturno
    val NightRed      = Color(0xCCCC0000)  // Overlay rojo nocturno
}
```

### Pantallas

#### SessionScreen
- Lista de sesiones con contador de frames (Lights/Darks/Flats)
- Diálogo de creación de nueva sesión
- Eliminación por deslizamiento

#### CameraScreen
- Preview de cámara en tiempo real
- **Controles manuales:** ISO (slider), Velocidad de obturador (chips), Enfoque (slider), Balance de blancos (chips)
- **Selector de tipo de captura:** Lights / Darks / Flats
- **Modo nocturno:** Overlay rojo configurable para preservar la visión nocturna
- **Botón de captura** + **Botón de stacking**
- Panel de progreso de stacking con barra animada

#### ResultScreen
- Información del archivo de salida (nombre, tamaño, formato)
- Botón de compartir (Intent.ACTION_SEND)
- Botón de abrir en galería (Intent.ACTION_VIEW)

---

## 6. Seguridad Térmica

### HardwareSafetyMonitor

Registra el listener de estado térmico del sistema (API 29+) y aplica throttling dinámico:

| Estado Térmico | Delay Inyectado | Acción |
|----------------|-----------------|--------|
| NONE / LIGHT / MODERATE | 0ms | Procesamiento normal |
| SEVERE | 500ms | Throttling moderado |
| CRITICAL | 1500ms | Throttling agresivo |
| EMERGENCY | 3000ms + suspensión | Suspensión del stacking |
| Power Save Mode | 300ms | Throttling de ahorro de batería |

---

## 7. Servicio en Primer Plano

`StackingService` mantiene el proceso de stacking activo cuando la app pasa a segundo plano:

- Notificación persistente con progreso
- Canal de notificación `IMPORTANCE_LOW` para no interrumpir al usuario
- Se inicia automáticamente al comenzar el stacking
- Se detiene al completar o cancelar

---

## 8. Compilación y Requisitos

### Requisitos de Hardware
- Android 9.0+ (API 28)
- Cámara con soporte Camera2 (nivel FULL o LIMITED)
- Soporte RAW recomendado (no obligatorio)
- ARM64-v8a o armeabi-v7a

### Compilación
```bash
# Debug
./gradlew assembleDebug

# Release
./gradlew assembleRelease
```

### Dependencias Clave
- `androidx.camera:camera-camera2:1.3.x` — Camera2 integration
- `androidx.compose.material3` — Material Design 3
- `com.google.accompanist:accompanist-systemuicontroller` — System bars
- `com.google.code.gson:gson` — JSON serialization
- NDK r25+ con soporte C++17

---

## 9. Módulos Implementados en Esta Versión

| Módulo | Estado | Notas |
|--------|--------|-------|
| CameraScreen UI | ✅ Completo | Controles manuales, modo nocturno, selector de tipo |
| SessionScreen UI | ✅ Completo | CRUD de sesiones, contadores de frames |
| ResultScreen UI | ✅ Completo | Compartir, abrir galería, info de archivo |
| StarStackTheme | ✅ Completo | Paleta AMOLED Material3 completa |
| MainActivity + Navegación | ✅ Completo | Flujo Sessions → Camera → Result |
| StackingViewModel | ✅ Completo | Orquestación, estados reactivos |
| SessionViewModel | ✅ Completo | CRUD con StateFlow |
| CameraViewModel | ✅ Completo | ISO, shutter, focus, WB |
| StackingEngine (Kotlin) | ✅ Completo | JNI bridge, OOM protection, thermal safety |
| StackingCore (C++) | ✅ Completo | Mean/Median/Sigma, Dark/Flat calibration |
| StarAlignment (C++) | ✅ Completo | Detección, centroide, RANSAC |
| HardwareSafetyMonitor | ✅ Completo | Thermal throttling, power save |
| StackingService | ✅ Completo | Foreground service con notificación |
| FileProvider | ✅ Completo | Compartir archivos de salida |
| AndroidManifest | ✅ Completo | Permisos, provider, service |

---

## 10. Próximas Mejoras Sugeridas

- **Histograma en tiempo real** durante la captura
- **Modo ráfaga automático** con intervalo configurable
- **Exportación a FITS** (formato estándar astronómico)
- **Integración con GPS** para metadatos de ubicación en EXIF
- **Guía polar** asistida para monturas ecuatoriales
- **Procesamiento RAW DNG** nativo con LibRaw
- **Inyección de dependencias** con Hilt/Dagger
