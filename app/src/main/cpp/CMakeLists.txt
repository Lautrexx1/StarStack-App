cmake_minimum_required(VERSION 3.22.1)

project("starstack-native")

# Support modern C++17
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Enable compiler optimization flags for performance-critical stacking code.
# Optimize for ARM NEON where applicable.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    add_compile_options(-O3 -mfloat-abi=softfp -mfpu=neon)
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
    add_compile_options(-O3)
else()
    add_compile_options(-O3)
endif()

add_library(
    starstack-native
    SHARED
    starstack-jni.cpp
    StackingCore.cpp
    StarAlignment.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    starstack-native
    ${log-lib}
)
