/*
 * HarmonyOS FreeRDP JNI Callback Stub
 * 
 * This file provides compatibility stubs for code that references JNI callbacks.
 * In HarmonyOS, we use N-API instead of JNI.
 * 
 * Copyright 2026 FreeRDP HarmonyOS Port
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */

#include <stddef.h>
#include <stdbool.h>

/* Stub implementations - actual callbacks are handled in harmonyos_napi.cpp */

/* These are placeholder functions that maintain compatibility with 
 * code that might reference JNI callback patterns */

void* jni_attach_thread(void** env) {
    /* No-op in HarmonyOS */
    *env = NULL;
    return NULL;
}

void jni_detach_thread(void) {
    /* No-op in HarmonyOS */
}

void* create_string_builder(void* env, const char* str) {
    /* No-op in HarmonyOS */
    return NULL;
}

char* get_string_from_string_builder(void* env, void* strBuilder) {
    /* No-op in HarmonyOS */
    return NULL;
}

int init_callback_environment(void* vm, void* env, void* libFreeRDPClass) {
    /* No-op in HarmonyOS - callbacks are registered via N-API */
    return 0;
}

void deinit_callback_environment(void* env) {
    /* No-op in HarmonyOS */
}

/* Generic callback function - not used in HarmonyOS */
void freerdp_callback(const char* callback, const char* signature, ...) {
    /* No-op in HarmonyOS - callbacks are handled via N-API */
}

bool freerdp_callback_bool_result(const char* callback, const char* signature, ...) {
    /* No-op in HarmonyOS */
    return false;
}

int freerdp_callback_int_result(const char* callback, const char* signature, ...) {
    /* No-op in HarmonyOS */
    return 0;
}
