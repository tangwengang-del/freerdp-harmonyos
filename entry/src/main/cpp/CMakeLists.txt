# CMakeLists.txt for FreeRDP HarmonyOS Native Library
# This builds the N-API wrapper for FreeRDP on HarmonyOS

cmake_minimum_required(VERSION 3.16)
project(freerdp_harmonyos)

# HarmonyOS specific settings
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# FreeRDP paths (set by build system)
# Note: CMAKE_CURRENT_SOURCE_DIR is entry/src/main/cpp
# Go up 3 levels to reach entry/ directory where libs/ is located
if(NOT DEFINED FREERDP_DIR)
    set(FREERDP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../libs")
endif()

# OpenSSL is already linked into FreeRDP libraries, no need to find_package
# Pre-compiled libraries are in libs/arm64-v8a directory
set(LIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/arm64-v8a")

# Include directories
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${FREERDP_DIR}/include
    ${FREERDP_DIR}/include/freerdp3
    ${FREERDP_DIR}/include/winpr3
)

# Link directories
link_directories(
    ${FREERDP_DIR}/lib
    ${LIBS_DIR}
)

# Source files
set(NATIVE_SOURCES
    harmonyos_freerdp.cpp
    harmonyos_napi.cpp
    harmonyos_event.c
    harmonyos_cliprdr.c
    harmonyos_jni_callback.c
    harmonyos_jni_utils.c
    freerdp_client_compat.c
)

# Create shared library
add_library(freerdp_harmonyos SHARED ${NATIVE_SOURCES})

# Link libraries
target_link_libraries(freerdp_harmonyos
    # HarmonyOS N-API
    ace_napi.z
    hilog_ndk.z
    
    # FreeRDP libraries (3.5.1 for OHOS/musl libc compatibility)
    # Note: freerdp-client3 不可用于OHOS - 使用兼容层替代
    freerdp3
    winpr3
    
    # OpenSSL (pre-compiled in libs directory)
    ssl
    crypto
    
    # FFmpeg (optional, for H.264)
    avcodec
    avutil
    swscale
    swresample
)

# Set output properties
set_target_properties(freerdp_harmonyos PROPERTIES
    OUTPUT_NAME "freerdp_harmonyos"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/arm64-v8a"
)

# Compiler flags
target_compile_options(freerdp_harmonyos PRIVATE
    -Wall
    -Wextra
    -O2
    -fPIC
    -DOHOS_PLATFORM
    -DWITH_OPENSSL
    -DWITH_FFMPEG
)

# Export symbols for N-API
target_compile_definitions(freerdp_harmonyos PRIVATE
    NAPI_VERSION=8
)
