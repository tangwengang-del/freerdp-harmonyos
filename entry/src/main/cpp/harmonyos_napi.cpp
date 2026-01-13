/*
 * HarmonyOS FreeRDP N-API Bindings
 * 
 * Copyright 2026 FreeRDP HarmonyOS Port
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */

#include <napi/native_api.h>
#include <string>
#include <cstring>
#include <vector>
#include <mutex>
#include <map>

#ifdef OHOS_PLATFORM
#include <hilog/log.h>
#define LOG_TAG "FreeRDP.NAPI"
#define LOGI(...) OH_LOG_INFO(LOG_APP, __VA_ARGS__)
#define LOGW(...) OH_LOG_WARN(LOG_APP, __VA_ARGS__)
#define LOGE(...) OH_LOG_ERROR(LOG_APP, __VA_ARGS__)
#define LOGD(...) OH_LOG_DEBUG(LOG_APP, __VA_ARGS__)
#else
#define LOGI(...) printf(__VA_ARGS__)
#define LOGW(...) printf(__VA_ARGS__)
#define LOGE(...) printf(__VA_ARGS__)
#define LOGD(...) printf(__VA_ARGS__)
#endif

extern "C" {
#include "harmonyos_freerdp.h"
}

// Global environment for callbacks
static napi_env g_env = nullptr;
static std::mutex g_callbackMutex;

// Callback references
static napi_ref g_onConnectionSuccessRef = nullptr;
static napi_ref g_onConnectionFailureRef = nullptr;
static napi_ref g_onPreConnectRef = nullptr;
static napi_ref g_onDisconnectingRef = nullptr;
static napi_ref g_onDisconnectedRef = nullptr;
static napi_ref g_onSettingsChangedRef = nullptr;
static napi_ref g_onGraphicsUpdateRef = nullptr;
static napi_ref g_onGraphicsResizeRef = nullptr;
static napi_ref g_onRemoteClipboardChangedRef = nullptr;
static napi_ref g_onCursorTypeChangedRef = nullptr;

// Instance state tracking
static std::map<int64_t, bool> g_instanceConnected;
static std::mutex g_instanceMutex;

// Helper: Get string from napi_value
static std::string GetString(napi_env env, napi_value value) {
    size_t length;
    napi_get_value_string_utf8(env, value, nullptr, 0, &length);
    std::string result(length, '\0');
    napi_get_value_string_utf8(env, value, &result[0], length + 1, &length);
    return result;
}

// Helper: Create napi_value from string
static napi_value CreateString(napi_env env, const char* str) {
    napi_value result;
    napi_create_string_utf8(env, str ? str : "", NAPI_AUTO_LENGTH, &result);
    return result;
}

// Helper: Get int64 from napi_value
static int64_t GetInt64(napi_env env, napi_value value) {
    int64_t result;
    napi_get_value_int64(env, value, &result);
    return result;
}

// Helper: Get int32 from napi_value
static int32_t GetInt32(napi_env env, napi_value value) {
    int32_t result;
    napi_get_value_int32(env, value, &result);
    return result;
}

// Helper: Get bool from napi_value
static bool GetBool(napi_env env, napi_value value) {
    bool result;
    napi_get_value_bool(env, value, &result);
    return result;
}

// ==================== Callback implementations ====================

static void OnConnectionSuccessImpl(int64_t instance) {
    std::lock_guard<std::mutex> lock(g_callbackMutex);
    if (!g_env || !g_onConnectionSuccessRef) return;
    
    napi_value callback, global, result;
    napi_get_reference_value(g_env, g_onConnectionSuccessRef, &callback);
    napi_get_global(g_env, &global);
    
    napi_value args[1];
    napi_create_int64(g_env, instance, &args[0]);
    
    napi_call_function(g_env, global, callback, 1, args, &result);
    
    // Update instance state
    std::lock_guard<std::mutex> instLock(g_instanceMutex);
    g_instanceConnected[instance] = true;
}

static void OnConnectionFailureImpl(int64_t instance) {
    std::lock_guard<std::mutex> lock(g_callbackMutex);
    if (!g_env || !g_onConnectionFailureRef) return;
    
    napi_value callback, global, result;
    napi_get_reference_value(g_env, g_onConnectionFailureRef, &callback);
    napi_get_global(g_env, &global);
    
    napi_value args[1];
    napi_create_int64(g_env, instance, &args[0]);
    
    napi_call_function(g_env, global, callback, 1, args, &result);
    
    // Update instance state
    std::lock_guard<std::mutex> instLock(g_instanceMutex);
    g_instanceConnected[instance] = false;
}

static void OnPreConnectImpl(int64_t instance) {
    std::lock_guard<std::mutex> lock(g_callbackMutex);
    if (!g_env || !g_onPreConnectRef) return;
    
    napi_value callback, global, result;
    napi_get_reference_value(g_env, g_onPreConnectRef, &callback);
    napi_get_global(g_env, &global);
    
    napi_value args[1];
    napi_create_int64(g_env, instance, &args[0]);
    
    napi_call_function(g_env, global, callback, 1, args, &result);
}

static void OnDisconnectingImpl(int64_t instance) {
    std::lock_guard<std::mutex> lock(g_callbackMutex);
    if (!g_env || !g_onDisconnectingRef) return;
    
    napi_value callback, global, result;
    napi_get_reference_value(g_env, g_onDisconnectingRef, &callback);
    napi_get_global(g_env, &global);
    
    napi_value args[1];
    napi_create_int64(g_env, instance, &args[0]);
    
    napi_call_function(g_env, global, callback, 1, args, &result);
}

static void OnDisconnectedImpl(int64_t instance) {
    std::lock_guard<std::mutex> lock(g_callbackMutex);
    if (!g_env || !g_onDisconnectedRef) return;
    
    napi_value callback, global, result;
    napi_get_reference_value(g_env, g_onDisconnectedRef, &callback);
    napi_get_global(g_env, &global);
    
    napi_value args[1];
    napi_create_int64(g_env, instance, &args[0]);
    
    napi_call_function(g_env, global, callback, 1, args, &result);
    
    // Update instance state
    std::lock_guard<std::mutex> instLock(g_instanceMutex);
    g_instanceConnected[instance] = false;
}

static void OnSettingsChangedImpl(int64_t instance, int width, int height, int bpp) {
    std::lock_guard<std::mutex> lock(g_callbackMutex);
    if (!g_env || !g_onSettingsChangedRef) return;
    
    napi_value callback, global, result;
    napi_get_reference_value(g_env, g_onSettingsChangedRef, &callback);
    napi_get_global(g_env, &global);
    
    napi_value args[4];
    napi_create_int64(g_env, instance, &args[0]);
    napi_create_int32(g_env, width, &args[1]);
    napi_create_int32(g_env, height, &args[2]);
    napi_create_int32(g_env, bpp, &args[3]);
    
    napi_call_function(g_env, global, callback, 4, args, &result);
}

static void OnGraphicsUpdateImpl(int64_t instance, int x, int y, int width, int height) {
    std::lock_guard<std::mutex> lock(g_callbackMutex);
    if (!g_env || !g_onGraphicsUpdateRef) return;
    
    napi_value callback, global, result;
    napi_get_reference_value(g_env, g_onGraphicsUpdateRef, &callback);
    napi_get_global(g_env, &global);
    
    napi_value args[5];
    napi_create_int64(g_env, instance, &args[0]);
    napi_create_int32(g_env, x, &args[1]);
    napi_create_int32(g_env, y, &args[2]);
    napi_create_int32(g_env, width, &args[3]);
    napi_create_int32(g_env, height, &args[4]);
    
    napi_call_function(g_env, global, callback, 5, args, &result);
}

static void OnGraphicsResizeImpl(int64_t instance, int width, int height, int bpp) {
    std::lock_guard<std::mutex> lock(g_callbackMutex);
    if (!g_env || !g_onGraphicsResizeRef) return;
    
    napi_value callback, global, result;
    napi_get_reference_value(g_env, g_onGraphicsResizeRef, &callback);
    napi_get_global(g_env, &global);
    
    napi_value args[4];
    napi_create_int64(g_env, instance, &args[0]);
    napi_create_int32(g_env, width, &args[1]);
    napi_create_int32(g_env, height, &args[2]);
    napi_create_int32(g_env, bpp, &args[3]);
    
    napi_call_function(g_env, global, callback, 4, args, &result);
}

static void OnCursorTypeChangedImpl(int64_t instance, int cursorType) {
    std::lock_guard<std::mutex> lock(g_callbackMutex);
    if (!g_env || !g_onCursorTypeChangedRef) return;
    
    napi_value callback, global, result;
    napi_get_reference_value(g_env, g_onCursorTypeChangedRef, &callback);
    napi_get_global(g_env, &global);
    
    napi_value args[2];
    napi_create_int64(g_env, instance, &args[0]);
    napi_create_int32(g_env, cursorType, &args[1]);
    
    napi_call_function(g_env, global, callback, 2, args, &result);
}

// ==================== N-API Exported Functions ====================

// freerdpNew(): number
static napi_value FreerdpNew(napi_env env, napi_callback_info info) {
    int64_t instance = freerdp_harmonyos_new();
    
    napi_value result;
    napi_create_int64(env, instance, &result);
    return result;
}

// freerdpFree(instance: number): void
static napi_value FreerdpFree(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    int64_t instance = GetInt64(env, args[0]);
    freerdp_harmonyos_free(instance);
    
    // Remove from instance tracking
    std::lock_guard<std::mutex> lock(g_instanceMutex);
    g_instanceConnected.erase(instance);
    
    napi_value undefined;
    napi_get_undefined(env, &undefined);
    return undefined;
}

// freerdpParseArguments(instance: number, args: string[]): boolean
static napi_value FreerdpParseArguments(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value args[2];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    int64_t instance = GetInt64(env, args[0]);
    
    // Get array length
    uint32_t arrayLength;
    napi_get_array_length(env, args[1], &arrayLength);
    
    // Build arguments array
    std::vector<std::string> argStrings(arrayLength);
    std::vector<const char*> argPtrs(arrayLength);
    
    for (uint32_t i = 0; i < arrayLength; i++) {
        napi_value element;
        napi_get_element(env, args[1], i, &element);
        argStrings[i] = GetString(env, element);
        argPtrs[i] = argStrings[i].c_str();
    }
    
    bool success = freerdp_harmonyos_parse_arguments(instance, argPtrs.data(), arrayLength);
    
    napi_value result;
    napi_get_boolean(env, success, &result);
    return result;
}

// freerdpConnect(instance: number): boolean
static napi_value FreerdpConnect(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    int64_t instance = GetInt64(env, args[0]);
    bool success = freerdp_harmonyos_connect(instance);
    
    napi_value result;
    napi_get_boolean(env, success, &result);
    return result;
}

// freerdpDisconnect(instance: number): boolean
static napi_value FreerdpDisconnect(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    int64_t instance = GetInt64(env, args[0]);
    bool success = freerdp_harmonyos_disconnect(instance);
    
    napi_value result;
    napi_get_boolean(env, success, &result);
    return result;
}

// freerdpSendCursorEvent(instance: number, x: number, y: number, flags: number): boolean
static napi_value FreerdpSendCursorEvent(napi_env env, napi_callback_info info) {
    size_t argc = 4;
    napi_value args[4];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    int64_t instance = GetInt64(env, args[0]);
    int32_t x = GetInt32(env, args[1]);
    int32_t y = GetInt32(env, args[2]);
    int32_t flags = GetInt32(env, args[3]);
    
    bool success = freerdp_harmonyos_send_cursor_event(instance, x, y, flags);
    
    napi_value result;
    napi_get_boolean(env, success, &result);
    return result;
}

// freerdpSendKeyEvent(instance: number, keycode: number, down: boolean): boolean
static napi_value FreerdpSendKeyEvent(napi_env env, napi_callback_info info) {
    size_t argc = 3;
    napi_value args[3];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    int64_t instance = GetInt64(env, args[0]);
    int32_t keycode = GetInt32(env, args[1]);
    bool down = GetBool(env, args[2]);
    
    bool success = freerdp_harmonyos_send_key_event(instance, keycode, down);
    
    napi_value result;
    napi_get_boolean(env, success, &result);
    return result;
}

// freerdpSendUnicodeKeyEvent(instance: number, keycode: number, down: boolean): boolean
static napi_value FreerdpSendUnicodeKeyEvent(napi_env env, napi_callback_info info) {
    size_t argc = 3;
    napi_value args[3];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    int64_t instance = GetInt64(env, args[0]);
    int32_t keycode = GetInt32(env, args[1]);
    bool down = GetBool(env, args[2]);
    
    bool success = freerdp_harmonyos_send_unicodekey_event(instance, keycode, down);
    
    napi_value result;
    napi_get_boolean(env, success, &result);
    return result;
}

// freerdpSetTcpKeepalive(instance: number, enabled: boolean, delay: number, interval: number, retries: number): boolean
static napi_value FreerdpSetTcpKeepalive(napi_env env, napi_callback_info info) {
    size_t argc = 5;
    napi_value args[5];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    int64_t instance = GetInt64(env, args[0]);
    bool enabled = GetBool(env, args[1]);
    int32_t delay = GetInt32(env, args[2]);
    int32_t interval = GetInt32(env, args[3]);
    int32_t retries = GetInt32(env, args[4]);
    
    bool success = freerdp_harmonyos_set_tcp_keepalive(instance, enabled, delay, interval, retries);
    
    napi_value result;
    napi_get_boolean(env, success, &result);
    return result;
}

// freerdpSendSynchronizeEvent(instance: number, flags: number): boolean
static napi_value FreerdpSendSynchronizeEvent(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value args[2];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    int64_t instance = GetInt64(env, args[0]);
    int32_t flags = GetInt32(env, args[1]);
    
    bool success = freerdp_harmonyos_send_synchronize_event(instance, flags);
    
    napi_value result;
    napi_get_boolean(env, success, &result);
    return result;
}

// freerdpSendClipboardData(instance: number, data: string): boolean
static napi_value FreerdpSendClipboardData(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value args[2];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    int64_t instance = GetInt64(env, args[0]);
    std::string data = GetString(env, args[1]);
    
    bool success = freerdp_harmonyos_send_clipboard_data(instance, data.c_str());
    
    napi_value result;
    napi_get_boolean(env, success, &result);
    return result;
}

// freerdpSetClientDecoding(instance: number, enable: boolean): number
static napi_value FreerdpSetClientDecoding(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value args[2];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    int64_t instance = GetInt64(env, args[0]);
    bool enable = GetBool(env, args[1]);
    
    int32_t resultCode = freerdp_harmonyos_set_client_decoding(instance, enable);
    
    napi_value result;
    napi_create_int32(env, resultCode, &result);
    return result;
}

// freerdpGetLastErrorString(instance: number): string
static napi_value FreerdpGetLastErrorString(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    int64_t instance = GetInt64(env, args[0]);
    const char* errorStr = freerdp_harmonyos_get_last_error_string(instance);
    
    return CreateString(env, errorStr);
}

// freerdpGetVersion(): string
static napi_value FreerdpGetVersion(napi_env env, napi_callback_info info) {
    const char* version = freerdp_harmonyos_get_version();
    return CreateString(env, version);
}

// freerdpHasH264(): boolean
static napi_value FreerdpHasH264(napi_env env, napi_callback_info info) {
    bool hasH264 = freerdp_harmonyos_has_h264();
    
    napi_value result;
    napi_get_boolean(env, hasH264, &result);
    return result;
}

// freerdpIsConnected(instance: number): boolean
static napi_value FreerdpIsConnected(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    int64_t instance = GetInt64(env, args[0]);
    bool connected = freerdp_harmonyos_is_connected(instance);
    
    napi_value result;
    napi_get_boolean(env, connected, &result);
    return result;
}

// Callback setters
static napi_value SetOnConnectionSuccess(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    if (g_onConnectionSuccessRef) {
        napi_delete_reference(env, g_onConnectionSuccessRef);
    }
    napi_create_reference(env, args[0], 1, &g_onConnectionSuccessRef);
    g_env = env;
    
    harmonyos_set_connection_success_callback(OnConnectionSuccessImpl);
    
    napi_value undefined;
    napi_get_undefined(env, &undefined);
    return undefined;
}

static napi_value SetOnConnectionFailure(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    if (g_onConnectionFailureRef) {
        napi_delete_reference(env, g_onConnectionFailureRef);
    }
    napi_create_reference(env, args[0], 1, &g_onConnectionFailureRef);
    g_env = env;
    
    harmonyos_set_connection_failure_callback(OnConnectionFailureImpl);
    
    napi_value undefined;
    napi_get_undefined(env, &undefined);
    return undefined;
}

static napi_value SetOnPreConnect(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    if (g_onPreConnectRef) {
        napi_delete_reference(env, g_onPreConnectRef);
    }
    napi_create_reference(env, args[0], 1, &g_onPreConnectRef);
    g_env = env;
    
    harmonyos_set_pre_connect_callback(OnPreConnectImpl);
    
    napi_value undefined;
    napi_get_undefined(env, &undefined);
    return undefined;
}

static napi_value SetOnDisconnecting(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    if (g_onDisconnectingRef) {
        napi_delete_reference(env, g_onDisconnectingRef);
    }
    napi_create_reference(env, args[0], 1, &g_onDisconnectingRef);
    g_env = env;
    
    harmonyos_set_disconnecting_callback(OnDisconnectingImpl);
    
    napi_value undefined;
    napi_get_undefined(env, &undefined);
    return undefined;
}

static napi_value SetOnDisconnected(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    if (g_onDisconnectedRef) {
        napi_delete_reference(env, g_onDisconnectedRef);
    }
    napi_create_reference(env, args[0], 1, &g_onDisconnectedRef);
    g_env = env;
    
    harmonyos_set_disconnected_callback(OnDisconnectedImpl);
    
    napi_value undefined;
    napi_get_undefined(env, &undefined);
    return undefined;
}

static napi_value SetOnSettingsChanged(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    if (g_onSettingsChangedRef) {
        napi_delete_reference(env, g_onSettingsChangedRef);
    }
    napi_create_reference(env, args[0], 1, &g_onSettingsChangedRef);
    g_env = env;
    
    harmonyos_set_settings_changed_callback(OnSettingsChangedImpl);
    
    napi_value undefined;
    napi_get_undefined(env, &undefined);
    return undefined;
}

static napi_value SetOnGraphicsUpdate(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    if (g_onGraphicsUpdateRef) {
        napi_delete_reference(env, g_onGraphicsUpdateRef);
    }
    napi_create_reference(env, args[0], 1, &g_onGraphicsUpdateRef);
    g_env = env;
    
    harmonyos_set_graphics_update_callback(OnGraphicsUpdateImpl);
    
    napi_value undefined;
    napi_get_undefined(env, &undefined);
    return undefined;
}

static napi_value SetOnGraphicsResize(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    if (g_onGraphicsResizeRef) {
        napi_delete_reference(env, g_onGraphicsResizeRef);
    }
    napi_create_reference(env, args[0], 1, &g_onGraphicsResizeRef);
    g_env = env;
    
    harmonyos_set_graphics_resize_callback(OnGraphicsResizeImpl);
    
    napi_value undefined;
    napi_get_undefined(env, &undefined);
    return undefined;
}

static napi_value SetOnCursorTypeChanged(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    if (g_onCursorTypeChangedRef) {
        napi_delete_reference(env, g_onCursorTypeChangedRef);
    }
    napi_create_reference(env, args[0], 1, &g_onCursorTypeChangedRef);
    g_env = env;
    
    harmonyos_set_cursor_type_changed_callback(OnCursorTypeChangedImpl);
    
    napi_value undefined;
    napi_get_undefined(env, &undefined);
    return undefined;
}

// ==================== Module Registration ====================

static napi_value Init(napi_env env, napi_value exports) {
    napi_property_descriptor desc[] = {
        // Core functions
        { "freerdpNew", nullptr, FreerdpNew, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "freerdpFree", nullptr, FreerdpFree, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "freerdpParseArguments", nullptr, FreerdpParseArguments, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "freerdpConnect", nullptr, FreerdpConnect, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "freerdpDisconnect", nullptr, FreerdpDisconnect, nullptr, nullptr, nullptr, napi_default, nullptr },
        
        // Input functions
        { "freerdpSendCursorEvent", nullptr, FreerdpSendCursorEvent, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "freerdpSendKeyEvent", nullptr, FreerdpSendKeyEvent, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "freerdpSendUnicodeKeyEvent", nullptr, FreerdpSendUnicodeKeyEvent, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "freerdpSendClipboardData", nullptr, FreerdpSendClipboardData, nullptr, nullptr, nullptr, napi_default, nullptr },
        
        // Network functions
        { "freerdpSetTcpKeepalive", nullptr, FreerdpSetTcpKeepalive, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "freerdpSendSynchronizeEvent", nullptr, FreerdpSendSynchronizeEvent, nullptr, nullptr, nullptr, napi_default, nullptr },
        
        // Display functions
        { "freerdpSetClientDecoding", nullptr, FreerdpSetClientDecoding, nullptr, nullptr, nullptr, napi_default, nullptr },
        
        // Utility functions
        { "freerdpGetLastErrorString", nullptr, FreerdpGetLastErrorString, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "freerdpGetVersion", nullptr, FreerdpGetVersion, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "freerdpHasH264", nullptr, FreerdpHasH264, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "freerdpIsConnected", nullptr, FreerdpIsConnected, nullptr, nullptr, nullptr, napi_default, nullptr },
        
        // Callback setters
        { "setOnConnectionSuccess", nullptr, SetOnConnectionSuccess, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "setOnConnectionFailure", nullptr, SetOnConnectionFailure, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "setOnPreConnect", nullptr, SetOnPreConnect, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "setOnDisconnecting", nullptr, SetOnDisconnecting, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "setOnDisconnected", nullptr, SetOnDisconnected, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "setOnSettingsChanged", nullptr, SetOnSettingsChanged, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "setOnGraphicsUpdate", nullptr, SetOnGraphicsUpdate, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "setOnGraphicsResize", nullptr, SetOnGraphicsResize, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "setOnCursorTypeChanged", nullptr, SetOnCursorTypeChanged, nullptr, nullptr, nullptr, napi_default, nullptr },
    };
    
    napi_define_properties(env, exports, sizeof(desc) / sizeof(desc[0]), desc);
    
    LOGI("FreeRDP HarmonyOS N-API module initialized");
    return exports;
}

NAPI_MODULE(freerdp_harmonyos, Init)
