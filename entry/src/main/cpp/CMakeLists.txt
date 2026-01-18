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

# Default prebuilt libs directory
set(DEFAULT_LIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/arm64-v8a")
set(LIBS_DIR "${DEFAULT_LIBS_DIR}")

# Prefer explicit lib dir or install prefix lib dirs when rebuilding
if(DEFINED FREERDP_LIB_DIR AND EXISTS "${FREERDP_LIB_DIR}")
    set(LIBS_DIR "${FREERDP_LIB_DIR}")
elseif(DEFINED FREERDP_DIR)
    if(EXISTS "${FREERDP_DIR}/lib")
        set(LIBS_DIR "${FREERDP_DIR}/lib")
    elseif(EXISTS "${FREERDP_DIR}/lib64")
        set(LIBS_DIR "${FREERDP_DIR}/lib64")
    endif()
endif()

# Optional extra dependency lib dirs (OpenSSL/FFmpeg/etc)
set(EXTRA_LIB_DIRS "")
if(DEFINED OPENSSL_DIR)
    if(EXISTS "${OPENSSL_DIR}/lib")
        list(APPEND EXTRA_LIB_DIRS "${OPENSSL_DIR}/lib")
    elseif(EXISTS "${OPENSSL_DIR}/lib64")
        list(APPEND EXTRA_LIB_DIRS "${OPENSSL_DIR}/lib64")
    endif()
endif()
if(DEFINED FFMPEG_DIR)
    if(EXISTS "${FFMPEG_DIR}/lib")
        list(APPEND EXTRA_LIB_DIRS "${FFMPEG_DIR}/lib")
    elseif(EXISTS "${FFMPEG_DIR}/lib64")
        list(APPEND EXTRA_LIB_DIRS "${FFMPEG_DIR}/lib64")
    endif()
endif()

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
    ${EXTRA_LIB_DIRS}
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
    OpenSLES
    
    # FreeRDP libraries - 使用官方客户端库
    # 现在启用了 WITH_CLIENT_COMMON=ON，可以使用官方 freerdp-client3 库
    # 这包含了完整的 freerdp_client_settings_parse_command_line 等实现
    freerdp-client3
    freerdp3
    winpr3
    
    # 注意：OpenSSL 已静态链接到 FreeRDP 核心库中（见 GitHub Actions workflow）
    # 不需要单独链接 ssl 和 crypto
    
    # 注意：FFmpeg 在 FreeRDP 构建时被禁用（-DWITH_FFMPEG=OFF）
    # 不需要链接 avcodec, avutil, swscale, swresample
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
    # 注意：OpenSSL 已静态链接到 FreeRDP，无需 -DWITH_OPENSSL
    # 注意：FFmpeg 被禁用，无需 -DWITH_FFMPEG
)

# Export symbols for N-API
target_compile_definitions(freerdp_harmonyos PRIVATE
    NAPI_VERSION=8
)
