/*
 * HarmonyOS FreeRDP Native Implementation
 * 
 * Copyright 2026 FreeRDP HarmonyOS Port
 * Based on Android FreeRDP implementation
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */

#include "harmonyos_freerdp.h"
#include "freerdp_client_compat.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <locale.h>

#ifdef OHOS_PLATFORM
#include <hilog/log.h>
#include <time.h>
#include <winpr/sysinfo.h>
#ifdef LOG_TAG
#undef LOG_TAG
#endif
#define LOG_TAG "FreeRDP"
#define LOGI(...) OH_LOG_INFO(LOG_APP, __VA_ARGS__)
#define LOGW(...) OH_LOG_WARN(LOG_APP, __VA_ARGS__)
#define LOGE(...) OH_LOG_ERROR(LOG_APP, __VA_ARGS__)
#define LOGD(...) OH_LOG_DEBUG(LOG_APP, __VA_ARGS__)

/* OHOS/musl 兼容: GetTickCount64 替代实现 */
#if !defined(_WIN32)
static inline UINT64 GetTickCount64_compat(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) == 0) {
        return (UINT64)ts.tv_sec * 1000ULL + (UINT64)ts.tv_nsec / 1000000ULL;
    }
    return 0;
}
#ifndef GetTickCount64
#define GetTickCount64 GetTickCount64_compat
#endif
#endif

#else
#define LOGI(...) printf(__VA_ARGS__)
#define LOGW(...) printf(__VA_ARGS__)
#define LOGE(...) printf(__VA_ARGS__)
#define LOGD(...) printf(__VA_ARGS__)
#endif

#define TAG "FreeRDP.HarmonyOS"

/* Global callback pointers */
static OnConnectionSuccessCallback g_onConnectionSuccess = nullptr;
static OnConnectionFailureCallback g_onConnectionFailure = nullptr;
static OnPreConnectCallback g_onPreConnect = nullptr;
static OnDisconnectingCallback g_onDisconnecting = nullptr;
static OnDisconnectedCallback g_onDisconnected = nullptr;
static OnSettingsChangedCallback g_onSettingsChanged = nullptr;
static OnGraphicsUpdateCallback g_onGraphicsUpdate = nullptr;
static OnGraphicsResizeCallback g_onGraphicsResize = nullptr;
static OnRemoteClipboardChangedCallback g_onRemoteClipboardChanged = nullptr;
static OnCursorTypeChangedCallback g_onCursorTypeChanged = nullptr;
static OnAuthenticateCallback g_onAuthenticate = nullptr;
static OnVerifyCertificateCallback g_onVerifyCertificate = nullptr;

/* Callback registration implementations */
void harmonyos_set_connection_success_callback(OnConnectionSuccessCallback callback) {
    g_onConnectionSuccess = callback;
}

void harmonyos_set_connection_failure_callback(OnConnectionFailureCallback callback) {
    g_onConnectionFailure = callback;
}

void harmonyos_set_pre_connect_callback(OnPreConnectCallback callback) {
    g_onPreConnect = callback;
}

void harmonyos_set_disconnecting_callback(OnDisconnectingCallback callback) {
    g_onDisconnecting = callback;
}

void harmonyos_set_disconnected_callback(OnDisconnectedCallback callback) {
    g_onDisconnected = callback;
}

void harmonyos_set_settings_changed_callback(OnSettingsChangedCallback callback) {
    g_onSettingsChanged = callback;
}

void harmonyos_set_graphics_update_callback(OnGraphicsUpdateCallback callback) {
    g_onGraphicsUpdate = callback;
}

void harmonyos_set_graphics_resize_callback(OnGraphicsResizeCallback callback) {
    g_onGraphicsResize = callback;
}

void harmonyos_set_remote_clipboard_changed_callback(OnRemoteClipboardChangedCallback callback) {
    g_onRemoteClipboardChanged = callback;
}

void harmonyos_set_cursor_type_changed_callback(OnCursorTypeChangedCallback callback) {
    g_onCursorTypeChanged = callback;
}

void harmonyos_set_authenticate_callback(OnAuthenticateCallback callback) {
    g_onAuthenticate = callback;
}

void harmonyos_set_verify_certificate_callback(OnVerifyCertificateCallback callback) {
    g_onVerifyCertificate = callback;
}

/* Identify cursor type based on pointer properties */
static int identify_cursor_type(rdpPointer* pointer) {
    if (!pointer)
        return CURSOR_TYPE_UNKNOWN;
    
    UINT32 width = pointer->width;
    UINT32 height = pointer->height;
    UINT32 xPos = pointer->xPos;
    UINT32 yPos = pointer->yPos;
    
    if (width == 32 && height == 32 && xPos < 5 && yPos < 5)
        return CURSOR_TYPE_DEFAULT;
    
    if (width == 32 && height == 32 && xPos >= 10 && xPos <= 16 && yPos >= 5 && yPos <= 10)
        return CURSOR_TYPE_HAND;
    
    if (width <= 12 && height >= 16 && xPos <= 6)
        return CURSOR_TYPE_IBEAM;
    
    if (width <= 20 && height >= 24 && xPos >= width/2 - 3 && xPos <= width/2 + 3) {
        if (height > width * 1.2)
            return CURSOR_TYPE_SIZE_NS;
    }
    
    if (height <= 20 && width >= 24 && yPos >= height/2 - 3 && yPos <= height/2 + 3) {
        if (width > height * 1.2)
            return CURSOR_TYPE_SIZE_WE;
    }
    
    if (width >= 24 && height >= 24 && 
        xPos >= width/2 - 4 && xPos <= width/2 + 4 &&
        yPos >= height/2 - 4 && yPos <= height/2 + 4)
        return CURSOR_TYPE_CROSS;
    
    if (width == 32 && height == 32)
        return CURSOR_TYPE_WAIT;
    
    return CURSOR_TYPE_UNKNOWN;
}

/* Channel event handlers */
static void harmonyos_OnChannelConnectedEventHandler(void* context, const ChannelConnectedEventArgs* e) {
    harmonyosContext* afc;

    if (!context || !e) {
        LOGE("(context=%p, EventArgs=%p)", context, (void*)e);
        return;
    }

    afc = (harmonyosContext*)context;
    // settings = afc->common.context.settings; // Unused

    if (strcmp(e->name, CLIPRDR_SVC_CHANNEL_NAME) == 0) {
        // TODO: Initialize clipboard
        LOGI("Clipboard channel connected");
    } else {
        freerdp_client_OnChannelConnectedEventHandler(context, e);
    }
}

static void harmonyos_OnChannelDisconnectedEventHandler(void* context, const ChannelDisconnectedEventArgs* e) {
    harmonyosContext* afc;

    if (!context || !e) {
        LOGE("(context=%p, EventArgs=%p)", context, (void*)e);
        return;
    }

    afc = (harmonyosContext*)context;
    // settings = afc->common.context.settings; // Unused

    if (strcmp(e->name, CLIPRDR_SVC_CHANNEL_NAME) == 0) {
        // TODO: Uninitialize clipboard
        LOGI("Clipboard channel disconnected");
    } else {
        freerdp_client_OnChannelDisconnectedEventHandler(context, e);
    }
}

/* Paint handlers */
static BOOL harmonyos_begin_paint(rdpContext* context) {
    (void)context;
    return TRUE;
}

static BOOL harmonyos_end_paint(rdpContext* context) {
    HGDI_WND hwnd;
    int ninvalid;
    rdpGdi* gdi;
    HGDI_RGN cinvalid;
    int x1, y1, x2, y2;
    harmonyosContext* ctx = (harmonyosContext*)context;
    rdpSettings* settings;

    if (!ctx || !context->instance)
        return FALSE;

    settings = context->settings;
    if (!settings)
        return FALSE;

    gdi = context->gdi;
    if (!gdi || !gdi->primary || !gdi->primary->hdc)
        return FALSE;

    hwnd = ctx->common.context.gdi->primary->hdc->hwnd;
    if (!hwnd)
        return FALSE;

    ninvalid = hwnd->ninvalid;
    if (ninvalid < 1)
        return TRUE;

    cinvalid = hwnd->cinvalid;
    if (!cinvalid)
        return FALSE;

    x1 = cinvalid[0].x;
    y1 = cinvalid[0].y;
    x2 = cinvalid[0].x + cinvalid[0].w;
    y2 = cinvalid[0].y + cinvalid[0].h;

    for (int i = 0; i < ninvalid; i++) {
        x1 = MIN(x1, cinvalid[i].x);
        y1 = MIN(y1, cinvalid[i].y);
        x2 = MAX(x2, cinvalid[i].x + cinvalid[i].w);
        y2 = MAX(y2, cinvalid[i].y + cinvalid[i].h);
    }

    if (g_onGraphicsUpdate) {
        g_onGraphicsUpdate((int64_t)(uintptr_t)context->instance, x1, y1, x2 - x1, y2 - y1);
    }

    hwnd->invalid->null = TRUE;
    hwnd->ninvalid = 0;
    return TRUE;
}

static BOOL harmonyos_desktop_resize(rdpContext* context) {
    /* 安全检查 - 不使用 WINPR_ASSERT 避免 abort */
    if (!context || !context->settings || !context->instance) {
        LOGE("harmonyos_desktop_resize: invalid context");
        return FALSE;
    }

    if (g_onGraphicsResize) {
        g_onGraphicsResize((int64_t)(uintptr_t)context->instance,
            freerdp_settings_get_uint32(context->settings, FreeRDP_DesktopWidth),
            freerdp_settings_get_uint32(context->settings, FreeRDP_DesktopHeight),
            freerdp_settings_get_uint32(context->settings, FreeRDP_ColorDepth));
    }
    return TRUE;
}

/* Pre-connect callback */
static BOOL harmonyos_pre_connect(freerdp* instance) {
    int rc;
    rdpSettings* settings;
    rdpContext* context;

    LOGI("harmonyos_pre_connect: ENTER");

    /* 安全检查 - 不使用 WINPR_ASSERT 避免 abort */
    if (!instance) {
        LOGE("harmonyos_pre_connect: instance is NULL");
        return FALSE;
    }
    
    context = instance->context;
    if (!context) {
        LOGE("harmonyos_pre_connect: context is NULL");
        return FALSE;
    }

    settings = context->settings;
    if (!settings) {
        LOGE("harmonyos_pre_connect: settings is NULL");
        return FALSE;
    }

    LOGI("harmonyos_pre_connect: Settings validated, proceeding...");

    /* 
     * 注意：移除了对 settings 字符串的手动提取和打印。
     * 在某些 FreeRDP 版本中，如果在 pre_connect 阶段 settings 尚未完全同步，
     * 访问这些字段可能导致不稳定的行为甚至崩溃。
     */

    rc = PubSub_SubscribeChannelConnected(context->pubSub,
                                          harmonyos_OnChannelConnectedEventHandler);
    if (rc != CHANNEL_RC_OK) {
        LOGE("Could not subscribe to connect event handler [%{public}08X]", rc);
        /* 不直接返回 FALSE，尝试继续 */
    } else {
        LOGI("harmonyos_pre_connect: ChannelConnected subscribed");
    }

    rc = PubSub_SubscribeChannelDisconnected(context->pubSub,
                                             harmonyos_OnChannelDisconnectedEventHandler);
    if (rc != CHANNEL_RC_OK) {
        LOGE("Could not subscribe to disconnect event handler [%{public}08X]", rc);
    } else {
        LOGI("harmonyos_pre_connect: ChannelDisconnected subscribed");
    }

    if (g_onPreConnect) {
        g_onPreConnect((int64_t)(uintptr_t)instance);
    }
    
    LOGI("harmonyos_pre_connect: returning TRUE");
    return TRUE;
}

/* Pointer handlers */
static BOOL harmonyos_Pointer_New(rdpContext* context, rdpPointer* pointer) {
    /* 安全检查 */
    if (!context || !pointer || !context->gdi)
        return FALSE;
    return TRUE;
}

static void harmonyos_Pointer_Free(rdpContext* context, rdpPointer* pointer) {
    WINPR_UNUSED(context);
    WINPR_UNUSED(pointer);
}

static BOOL harmonyos_Pointer_Set(rdpContext* context, rdpPointer* pointer) {
    /* 安全检查 */
    if (!context || !pointer)
        return FALSE;

    int cursorType = identify_cursor_type(pointer);
    
    freerdp* instance = context->instance;
    if (instance && g_onCursorTypeChanged) {
        g_onCursorTypeChanged((int64_t)(uintptr_t)instance, cursorType);
    }

    return TRUE;
}

static BOOL harmonyos_Pointer_SetPosition(rdpContext* context, UINT32 x, UINT32 y) {
    if (!context)
        return FALSE;
    LOGD("Pointer SetPosition: x=%u, y=%u", x, y);
    return TRUE;
}

static BOOL harmonyos_Pointer_SetNull(rdpContext* context) {
    if (!context)
        return FALSE;
    LOGD("Pointer_SetNull");
    
    freerdp* instance = context->instance;
    if (instance && g_onCursorTypeChanged) {
        g_onCursorTypeChanged((int64_t)(uintptr_t)instance, CURSOR_TYPE_UNKNOWN);
    }
    return TRUE;
}

static BOOL harmonyos_Pointer_SetDefault(rdpContext* context) {
    if (!context)
        return FALSE;
    LOGD("Pointer_SetDefault");
    
    freerdp* instance = context->instance;
    if (instance && g_onCursorTypeChanged) {
        g_onCursorTypeChanged((int64_t)(uintptr_t)instance, CURSOR_TYPE_DEFAULT);
    }
    return TRUE;
}

static BOOL harmonyos_register_pointer(rdpGraphics* graphics) {
    rdpPointer pointer;
    memset(&pointer, 0, sizeof(rdpPointer));

    if (!graphics)
        return FALSE;

    pointer.size = sizeof(pointer);
    pointer.New = harmonyos_Pointer_New;
    pointer.Free = harmonyos_Pointer_Free;
    pointer.Set = harmonyos_Pointer_Set;
    pointer.SetNull = harmonyos_Pointer_SetNull;
    pointer.SetDefault = harmonyos_Pointer_SetDefault;
    pointer.SetPosition = harmonyos_Pointer_SetPosition;
    graphics_register_pointer(graphics, &pointer);
    return TRUE;
}

/* Post-connect callback */
static BOOL harmonyos_post_connect(freerdp* instance) {
    rdpSettings* settings;
    rdpUpdate* update;

    LOGI("harmonyos_post_connect: ENTER");

    /* 安全检查 - 不使用 WINPR_ASSERT 避免 abort */
    if (!instance) {
        LOGE("harmonyos_post_connect: instance is NULL");
        return FALSE;
    }
    if (!instance->context) {
        LOGE("harmonyos_post_connect: context is NULL");
        return FALSE;
    }

    update = instance->context->update;
    if (!update) {
        LOGE("harmonyos_post_connect: update is NULL");
        return FALSE;
    }

    settings = instance->context->settings;
    if (!settings) {
        LOGE("harmonyos_post_connect: settings is NULL");
        return FALSE;
    }

    LOGI("harmonyos_post_connect: Calling gdi_init...");
    if (!gdi_init(instance, PIXEL_FORMAT_RGBX32)) {
        LOGE("harmonyos_post_connect: gdi_init failed");
        return FALSE;
    }
    LOGI("harmonyos_post_connect: gdi_init succeeded");

    if (!harmonyos_register_pointer(instance->context->graphics)) {
        LOGE("harmonyos_post_connect: register_pointer failed");
        return FALSE;
    }
    LOGI("harmonyos_post_connect: register_pointer succeeded");

    update->BeginPaint = harmonyos_begin_paint;
    update->EndPaint = harmonyos_end_paint;
    update->DesktopResize = harmonyos_desktop_resize;

    if (g_onSettingsChanged) {
        g_onSettingsChanged((int64_t)(uintptr_t)instance,
            freerdp_settings_get_uint32(settings, FreeRDP_DesktopWidth),
            freerdp_settings_get_uint32(settings, FreeRDP_DesktopHeight),
            freerdp_settings_get_uint32(settings, FreeRDP_ColorDepth));
    }

    if (g_onConnectionSuccess) {
        g_onConnectionSuccess((int64_t)(uintptr_t)instance);
    }
    
    LOGI("harmonyos_post_connect: returning TRUE");
    return TRUE;
}

/* Post-disconnect callback */
static void harmonyos_post_disconnect(freerdp* instance) {
    LOGI("harmonyos_post_disconnect: ENTER");
    
    /* 尝试获取更多断开原因的信息 */
    if (instance && instance->context) {
        UINT32 errorCode = freerdp_get_last_error(instance->context);
        const char* errorString = freerdp_get_last_error_string(errorCode);
        LOGI("harmonyos_post_disconnect: ErrorCode=0x%{public}08X Msg=%{public}s", 
             errorCode, errorString ? errorString : "NULL");
    }
    
    if (g_onDisconnecting) {
        g_onDisconnecting((int64_t)(uintptr_t)instance);
    }
    gdi_free(instance);
    
    LOGI("harmonyos_post_disconnect: EXIT");
}

/* Authentication callback */
static BOOL harmonyos_authenticate(freerdp* instance, char** username, char** password, char** domain) {
    if (g_onAuthenticate) {
        return g_onAuthenticate((int64_t)(uintptr_t)instance, username, domain, password);
    }
    return FALSE;
}

static BOOL harmonyos_gw_authenticate(freerdp* instance, char** username, char** password, char** domain) {
    return harmonyos_authenticate(instance, username, password, domain);
}

/* Certificate verification callback */
static DWORD harmonyos_verify_certificate_ex(freerdp* instance, const char* host, UINT16 port,
                                             const char* common_name, const char* subject,
                                             const char* issuer, const char* fingerprint, DWORD flags) {
    LOGD("Certificate details [%s:%u]:", host, port);
    LOGD("\tSubject: %s", subject);
    LOGD("\tIssuer: %s", issuer);
    LOGD("\tThumbprint: %s", fingerprint);

    if (g_onVerifyCertificate) {
        return g_onVerifyCertificate((int64_t)(uintptr_t)instance, host, port,
                                     common_name, subject, issuer, fingerprint, flags);
    }
    
    // Default: accept certificate
    return 1;
}

static DWORD harmonyos_verify_changed_certificate_ex(freerdp* instance, const char* host, UINT16 port,
                                                     const char* common_name, const char* subject,
                                                     const char* issuer, const char* new_fingerprint,
                                                     const char* old_subject, const char* old_issuer,
                                                     const char* old_fingerprint, DWORD flags) {
    (void)old_subject;
    (void)old_issuer;
    (void)old_fingerprint;
    return harmonyos_verify_certificate_ex(instance, host, port, common_name, subject,
                                           issuer, new_fingerprint, flags);
}

/* Background mode state tracking */
static volatile BOOL g_isInBackgroundMode = FALSE;
static volatile UINT64 g_lastNetworkActivityTime = 0;
static const DWORD BACKGROUND_KEEPALIVE_INTERVAL_MS = 30000; /* 30 seconds */
static const DWORD NETWORK_TIMEOUT_MS = 60000; /* 60 seconds without activity = timeout */

/* Update network activity timestamp */
static void update_network_activity(void) {
    g_lastNetworkActivityTime = GetTickCount64();
}

/* Check if network is still alive based on activity */
static BOOL is_network_alive(void) {
    if (g_lastNetworkActivityTime == 0)
        return TRUE; /* Not initialized yet */
    
    UINT64 elapsed = GetTickCount64() - g_lastNetworkActivityTime;
    return elapsed < NETWORK_TIMEOUT_MS;
}

/* Main run loop with background mode support */
static int harmonyos_freerdp_run(freerdp* instance) {
    DWORD count;
    DWORD status = WAIT_FAILED;
    HANDLE handles[MAXIMUM_WAIT_OBJECTS];
    HANDLE inputEvent = NULL;
    rdpContext* context = instance->context;
    DWORD waitTimeout;
    DWORD consecutiveTimeouts = 0;
    const DWORD MAX_CONSECUTIVE_TIMEOUTS = 10;

    inputEvent = harmonyos_get_handle(instance);
    update_network_activity(); /* Initialize activity timestamp */

    while (!freerdp_shall_disconnect_context(instance->context)) {
        DWORD tmp;
        count = 0;

        handles[count++] = inputEvent;

        tmp = freerdp_get_event_handles(context, &handles[count], 64 - count);
        if (tmp == 0) {
            LOGE("freerdp_get_event_handles failed");
            break;
        }

        count += tmp;
        
        /* In background mode, use timeout to periodically check connection health */
        if (g_isInBackgroundMode) {
            waitTimeout = BACKGROUND_KEEPALIVE_INTERVAL_MS;
        } else {
            waitTimeout = INFINITE;
        }
        
        status = WaitForMultipleObjects(count, handles, FALSE, waitTimeout);

        if (status == WAIT_FAILED) {
            LOGE("WaitForMultipleObjects failed with %u [%08X]", status, (unsigned int)GetLastError());
            break;
        }
        
        if (status == WAIT_TIMEOUT) {
            /* Timeout in background mode - check connection health */
            if (g_isInBackgroundMode) {
                consecutiveTimeouts++;
                LOGD("Background keepalive check (%d/%d)", consecutiveTimeouts, MAX_CONSECUTIVE_TIMEOUTS);
                
                /* Check if network is still alive */
                if (!is_network_alive()) {
                    LOGW("Network timeout detected in background mode");
                    break;
                }
                
                /* Too many consecutive timeouts might indicate connection issue */
                if (consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
                    LOGW("Too many consecutive timeouts, checking connection...");
                    /* Reset counter but don't break - let FreeRDP handle it */
                    consecutiveTimeouts = 0;
                }
                
                continue; /* Continue waiting */
            }
        } else {
            /* Reset timeout counter on activity */
            consecutiveTimeouts = 0;
            update_network_activity();
        }

        if (!freerdp_check_event_handles(context)) {
            LOGE("Failed to check FreeRDP file descriptor");
            status = GetLastError();
            break;
        }

        if (freerdp_shall_disconnect_context(instance->context))
            break;

        if (harmonyos_check_handle(instance) != TRUE) {
            LOGE("Failed to check harmonyos file descriptor");
            status = GetLastError();
            break;
        }
    }

    LOGI("Prepare shutdown...");
    return status;
}

/* Auto-reconnect callback for logging */
static void internal_on_reconnecting(void* ctx, int attempt, int maxAttempts) {
    LOGI("Auto-reconnect attempt %d/%d", attempt, maxAttempts);
}

/* Thread function with auto-reconnect support */
static DWORD WINAPI harmonyos_thread_func(LPVOID param) {
    DWORD status = ERROR_BAD_ARGUMENTS;
    freerdp* instance = (freerdp*)param;
    rdpContext* context = NULL;
    BOOL shouldReconnect = FALSE;
    BOOL connectResult = FALSE;
    int reconnectAttempts = 0;
    const int MAX_RECONNECT_ATTEMPTS = 5;
    UINT64 connectStartTime = 0;
    UINT64 connectEndTime = 0;
    UINT64 connectDuration = 0;
    int savedErrno = 0;
    
    LOGD("Start...");

    /* 安全检查 - 不使用 WINPR_ASSERT 避免 abort */
    if (!instance) {
        LOGE("harmonyos_thread_func: instance is NULL");
        goto fail;
    }
    if (!instance->context) {
        LOGE("harmonyos_thread_func: context is NULL");
        goto fail;
    }
    
    context = instance->context;

    if (freerdp_client_start(context) != CHANNEL_RC_OK) {
        LOGE("freerdp_client_start failed");
        goto fail;
    }

reconnect_loop:
    LOGI("Connect... (attempt %{public}d)", reconnectAttempts + 1);
    LOGI("Checking instance validity...");
    
    if (!instance) {
        LOGE("instance became NULL!");
        goto fail;
    }
    
    LOGI("instance=%{public}p", (void*)instance);
    LOGI("context=%{public}p", (void*)instance->context);
    
    if (!instance->context) {
        LOGE("context is NULL before connect!");
        goto fail;
    }
    
    if (!instance->context->settings) {
        LOGE("settings is NULL before connect!");
        goto fail;
    }
    
    LOGI("PreConnect callback=%{public}p", (void*)instance->PreConnect);
    LOGI("PostConnect callback=%{public}p", (void*)instance->PostConnect);
    
    LOGI("Calling freerdp_connect NOW...");

    /* 清除 errno 以便准确捕获连接错误 */
    errno = 0;
    
    /* 记录连接开始时间 */
    connectStartTime = GetTickCount64();
    
    connectResult = freerdp_connect(instance);
    
    /* 立即保存 errno，避免被后续调用覆盖 */
    savedErrno = errno;
    
    /* 计算连接耗时 */
    connectEndTime = GetTickCount64();
    connectDuration = connectEndTime - connectStartTime;
    
    LOGI("freerdp_connect returned: %{public}s (took %{public}llu ms)", 
         connectResult ? "TRUE" : "FALSE", (unsigned long long)connectDuration);
    
    /* 如果失败，打印 errno */
    if (!connectResult) {
        LOGE("errno=%{public}d (%{public}s)", savedErrno, 
             savedErrno ? strerror(savedErrno) : "No error");
        
        /* 如果连接时间很短（<100ms），说明可能是配置或初始化错误 */
        if (connectDuration < 100) {
            LOGE("Connection failed very quickly - likely config/init error, not network");
        }
    }
    
    if (!connectResult) {
        status = GetLastError();
        
        /* 获取详细错误信息 */
        UINT32 errorCode = freerdp_get_last_error(context);
        const char* errorString = freerdp_get_last_error_string(errorCode);
        const char* errorCategory = freerdp_get_last_error_category(errorCode);
        
        /* 使用 {public} 标记避免隐私过滤 */
        LOGE("Connection failed! GetLastError=0x%{public}08X", status);
        LOGE("FreeRDP ErrorCode=0x%{public}08X", errorCode);
        LOGE("FreeRDP Category=%{public}s", errorCategory ? errorCategory : "Unknown");
        LOGE("FreeRDP Message=%{public}s", errorString ? errorString : "No error message");
        
        /* 
         * 错误类型分类（供上层 UI 使用）:
         * 
         * 1. 网络/主机错误 (ERRCONNECT_CONNECT_*):
         *    - ERRCONNECT_CONNECT_FAILED (0x00020006) - 无法连接到主机/端口
         *    - ERRCONNECT_DNS_ERROR (0x00020005) - DNS 解析失败
         *    - ERRCONNECT_CONNECT_TRANSPORT_FAILED (0x0002000C) - 传输层失败
         *    - GetLastError = WSAETIMEDOUT / WSAECONNREFUSED 等
         * 
         * 2. 认证错误 (ERRCONNECT_AUTHENTICATION_*):
         *    - ERRCONNECT_AUTHENTICATION_FAILED (0x00020009) - 用户名/密码错误
         *    - ERRCONNECT_LOGON_FAILURE (0x0002000F) - 登录失败
         *    - ERRCONNECT_ACCOUNT_* - 账户问题（锁定、过期等）
         * 
         * 3. 安全协议错误 (ERRCONNECT_SECURITY_*):
         *    - ERRCONNECT_SECURITY_NEGO_CONNECT_FAILED (0x0002000B) - 安全协商失败
         *    - ERRCONNECT_TLS_CONNECT_FAILED (0x00020008) - TLS 连接失败
         *    - ERRCONNECT_MCS_CONNECT_INITIAL_ERROR - MCS 协议错误
         * 
         * 4. 其他错误:
         *    - ErrorCode = 0 但连接失败 - 可能是内部错误或配置问题
         */
        
        /* 分类错误类型 */
        const char* errorType = "UNKNOWN";
        BOOL shouldRetry = TRUE;
        
        /* 检查 FreeRDP 错误码范围 */
        UINT32 errorClass = (errorCode >> 16) & 0xFF;
        UINT32 errorType_id = errorCode & 0xFFFF;
        
        if (errorCode == 0) {
            /* 特殊情况：错误码为0但连接失败 */
            errorType = "INTERNAL_ERROR";
            LOGE("Error Type: %{public}s - Internal error or premature disconnect", errorType);
            /* 内部错误不重试 */
            shouldRetry = FALSE;
        } else if (errorType_id >= 0x0005 && errorType_id <= 0x0007) {
            /* 网络连接错误 */
            errorType = "NETWORK_ERROR";
            LOGE("Error Type: %{public}s - Check host address and port", errorType);
        } else if (errorType_id == 0x0009 || errorType_id == 0x000F || 
                   (errorType_id >= 0x0010 && errorType_id <= 0x001F)) {
            /* 认证错误 */
            errorType = "AUTH_ERROR";
            LOGE("Error Type: %{public}s - Check username and password", errorType);
            /* 认证错误不重试 */
            shouldRetry = FALSE;
        } else if (errorType_id == 0x0008 || errorType_id == 0x000B || errorType_id == 0x000D) {
            /* 安全协议错误 */
            errorType = "SECURITY_ERROR";
            LOGE("Error Type: %{public}s - Check security settings (RDP/TLS/NLA)", errorType);
        } else {
            errorType = "CONNECTION_ERROR";
            LOGE("Error Type: %{public}s - General connection failure", errorType);
        }
        
        /* Check if we should try to reconnect */
        if (shouldRetry && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++;
            LOGI("Will retry connection in %{public}d seconds... (%{public}d/%{public}d)", 
                 reconnectAttempts * 2, reconnectAttempts, MAX_RECONNECT_ATTEMPTS);
            
            /* Exponential backoff */
            Sleep(reconnectAttempts * 2000);
            
            if (!freerdp_shall_disconnect_context(context)) {
                goto reconnect_loop;
            }
        } else if (!shouldRetry) {
            LOGI("Not retrying due to error type: %{public}s", errorType);
        }
    } else {
        /* Connection successful */
        reconnectAttempts = 0;
        freerdp_client_set_connected(context, TRUE);
        
        status = harmonyos_freerdp_run(instance);
        LOGD("Run loop exited with status: %08X", status);
        
        freerdp_client_set_connected(context, FALSE);

        /* Check if disconnection was unexpected */
        shouldReconnect = (status != CHANNEL_RC_OK) && 
                          !freerdp_shall_disconnect_context(context) &&
                          (reconnectAttempts < MAX_RECONNECT_ATTEMPTS);

        if (!freerdp_disconnect(instance)) {
            LOGE("Disconnect failed");
        }

        if (shouldReconnect) {
            reconnectAttempts++;
            LOGI("Connection lost, attempting reconnect... (%d/%d)", 
                 reconnectAttempts, MAX_RECONNECT_ATTEMPTS);
            
            /* Wait before reconnecting */
            Sleep(reconnectAttempts * 2000);
            
            if (!freerdp_shall_disconnect_context(context)) {
                goto reconnect_loop;
            }
        }
    }

    LOGD("Stop...");

    if (freerdp_client_stop(context) != CHANNEL_RC_OK)
        goto fail;

fail:
    LOGD("Session ended with %08X", status);

    if (status == CHANNEL_RC_OK || reconnectAttempts == 0) {
        if (g_onDisconnected) {
            g_onDisconnected((int64_t)(uintptr_t)instance);
        }
    } else {
        if (g_onConnectionFailure) {
            g_onConnectionFailure((int64_t)(uintptr_t)instance);
        }
    }

    LOGD("Quit.");
    ExitThread(status);
    return status;
}

/* Client new/free */
static BOOL harmonyos_client_new(freerdp* instance, rdpContext* context) {
    /* 安全检查 - 不使用 WINPR_ASSERT 避免 abort */
    if (!instance) {
        LOGE("harmonyos_client_new: instance is NULL");
        return FALSE;
    }
    if (!context) {
        LOGE("harmonyos_client_new: context is NULL");
        return FALSE;
    }

    if (!harmonyos_event_queue_init(instance)) {
        LOGE("harmonyos_client_new: event_queue_init failed");
        return FALSE;
    }

    instance->PreConnect = harmonyos_pre_connect;
    instance->PostConnect = harmonyos_post_connect;
    instance->PostDisconnect = harmonyos_post_disconnect;
    instance->Authenticate = harmonyos_authenticate;
    instance->GatewayAuthenticate = harmonyos_gw_authenticate;
    instance->VerifyCertificateEx = harmonyos_verify_certificate_ex;
    instance->VerifyChangedCertificateEx = harmonyos_verify_changed_certificate_ex;
    instance->LogonErrorInfo = NULL;
    return TRUE;
}

static void harmonyos_client_free(freerdp* instance, rdpContext* context) {
    if (!context)
        return;

    harmonyos_event_queue_uninit(instance);
}

static int RdpClientEntry(RDP_CLIENT_ENTRY_POINTS* pEntryPoints) {
    /* 安全检查 - 不使用 WINPR_ASSERT 避免 abort */
    if (!pEntryPoints) {
        LOGE("RdpClientEntry: pEntryPoints is NULL");
        return -1;
    }

    ZeroMemory(pEntryPoints, sizeof(RDP_CLIENT_ENTRY_POINTS));

    pEntryPoints->Version = RDP_CLIENT_INTERFACE_VERSION;
    pEntryPoints->Size = sizeof(RDP_CLIENT_ENTRY_POINTS_V1);
    pEntryPoints->GlobalInit = NULL;
    pEntryPoints->GlobalUninit = NULL;
    pEntryPoints->ContextSize = sizeof(harmonyosContext);
    pEntryPoints->ClientNew = harmonyos_client_new;
    pEntryPoints->ClientFree = harmonyos_client_free;
    pEntryPoints->ClientStart = NULL;
    pEntryPoints->ClientStop = NULL;
    return 0;
}

/* ==================== Public API Implementation ==================== */

/* 全局 SSL 初始化标志 */
static BOOL g_sslInitialized = FALSE;

int64_t freerdp_harmonyos_new(void) {
    RDP_CLIENT_ENTRY_POINTS clientEntryPoints;
    rdpContext* ctx;

    setlocale(LC_ALL, "");
    
    /* 初始化 OpenSSL（只需要做一次） */
    if (!g_sslInitialized) {
        LOGI("freerdp_harmonyos_new: Initializing SSL...");
        
        /* 
         * 针对 HarmonyOS 的重要修复：
         * 1. 设置 HOME 环境变量到应用沙箱目录，防止 FreeRDP 尝试在沙箱外创建配置目录。
         * 2. 显式清除 OpenSSL 模块路径环境变量，防止其尝试访问编译机路径 (/home/runner/work/...)
         */
        setenv("HOME", "/data/storage/el2/base/files", 1);
        unsetenv("OPENSSL_MODULES");
        unsetenv("OPENSSL_CONF");
        unsetenv("OPENSSL_ENGINES");
        
        /* 使用 winpr 的 SSL 初始化函数 */
        if (winpr_InitializeSSL(WINPR_SSL_INIT_DEFAULT)) {
            LOGI("freerdp_harmonyos_new: SSL initialized successfully");
        } else {
            LOGW("freerdp_harmonyos_new: SSL initialization returned FALSE (proceeding anyway)");
        }
        g_sslInitialized = TRUE;
    }

    RdpClientEntry(&clientEntryPoints);
    ctx = freerdp_client_context_new(&clientEntryPoints);

    if (!ctx)
        return 0;

    return (int64_t)(uintptr_t)ctx->instance;
}

void freerdp_harmonyos_free(int64_t instance) {
    freerdp* inst = (freerdp*)(uintptr_t)instance;

    if (inst)
        freerdp_client_context_free(inst->context);
}

bool freerdp_harmonyos_parse_arguments(int64_t instance, const char** args, int argc) {
    freerdp* inst = (freerdp*)(uintptr_t)instance;
    DWORD status;

    LOGI("parse_arguments: ENTER instance=0x%llx argc=%d", (unsigned long long)instance, argc);

    if (!inst) {
        LOGE("parse_arguments: inst is NULL");
        return false;
    }
    
    LOGI("parse_arguments: inst=%p", (void*)inst);
    
    if (!inst->context) {
        LOGE("parse_arguments: context is NULL");
        return false;
    }
    
    LOGI("parse_arguments: context=%p", (void*)inst->context);
    
    if (!inst->context->settings) {
        LOGE("parse_arguments: Settings is NULL");
        return false;
    }
    
    LOGI("parse_arguments: settings=%p - All checks passed!", (void*)inst->context->settings);

    char** argv = (char**)malloc(argc * sizeof(char*));
    if (!argv) {
        LOGE("parse_arguments: Failed to allocate argv");
        return false;
    }
    
    LOGI("parse_arguments: argv allocated, copying args...");

    for (int i = 0; i < argc; i++) {
        argv[i] = strdup(args[i]);
        if (argv[i]) {
            /* 不打印密码 */
            if (strncmp(argv[i], "/p:", 3) == 0) {
                LOGI("parse_arguments: argv[%d]=/p:****", i);
            } else {
                LOGI("parse_arguments: argv[%d]=%s", i, argv[i]);
            }
        } else {
            LOGE("parse_arguments: strdup failed for arg %d", i);
        }
    }

    /* 
     * 设置默认配置以增强稳定性
     */
    freerdp_settings_set_bool(inst->context->settings, FreeRDP_RemoteConsoleAudio, FALSE);
    freerdp_settings_set_bool(inst->context->settings, FreeRDP_AudioPlayback, TRUE);
    
    /* 
     * 针对连接 0x0002000D 错误的修复：
     * 1. 默认启用多种安全协议协商 (RDP + TLS + NLA)，以提高服务器兼容性。
     * 2. 显式启用各种安全层。
     * 3. 忽略证书验证错误，防止因 UI 缺失导致的验证失败或崩溃。
     */
    freerdp_settings_set_uint32(inst->context->settings, FreeRDP_RequestedProtocols, 
                                0x00000001 | 0x00000002 | 0x00000004);
    freerdp_settings_set_bool(inst->context->settings, FreeRDP_TlsSecurity, TRUE);
    freerdp_settings_set_bool(inst->context->settings, FreeRDP_NlaSecurity, TRUE);
    freerdp_settings_set_bool(inst->context->settings, FreeRDP_RdpSecurity, TRUE);
    freerdp_settings_set_bool(inst->context->settings, FreeRDP_IgnoreCertificate, TRUE);
    
    /* 
     * 关键修复：设置插件加载路径为当前目录，并禁用外部插件自动搜索。
     * 这可以配合 BUILTIN_CHANNELS=ON 彻底解决 absolute path 加载失败导致的崩溃。
     */
    freerdp_settings_set_string(inst->context->settings, FreeRDP_ConfigPath, ".");
    
    /* 
     * 关键修复：禁用各种可能尝试加载绝对路径或引起移动端不稳定的选项
     */
    freerdp_settings_set_bool(inst->context->settings, FreeRDP_SupportMonitorLayoutPdu, FALSE);
    freerdp_settings_set_bool(inst->context->settings, FreeRDP_SupportGraphicsPipeline, TRUE);
    
    LOGI("parse_arguments: Calling freerdp_client_settings_parse_command_line...");
    status = freerdp_client_settings_parse_command_line(inst->context->settings, argc, argv, FALSE);
    LOGI("parse_arguments: freerdp_client_settings_parse_command_line returned %u", (unsigned int)status);

    for (int i = 0; i < argc; i++)
        free(argv[i]);
    free(argv);

    return (status == 0);
}

bool freerdp_harmonyos_connect(int64_t instance) {
    freerdp* inst = (freerdp*)(uintptr_t)instance;
    harmonyosContext* ctx;

    if (!inst || !inst->context) {
        LOGE("Invalid instance");
        return false;
    }

    ctx = (harmonyosContext*)inst->context;

    if (!(ctx->thread = CreateThread(NULL, 0, harmonyos_thread_func, inst, 0, NULL))) {
        return false;
    }

    return true;
}

bool freerdp_harmonyos_disconnect(int64_t instance) {
    freerdp* inst = (freerdp*)(uintptr_t)instance;
    HARMONYOS_EVENT* event;

    if (!inst || !inst->context) {
        LOGE("Invalid instance");
        return false;
    }

    event = (HARMONYOS_EVENT*)harmonyos_event_disconnect_new();

    if (!event)
        return false;

    if (!harmonyos_push_event(inst, event)) {
        harmonyos_event_free(event);
        return false;
    }

    if (!freerdp_abort_connect_context(inst->context))
        return false;

    return true;
}

bool freerdp_harmonyos_update_graphics(int64_t instance, uint8_t* buffer, int x, int y, int width, int height) {
    freerdp* inst = (freerdp*)(uintptr_t)instance;
    rdpGdi* gdi;

    if (!inst || !inst->context)
        return false;

    gdi = inst->context->gdi;
    if (!gdi || !gdi->primary_buffer)
        return false;

    // Copy from GDI buffer to output buffer
    UINT32 DstFormat = PIXEL_FORMAT_RGBX32;
    return freerdp_image_copy(buffer, DstFormat, width * 4, 0, 0, width, height,
                              gdi->primary_buffer, gdi->dstFormat, gdi->stride, x, y,
                              &gdi->palette, FREERDP_FLIP_NONE);
}

bool freerdp_harmonyos_send_cursor_event(int64_t instance, int x, int y, int flags) {
    freerdp* inst = (freerdp*)(uintptr_t)instance;
    HARMONYOS_EVENT* event;

    if (!inst || !inst->context) {
        LOGE("Invalid instance");
        return false;
    }

    event = (HARMONYOS_EVENT*)harmonyos_event_cursor_new(flags, x, y);
    if (!event)
        return false;

    if (!harmonyos_push_event(inst, event)) {
        harmonyos_event_free(event);
        return false;
    }

    return true;
}

bool freerdp_harmonyos_send_key_event(int64_t instance, int keycode, bool down) {
    freerdp* inst = (freerdp*)(uintptr_t)instance;
    HARMONYOS_EVENT* event;
    DWORD scancode;

    if (!inst)
        return false;

    scancode = GetVirtualScanCodeFromVirtualKeyCode(keycode, 4);
    int flags = down ? KBD_FLAGS_DOWN : KBD_FLAGS_RELEASE;
    flags |= (scancode & KBDEXT) ? KBD_FLAGS_EXTENDED : 0;

    event = (HARMONYOS_EVENT*)harmonyos_event_key_new(flags, scancode & 0xFF);
    if (!event)
        return false;

    if (!harmonyos_push_event(inst, event)) {
        harmonyos_event_free(event);
        return false;
    }

    return true;
}

bool freerdp_harmonyos_send_unicodekey_event(int64_t instance, int keycode, bool down) {
    freerdp* inst = (freerdp*)(uintptr_t)instance;
    HARMONYOS_EVENT* event;

    if (!inst)
        return false;

    UINT16 flags = down ? 0 : KBD_FLAGS_RELEASE;
    event = (HARMONYOS_EVENT*)harmonyos_event_unicodekey_new(flags, keycode);

    if (!event)
        return false;

    if (!harmonyos_push_event(inst, event)) {
        harmonyos_event_free(event);
        return false;
    }

    return true;
}

bool freerdp_harmonyos_set_tcp_keepalive(int64_t instance, bool enabled, int delay, int interval, int retries) {
    freerdp* inst = (freerdp*)(uintptr_t)instance;

    if (!inst || !inst->context) {
        LOGE("freerdp_set_tcp_keepalive: Invalid instance");
        return false;
    }

    rdpSettings* settings = inst->context->settings;
    if (!settings) {
        LOGE("freerdp_set_tcp_keepalive: Invalid settings");
        return false;
    }

    if (!freerdp_settings_set_bool(settings, FreeRDP_TcpKeepAlive, enabled ? TRUE : FALSE)) {
        LOGW("Failed to set TcpKeepAlive=%d (possibly unsupported by this build)", enabled);
        /* 不返回 false，因为这不一定是致命错误 */
    }

    if (enabled) {
        freerdp_settings_set_uint32(settings, FreeRDP_TcpKeepAliveDelay, (UINT32)delay);
        freerdp_settings_set_uint32(settings, FreeRDP_TcpKeepAliveInterval, (UINT32)interval);
        freerdp_settings_set_uint32(settings, FreeRDP_TcpKeepAliveRetries, (UINT32)retries);
        LOGI("TCP Keepalive configured: delay=%ds, interval=%ds, retries=%d", delay, interval, retries);
    }

    return true;
}

bool freerdp_harmonyos_send_synchronize_event(int64_t instance, int flags) {
    freerdp* inst = (freerdp*)(uintptr_t)instance;

    if (!inst || !inst->context) {
        LOGE("freerdp_send_synchronize_event: Invalid instance");
        return false;
    }

    rdpInput* input = inst->context->input;
    if (!input) {
        LOGE("freerdp_send_synchronize_event: Invalid input");
        return false;
    }

    return freerdp_input_send_synchronize_event(input, (UINT32)flags);
}

bool freerdp_harmonyos_send_clipboard_data(int64_t instance, const char* data) {
    freerdp* inst = (freerdp*)(uintptr_t)instance;
    HARMONYOS_EVENT* event;

    if (!inst)
        return false;

    size_t length = data ? strlen(data) : 0;
    event = (HARMONYOS_EVENT*)harmonyos_event_clipboard_new(data, length);

    if (!event)
        return false;

    if (!harmonyos_push_event(inst, event)) {
        harmonyos_event_free(event);
        return false;
    }

    return true;
}

int freerdp_harmonyos_set_client_decoding(int64_t instance, bool enable) {
    freerdp* inst = (freerdp*)(uintptr_t)instance;

    if (!inst || !inst->context)
        return -1;

    rdpContext* context = inst->context;
    
    /* 
     * 关键安全检查：如果连接尚未完全建立或已断开，不应调用 update pdus。
     * FreeRDP 3.x 使用 freerdp_shall_disconnect_context 检查状态。
     */
    if (freerdp_shall_disconnect_context(context)) {
        LOGW("set_client_decoding: session not connected or disconnecting, skipping PDU");
        return -8;
    }

    rdpSettings* settings = context->settings;
    if (!settings)
        return -2;

    rdpUpdate* update = context->update;
    if (!update)
        return -3;

    /* 
     * FreeRDP 3.x 核心库中可能没有 FreeRDP_DeactivateClientDecoding 这个自定义键。
     * 我们改用标准键 FreeRDP_SuppressOutput。
     */
    BOOL suppress = enable ? FALSE : TRUE;
    freerdp_settings_set_bool(settings, FreeRDP_SuppressOutput, suppress);

    BOOL allowDisplayUpdates = enable ? TRUE : FALSE;
    
    RECTANGLE_16 rect = { 0, 0, 0, 0 };
    rect.left = 0;
    rect.top = 0;
    rect.right = (UINT16)freerdp_settings_get_uint32(settings, FreeRDP_DesktopWidth);
    rect.bottom = (UINT16)freerdp_settings_get_uint32(settings, FreeRDP_DesktopHeight);

    if (update->SuppressOutput) {
        if (!update->SuppressOutput(context, allowDisplayUpdates, &rect)) {
            LOGE("SuppressOutput PDU failed");
            return -6;
        }
        
        LOGI("Client decoding %s, SuppressOutput sent (allowDisplayUpdates=%d)",
             enable ? "enabled" : "disabled", allowDisplayUpdates);
        return 0;
    } else {
        LOGW("SuppressOutput callback not available");
        return -7;
    }
}

const char* freerdp_harmonyos_get_last_error_string(int64_t instance) {
    freerdp* inst = (freerdp*)(uintptr_t)instance;

    if (!inst || !inst->context)
        return "";

    return freerdp_get_last_error_string(freerdp_get_last_error(inst->context));
}

const char* freerdp_harmonyos_get_version(void) {
    return freerdp_get_version_string();
}

bool freerdp_harmonyos_has_h264(void) {
    H264_CONTEXT* ctx = h264_context_new(FALSE);
    if (!ctx)
        return false;
    h264_context_free(ctx);
    return true;
}

bool freerdp_harmonyos_is_connected(int64_t instance) {
    freerdp* inst = (freerdp*)(uintptr_t)instance;
    
    if (!inst || !inst->context)
        return false;
    
    /* FreeRDP 3.x doesn't have freerdp_is_connected, check via shall_disconnect */
    return !freerdp_shall_disconnect_context(inst->context);
}

/* ==================== Background Mode & Audio Priority ==================== */

bool freerdp_harmonyos_enter_background_mode(int64_t instance) {
    freerdp* inst = (freerdp*)(uintptr_t)instance;
    
    if (!inst || !inst->context) {
        LOGE("enter_background_mode: Invalid instance");
        return false;
    }
    
    rdpContext* context = inst->context;
    rdpSettings* settings = context->settings;
    rdpUpdate* update = context->update;
    
    if (!settings || !update) {
        LOGE("enter_background_mode: Invalid settings or update");
        return false;
    }
    
    LOGI("Entering background mode - audio only");
    
    /* Set background mode flag for run loop */
    g_isInBackgroundMode = TRUE;
    
    /* Disable graphics decoding to save CPU/bandwidth */
    freerdp_settings_set_bool(settings, FreeRDP_DeactivateClientDecoding, TRUE);
    
    /* Send SuppressOutput PDU to tell server to stop sending graphics */
    RECTANGLE_16 rect = { 0, 0, 0, 0 };
    rect.left = 0;
    rect.top = 0;
    rect.right = (UINT16)freerdp_settings_get_uint32(settings, FreeRDP_DesktopWidth);
    rect.bottom = (UINT16)freerdp_settings_get_uint32(settings, FreeRDP_DesktopHeight);
    
    if (update->SuppressOutput) {
        /* FALSE = suppress display updates (only audio continues) */
        if (!update->SuppressOutput(context, FALSE, &rect)) {
            LOGW("SuppressOutput PDU failed, but continuing");
        }
    }
    
    LOGI("Background mode active - graphics suppressed, audio continues, keepalive enabled");
    return true;
}

bool freerdp_harmonyos_exit_background_mode(int64_t instance) {
    freerdp* inst = (freerdp*)(uintptr_t)instance;
    
    if (!inst || !inst->context) {
        LOGE("exit_background_mode: Invalid instance");
        return false;
    }
    
    rdpContext* context = inst->context;
    rdpSettings* settings = context->settings;
    rdpUpdate* update = context->update;
    
    if (!settings || !update) {
        LOGE("exit_background_mode: Invalid settings or update");
        return false;
    }
    
    LOGI("Exiting background mode - resuming graphics with full refresh");
    
    /* Clear background mode flag FIRST */
    g_isInBackgroundMode = FALSE;
    
    /* Re-enable graphics decoding */
    freerdp_settings_set_bool(settings, FreeRDP_DeactivateClientDecoding, FALSE);
    
    /* Get full screen dimensions */
    UINT32 width = freerdp_settings_get_uint32(settings, FreeRDP_DesktopWidth);
    UINT32 height = freerdp_settings_get_uint32(settings, FreeRDP_DesktopHeight);
    
    RECTANGLE_16 rect = { 0, 0, 0, 0 };
    rect.left = 0;
    rect.top = 0;
    rect.right = (UINT16)width;
    rect.bottom = (UINT16)height;
    
    /* Step 1: Send SuppressOutput PDU to tell server to resume graphics */
    if (update->SuppressOutput) {
        /* TRUE = allow display updates */
        if (!update->SuppressOutput(context, TRUE, &rect)) {
            LOGW("SuppressOutput resume PDU failed");
        }
    }
    
    /* Step 2: Request full screen refresh using RefreshRect PDU */
    if (update->RefreshRect) {
        /* RefreshRect tells the server to re-send the specified area */
        if (!update->RefreshRect(context, 1, &rect)) {
            LOGW("RefreshRect PDU failed");
        } else {
            LOGI("RefreshRect sent for full screen (%ux%u)", width, height);
        }
    } else {
        LOGW("RefreshRect callback not available");
    }
    
    /* Step 3: Mark entire GDI surface as invalid to force redraw */
    rdpGdi* gdi = context->gdi;
    if (gdi && gdi->primary && gdi->primary->hdc && gdi->primary->hdc->hwnd) {
        HGDI_WND hwnd = gdi->primary->hdc->hwnd;
        
        /* Create a full-screen invalid region */
        GDI_RGN invalidRegion;
        invalidRegion.x = 0;
        invalidRegion.y = 0;
        invalidRegion.w = (INT32)width;
        invalidRegion.h = (INT32)height;
        
        /* Add to invalid regions - this will trigger redraw on next update cycle */
        if (hwnd->invalid) {
            hwnd->invalid->null = FALSE;
            hwnd->invalid->x = 0;
            hwnd->invalid->y = 0;
            hwnd->invalid->w = (INT32)width;
            hwnd->invalid->h = (INT32)height;
        }
        
        /* Also expand cinvalid array if needed */
        if (hwnd->cinvalid && hwnd->count > 0) {
            hwnd->cinvalid[0] = invalidRegion;
            hwnd->ninvalid = 1;
        }
        
        LOGI("GDI surface marked as invalid for full redraw");
    }
    
    /* Step 4: Notify application that graphics update is coming */
    if (g_onGraphicsUpdate) {
        /* Trigger an immediate update callback for the full screen */
        g_onGraphicsUpdate((int64_t)(uintptr_t)inst, 0, 0, (int)width, (int)height);
        LOGI("Graphics update callback triggered");
    }
    
    LOGI("Background mode exited - full screen refresh requested");
    return true;
}

bool freerdp_harmonyos_configure_audio(int64_t instance, bool playback, bool capture, int quality) {
    freerdp* inst = (freerdp*)(uintptr_t)instance;
    
    if (!inst || !inst->context) {
        LOGE("configure_audio: Invalid instance");
        return false;
    }
    
    rdpSettings* settings = inst->context->settings;
    if (!settings) {
        LOGE("configure_audio: Invalid settings");
        return false;
    }
    
    /* Enable audio playback */
    if (playback) {
        freerdp_settings_set_bool(settings, FreeRDP_AudioPlayback, TRUE);
        LOGI("Audio playback enabled");
    }
    
    /* Enable audio capture (microphone) */
    if (capture) {
        freerdp_settings_set_bool(settings, FreeRDP_AudioCapture, TRUE);
        LOGI("Audio capture enabled");
    }
    
    /* Set quality based on connection type */
    switch (quality) {
        case 0: /* Dynamic */
            freerdp_settings_set_uint32(settings, FreeRDP_ConnectionType, CONNECTION_TYPE_AUTODETECT);
            LOGI("Audio quality: Dynamic");
            break;
        case 1: /* Medium */
            freerdp_settings_set_uint32(settings, FreeRDP_ConnectionType, CONNECTION_TYPE_BROADBAND_LOW);
            LOGI("Audio quality: Medium");
            break;
        case 2: /* High */
            freerdp_settings_set_uint32(settings, FreeRDP_ConnectionType, CONNECTION_TYPE_LAN);
            LOGI("Audio quality: High");
            break;
        default:
            LOGW("Unknown audio quality mode: %d", quality);
            break;
    }
    
    return true;
}

bool freerdp_harmonyos_set_auto_reconnect(int64_t instance, bool enabled, int maxRetries, int delayMs) {
    freerdp* inst = (freerdp*)(uintptr_t)instance;
    
    if (!inst || !inst->context) {
        LOGE("set_auto_reconnect: Invalid instance");
        return false;
    }
    
    rdpSettings* settings = inst->context->settings;
    if (!settings) {
        LOGE("set_auto_reconnect: Invalid settings");
        return false;
    }
    
    /* Configure FreeRDP auto-reconnect settings */
    freerdp_settings_set_bool(settings, FreeRDP_AutoReconnectionEnabled, enabled);
    
    if (enabled && maxRetries > 0) {
        freerdp_settings_set_uint32(settings, FreeRDP_AutoReconnectMaxRetries, (UINT32)maxRetries);
        LOGI("Auto-reconnect enabled: maxRetries=%d, delayMs=%d", maxRetries, delayMs);
    } else {
        LOGI("Auto-reconnect disabled");
    }
    
    return true;
}

/* Get connection health status */
int freerdp_harmonyos_get_connection_health(int64_t instance) {
    freerdp* inst = (freerdp*)(uintptr_t)instance;
    
    if (!inst || !inst->context)
        return -1; /* Invalid */
    
    if (freerdp_shall_disconnect_context(inst->context))
        return 0; /* Disconnected */
    
    /* Check if we can get event handles - indicates healthy connection */
    HANDLE handles[8];
    DWORD count = freerdp_get_event_handles(inst->context, handles, 8);
    
    if (count == 0)
        return 1; /* Connected but degraded */
    
    return 2; /* Healthy */
}

/* Force immediate full screen refresh - use after unlock/foreground */
bool freerdp_harmonyos_request_refresh(int64_t instance) {
    freerdp* inst = (freerdp*)(uintptr_t)instance;
    
    if (!inst || !inst->context) {
        LOGE("request_refresh: Invalid instance");
        return false;
    }
    
    rdpContext* context = inst->context;
    rdpSettings* settings = context->settings;
    rdpUpdate* update = context->update;
    
    if (!settings || !update) {
        LOGE("request_refresh: Invalid settings or update");
        return false;
    }
    
    /* Get screen dimensions */
    UINT32 width = freerdp_settings_get_uint32(settings, FreeRDP_DesktopWidth);
    UINT32 height = freerdp_settings_get_uint32(settings, FreeRDP_DesktopHeight);
    
    LOGI("Requesting full screen refresh (%ux%u)", width, height);
    
    RECTANGLE_16 rect = { 0, 0, 0, 0 };
    rect.left = 0;
    rect.top = 0;
    rect.right = (UINT16)width;
    rect.bottom = (UINT16)height;
    
    bool success = true;
    
    /* Method 1: RefreshRect PDU - asks server to re-send the area */
    if (update->RefreshRect) {
        if (!update->RefreshRect(context, 1, &rect)) {
            LOGW("RefreshRect PDU failed");
            success = false;
        } else {
            LOGI("RefreshRect PDU sent");
        }
    }
    
    /* Method 2: Mark GDI as invalid */
    rdpGdi* gdi = context->gdi;
    if (gdi && gdi->primary && gdi->primary->hdc && gdi->primary->hdc->hwnd) {
        HGDI_WND hwnd = gdi->primary->hdc->hwnd;
        
        if (hwnd->invalid) {
            hwnd->invalid->null = FALSE;
            hwnd->invalid->x = 0;
            hwnd->invalid->y = 0;
            hwnd->invalid->w = (INT32)width;
            hwnd->invalid->h = (INT32)height;
            LOGI("GDI invalid region set");
        }
    }
    
    /* Method 3: Trigger immediate callback with current buffer */
    if (g_onGraphicsUpdate) {
        g_onGraphicsUpdate((int64_t)(uintptr_t)inst, 0, 0, (int)width, (int)height);
        LOGI("Graphics update callback triggered");
    }
    
    return success;
}

/* Request partial screen refresh for specific area */
bool freerdp_harmonyos_request_refresh_rect(int64_t instance, int x, int y, int width, int height) {
    freerdp* inst = (freerdp*)(uintptr_t)instance;
    
    if (!inst || !inst->context) {
        LOGE("request_refresh_rect: Invalid instance");
        return false;
    }
    
    rdpContext* context = inst->context;
    rdpUpdate* update = context->update;
    
    if (!update) {
        LOGE("request_refresh_rect: Invalid update");
        return false;
    }
    
    RECTANGLE_16 rect = { 0, 0, 0, 0 };
    rect.left = (UINT16)x;
    rect.top = (UINT16)y;
    rect.right = (UINT16)(x + width);
    rect.bottom = (UINT16)(y + height);
    
    if (update->RefreshRect) {
        if (!update->RefreshRect(context, 1, &rect)) {
            LOGW("RefreshRect PDU failed for rect (%d,%d,%d,%d)", x, y, width, height);
            return false;
        }
        LOGI("RefreshRect sent for (%d,%d,%d,%d)", x, y, width, height);
    }
    
    return true;
}

/* Get the current frame buffer for immediate display */
bool freerdp_harmonyos_get_frame_buffer(int64_t instance, uint8_t** buffer, 
                                         int* width, int* height, int* stride) {
    freerdp* inst = (freerdp*)(uintptr_t)instance;
    
    if (!inst || !inst->context) {
        LOGE("get_frame_buffer: Invalid instance");
        return false;
    }
    
    rdpContext* context = inst->context;
    rdpGdi* gdi = context->gdi;
    
    if (!gdi || !gdi->primary) {
        LOGE("get_frame_buffer: GDI not initialized");
        return false;
    }
    
    /* 使用 gdi 直接获取尺寸，避免 HGDI_BITMAP/rdpBitmap 类型不匹配 */
    if (buffer) *buffer = gdi->primary_buffer;
    if (width) *width = (int)gdi->width;
    if (height) *height = (int)gdi->height;
    if (stride) *stride = (int)gdi->stride;
    
    return true;
}

/* Check if currently in background mode */
bool freerdp_harmonyos_is_in_background_mode(int64_t instance) {
    WINPR_UNUSED(instance);
    return g_isInBackgroundMode ? true : false;
}

/* Send a keepalive/heartbeat to maintain connection in background */
bool freerdp_harmonyos_send_keepalive(int64_t instance) {
    freerdp* inst = (freerdp*)(uintptr_t)instance;
    
    if (!inst || !inst->context) {
        LOGE("send_keepalive: Invalid instance");
        return false;
    }
    
    rdpContext* context = inst->context;
    rdpInput* input = context->input;
    
    if (!input) {
        LOGE("send_keepalive: Invalid input");
        return false;
    }
    
    /* Send a synchronize event as heartbeat - this doesn't affect the session */
    if (!freerdp_input_send_synchronize_event(input, 0)) {
        LOGW("Keepalive synchronize event failed");
        return false;
    }
    
    update_network_activity();
    LOGD("Keepalive sent");
    return true;
}

/* Get time since last network activity in milliseconds */
uint64_t freerdp_harmonyos_get_idle_time(int64_t instance) {
    WINPR_UNUSED(instance);
    
    if (g_lastNetworkActivityTime == 0)
        return 0;
    
    return GetTickCount64() - g_lastNetworkActivityTime;
}

/* Force check connection health and return detailed status */
int freerdp_harmonyos_check_connection_status(int64_t instance) {
    freerdp* inst = (freerdp*)(uintptr_t)instance;
    
    if (!inst || !inst->context)
        return -1; /* Invalid instance */
    
    rdpContext* context = inst->context;
    
    /* Check if disconnect was requested */
    if (freerdp_shall_disconnect_context(context))
        return 0; /* Disconnecting */
    
    /* Check network activity */
    if (!is_network_alive())
        return 1; /* Network timeout */
    
    /* Check if we can get event handles */
    HANDLE handles[8];
    DWORD count = freerdp_get_event_handles(context, handles, 8);
    
    if (count == 0)
        return 2; /* Event handles failed */
    
    /* Check if in background mode */
    if (g_isInBackgroundMode)
        return 10; /* Connected, background mode */
    
    return 100; /* Connected, foreground mode */
}
