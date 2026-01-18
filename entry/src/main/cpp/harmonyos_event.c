/*
 * HarmonyOS FreeRDP Event Queue Implementation
 * 
 * Copyright 2026 FreeRDP HarmonyOS Port
 * Based on Android FreeRDP implementation
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */

#include "harmonyos_freerdp.h"
#include <stdlib.h>
#include <string.h>

#ifdef OHOS_PLATFORM
#include <hilog/log.h>
#ifdef LOG_TAG
#undef LOG_TAG
#endif
#define LOG_TAG "FreeRDP.Event"
#define LOGI(...) OH_LOG_INFO(LOG_APP, __VA_ARGS__)
#define LOGW(...) OH_LOG_WARN(LOG_APP, __VA_ARGS__)
#define LOGE(...) OH_LOG_ERROR(LOG_APP, __VA_ARGS__)
#define LOGD(...) OH_LOG_DEBUG(LOG_APP, __VA_ARGS__)
#else
#include <stdio.h>
#define LOGI(...) printf(__VA_ARGS__)
#define LOGW(...) printf(__VA_ARGS__)
#define LOGE(...) printf(__VA_ARGS__)
#define LOGD(...) printf(__VA_ARGS__)
#endif

#include <winpr/collections.h>
#include <winpr/synch.h>

typedef struct {
    wQueue* queue;
    HANDLE event;
} EventQueue;

static EventQueue* get_event_queue(freerdp* instance) {
    if (!instance || !instance->context)
        return NULL;
    
    harmonyosContext* ctx = (harmonyosContext*)instance->context;
    // Store queue in context (use napi_env field temporarily)
    return (EventQueue*)ctx->napi_env;
}

static void set_event_queue(freerdp* instance, EventQueue* queue) {
    if (!instance || !instance->context)
        return;
    
    harmonyosContext* ctx = (harmonyosContext*)instance->context;
    ctx->napi_env = queue;
}

bool harmonyos_event_queue_init(freerdp* instance) {
    EventQueue* eq;
    
    if (!instance)
        return false;
    
    eq = (EventQueue*)calloc(1, sizeof(EventQueue));
    if (!eq)
        return false;
    
    eq->queue = Queue_New(TRUE, -1, -1);
    if (!eq->queue) {
        free(eq);
        return false;
    }
    
    eq->event = CreateEvent(NULL, TRUE, FALSE, NULL);
    if (!eq->event) {
        Queue_Free(eq->queue);
        free(eq);
        return false;
    }
    
    set_event_queue(instance, eq);
    return true;
}

void harmonyos_event_queue_uninit(freerdp* instance) {
    EventQueue* eq = get_event_queue(instance);
    
    if (!eq)
        return;
    
    if (eq->event) {
        CloseHandle(eq->event);
        eq->event = NULL;
    }
    
    if (eq->queue) {
        // Free remaining events
        HARMONYOS_EVENT* event;
        while ((event = (HARMONYOS_EVENT*)Queue_Dequeue(eq->queue)) != NULL) {
            harmonyos_event_free(event);
        }
        Queue_Free(eq->queue);
        eq->queue = NULL;
    }
    
    free(eq);
    set_event_queue(instance, NULL);
}

bool harmonyos_push_event(freerdp* instance, HARMONYOS_EVENT* event) {
    EventQueue* eq = get_event_queue(instance);
    
    if (!eq || !eq->queue || !event)
        return false;
    
    if (!Queue_Enqueue(eq->queue, event))
        return false;
    
    SetEvent(eq->event);
    return true;
}

HANDLE harmonyos_get_handle(freerdp* instance) {
    EventQueue* eq = get_event_queue(instance);
    
    if (!eq)
        return NULL;
    
    return eq->event;
}

bool harmonyos_check_handle(freerdp* instance) {
    EventQueue* eq = get_event_queue(instance);
    HARMONYOS_EVENT* event;
    rdpInput* input;
    
    if (!eq || !eq->queue)
        return false;
    
    if (!instance || !instance->context)
        return false;
    
    input = instance->context->input;
    
    while ((event = (HARMONYOS_EVENT*)Queue_Dequeue(eq->queue)) != NULL) {
        switch (event->type) {
            case HARMONYOS_EVENT_TYPE_KEY: {
                HARMONYOS_EVENT_KEY* keyEvent = (HARMONYOS_EVENT_KEY*)event;
                if (input) {
                    freerdp_input_send_keyboard_event(input, keyEvent->flags, keyEvent->scancode);
                }
                break;
            }
            
            case HARMONYOS_EVENT_TYPE_UNICODEKEY: {
                HARMONYOS_EVENT_UNICODEKEY* unicodeEvent = (HARMONYOS_EVENT_UNICODEKEY*)event;
                if (input) {
                    freerdp_input_send_unicode_keyboard_event(input, unicodeEvent->flags, unicodeEvent->character);
                }
                break;
            }
            
            case HARMONYOS_EVENT_TYPE_CURSOR: {
                HARMONYOS_EVENT_CURSOR* cursorEvent = (HARMONYOS_EVENT_CURSOR*)event;
                if (input) {
                    freerdp_input_send_mouse_event(input, cursorEvent->flags, cursorEvent->x, cursorEvent->y);
                }
                break;
            }
            
            case HARMONYOS_EVENT_TYPE_DISCONNECT: {
                // Signal disconnect
                if (!freerdp_abort_connect_context(instance->context)) {
                    LOGE("Failed to abort connect");
                }
                break;
            }
            
            case HARMONYOS_EVENT_TYPE_CLIPBOARD: {
                HARMONYOS_EVENT_CLIPBOARD* clipEvent = (HARMONYOS_EVENT_CLIPBOARD*)event;
                // TODO: Handle clipboard data
                LOGD("Clipboard event received: %s", clipEvent->data ? clipEvent->data : "(null)");
                break;
            }
            
            default:
                LOGW("Unknown event type: %d", event->type);
                break;
        }
        
        harmonyos_event_free(event);
    }
    
    ResetEvent(eq->event);
    return true;
}

void harmonyos_event_free(HARMONYOS_EVENT* event) {
    if (!event)
        return;
    
    if (event->type == HARMONYOS_EVENT_TYPE_CLIPBOARD) {
        HARMONYOS_EVENT_CLIPBOARD* clipEvent = (HARMONYOS_EVENT_CLIPBOARD*)event;
        if (clipEvent->data) {
            free(clipEvent->data);
        }
    }
    
    free(event);
}

/* Event creation functions */

HARMONYOS_EVENT_KEY* harmonyos_event_key_new(int flags, uint16_t scancode) {
    HARMONYOS_EVENT_KEY* event = (HARMONYOS_EVENT_KEY*)calloc(1, sizeof(HARMONYOS_EVENT_KEY));
    if (!event)
        return NULL;
    
    event->type = HARMONYOS_EVENT_TYPE_KEY;
    event->flags = flags;
    event->scancode = scancode;
    return event;
}

HARMONYOS_EVENT_UNICODEKEY* harmonyos_event_unicodekey_new(int flags, uint16_t character) {
    HARMONYOS_EVENT_UNICODEKEY* event = (HARMONYOS_EVENT_UNICODEKEY*)calloc(1, sizeof(HARMONYOS_EVENT_UNICODEKEY));
    if (!event)
        return NULL;
    
    event->type = HARMONYOS_EVENT_TYPE_UNICODEKEY;
    event->flags = flags;
    event->character = character;
    return event;
}

HARMONYOS_EVENT_CURSOR* harmonyos_event_cursor_new(int flags, int x, int y) {
    HARMONYOS_EVENT_CURSOR* event = (HARMONYOS_EVENT_CURSOR*)calloc(1, sizeof(HARMONYOS_EVENT_CURSOR));
    if (!event)
        return NULL;
    
    event->type = HARMONYOS_EVENT_TYPE_CURSOR;
    event->flags = flags;
    event->x = x;
    event->y = y;
    return event;
}

HARMONYOS_EVENT_DISCONNECT* harmonyos_event_disconnect_new(void) {
    HARMONYOS_EVENT_DISCONNECT* event = (HARMONYOS_EVENT_DISCONNECT*)calloc(1, sizeof(HARMONYOS_EVENT_DISCONNECT));
    if (!event)
        return NULL;
    
    event->type = HARMONYOS_EVENT_TYPE_DISCONNECT;
    return event;
}

HARMONYOS_EVENT_CLIPBOARD* harmonyos_event_clipboard_new(const char* data, size_t length) {
    HARMONYOS_EVENT_CLIPBOARD* event = (HARMONYOS_EVENT_CLIPBOARD*)calloc(1, sizeof(HARMONYOS_EVENT_CLIPBOARD));
    if (!event)
        return NULL;
    
    event->type = HARMONYOS_EVENT_TYPE_CLIPBOARD;
    event->length = length;
    
    if (data && length > 0) {
        event->data = (char*)malloc(length + 1);
        if (!event->data) {
            free(event);
            return NULL;
        }
        memcpy(event->data, data, length);
        event->data[length] = '\0';
    } else {
        event->data = NULL;
    }
    
    return event;
}
