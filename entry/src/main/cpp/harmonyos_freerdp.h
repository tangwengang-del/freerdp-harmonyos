/*
 * HarmonyOS FreeRDP Native Header
 * 
 * Copyright 2026 FreeRDP HarmonyOS Port
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */

#ifndef HARMONYOS_FREERDP_H
#define HARMONYOS_FREERDP_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* FreeRDP includes */
#include <freerdp/freerdp.h>
#include <freerdp/client/rdpei.h>
#include <freerdp/client/rdpgfx.h>
#include <freerdp/client/cliprdr.h>
#include <freerdp/gdi/gdi.h>
#include <freerdp/gdi/gfx.h>
#include <freerdp/codec/h264.h>
#include <freerdp/channels/channels.h>
#include <freerdp/client/channels.h>
#include <freerdp/client/cmdline.h>
#include <freerdp/constants.h>
#include <freerdp/locale/keyboard.h>
#include <freerdp/primitives.h>
#include <freerdp/version.h>
#include <freerdp/settings.h>
#include <freerdp/utils/signal.h>

#include <winpr/assert.h>

/* HarmonyOS context extension */
typedef struct {
    rdpClientContext common;
    HANDLE thread;
    void* napi_env;
    void* napi_callback_ref;
} harmonyosContext;

/* Cursor type definitions */
#define CURSOR_TYPE_UNKNOWN     0
#define CURSOR_TYPE_DEFAULT     1   /* 默认箭头 */
#define CURSOR_TYPE_HAND        2   /* 手型（链接）*/
#define CURSOR_TYPE_IBEAM       3   /* I型（文本）*/
#define CURSOR_TYPE_SIZE_NS     4   /* 上下双箭头 */
#define CURSOR_TYPE_SIZE_WE     5   /* 左右双箭头 */
#define CURSOR_TYPE_SIZE_NWSE   6   /* 斜向双箭头（左上-右下）*/
#define CURSOR_TYPE_SIZE_NESW   7   /* 斜向双箭头（右上-左下）*/
#define CURSOR_TYPE_CROSS       8   /* 十字（移动）*/
#define CURSOR_TYPE_WAIT        9   /* 等待（沙漏）*/

/* Event types */
typedef enum {
    HARMONYOS_EVENT_TYPE_KEY,
    HARMONYOS_EVENT_TYPE_UNICODEKEY,
    HARMONYOS_EVENT_TYPE_CURSOR,
    HARMONYOS_EVENT_TYPE_DISCONNECT,
    HARMONYOS_EVENT_TYPE_CLIPBOARD
} HARMONYOS_EVENT_TYPE;

/* Base event structure */
typedef struct {
    HARMONYOS_EVENT_TYPE type;
} HARMONYOS_EVENT;

/* Key event */
typedef struct {
    HARMONYOS_EVENT_TYPE type;
    int flags;
    uint16_t scancode;
} HARMONYOS_EVENT_KEY;

/* Unicode key event */
typedef struct {
    HARMONYOS_EVENT_TYPE type;
    int flags;
    uint16_t character;
} HARMONYOS_EVENT_UNICODEKEY;

/* Cursor/mouse event */
typedef struct {
    HARMONYOS_EVENT_TYPE type;
    int flags;
    int x;
    int y;
} HARMONYOS_EVENT_CURSOR;

/* Disconnect event */
typedef struct {
    HARMONYOS_EVENT_TYPE type;
} HARMONYOS_EVENT_DISCONNECT;

/* Clipboard event */
typedef struct {
    HARMONYOS_EVENT_TYPE type;
    char* data;
    size_t length;
} HARMONYOS_EVENT_CLIPBOARD;

/* Event queue functions */
bool harmonyos_event_queue_init(freerdp* instance);
void harmonyos_event_queue_uninit(freerdp* instance);
bool harmonyos_push_event(freerdp* instance, HARMONYOS_EVENT* event);
HANDLE harmonyos_get_handle(freerdp* instance);
bool harmonyos_check_handle(freerdp* instance);
void harmonyos_event_free(HARMONYOS_EVENT* event);

/* Event creation functions */
HARMONYOS_EVENT_KEY* harmonyos_event_key_new(int flags, uint16_t scancode);
HARMONYOS_EVENT_UNICODEKEY* harmonyos_event_unicodekey_new(int flags, uint16_t character);
HARMONYOS_EVENT_CURSOR* harmonyos_event_cursor_new(int flags, int x, int y);
HARMONYOS_EVENT_DISCONNECT* harmonyos_event_disconnect_new(void);
HARMONYOS_EVENT_CLIPBOARD* harmonyos_event_clipboard_new(const char* data, size_t length);

/* Callback definitions for N-API */
typedef void (*OnConnectionSuccessCallback)(int64_t instance);
typedef void (*OnConnectionFailureCallback)(int64_t instance);
typedef void (*OnPreConnectCallback)(int64_t instance);
typedef void (*OnDisconnectingCallback)(int64_t instance);
typedef void (*OnDisconnectedCallback)(int64_t instance);
typedef void (*OnSettingsChangedCallback)(int64_t instance, int width, int height, int bpp);
typedef void (*OnGraphicsUpdateCallback)(int64_t instance, int x, int y, int width, int height);
typedef void (*OnGraphicsResizeCallback)(int64_t instance, int width, int height, int bpp);
typedef void (*OnRemoteClipboardChangedCallback)(int64_t instance, const char* data);
typedef void (*OnCursorTypeChangedCallback)(int64_t instance, int cursorType);
typedef bool (*OnAuthenticateCallback)(int64_t instance, char** username, char** domain, char** password);
typedef int (*OnVerifyCertificateCallback)(int64_t instance, const char* host, int port,
                                           const char* commonName, const char* subject,
                                           const char* issuer, const char* fingerprint, int64_t flags);

/* Callback registration */
void harmonyos_set_connection_success_callback(OnConnectionSuccessCallback callback);
void harmonyos_set_connection_failure_callback(OnConnectionFailureCallback callback);
void harmonyos_set_pre_connect_callback(OnPreConnectCallback callback);
void harmonyos_set_disconnecting_callback(OnDisconnectingCallback callback);
void harmonyos_set_disconnected_callback(OnDisconnectedCallback callback);
void harmonyos_set_settings_changed_callback(OnSettingsChangedCallback callback);
void harmonyos_set_graphics_update_callback(OnGraphicsUpdateCallback callback);
void harmonyos_set_graphics_resize_callback(OnGraphicsResizeCallback callback);
void harmonyos_set_remote_clipboard_changed_callback(OnRemoteClipboardChangedCallback callback);
void harmonyos_set_cursor_type_changed_callback(OnCursorTypeChangedCallback callback);
void harmonyos_set_authenticate_callback(OnAuthenticateCallback callback);
void harmonyos_set_verify_certificate_callback(OnVerifyCertificateCallback callback);

/* Core FreeRDP functions */
int64_t freerdp_harmonyos_new(void);
void freerdp_harmonyos_free(int64_t instance);
bool freerdp_harmonyos_parse_arguments(int64_t instance, const char** args, int argc);
bool freerdp_harmonyos_connect(int64_t instance);
bool freerdp_harmonyos_disconnect(int64_t instance);
bool freerdp_harmonyos_update_graphics(int64_t instance, uint8_t* buffer, int x, int y, int width, int height);
bool freerdp_harmonyos_send_cursor_event(int64_t instance, int x, int y, int flags);
bool freerdp_harmonyos_send_key_event(int64_t instance, int keycode, bool down);
bool freerdp_harmonyos_send_unicodekey_event(int64_t instance, int keycode, bool down);
bool freerdp_harmonyos_set_tcp_keepalive(int64_t instance, bool enabled, int delay, int interval, int retries);
bool freerdp_harmonyos_send_synchronize_event(int64_t instance, int flags);
bool freerdp_harmonyos_send_clipboard_data(int64_t instance, const char* data);
int freerdp_harmonyos_set_client_decoding(int64_t instance, bool enable);
const char* freerdp_harmonyos_get_last_error_string(int64_t instance);
const char* freerdp_harmonyos_get_version(void);
bool freerdp_harmonyos_has_h264(void);
bool freerdp_harmonyos_is_connected(int64_t instance);

#ifdef __cplusplus
}
#endif

#endif /* HARMONYOS_FREERDP_H */
