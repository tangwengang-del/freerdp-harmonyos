/*
 * FreeRDP Client Extensions for HarmonyOS
 * 
 * 此文件提供 FreeRDP 客户端的扩展功能：
 * - 自动重连支持
 * - 音频配置
 * - 连接监控
 * - 后台模式支持
 * 
 * 注意：核心客户端功能由官方 freerdp-client 库提供
 * (通过 WITH_CLIENT_COMMON=ON 编译)
 */

#include <freerdp/freerdp.h>
#include <freerdp/settings.h>
#include <freerdp/channels/channels.h>
#include <freerdp/client/channels.h>
#include <freerdp/client/cmdline.h>
#include <freerdp/client.h>

#include <winpr/assert.h>
#include <winpr/crt.h>
#include <winpr/synch.h>
#include <winpr/file.h>
#include <winpr/path.h>
#include <winpr/thread.h>
#include <winpr/wlog.h>

#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <time.h>

/* HarmonyOS 日志 */
#include <hilog/log.h>
#undef LOG_DOMAIN
#undef LOG_TAG
#define LOG_DOMAIN 0x0000
#define LOG_TAG "FreeRDP.Compat"
#define COMPAT_LOGD(...) OH_LOG_DEBUG(LOG_APP, __VA_ARGS__)
#define COMPAT_LOGI(...) OH_LOG_INFO(LOG_APP, __VA_ARGS__)
#define COMPAT_LOGW(...) OH_LOG_WARN(LOG_APP, __VA_ARGS__)
#define COMPAT_LOGE(...) OH_LOG_ERROR(LOG_APP, __VA_ARGS__)

/* OHOS/musl 兼容: GetTickCount64 替代实现 */
#if !defined(_WIN32)
#include <time.h>
static inline UINT64 GetTickCount64_compat(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) == 0) {
        return (UINT64)ts.tv_sec * 1000ULL + (UINT64)ts.tv_nsec / 1000000ULL;
    }
    return 0;
}
#undef GetTickCount64
#define GetTickCount64() GetTickCount64_compat()
#endif

/* 
 * FreeRDP 3.x 不导出 freerdp_is_connected，使用替代实现
 * 检查连接状态：如果不需要断开则视为已连接
 */
static inline BOOL is_freerdp_connected(freerdp* instance) {
    if (!instance || !instance->context)
        return FALSE;
    return !freerdp_shall_disconnect_context(instance->context);
}

/* Callback types for connection events */
typedef void (*pOnConnectionLostCallback)(void* context, int errorCode);
typedef void (*pOnReconnectingCallback)(void* context, int attempt, int maxAttempts);
typedef void (*pOnReconnectedCallback)(void* context);

/* 
 * FreeRDP 3.x 中 rdpContext 没有 custom 字段
 * 使用全局数组存储自定义数据
 */
#define MAX_CONTEXT_SLOTS 16
typedef struct {
    rdpContext* context;
    void* customData;
} ContextDataSlot;

static ContextDataSlot g_contextData[MAX_CONTEXT_SLOTS] = { {NULL, NULL} };

static void* get_context_custom(rdpContext* context) {
    for (int i = 0; i < MAX_CONTEXT_SLOTS; i++) {
        if (g_contextData[i].context == context) {
            return g_contextData[i].customData;
        }
    }
    return NULL;
}

static BOOL set_context_custom(rdpContext* context, void* data) {
    /* 先查找已存在的槽位 */
    for (int i = 0; i < MAX_CONTEXT_SLOTS; i++) {
        if (g_contextData[i].context == context) {
            g_contextData[i].customData = data;
            return TRUE;
        }
    }
    /* 查找空槽位 */
    for (int i = 0; i < MAX_CONTEXT_SLOTS; i++) {
        if (g_contextData[i].context == NULL) {
            g_contextData[i].context = context;
            g_contextData[i].customData = data;
            return TRUE;
        }
    }
    return FALSE;
}

static void clear_context_custom(rdpContext* context) {
    for (int i = 0; i < MAX_CONTEXT_SLOTS; i++) {
        if (g_contextData[i].context == context) {
            g_contextData[i].context = NULL;
            g_contextData[i].customData = NULL;
            return;
        }
    }
}

/* Reconnection settings */
#define RECONNECT_MAX_RETRIES 5
#define RECONNECT_INITIAL_DELAY_MS 1000
#define RECONNECT_MAX_DELAY_MS 30000

/* Extended context for reconnection support */
typedef struct {
    BOOL reconnectEnabled;
    UINT32 reconnectMaxRetries;
    UINT32 reconnectDelayMs;
    UINT32 reconnectCount;
    BOOL isReconnecting;
    HANDLE reconnectThread;
    BOOL stopReconnect;
} ClientReconnectContext;

/* Get reconnect context for an rdpContext */
static ClientReconnectContext* get_reconnect_context(rdpContext* context)
{
    return (ClientReconnectContext*)get_context_custom(context);
}

/*
 * ============================================================================
 * Auto-Reconnect Support
 * ============================================================================
 */

BOOL freerdp_client_reconnect_init(rdpContext* context, UINT32 maxRetries, UINT32 delayMs)
{
    ClientReconnectContext* rctx;
    
    if (!context)
        return FALSE;
    
    rctx = (ClientReconnectContext*)calloc(1, sizeof(ClientReconnectContext));
    if (!rctx)
        return FALSE;
    
    rctx->reconnectEnabled = TRUE;
    rctx->reconnectMaxRetries = maxRetries > 0 ? maxRetries : RECONNECT_MAX_RETRIES;
    rctx->reconnectDelayMs = delayMs > 0 ? delayMs : RECONNECT_INITIAL_DELAY_MS;
    rctx->reconnectCount = 0;
    rctx->isReconnecting = FALSE;
    rctx->reconnectThread = NULL;
    rctx->stopReconnect = FALSE;
    
    if (!set_context_custom(context, rctx)) {
        free(rctx);
        return FALSE;
    }
    
    COMPAT_LOGI("Reconnect initialized: maxRetries=%u, delayMs=%u", 
             rctx->reconnectMaxRetries, rctx->reconnectDelayMs);
    return TRUE;
}

void freerdp_client_reconnect_cleanup(rdpContext* context)
{
    ClientReconnectContext* rctx;
    
    if (!context)
        return;
    
    rctx = get_reconnect_context(context);
    if (rctx) {
        rctx->stopReconnect = TRUE;
        if (rctx->reconnectThread) {
            WaitForSingleObject(rctx->reconnectThread, 5000);
            CloseHandle(rctx->reconnectThread);
        }
        clear_context_custom(context);
        free(rctx);
    }
}

BOOL freerdp_client_auto_reconnect(rdpContext* context)
{
    ClientReconnectContext* rctx;
    UINT32 delay;
    
    if (!context || !context->instance)
        return FALSE;
    
    rctx = get_reconnect_context(context);
    if (!rctx || !rctx->reconnectEnabled)
        return FALSE;
    
    if (rctx->reconnectCount >= rctx->reconnectMaxRetries) {
        COMPAT_LOGW("Max reconnect attempts (%u) reached", rctx->reconnectMaxRetries);
        return FALSE;
    }
    
    rctx->isReconnecting = TRUE;
    rctx->reconnectCount++;
    
    /* Exponential backoff */
    delay = rctx->reconnectDelayMs * (1 << (rctx->reconnectCount - 1));
    if (delay > RECONNECT_MAX_DELAY_MS)
        delay = RECONNECT_MAX_DELAY_MS;
    
    COMPAT_LOGI("Reconnect attempt %u/%u in %u ms", 
             rctx->reconnectCount, rctx->reconnectMaxRetries, delay);
    
    Sleep(delay);
    
    if (rctx->stopReconnect) {
        rctx->isReconnecting = FALSE;
        return FALSE;
    }
    
    /* Attempt reconnection using FreeRDP's built-in reconnect */
    BOOL success = freerdp_reconnect(context->instance);
    
    if (success) {
        COMPAT_LOGI("Reconnection successful!");
        rctx->reconnectCount = 0;
    }
    
    rctx->isReconnecting = FALSE;
    return success;
}

BOOL freerdp_client_is_reconnecting(rdpContext* context)
{
    ClientReconnectContext* rctx;
    
    if (!context)
        return FALSE;
    
    rctx = get_reconnect_context(context);
    return rctx ? rctx->isReconnecting : FALSE;
}

UINT32 freerdp_client_get_reconnect_count(rdpContext* context)
{
    ClientReconnectContext* rctx;
    
    if (!context)
        return 0;
    
    rctx = get_reconnect_context(context);
    return rctx ? rctx->reconnectCount : 0;
}

void freerdp_client_stop_reconnect(rdpContext* context)
{
    ClientReconnectContext* rctx;
    
    if (!context)
        return;
    
    rctx = get_reconnect_context(context);
    if (rctx) {
        rctx->stopReconnect = TRUE;
        COMPAT_LOGI("Reconnect stopped");
    }
}

void freerdp_client_set_reconnect_enabled(rdpContext* context, BOOL enabled)
{
    ClientReconnectContext* rctx;
    
    if (!context)
        return;
    
    rctx = get_reconnect_context(context);
    if (rctx) {
        rctx->reconnectEnabled = enabled;
        COMPAT_LOGI("Reconnect %s", enabled ? "enabled" : "disabled");
    }
}

/*
 * ============================================================================
 * Audio (RDPSND) Support
 * ============================================================================
 */

BOOL freerdp_client_configure_audio(rdpSettings* settings, BOOL playback, BOOL capture)
{
    if (!settings)
        return FALSE;
    
    freerdp_settings_set_bool(settings, FreeRDP_AudioPlayback, playback);
    freerdp_settings_set_bool(settings, FreeRDP_AudioCapture, capture);
    
    COMPAT_LOGI("Audio configured: playback=%d, capture=%d", playback, capture);
    return TRUE;
}

BOOL freerdp_client_set_audio_quality(rdpSettings* settings, int qualityMode)
{
    if (!settings)
        return FALSE;
    
    /* In FreeRDP 3, quality is often handled via connection type or specific channel settings */
    /* Quality modes: 0=dynamic, 1=medium, 2=high */
    
    COMPAT_LOGI("Audio quality set to: %d (FreeRDP 3 handles this via connection type)", qualityMode);
    return TRUE;
}

/*
 * ============================================================================
 * Connection Monitoring
 * ============================================================================
 */

typedef struct {
    BOOL isConnected;
    UINT64 lastActivityTime;
    UINT32 heartbeatIntervalMs;
    UINT32 connectionTimeoutMs;
    pOnConnectionLostCallback onLost;
    pOnReconnectingCallback onReconnecting;
    pOnReconnectedCallback onReconnected;
    void* callbackContext;
} ConnectionMonitorContext;

/* Use separate storage for connection monitor */
static ConnectionMonitorContext g_connMonitor[MAX_CONTEXT_SLOTS] = { {0} };

static ConnectionMonitorContext* get_conn_monitor(rdpContext* context) {
    for (int i = 0; i < MAX_CONTEXT_SLOTS; i++) {
        if (g_contextData[i].context == context) {
            return &g_connMonitor[i];
        }
    }
    return NULL;
}

BOOL freerdp_client_init_connection_monitor(rdpContext* context, 
                                             UINT32 heartbeatIntervalMs,
                                             UINT32 connectionTimeoutMs)
{
    ConnectionMonitorContext* mon;
    
    if (!context)
        return FALSE;
    
    /* Find slot */
    int slot = -1;
    for (int i = 0; i < MAX_CONTEXT_SLOTS; i++) {
        if (g_contextData[i].context == context) {
            slot = i;
            break;
        }
    }
    
    if (slot < 0) {
        /* Allocate new slot */
        for (int i = 0; i < MAX_CONTEXT_SLOTS; i++) {
            if (g_contextData[i].context == NULL) {
                g_contextData[i].context = context;
                slot = i;
                break;
            }
        }
    }
    
    if (slot < 0)
        return FALSE;
    
    mon = &g_connMonitor[slot];
    memset(mon, 0, sizeof(ConnectionMonitorContext));
    mon->heartbeatIntervalMs = heartbeatIntervalMs;
    mon->connectionTimeoutMs = connectionTimeoutMs;
    mon->lastActivityTime = GetTickCount64();
    
    COMPAT_LOGI("Connection monitor initialized: heartbeat=%u ms, timeout=%u ms",
             heartbeatIntervalMs, connectionTimeoutMs);
    return TRUE;
}

void freerdp_client_set_connection_callbacks(rdpContext* context,
                                              pOnConnectionLostCallback onLost,
                                              pOnReconnectingCallback onReconnecting,
                                              pOnReconnectedCallback onReconnected,
                                              void* callbackContext)
{
    ConnectionMonitorContext* mon;
    
    if (!context)
        return;
    
    mon = get_conn_monitor(context);
    if (mon) {
        mon->onLost = onLost;
        mon->onReconnecting = onReconnecting;
        mon->onReconnected = onReconnected;
        mon->callbackContext = callbackContext;
    }
}

void freerdp_client_set_connected(rdpContext* context, BOOL connected)
{
    ConnectionMonitorContext* mon;
    
    if (!context)
        return;
    
    mon = get_conn_monitor(context);
    if (mon) {
        mon->isConnected = connected;
        if (connected)
            mon->lastActivityTime = GetTickCount64();
    }
}

BOOL freerdp_client_check_connection_alive(rdpContext* context)
{
    ConnectionMonitorContext* mon;
    UINT64 now, elapsed;
    
    if (!context)
        return FALSE;
    
    mon = get_conn_monitor(context);
    if (!mon || !mon->isConnected)
        return FALSE;
    
    now = GetTickCount64();
    elapsed = now - mon->lastActivityTime;
    
    if (mon->connectionTimeoutMs > 0 && elapsed > mon->connectionTimeoutMs) {
        COMPAT_LOGW("Connection timeout: %llu ms since last activity", 
                 (unsigned long long)elapsed);
        return FALSE;
    }
    
    return TRUE;
}

void freerdp_client_update_activity(rdpContext* context)
{
    ConnectionMonitorContext* mon;
    
    if (!context)
        return;
    
    mon = get_conn_monitor(context);
    if (mon) {
        mon->lastActivityTime = GetTickCount64();
    }
}

BOOL freerdp_client_on_connection_lost(rdpContext* context, int errorCode)
{
    ConnectionMonitorContext* mon;
    
    if (!context)
        return FALSE;
    
    mon = get_conn_monitor(context);
    if (mon) {
        mon->isConnected = FALSE;
        
        if (mon->onLost) {
            mon->onLost(mon->callbackContext, errorCode);
        }
    }
    
    /* Try auto-reconnect */
    return freerdp_client_auto_reconnect(context);
}

/*
 * ============================================================================
 * Background/Lockscreen Mode
 * ============================================================================
 */

static BOOL g_backgroundMode[MAX_CONTEXT_SLOTS] = { FALSE };

static int get_context_slot(rdpContext* context) {
    for (int i = 0; i < MAX_CONTEXT_SLOTS; i++) {
        if (g_contextData[i].context == context) {
            return i;
        }
    }
    return -1;
}

BOOL freerdp_client_enter_background_mode(rdpContext* context)
{
    int slot;
    
    if (!context || !context->settings)
        return FALSE;
    
    slot = get_context_slot(context);
    if (slot < 0) {
        /* Allocate new slot */
        for (int i = 0; i < MAX_CONTEXT_SLOTS; i++) {
            if (g_contextData[i].context == NULL) {
                g_contextData[i].context = context;
                slot = i;
                break;
            }
        }
    }
    
    if (slot < 0)
        return FALSE;
    
    g_backgroundMode[slot] = TRUE;
    
    /* Suppress graphics output to reduce battery usage */
    freerdp_settings_set_bool(context->settings, FreeRDP_SuppressOutput, TRUE);
    
    COMPAT_LOGI("Entered background mode (audio only)");
    return TRUE;
}

BOOL freerdp_client_exit_background_mode(rdpContext* context)
{
    int slot;
    
    if (!context || !context->settings)
        return FALSE;
    
    slot = get_context_slot(context);
    if (slot < 0)
        return FALSE;
    
    g_backgroundMode[slot] = FALSE;
    
    /* Resume graphics output */
    freerdp_settings_set_bool(context->settings, FreeRDP_SuppressOutput, FALSE);
    
    COMPAT_LOGI("Exited background mode (resuming graphics)");
    return TRUE;
}

BOOL freerdp_client_is_in_background(rdpContext* context)
{
    int slot;
    
    if (!context)
        return FALSE;
    
    slot = get_context_slot(context);
    if (slot < 0)
        return FALSE;
    
    return g_backgroundMode[slot];
}

BOOL freerdp_client_is_audio_only(rdpContext* context)
{
    return freerdp_client_is_in_background(context);
}
