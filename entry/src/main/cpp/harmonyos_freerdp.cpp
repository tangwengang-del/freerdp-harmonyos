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
#define LOG_TAG "FreeRDP"
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
    rdpSettings* settings;
    harmonyosContext* afc;

    if (!context || !e) {
        LOGE("(context=%p, EventArgs=%p)", context, (void*)e);
        return;
    }

    afc = (harmonyosContext*)context;
    settings = afc->common.context.settings;

    if (strcmp(e->name, CLIPRDR_SVC_CHANNEL_NAME) == 0) {
        // TODO: Initialize clipboard
        LOGI("Clipboard channel connected");
    } else {
        freerdp_client_OnChannelConnectedEventHandler(context, e);
    }
}

static void harmonyos_OnChannelDisconnectedEventHandler(void* context, const ChannelDisconnectedEventArgs* e) {
    rdpSettings* settings;
    harmonyosContext* afc;

    if (!context || !e) {
        LOGE("(context=%p, EventArgs=%p)", context, (void*)e);
        return;
    }

    afc = (harmonyosContext*)context;
    settings = afc->common.context.settings;

    if (strcmp(e->name, CLIPRDR_SVC_CHANNEL_NAME) == 0) {
        // TODO: Uninitialize clipboard
        LOGI("Clipboard channel disconnected");
    } else {
        freerdp_client_OnChannelDisconnectedEventHandler(context, e);
    }
}

/* Paint handlers */
static BOOL harmonyos_begin_paint(rdpContext* context) {
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
    WINPR_ASSERT(context);
    WINPR_ASSERT(context->settings);
    WINPR_ASSERT(context->instance);

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

    WINPR_ASSERT(instance);
    WINPR_ASSERT(instance->context);

    settings = instance->context->settings;
    if (!settings)
        return FALSE;

    rc = PubSub_SubscribeChannelConnected(instance->context->pubSub,
                                          harmonyos_OnChannelConnectedEventHandler);
    if (rc != CHANNEL_RC_OK) {
        LOGE("Could not subscribe to connect event handler [%08X]", rc);
        return FALSE;
    }

    rc = PubSub_SubscribeChannelDisconnected(instance->context->pubSub,
                                             harmonyos_OnChannelDisconnectedEventHandler);
    if (rc != CHANNEL_RC_OK) {
        LOGE("Could not subscribe to disconnect event handler [%08X]", rc);
        return FALSE;
    }

    if (g_onPreConnect) {
        g_onPreConnect((int64_t)(uintptr_t)instance);
    }
    return TRUE;
}

/* Pointer handlers */
static BOOL harmonyos_Pointer_New(rdpContext* context, rdpPointer* pointer) {
    WINPR_ASSERT(context);
    WINPR_ASSERT(pointer);
    WINPR_ASSERT(context->gdi);
    return TRUE;
}

static void harmonyos_Pointer_Free(rdpContext* context, rdpPointer* pointer) {
    WINPR_UNUSED(context);
    WINPR_UNUSED(pointer);
}

static BOOL harmonyos_Pointer_Set(rdpContext* context, rdpPointer* pointer) {
    WINPR_ASSERT(context);
    WINPR_ASSERT(pointer);

    int cursorType = identify_cursor_type(pointer);
    
    freerdp* instance = context->instance;
    if (instance && g_onCursorTypeChanged) {
        g_onCursorTypeChanged((int64_t)(uintptr_t)instance, cursorType);
    }

    return TRUE;
}

static BOOL harmonyos_Pointer_SetPosition(rdpContext* context, UINT32 x, UINT32 y) {
    WINPR_ASSERT(context);
    LOGD("Pointer SetPosition: x=%u, y=%u", x, y);
    return TRUE;
}

static BOOL harmonyos_Pointer_SetNull(rdpContext* context) {
    WINPR_ASSERT(context);
    LOGD("Pointer_SetNull");
    
    freerdp* instance = context->instance;
    if (instance && g_onCursorTypeChanged) {
        g_onCursorTypeChanged((int64_t)(uintptr_t)instance, CURSOR_TYPE_UNKNOWN);
    }
    return TRUE;
}

static BOOL harmonyos_Pointer_SetDefault(rdpContext* context) {
    WINPR_ASSERT(context);
    LOGD("Pointer_SetDefault");
    
    freerdp* instance = context->instance;
    if (instance && g_onCursorTypeChanged) {
        g_onCursorTypeChanged((int64_t)(uintptr_t)instance, CURSOR_TYPE_DEFAULT);
    }
    return TRUE;
}

static BOOL harmonyos_register_pointer(rdpGraphics* graphics) {
    rdpPointer pointer = { 0 };

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

    WINPR_ASSERT(instance);
    WINPR_ASSERT(instance->context);

    update = instance->context->update;
    WINPR_ASSERT(update);

    settings = instance->context->settings;
    WINPR_ASSERT(settings);

    if (!gdi_init(instance, PIXEL_FORMAT_RGBX32))
        return FALSE;

    if (!harmonyos_register_pointer(instance->context->graphics))
        return FALSE;

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
    return TRUE;
}

/* Post-disconnect callback */
static void harmonyos_post_disconnect(freerdp* instance) {
    if (g_onDisconnecting) {
        g_onDisconnecting((int64_t)(uintptr_t)instance);
    }
    gdi_free(instance);
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
    return harmonyos_verify_certificate_ex(instance, host, port, common_name, subject,
                                           issuer, new_fingerprint, flags);
}

/* Main run loop */
static int harmonyos_freerdp_run(freerdp* instance) {
    DWORD count;
    DWORD status = WAIT_FAILED;
    HANDLE handles[MAXIMUM_WAIT_OBJECTS];
    HANDLE inputEvent = NULL;
    const rdpSettings* settings = instance->context->settings;
    rdpContext* context = instance->context;

    inputEvent = harmonyos_get_handle(instance);

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
        status = WaitForMultipleObjects(count, handles, FALSE, INFINITE);

        if (status == WAIT_FAILED) {
            LOGE("WaitForMultipleObjects failed with %u [%08lX]", status, GetLastError());
            break;
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
    rdpContext* context;
    BOOL shouldReconnect = FALSE;
    int reconnectAttempts = 0;
    const int MAX_RECONNECT_ATTEMPTS = 5;
    
    LOGD("Start...");

    WINPR_ASSERT(instance);
    WINPR_ASSERT(instance->context);
    
    context = instance->context;

    if (freerdp_client_start(context) != CHANNEL_RC_OK)
        goto fail;

reconnect_loop:
    LOGD("Connect... (attempt %d)", reconnectAttempts + 1);

    if (!freerdp_connect(instance)) {
        status = GetLastError();
        LOGE("Connection failed with error: %08X", status);
        
        /* Check if we should try to reconnect */
        if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++;
            LOGI("Will retry connection in %d seconds... (%d/%d)", 
                 reconnectAttempts * 2, reconnectAttempts, MAX_RECONNECT_ATTEMPTS);
            
            /* Exponential backoff */
            Sleep(reconnectAttempts * 2000);
            
            if (!freerdp_shall_disconnect_context(context)) {
                goto reconnect_loop;
            }
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
    WINPR_ASSERT(instance);
    WINPR_ASSERT(context);

    if (!harmonyos_event_queue_init(instance))
        return FALSE;

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
    WINPR_ASSERT(pEntryPoints);

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

int64_t freerdp_harmonyos_new(void) {
    RDP_CLIENT_ENTRY_POINTS clientEntryPoints;
    rdpContext* ctx;

    setlocale(LC_ALL, "");

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

    if (!inst || !inst->context)
        return false;

    char** argv = (char**)malloc(argc * sizeof(char*));
    if (!argv)
        return false;

    for (int i = 0; i < argc; i++) {
        argv[i] = strdup(args[i]);
    }

    status = freerdp_client_settings_parse_command_line(inst->context->settings, argc, argv, FALSE);

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
    harmonyosContext* ctx;
    HARMONYOS_EVENT* event;

    if (!inst || !inst->context) {
        LOGE("Invalid instance");
        return false;
    }

    ctx = (harmonyosContext*)inst->context;
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

    if (!freerdp_settings_set_bool(settings, FreeRDP_TcpKeepAlive, enabled)) {
        LOGE("Failed to set TcpKeepAlive=%d", enabled);
        return false;
    }

    if (enabled) {
        if (!freerdp_settings_set_uint32(settings, FreeRDP_TcpKeepAliveDelay, (UINT32)delay)) {
            LOGE("Failed to set TcpKeepAliveDelay=%d", delay);
            return false;
        }

        if (!freerdp_settings_set_uint32(settings, FreeRDP_TcpKeepAliveInterval, (UINT32)interval)) {
            LOGE("Failed to set TcpKeepAliveInterval=%d", interval);
            return false;
        }

        if (!freerdp_settings_set_uint32(settings, FreeRDP_TcpKeepAliveRetries, (UINT32)retries)) {
            LOGE("Failed to set TcpKeepAliveRetries=%d", retries);
            return false;
        }

        LOGI("TCP Keepalive enabled: delay=%ds, interval=%ds, retries=%d", delay, interval, retries);
    } else {
        LOGI("TCP Keepalive disabled");
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
    rdpSettings* settings = context->settings;
    if (!settings)
        return -2;

    rdpUpdate* update = context->update;
    if (!update)
        return -3;

    BOOL deactivate = enable ? FALSE : TRUE;
    if (!freerdp_settings_set_bool(settings, FreeRDP_DeactivateClientDecoding, deactivate)) {
        LOGE("Failed to set DeactivateClientDecoding");
        return -4;
    }

    if (!freerdp_settings_set_bool(settings, FreeRDP_SuppressOutput, TRUE)) {
        LOGE("Failed to enable SuppressOutput capability");
        return -5;
    }

    BOOL allowDisplayUpdates = enable ? TRUE : FALSE;
    
    RECTANGLE_16 rect = { 0 };
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
    
    /* Disable graphics decoding to save CPU/bandwidth */
    freerdp_settings_set_bool(settings, FreeRDP_DeactivateClientDecoding, TRUE);
    
    /* Send SuppressOutput PDU to tell server to stop sending graphics */
    RECTANGLE_16 rect = { 0 };
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
    
    LOGI("Background mode active - graphics suppressed, audio continues");
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
    
    /* Re-enable graphics decoding FIRST */
    freerdp_settings_set_bool(settings, FreeRDP_DeactivateClientDecoding, FALSE);
    
    /* Get full screen dimensions */
    UINT32 width = freerdp_settings_get_uint32(settings, FreeRDP_DesktopWidth);
    UINT32 height = freerdp_settings_get_uint32(settings, FreeRDP_DesktopHeight);
    
    RECTANGLE_16 rect = { 0 };
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
        freerdp_settings_set_bool(settings, FreeRDP_RemoteConsoleAudio, FALSE);
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
    
    RECTANGLE_16 rect = { 0 };
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
    
    RECTANGLE_16 rect = { 0 };
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
    
    if (!gdi || !gdi->primary || !gdi->primary->bitmap) {
        LOGE("get_frame_buffer: GDI not initialized");
        return false;
    }
    
    rdpBitmap* bitmap = gdi->primary->bitmap;
    
    if (buffer) *buffer = gdi->primary_buffer;
    if (width) *width = (int)bitmap->width;
    if (height) *height = (int)bitmap->height;
    if (stride) *stride = (int)gdi->stride;
    
    return true;
}
