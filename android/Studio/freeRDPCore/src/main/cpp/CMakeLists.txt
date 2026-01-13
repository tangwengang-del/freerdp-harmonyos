cmake_minimum_required(VERSION 3.13)

project("freerdp-android" LANGUAGES C VERSION "3.18.0")

set(MODULE_NAME "freerdp-android")

set(ANDROID_JNI_SOURCES
    android_freerdp.c
    android_cliprdr.c
    android_event.c
    android_jni_callback.c
    android_jni_utils.c
)

add_library(${MODULE_NAME} SHARED ${ANDROID_JNI_SOURCES})

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

target_include_directories(${MODULE_NAME} PRIVATE
    ${CMAKE_SOURCE_DIR}/include
    ${CMAKE_SOURCE_DIR}/winpr/include
    ${CMAKE_SOURCE_DIR}/client/common
    ${CMAKE_BINARY_DIR}/include
    ${CMAKE_BINARY_DIR}/winpr/include
)

target_link_libraries(${MODULE_NAME} PRIVATE
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
    freerdp-client
    freerdp
    winpr
)
