cmake_minimum_required(VERSION 3.18)
project(waifu2x-jni)

set(CMAKE_CXX_STANDARD 17)

# Point to the local ncnn SDK (2026 version)
set(NCNN_SDK_DIR "/Users/haoweili/Downloads/ncnn-20260113-android-vulkan")

# Find ncnn package in the local SDK
set(ncnn_DIR ${NCNN_SDK_DIR}/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

# Source files
set(WAIFU2X_SOURCES
    waifu2x.cpp
    anime4k.cpp
    waifu2x_jni.cpp
)

# Create shared library
add_library(waifu2x-jni SHARED ${WAIFU2X_SOURCES})

# Include directories
target_include_directories(waifu2x-jni PRIVATE
    ${CMAKE_SOURCE_DIR}
)

# Link libraries
target_link_libraries(waifu2x-jni
    ncnn
    android
    log
    jnigraphics
    GLESv3
    EGL
)
