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
#include <stdlib.h>  // for setenv/unsetenv
}

// Global environment for callbacks
static napi_env g_env = nullptr;
static std::mutex g_callbackMutex;

// Thread-safe function handles
static napi_threadsafe_function g_tsfnConnectionSuccess = nullptr;
static napi_threadsafe_function g_tsfnConnectionFailure = nullptr;
static napi_threadsafe_function g_tsfnPreConnect = nullptr;
static napi_threadsafe_function g_tsfnDisconnecting = nullptr;
static napi_threadsafe_function g_tsfnDisconnected = nullptr;
static napi_threadsafe_function g_tsfnSettingsChanged = nullptr;
static napi_threadsafe_function g_tsfnGraphicsUpdate = nullptr;
static napi_threadsafe_function g_tsfnGraphicsResize = nullptr;
static napi_threadsafe_function g_tsfnCursorTypeChanged = nullptr;

// Mutex for protecting TSFN pointers
static std::mutex g_tsfnMutex;

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

// ==================== Callback Data Structures ====================

struct CallbackData {
    int64_t instance;
    int32_t x = 0;
    int32_t y = 0;
    int32_t width = 0;
    int32_t height = 0;
    int32_t bpp = 0;
    int32_t cursorType = 0;
};

// ==================== Thread-Safe Callbacks ====================

static void CallJS_InstanceOnly(napi_env env, napi_value js_callback, void* context, void* data) {
    if (!env || !js_callback || !data) return;
    CallbackData* cbData = static_cast<CallbackData*>(data);
    napi_value global, result, arg;
    if (napi_get_global(env, &global) != napi_ok) {
        delete cbData;
        return;
    }
    napi_create_int64(env, cbData->instance, &arg);
    napi_call_function(env, global, js_callback, 1, &arg, &result);
    delete cbData;
}

static void CallJS_GraphicsUpdate(napi_env env, napi_value js_callback, void* context, void* data) {
    if (!env || !js_callback || !data) return;
    CallbackData* cbData = static_cast<CallbackData*>(data);
    napi_value global, result;
    if (napi_get_global(env, &global) != napi_ok) {
        delete cbData;
        return;
    }
    
    napi_value args[5];
    napi_create_int64(env, cbData->instance, &args[0]);
    napi_create_int32(env, cbData->x, &args[1]);
    napi_create_int32(env, cbData->y, &args[2]);
    napi_create_int32(env, cbData->width, &args[3]);
    napi_create_int32(env, cbData->height, &args[4]);
    
    napi_call_function(env, global, js_callback, 5, args, &result);
    delete cbData;
}

static void CallJS_ResizeOrSettings(napi_env env, napi_value js_callback, void* context, void* data) {
    if (!env || !js_callback || !data) return;
    CallbackData* cbData = static_cast<CallbackData*>(data);
    napi_value global, result;
    if (napi_get_global(env, &global) != napi_ok) {
        delete cbData;
        return;
    }
    
    napi_value args[4];
    napi_create_int64(env, cbData->instance, &args[0]);
    napi_create_int32(env, cbData->width, &args[1]);
    napi_create_int32(env, cbData->height, &args[2]);
    napi_create_int32(env, cbData->bpp, &args[3]);
    
    napi_call_function(env, global, js_callback, 4, args, &result);
    delete cbData;
}

static void CallJS_CursorType(napi_env env, napi_value js_callback, void* context, void* data) {
    if (!env || !js_callback || !data) return;
    CallbackData* cbData = static_cast<CallbackData*>(data);
    napi_value global, result;
    if (napi_get_global(env, &global) != napi_ok) {
        delete cbData;
        return;
    }
    
    napi_value args[2];
    napi_create_int64(env, cbData->instance, &args[0]);
    napi_create_int32(env, cbData->cursorType, &args[1]);
    
    napi_call_function(env, global, js_callback, 2, args, &result);
    delete cbData;
}

// ==================== Native Callback Implementations (Bridge to TSFN) ====================

static void OnConnectionSuccessImpl(int64_t instance) {
    std::lock_guard<std::mutex> lock(g_tsfnMutex);
    if (!g_tsfnConnectionSuccess) return;
    CallbackData* data = new CallbackData{instance};
    napi_call_threadsafe_function(g_tsfnConnectionSuccess, data, napi_tsfn_blocking);
    
    std::lock_guard<std::mutex> instLock(g_instanceMutex);
    g_instanceConnected[instance] = true;
}

static void OnConnectionFailureImpl(int64_t instance) {
    std::lock_guard<std::mutex> lock(g_tsfnMutex);
    if (!g_tsfnConnectionFailure) return;
    CallbackData* data = new CallbackData{instance};
    napi_call_threadsafe_function(g_tsfnConnectionFailure, data, napi_tsfn_blocking);
    
    std::lock_guard<std::mutex> instLock(g_instanceMutex);
    g_instanceConnected[instance] = false;
}

static void OnPreConnectImpl(int64_t instance) {
    std::lock_guard<std::mutex> lock(g_tsfnMutex);
    if (!g_tsfnPreConnect) return;
    CallbackData* data = new CallbackData{instance};
    napi_call_threadsafe_function(g_tsfnPreConnect, data, napi_tsfn_blocking);
}

static void OnDisconnectingImpl(int64_t instance) {
    std::lock_guard<std::mutex> lock(g_tsfnMutex);
    if (!g_tsfnDisconnecting) return;
    CallbackData* data = new CallbackData{instance};
    napi_call_threadsafe_function(g_tsfnDisconnecting, data, napi_tsfn_blocking);
}

static void OnDisconnectedImpl(int64_t instance) {
    std::lock_guard<std::mutex> lock(g_tsfnMutex);
    if (!g_tsfnDisconnected) return;
    CallbackData* data = new CallbackData{instance};
    napi_call_threadsafe_function(g_tsfnDisconnected, data, napi_tsfn_blocking);
    
    std::lock_guard<std::mutex> instLock(g_instanceMutex);
    g_instanceConnected[instance] = false;
}

static void OnSettingsChangedImpl(int64_t instance, int width, int height, int bpp) {
    std::lock_guard<std::mutex> lock(g_tsfnMutex);
    if (!g_tsfnSettingsChanged) return;
    CallbackData* data = new CallbackData{instance};
    data->width = width;
    data->height = height;
    data->bpp = bpp;
    napi_call_threadsafe_function(g_tsfnSettingsChanged, data, napi_tsfn_blocking);
}

static void OnGraphicsUpdateImpl(int64_t instance, int x, int y, int width, int height) {
    std::lock_guard<std::mutex> lock(g_tsfnMutex);
    if (!g_tsfnGraphicsUpdate) return;
    CallbackData* data = new CallbackData{instance};
    data->x = x;
    data->y = y;
    data->width = width;
    data->height = height;
    napi_call_threadsafe_function(g_tsfnGraphicsUpdate, data, napi_tsfn_blocking);
}

static void OnGraphicsResizeImpl(int64_t instance, int width, int height, int bpp) {
    std::lock_guard<std::mutex> lock(g_tsfnMutex);
    if (!g_tsfnGraphicsResize) return;
    CallbackData* data = new CallbackData{instance};
    data->width = width;
    data->height = height;
    data->bpp = bpp;
    napi_call_threadsafe_function(g_tsfnGraphicsResize, data, napi_tsfn_blocking);
}

static void OnCursorTypeChangedImpl(int64_t instance, int cursorType) {
    std::lock_guard<std::mutex> lock(g_tsfnMutex);
    if (!g_tsfnCursorTypeChanged) return;
    CallbackData* data = new CallbackData{instance};
    data->cursorType = cursorType;
    napi_call_threadsafe_function(g_tsfnCursorTypeChanged, data, napi_tsfn_blocking);
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

// ==================== Background Mode & Audio Priority ====================

// freerdpEnterBackgroundMode(instance: number): boolean
static napi_value FreerdpEnterBackgroundMode(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    int64_t instance = GetInt64(env, args[0]);
    bool success = freerdp_harmonyos_enter_background_mode(instance);
    
    napi_value result;
    napi_get_boolean(env, success, &result);
    return result;
}

// freerdpExitBackgroundMode(instance: number): boolean
static napi_value FreerdpExitBackgroundMode(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    int64_t instance = GetInt64(env, args[0]);
    bool success = freerdp_harmonyos_exit_background_mode(instance);
    
    napi_value result;
    napi_get_boolean(env, success, &result);
    return result;
}

// freerdpConfigureAudio(instance: number, playback: boolean, capture: boolean, quality: number): boolean
static napi_value FreerdpConfigureAudio(napi_env env, napi_callback_info info) {
    size_t argc = 4;
    napi_value args[4];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    int64_t instance = GetInt64(env, args[0]);
    bool playback = GetBool(env, args[1]);
    bool capture = GetBool(env, args[2]);
    int32_t quality = GetInt32(env, args[3]);
    
    bool success = freerdp_harmonyos_configure_audio(instance, playback, capture, quality);
    
    napi_value result;
    napi_get_boolean(env, success, &result);
    return result;
}

// freerdpSetAutoReconnect(instance: number, enabled: boolean, maxRetries: number, delayMs: number): boolean
static napi_value FreerdpSetAutoReconnect(napi_env env, napi_callback_info info) {
    size_t argc = 4;
    napi_value args[4];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    int64_t instance = GetInt64(env, args[0]);
    bool enabled = GetBool(env, args[1]);
    int32_t maxRetries = GetInt32(env, args[2]);
    int32_t delayMs = GetInt32(env, args[3]);
    
    bool success = freerdp_harmonyos_set_auto_reconnect(instance, enabled, maxRetries, delayMs);
    
    napi_value result;
    napi_get_boolean(env, success, &result);
    return result;
}

// freerdpGetConnectionHealth(instance: number): number
static napi_value FreerdpGetConnectionHealth(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    int64_t instance = GetInt64(env, args[0]);
    int32_t health = freerdp_harmonyos_get_connection_health(instance);
    
    napi_value result;
    napi_create_int32(env, health, &result);
    return result;
}

// ==================== Screen Refresh ====================

// freerdpRequestRefresh(instance: number): boolean
static napi_value FreerdpRequestRefresh(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    int64_t instance = GetInt64(env, args[0]);
    bool success = freerdp_harmonyos_request_refresh(instance);
    
    napi_value result;
    napi_get_boolean(env, success, &result);
    return result;
}

// freerdpRequestRefreshRect(instance: number, x: number, y: number, width: number, height: number): boolean
static napi_value FreerdpRequestRefreshRect(napi_env env, napi_callback_info info) {
    size_t argc = 5;
    napi_value args[5];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    int64_t instance = GetInt64(env, args[0]);
    int32_t x = GetInt32(env, args[1]);
    int32_t y = GetInt32(env, args[2]);
    int32_t width = GetInt32(env, args[3]);
    int32_t height = GetInt32(env, args[4]);
    
    bool success = freerdp_harmonyos_request_refresh_rect(instance, x, y, width, height);
    
    napi_value result;
    napi_get_boolean(env, success, &result);
    return result;
}

// ==================== Connection Stability ====================

// freerdpIsInBackgroundMode(instance: number): boolean
static napi_value FreerdpIsInBackgroundMode(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    int64_t instance = GetInt64(env, args[0]);
    bool isBackground = freerdp_harmonyos_is_in_background_mode(instance);
    
    napi_value result;
    napi_get_boolean(env, isBackground, &result);
    return result;
}

// freerdpSendKeepalive(instance: number): boolean
static napi_value FreerdpSendKeepalive(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    int64_t instance = GetInt64(env, args[0]);
    bool success = freerdp_harmonyos_send_keepalive(instance);
    
    napi_value result;
    napi_get_boolean(env, success, &result);
    return result;
}

// freerdpGetIdleTime(instance: number): number
static napi_value FreerdpGetIdleTime(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    int64_t instance = GetInt64(env, args[0]);
    uint64_t idleTime = freerdp_harmonyos_get_idle_time(instance);
    
    napi_value result;
    napi_create_int64(env, (int64_t)idleTime, &result);
    return result;
}

// freerdpCheckConnectionStatus(instance: number): number
static napi_value FreerdpCheckConnectionStatus(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    int64_t instance = GetInt64(env, args[0]);
    int32_t status = freerdp_harmonyos_check_connection_status(instance);
    
    napi_value result;
    napi_create_int32(env, status, &result);
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
static napi_value CreateTSFN(napi_env env, napi_value callback, const char* name, 
                            napi_threadsafe_function_call_js call_js, 
                            napi_threadsafe_function* result_tsfn) {
    napi_value resource_name;
    napi_create_string_utf8(env, name, NAPI_AUTO_LENGTH, &resource_name);
    
    if (*result_tsfn) {
        napi_release_threadsafe_function(*result_tsfn, napi_tsfn_release);
    }
    
    napi_status status = napi_create_threadsafe_function(
        env,
        callback,
        nullptr,
        resource_name,
        0,
        1,
        nullptr,
        nullptr,
        nullptr,
        call_js,
        result_tsfn
    );
    
    if (status != napi_ok) {
        LOGE("Failed to create threadsafe function for %s: %d", name, (int)status);
    }
    
    napi_value undefined;
    napi_get_undefined(env, &undefined);
    return undefined;
}

static napi_value SetOnConnectionSuccess(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    harmonyos_set_connection_success_callback(OnConnectionSuccessImpl);
    return CreateTSFN(env, args[0], "OnConnectionSuccess", CallJS_InstanceOnly, &g_tsfnConnectionSuccess);
}

static napi_value SetOnConnectionFailure(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    harmonyos_set_connection_failure_callback(OnConnectionFailureImpl);
    return CreateTSFN(env, args[0], "OnConnectionFailure", CallJS_InstanceOnly, &g_tsfnConnectionFailure);
}

static napi_value SetOnPreConnect(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    harmonyos_set_pre_connect_callback(OnPreConnectImpl);
    return CreateTSFN(env, args[0], "OnPreConnect", CallJS_InstanceOnly, &g_tsfnPreConnect);
}

static napi_value SetOnDisconnecting(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    harmonyos_set_disconnecting_callback(OnDisconnectingImpl);
    return CreateTSFN(env, args[0], "OnDisconnecting", CallJS_InstanceOnly, &g_tsfnDisconnecting);
}

static napi_value SetOnDisconnected(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    harmonyos_set_disconnected_callback(OnDisconnectedImpl);
    return CreateTSFN(env, args[0], "OnDisconnected", CallJS_InstanceOnly, &g_tsfnDisconnected);
}

static napi_value SetOnSettingsChanged(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    harmonyos_set_settings_changed_callback(OnSettingsChangedImpl);
    return CreateTSFN(env, args[0], "OnSettingsChanged", CallJS_ResizeOrSettings, &g_tsfnSettingsChanged);
}

static napi_value SetOnGraphicsUpdate(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    harmonyos_set_graphics_update_callback(OnGraphicsUpdateImpl);
    return CreateTSFN(env, args[0], "OnGraphicsUpdate", CallJS_GraphicsUpdate, &g_tsfnGraphicsUpdate);
}

static napi_value SetOnGraphicsResize(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    harmonyos_set_graphics_resize_callback(OnGraphicsResizeImpl);
    return CreateTSFN(env, args[0], "OnGraphicsResize", CallJS_ResizeOrSettings, &g_tsfnGraphicsResize);
}

static napi_value SetOnCursorTypeChanged(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    
    harmonyos_set_cursor_type_changed_callback(OnCursorTypeChangedImpl);
    return CreateTSFN(env, args[0], "OnCursorTypeChanged", CallJS_CursorType, &g_tsfnCursorTypeChanged);
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
        
        // Background mode & audio priority
        { "freerdpEnterBackgroundMode", nullptr, FreerdpEnterBackgroundMode, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "freerdpExitBackgroundMode", nullptr, FreerdpExitBackgroundMode, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "freerdpConfigureAudio", nullptr, FreerdpConfigureAudio, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "freerdpSetAutoReconnect", nullptr, FreerdpSetAutoReconnect, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "freerdpGetConnectionHealth", nullptr, FreerdpGetConnectionHealth, nullptr, nullptr, nullptr, napi_default, nullptr },
        
        // Screen refresh
        { "freerdpRequestRefresh", nullptr, FreerdpRequestRefresh, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "freerdpRequestRefreshRect", nullptr, FreerdpRequestRefreshRect, nullptr, nullptr, nullptr, napi_default, nullptr },
        
        // Connection stability
        { "freerdpIsInBackgroundMode", nullptr, FreerdpIsInBackgroundMode, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "freerdpSendKeepalive", nullptr, FreerdpSendKeepalive, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "freerdpGetIdleTime", nullptr, FreerdpGetIdleTime, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "freerdpCheckConnectionStatus", nullptr, FreerdpCheckConnectionStatus, nullptr, nullptr, nullptr, napi_default, nullptr },
        
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
    
    /*
     * CRITICAL FIX for OpenSSL SIGABRT crash:
     * Set environment variables BEFORE any OpenSSL function is called.
     * This prevents OpenSSL from trying to load modules from hardcoded build paths.
     * 
     * The library was built with paths like:
     *   /home/runner/work/freerdp-harmonyos/freerdp-harmonyos/install/openssl/lib/ossl-modules
     * which don't exist in the HarmonyOS sandbox, causing dlopen failures and SIGABRT.
     */
    setenv("HOME", "/data/storage/el2/base/files", 1);
    setenv("OPENSSL_CONF", "/dev/null", 1);  // Point to non-existent/empty config
    unsetenv("OPENSSL_MODULES");
    unsetenv("OPENSSL_ENGINES");
    unsetenv("OPENSSL_DIR");
    
    LOGI("FreeRDP HarmonyOS N-API module initialized (OpenSSL env configured)");
    return exports;
}

NAPI_MODULE(freerdp_harmonyos, Init)
