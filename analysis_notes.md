# StarStack Analysis Notes

## Completado
- Estructura base del proyecto y Gradle.
- Capa de datos básica (Modelos `Session`, `CameraSettings`, `CaptureType`).
- Repositorios básicos (`SessionRepositoryImpl`, `FileSessionRepositoryImpl`).
- JNI básico y esqueletos C++ (`StackingCore`, `StarAlignment`, `starstack-jni.cpp`).
- Servicio en primer plano (`StackingService`).
- Monitoreo de seguridad de hardware básico (`HardwareSafetyMonitor`).
- ViewModels básicos (`SessionViewModel`, `CameraViewModel`).

## Incompleto / Riesgos de Compilación
- **Camera2 Integration:** Faltan detalles de implementación en `CameraRepositoryImpl` (captura RAW/DNG, controles manuales reales, inicialización de foco a infinito, desactivación de OIS).
- **C++ Native Engine:**
  - `StarAlignment`: Detección de estrellas (centroid extraction) no implementada.
  - `StackingCore`: Calibración (Dark/Flat) no implementada. Acumulación (Mean, Median, Sigma-clipping) no implementada. SIMD no implementado.
- **UI Compose:** `CameraScreen` es un esqueleto básico con un `PreviewView` y un bloque de texto para controles. Faltan controles manuales, tema AMOLED, modo nocturno, y retroalimentación de progreso de apilado.
- **Domain Layer:** Faltan detalles en los UseCases (e.g., `CameraUseCase` existe pero delega a repositorio que está incompleto).

## Próximos pasos
1. Compilar el proyecto para verificar errores iniciales.
2. Implementar los métodos vacíos en `CameraRepositoryImpl` para habilitar controles manuales.
3. Desarrollar la UI de `CameraScreen` para los controles manuales.
4. Implementar los algoritmos C++ faltantes en `StackingCore.cpp` y `StarAlignment.cpp`.
