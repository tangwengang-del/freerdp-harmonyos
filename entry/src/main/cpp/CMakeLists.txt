# CMakeLists.txt for FreeRDP HarmonyOS Native Library
# This builds the N-API wrapper for FreeRDP on HarmonyOS
#
# IMPORTANT: All pre-compiled .so libraries in libs/arm64-v8a MUST be compiled
# with OHOS NDK (musl libc). Libraries compiled with standard Linux toolchain
# (glibc) will fail at runtime with "ld-linux-aarch64.so.1 not found" error.

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
# Note: FreeRDP core libraries (freerdp3, winpr3) have OpenSSL statically linked
# if built with the updated workflow. Otherwise, ssl and crypto are needed.
target_link_libraries(freerdp_harmonyos
    # HarmonyOS N-API
    ace_napi.z
    hilog_ndk.z
    
    # FreeRDP core libraries (compiled with OHOS NDK)
    # freerdp-client3 not available on OHOS/musl, using compatibility layer
    freerdp3
    winpr3
    
    # OpenSSL - only if FreeRDP was built with shared OpenSSL
    # These libraries MUST be compiled with OHOS NDK (musl libc)
    # Uncomment if you have musl-compatible OpenSSL .so files:
    # ssl
    # crypto
)

# Set output properties
set_target_properties(freerdp_harmonyos PROPERTIES
    OUTPUT_NAME "freerdp_harmonyos"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/arm64-v8a"
)

# Compiler flags
# Note: WITH_FFMPEG disabled as FFmpeg libraries need to be compiled with OHOS NDK
target_compile_options(freerdp_harmonyos PRIVATE
    -Wall
    -Wextra
    -O2
    -fPIC
    -DOHOS_PLATFORM
    -DWITH_OPENSSL
    # -DWITH_FFMPEG  # Disabled until FFmpeg is compiled with OHOS NDK
)

# Export symbols for N-API
target_compile_definitions(freerdp_harmonyos PRIVATE
    NAPI_VERSION=8
)
