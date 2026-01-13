/**
 * FreeRDP: A Remote Desktop Protocol Implementation
 * Android Event System
 *
 * Copyright 2010-2012 Marc-Andre Moreau <marcandre.moreau@gmail.com>
 * Copyright 2013 Thincast Technologies GmbH, Author: Martin Fleisz
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

#include <freerdp/config.h>

#include <winpr/crt.h>
#include <winpr/synch.h>

#include <freerdp/freerdp.h>
#include <freerdp/log.h>

#define TAG CLIENT_TAG("android")

#include "android_freerdp.h"
#include "android_cliprdr.h"

BOOL android_push_event(freerdp* inst, ANDROID_EVENT* event)
{
	if (!inst || !inst->context || !event)
		return FALSE;

	androidContext* aCtx = (androidContext*)inst->context;

	if (!aCtx->event_queue)
	{
		WLog_WARN(TAG, "[QUEUE] push: event_queue is NULL (type=%d)", event->type);
		return FALSE;
	}

	ANDROID_EVENT_QUEUE* queue = aCtx->event_queue;
	/* Avoid blocking on a lock that might be torn down during shutdown */
	if (!TryEnterCriticalSection(&queue->lock))
	{
		WLog_WARN(TAG, "[QUEUE] push: lock busy, drop event (type=%d)", event->type);
		return FALSE;
	}

	if (queue->closing)
	{
		WLog_WARN(TAG, "[QUEUE] push: queue closing, drop event (type=%d)", event->type);
		LeaveCriticalSection(&queue->lock);
		return FALSE;
	}
	
	WLog_INFO(TAG, "[QUEUE] push: type=%d, count=%d, size=%d", 
	          event->type, queue->count, queue->size);

	if (queue->count >= queue->size)
	{
		int new_size;
		void* new_events;
		new_size = queue->size * 2;
		new_events = realloc((void*)queue->events, sizeof(ANDROID_EVENT*) * new_size);

		if (!new_events) {
			WLog_ERR(TAG, "[QUEUE] expand FAIL");
			LeaveCriticalSection(&queue->lock);
			return FALSE;
		}

		WLog_INFO(TAG, "[QUEUE] expanded: %d->%d", queue->size, new_size);
		queue->events = new_events;
		queue->size = new_size;
	}

	queue->events[(queue->count)++] = event;
	BOOL result = SetEvent(queue->isSet);
	WLog_INFO(TAG, "[QUEUE] pushed OK, SetEvent=%d, new_count=%d", result, queue->count);
	LeaveCriticalSection(&queue->lock);
	return result;
}

static ANDROID_EVENT* android_peek_event(ANDROID_EVENT_QUEUE* queue)
{
	ANDROID_EVENT* event;

	if (queue->count < 1)
		return NULL;

	event = queue->events[0];
	return event;
}

static ANDROID_EVENT* android_pop_event(ANDROID_EVENT_QUEUE* queue)
{
	ANDROID_EVENT* event;

	if (queue->count < 1)
		return NULL;

	event = queue->events[0];
	(queue->count)--;

	for (size_t i = 0; i < queue->count; i++)
	{
		queue->events[i] = queue->events[i + 1];
	}

	return event;
}

static BOOL android_process_event(ANDROID_EVENT_QUEUE* queue, freerdp* inst)
{
	rdpContext* context;

	WINPR_ASSERT(queue);
	WINPR_ASSERT(inst);

	context = inst->context;
	WINPR_ASSERT(context);

	while (TRUE)
	{
		if (!TryEnterCriticalSection(&queue->lock))
			return TRUE;

		if (queue->closing)
		{
			LeaveCriticalSection(&queue->lock);
			return TRUE;
		}

		ANDROID_EVENT* event = android_pop_event(queue);
		LeaveCriticalSection(&queue->lock);

		if (!event)
			break;

		BOOL rc = FALSE;
		androidContext* afc = (androidContext*)context;

		WINPR_ASSERT(event);

		switch (event->type)
		{
			case EVENT_TYPE_KEY:
			{
				ANDROID_EVENT_KEY* key_event = (ANDROID_EVENT_KEY*)event;

				rc = freerdp_input_send_keyboard_event(context->input, key_event->flags,
				                                       key_event->scancode);
			}
			break;

			case EVENT_TYPE_KEY_UNICODE:
			{
				ANDROID_EVENT_KEY* key_event = (ANDROID_EVENT_KEY*)event;

				rc = freerdp_input_send_unicode_keyboard_event(context->input, key_event->flags,
				                                               key_event->scancode);
			}
			break;

	case EVENT_TYPE_CURSOR:
	{
		ANDROID_EVENT_CURSOR* cursor_event = (ANDROID_EVENT_CURSOR*)event;
		
		WLog_INFO(TAG, "[PROCESS] CURSOR: flags=0x%04X, x=%d, y=%d, input=%p", 
		          cursor_event->flags, cursor_event->x, cursor_event->y, (void*)context->input);
		
		if (!context->input) {
			WLog_ERR(TAG, "[PROCESS] context->input NULL!");
			rc = FALSE;
			break;
		}
		
		// ✅ P0 Bug修复: 使用实际桌面分辨率进行边界检查，而非固定的32000
		// 防止RDP协议错误导致服务器断开连接
		if (!context->settings) {
			WLog_ERR(TAG, "[PROCESS] context->settings NULL!");
			rc = FALSE;
			break;
		}
		
		UINT32 desktopWidth = freerdp_settings_get_uint32(context->settings, FreeRDP_DesktopWidth);
		UINT32 desktopHeight = freerdp_settings_get_uint32(context->settings, FreeRDP_DesktopHeight);
		
		if (cursor_event->x < 0 || cursor_event->y < 0 || 
		    cursor_event->x > (int)desktopWidth || cursor_event->y > (int)desktopHeight) {
			WLog_ERR(TAG, "[PROCESS] coord out of bounds: x=%d, y=%d (desktop: %ux%u) - REJECTED", 
			          cursor_event->x, cursor_event->y, desktopWidth, desktopHeight);
			rc = FALSE;
			break;
		}

		rc = freerdp_input_send_mouse_event(context->input, (UINT16)cursor_event->flags,
		                                    (UINT16)cursor_event->x, (UINT16)cursor_event->y);
		
		WLog_INFO(TAG, "[PROCESS] send_mouse_event result=%d", rc);
	}
	break;

			case EVENT_TYPE_CLIPBOARD:
			{
				ANDROID_EVENT_CLIPBOARD* clipboard_event = (ANDROID_EVENT_CLIPBOARD*)event;
				UINT32 formatId = ClipboardRegisterFormat(afc->clipboard, "text/plain");
				UINT32 size = clipboard_event->data_length;

				if (size)
					ClipboardSetData(afc->clipboard, formatId, clipboard_event->data, size);
				else
					ClipboardEmpty(afc->clipboard);

				rc = (android_cliprdr_send_client_format_list(afc->cliprdr) == CHANNEL_RC_OK);
			}
			break;

			case EVENT_TYPE_DISCONNECT:
			default:
				/* disconnect event is handled by main loop; ignore here */
				break;
		}

		android_event_free(event);

		if (!rc)
			return FALSE;
	}

	return TRUE;
}

HANDLE android_get_handle(freerdp* inst)
{
	androidContext* aCtx;

	if (!inst || !inst->context)
		return NULL;

	aCtx = (androidContext*)inst->context;

	if (!aCtx->event_queue || !aCtx->event_queue->isSet)
		return NULL;

	return aCtx->event_queue->isSet;
}

BOOL android_check_handle(freerdp* inst)
{
	androidContext* aCtx;

	if (!inst || !inst->context)
		return FALSE;

	aCtx = (androidContext*)inst->context;

	/* During shutdown the queue may be torn down. */
	if (!aCtx->event_queue)
		return TRUE;
	if (!aCtx->event_queue->isSet)
		return FALSE;

	if (WaitForSingleObject(aCtx->event_queue->isSet, 0) == WAIT_OBJECT_0)
	{
		if (!ResetEvent(aCtx->event_queue->isSet))
			return FALSE;

		if (!android_process_event(aCtx->event_queue, inst))
			return FALSE;
	}

	return TRUE;
}

ANDROID_EVENT_KEY* android_event_key_new(int flags, UINT16 scancode)
{
	ANDROID_EVENT_KEY* event = (ANDROID_EVENT_KEY*)calloc(1, sizeof(ANDROID_EVENT_KEY));

	if (!event)
		return NULL;

	event->type = EVENT_TYPE_KEY;
	event->flags = flags;
	event->scancode = scancode;
	return event;
}

static void android_event_key_free(ANDROID_EVENT_KEY* event)
{
	free(event);
}

ANDROID_EVENT_KEY* android_event_unicodekey_new(UINT16 flags, UINT16 key)
{
	ANDROID_EVENT_KEY* event;
	event = (ANDROID_EVENT_KEY*)calloc(1, sizeof(ANDROID_EVENT_KEY));

	if (!event)
		return NULL;

	event->type = EVENT_TYPE_KEY_UNICODE;
	event->flags = flags;
	event->scancode = key;
	return event;
}

static void android_event_unicodekey_free(ANDROID_EVENT_KEY* event)
{
	free(event);
}

ANDROID_EVENT_CURSOR* android_event_cursor_new(int flags, int x, int y)
{
	ANDROID_EVENT_CURSOR* event;
	event = (ANDROID_EVENT_CURSOR*)calloc(1, sizeof(ANDROID_EVENT_CURSOR));

	if (!event)
		return NULL;

	event->type = EVENT_TYPE_CURSOR;
	event->x = x;
	event->y = y;
	event->flags = flags;
	return event;
}

static void android_event_cursor_free(ANDROID_EVENT_CURSOR* event)
{
	free(event);
}

ANDROID_EVENT* android_event_disconnect_new(void)
{
	ANDROID_EVENT* event;
	event = (ANDROID_EVENT*)calloc(1, sizeof(ANDROID_EVENT));

	if (!event)
		return NULL;

	event->type = EVENT_TYPE_DISCONNECT;
	return event;
}

static void android_event_disconnect_free(ANDROID_EVENT* event)
{
	free(event);
}

ANDROID_EVENT_CLIPBOARD* android_event_clipboard_new(const void* data, size_t data_length)
{
	ANDROID_EVENT_CLIPBOARD* event;
	event = (ANDROID_EVENT_CLIPBOARD*)calloc(1, sizeof(ANDROID_EVENT_CLIPBOARD));

	if (!event)
		return NULL;

	event->type = EVENT_TYPE_CLIPBOARD;

	if (data)
	{
		event->data = calloc(data_length + 1, sizeof(char));

		if (!event->data)
		{
			free(event);
			return NULL;
		}

		memcpy(event->data, data, data_length);
		event->data_length = data_length + 1;
	}

	return event;
}

static void android_event_clipboard_free(ANDROID_EVENT_CLIPBOARD* event)
{
	if (event)
	{
		free(event->data);
		free(event);
	}
}

BOOL android_event_queue_init(freerdp* inst)
{
	androidContext* aCtx = (androidContext*)inst->context;
	ANDROID_EVENT_QUEUE* queue;
	queue = (ANDROID_EVENT_QUEUE*)calloc(1, sizeof(ANDROID_EVENT_QUEUE));

	if (!queue)
	{
		WLog_ERR(TAG, "android_event_queue_init: memory allocation failed");
		return FALSE;
	}

	queue->size = 16;
	queue->count = 0;
	queue->isSet = CreateEventA(NULL, TRUE, FALSE, NULL);
	InitializeCriticalSection(&queue->lock);
	queue->closing = FALSE;

	if (!queue->isSet)
	{
		DeleteCriticalSection(&queue->lock);
		free(queue);
		return FALSE;
	}

	queue->events = (ANDROID_EVENT**)calloc(queue->size, sizeof(ANDROID_EVENT*));

	if (!queue->events)
	{
		WLog_ERR(TAG, "android_event_queue_init: memory allocation failed");
		(void)CloseHandle(queue->isSet);
		DeleteCriticalSection(&queue->lock);
		free(queue);
		return FALSE;
	}

	aCtx->event_queue = queue;
	return TRUE;
}

void android_event_queue_uninit(freerdp* inst)
{
	androidContext* aCtx;
	ANDROID_EVENT_QUEUE* queue;

	if (!inst || !inst->context)
		return;

	aCtx = (androidContext*)inst->context;
	queue = aCtx->event_queue;
	/* Stop new producers from even seeing the queue pointer. */
	aCtx->event_queue = NULL;

	if (queue)
	{
		/* Prevent concurrent producers/consumers during teardown */
		EnterCriticalSection(&queue->lock);
		queue->closing = TRUE;

		if (queue->isSet)
		{
			(void)CloseHandle(queue->isSet);
			queue->isSet = NULL;
		}

		if (queue->events)
		{
			free(queue->events);
			queue->events = NULL;
			queue->size = 0;
			queue->count = 0;
		}

		LeaveCriticalSection(&queue->lock);
		DeleteCriticalSection(&queue->lock);

		free(queue);
	}
}

void android_event_free(ANDROID_EVENT* event)
{
	if (!event)
		return;

	switch (event->type)
	{
		case EVENT_TYPE_KEY:
			android_event_key_free((ANDROID_EVENT_KEY*)event);
			break;

		case EVENT_TYPE_KEY_UNICODE:
			android_event_unicodekey_free((ANDROID_EVENT_KEY*)event);
			break;

		case EVENT_TYPE_CURSOR:
			android_event_cursor_free((ANDROID_EVENT_CURSOR*)event);
			break;

		case EVENT_TYPE_DISCONNECT:
			android_event_disconnect_free((ANDROID_EVENT*)event);
			break;

		case EVENT_TYPE_CLIPBOARD:
			android_event_clipboard_free((ANDROID_EVENT_CLIPBOARD*)event);
			break;

		default:
			break;
	}
}
