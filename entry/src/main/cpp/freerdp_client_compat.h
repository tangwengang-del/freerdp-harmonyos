/*
 * FreeRDP Client Compatibility Layer for HarmonyOS
 * Header file
 * 
 * 当使用官方 freerdp-client 库时 (WITH_CLIENT_COMMON=ON)，
 * 核心函数由官方库提供，此文件只提供扩展功能。
 * 
 * 当不使用官方库时 (WITH_CLIENT_COMMON=OFF)，
 * 此文件提供简化的兼容实现。
 */

#ifndef FREERDP_CLIENT_COMPAT_H
#define FREERDP_CLIENT_COMPAT_H

#include <freerdp/freerdp.h>
#include <freerdp/settings.h>
#include <freerdp/client.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * 核心客户端函数声明
 * 
 * 这些函数由官方 freerdp-client 库提供（当 WITH_CLIENT_COMMON=ON 时）
 * 我们只声明它们，不重新实现
 * 
 * 注意：freerdp/client.h 已经声明了这些函数，这里不需要重复声明
 */

/* 以下函数已在 freerdp/client.h 中声明：
 * - freerdp_client_context_new
 * - freerdp_client_context_free
 * - freerdp_client_start
 * - freerdp_client_stop
 * - freerdp_client_settings_parse_command_line
 * - freerdp_client_OnChannelConnectedEventHandler
 * - freerdp_client_OnChannelDisconnectedEventHandler
 */

/*
 * Auto-Reconnect Support
 */

/* Initialize reconnect support with settings */
BOOL freerdp_client_reconnect_init(rdpContext* context, UINT32 maxRetries, UINT32 delayMs);

/* Cleanup reconnect resources */
void freerdp_client_reconnect_cleanup(rdpContext* context);

/* Perform auto-reconnection with exponential backoff */
BOOL freerdp_client_auto_reconnect(rdpContext* context);

/* Check if reconnection is in progress */
BOOL freerdp_client_is_reconnecting(rdpContext* context);

/* Get current reconnect attempt count */
UINT32 freerdp_client_get_reconnect_count(rdpContext* context);

/* Stop ongoing reconnection attempts */
void freerdp_client_stop_reconnect(rdpContext* context);

/* Enable/disable auto-reconnect feature */
void freerdp_client_set_reconnect_enabled(rdpContext* context, BOOL enabled);

/*
 * Audio (RDPSND) Support
 */

/* Configure audio playback and capture */
BOOL freerdp_client_configure_audio(rdpSettings* settings, BOOL playback, BOOL capture);

/* Set audio quality mode (0=dynamic, 1=medium, 2=high) */
BOOL freerdp_client_set_audio_quality(rdpSettings* settings, int qualityMode);

/*
 * Connection Stability & Monitoring
 */

/* Callback function types */
typedef void (*pOnConnectionLostCallback)(void* context, int errorCode);
typedef void (*pOnReconnectingCallback)(void* context, int attempt, int maxAttempts);
typedef void (*pOnReconnectedCallback)(void* context);

/* Initialize connection monitoring with heartbeat */
BOOL freerdp_client_init_connection_monitor(rdpContext* context, 
                                             UINT32 heartbeatIntervalMs,
                                             UINT32 connectionTimeoutMs);

/* Set connection event callbacks */
void freerdp_client_set_connection_callbacks(rdpContext* context,
                                              pOnConnectionLostCallback onLost,
                                              pOnReconnectingCallback onReconnecting,
                                              pOnReconnectedCallback onReconnected,
                                              void* callbackContext);

/* Update connection state */
void freerdp_client_set_connected(rdpContext* context, BOOL connected);

/* Check if connection is alive */
BOOL freerdp_client_check_connection_alive(rdpContext* context);

/* Update activity timestamp (call on any data received) */
void freerdp_client_update_activity(rdpContext* context);

/* Handle connection lost event (triggers auto-reconnect if enabled) */
BOOL freerdp_client_on_connection_lost(rdpContext* context, int errorCode);

/*
 * Background/Lockscreen Mode - Audio Only
 */

/* Enter background mode (only audio, no graphics) - for lockscreen/minimize */
BOOL freerdp_client_enter_background_mode(rdpContext* context);

/* Exit background mode (resume graphics) - for unlock/restore */
BOOL freerdp_client_exit_background_mode(rdpContext* context);

/* Check if in background mode */
BOOL freerdp_client_is_in_background(rdpContext* context);

/* Check if audio only mode is active */
BOOL freerdp_client_is_audio_only(rdpContext* context);

#ifdef __cplusplus
}
#endif

#endif /* FREERDP_CLIENT_COMPAT_H */
