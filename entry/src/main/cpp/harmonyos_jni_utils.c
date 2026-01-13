/*
 * HarmonyOS FreeRDP JNI Utils Stub
 * 
 * This file provides compatibility stubs for code that references JNI utilities.
 * In HarmonyOS, we use N-API instead of JNI.
 * 
 * Copyright 2026 FreeRDP HarmonyOS Port
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */

#include <stddef.h>
#include <stdlib.h>
#include <string.h>

/* Java class name constants - kept for compatibility */
#define JAVA_CONTEXT_CLASS "android/content/Context"
#define JAVA_FILE_CLASS "java/io/File"
#define JAVA_LIBFREERDP_CLASS "com/freerdp/freerdpcore/services/LibFreeRDP"

/* Stub implementations for JNI utility functions */

char* jni_get_string_utf_chars(void* env, void* jstring, void* isCopy) {
    /* No-op in HarmonyOS */
    return NULL;
}

void jni_release_string_utf_chars(void* env, void* jstring, const char* chars) {
    /* No-op in HarmonyOS */
}

void* jni_new_string_utf(void* env, const char* str) {
    /* No-op in HarmonyOS */
    return NULL;
}

void* jni_find_class(void* env, const char* name) {
    /* No-op in HarmonyOS */
    return NULL;
}

void* jni_get_method_id(void* env, void* clazz, const char* name, const char* sig) {
    /* No-op in HarmonyOS */
    return NULL;
}

void* jni_call_object_method(void* env, void* obj, void* methodId, ...) {
    /* No-op in HarmonyOS */
    return NULL;
}

int jni_call_int_method(void* env, void* obj, void* methodId, ...) {
    /* No-op in HarmonyOS */
    return 0;
}

void jni_call_void_method(void* env, void* obj, void* methodId, ...) {
    /* No-op in HarmonyOS */
}
