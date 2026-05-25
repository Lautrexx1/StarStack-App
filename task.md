# StarStack - Native Stacking & Alignment Engineering Tasks

Track progress of the native C++ NDK implementation for StarStack.

- [x] Initialize NDK structure under `app/src/main/cpp/`
  - [x] Create `CMakeLists.txt` with compiler flags (NEON, C++17, optimization)
  - [x] Create base JNI interface file `starstack-jni.cpp` matching StackingEngine JNI lifecycle
  - [x] Establish basic headers and skeleton implementations for `StarAlignment` and `StackingCore`
- [ ] Star Detection & Centroid Calculation (`StarAlignment`)
  - [ ] Implement adaptive threshold peak finder
  - [ ] Implement sub-pixel centroid calculation (intensity-weighted center of mass)
- [ ] RANSAC-based 2D Alignment Solving (`StarAlignment`)
  - [ ] Star matching / triangle matching algorithm
  - [ ] RANSAC solver for rotation, translation, and scaling (sidereal drift compensation)
- [ ] Calibration Engine (`StackingCore`)
  - [ ] Dark frame subtraction logic
  - [ ] Flat frame vignette division logic
- [ ] Stacking Engine implementations (`StackingCore`)
  - [ ] Mean stacking accumulator
  - [ ] Median stacking algorithm
  - [ ] Sigma-clipping stacking algorithm
- [ ] Optimization & Streaming Pipeline
  - [ ] Neon SIMD vectorization for calibration and blending
  - [x] Chunked streaming pipeline (sequential frame processing to prevent OOM)
  - [ ] Tile-based processing for high-resolution images
  - [ ] JNI progressive preview updates/callbacks

# StarStack - Android & Camera Systems Engineering Tasks

- [x] Project Initialization & Gradle Setup [x]
  - [x] Configure `settings.gradle.kts` with catalog/plugins support
  - [x] Configure root `build.gradle.kts`
  - [x] Configure `gradle.properties`
  - [x] Configure `app/build.gradle.kts` with Android 15 (targetSdk/compileSdk 35), Compose, and Camera2
  - [x] Create `AndroidManifest.xml` and package structure
  - [x] Create vector launcher icon `ic_launcher.xml`
- [x] JNI Boundary Interfaces [x]
  - [x] Establish native JNI interface `com.starstack.app.processing.StackingEngine`
  - [x] Establish data model `com.starstack.app.data.model.Session`
- [ ] Clean Architecture & MVVM Setup [ ]
  - [ ] Setup Data layer (Repositories, SessionStorage)
  - [ ] Setup Domain layer (Use cases, Models)
  - [ ] Setup Presentation layer (ViewModels, UI States)
- [ ] Camera2 Integration with Manual Controls [ ]
  - [ ] Manual exposure (ISO, Shutter speed) controls
  - [ ] White Balance controls
  - [ ] Manual focus initialized to infinity
  - [ ] RAW/DNG and JPEG dual capture
  - [ ] OIS disabling implementation
- [ ] Session Storage Repository & Directory Structure [ ]
  - [ ] Organize directory: `/Sessions/MilkyWay_date/`
  - [ ] Frame categorization: `Frames/`, `Darks/`, `Flats/`
- [ ] Jetpack Compose UI [ ]
  - [ ] AMOLED black theme
  - [ ] Global Red-Light Night Mode implementation
- [ ] Foreground Services & HW Monitoring [ ]
  - [ ] Foreground service for background stacking/processing
  - [ ] Device thermal & battery monitoring UI integration
- [ ] Final Image Export Integration [ ]
  - [ ] Implementation of export helper
