/*
 * HarmonyOS FreeRDP Clipboard Implementation
 * 
 * Copyright 2026 FreeRDP HarmonyOS Port
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */

#include "harmonyos_freerdp.h"
#include <stdlib.h>
#include <string.h>

#ifdef OHOS_PLATFORM
#include <hilog/log.h>
#define LOG_TAG "FreeRDP.Clipboard"
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

#include <freerdp/client/cliprdr.h>

typedef struct {
    CliprdrClientContext* cliprdr;
    harmonyosContext* afc;
    UINT32 requestedFormatId;
    char* lastReceivedData;
    size_t lastReceivedDataLength;
} harmonyosClipboardContext;

static harmonyosClipboardContext* g_clipboardCtx = NULL;

/* Format data request callback */
static UINT harmonyos_cliprdr_send_client_format_data_request(CliprdrClientContext* cliprdr, UINT32 formatId) {
    CLIPRDR_FORMAT_DATA_REQUEST formatDataRequest;
    
    if (!cliprdr)
        return ERROR_INVALID_PARAMETER;
    
    ZeroMemory(&formatDataRequest, sizeof(CLIPRDR_FORMAT_DATA_REQUEST));
    formatDataRequest.requestedFormatId = formatId;
    
    return cliprdr->ClientFormatDataRequest(cliprdr, &formatDataRequest);
}

/* Server capabilities callback */
static UINT harmonyos_cliprdr_server_capabilities(CliprdrClientContext* cliprdr, const CLIPRDR_CAPABILITIES* capabilities) {
    LOGD("Server clipboard capabilities received");
    return CHANNEL_RC_OK;
}

/* Monitor ready callback */
static UINT harmonyos_cliprdr_monitor_ready(CliprdrClientContext* cliprdr, const CLIPRDR_MONITOR_READY* monitorReady) {
    CLIPRDR_CAPABILITIES capabilities;
    CLIPRDR_GENERAL_CAPABILITY_SET generalCapabilitySet;
    CLIPRDR_FORMAT_LIST formatList;
    CLIPRDR_FORMAT formats[1];
    
    LOGD("Clipboard monitor ready");
    
    if (!cliprdr)
        return ERROR_INVALID_PARAMETER;
    
    /* Send capabilities */
    ZeroMemory(&capabilities, sizeof(CLIPRDR_CAPABILITIES));
    ZeroMemory(&generalCapabilitySet, sizeof(CLIPRDR_GENERAL_CAPABILITY_SET));
    
    generalCapabilitySet.capabilitySetType = CB_CAPSTYPE_GENERAL;
    generalCapabilitySet.capabilitySetLength = 12;
    generalCapabilitySet.version = CB_CAPS_VERSION_2;
    generalCapabilitySet.generalFlags = CB_USE_LONG_FORMAT_NAMES;
    
    capabilities.cCapabilitiesSets = 1;
    capabilities.capabilitySets = (CLIPRDR_CAPABILITY_SET*)&generalCapabilitySet;
    
    cliprdr->ClientCapabilities(cliprdr, &capabilities);
    
    /* Send format list - we support text */
    ZeroMemory(&formatList, sizeof(CLIPRDR_FORMAT_LIST));
    ZeroMemory(&formats, sizeof(formats));
    
    formats[0].formatId = CF_UNICODETEXT;
    formats[0].formatName = NULL;
    
    formatList.numFormats = 1;
    formatList.formats = formats;
    
    return cliprdr->ClientFormatList(cliprdr, &formatList);
}

/* Server format list callback */
static UINT harmonyos_cliprdr_server_format_list(CliprdrClientContext* cliprdr, const CLIPRDR_FORMAT_LIST* formatList) {
    CLIPRDR_FORMAT_LIST_RESPONSE formatListResponse;
    
    LOGD("Server format list received: %d formats", formatList->numFormats);
    
    if (!cliprdr)
        return ERROR_INVALID_PARAMETER;
    
    /* Send format list response */
    ZeroMemory(&formatListResponse, sizeof(CLIPRDR_FORMAT_LIST_RESPONSE));
    formatListResponse.common.msgFlags = CB_RESPONSE_OK;
    
    cliprdr->ClientFormatListResponse(cliprdr, &formatListResponse);
    
    /* Check for text format and request it */
    for (UINT32 i = 0; i < formatList->numFormats; i++) {
        if (formatList->formats[i].formatId == CF_UNICODETEXT ||
            formatList->formats[i].formatId == CF_TEXT) {
            if (g_clipboardCtx) {
                g_clipboardCtx->requestedFormatId = formatList->formats[i].formatId;
            }
            return harmonyos_cliprdr_send_client_format_data_request(cliprdr, formatList->formats[i].formatId);
        }
    }
    
    return CHANNEL_RC_OK;
}

/* Server format list response callback */
static UINT harmonyos_cliprdr_server_format_list_response(CliprdrClientContext* cliprdr, const CLIPRDR_FORMAT_LIST_RESPONSE* formatListResponse) {
    (void)cliprdr;
    LOGD("Server format list response: flags=0x%04X", formatListResponse->common.msgFlags);
    return CHANNEL_RC_OK;
}

/* Server format data request callback */
static UINT harmonyos_cliprdr_server_format_data_request(CliprdrClientContext* cliprdr, const CLIPRDR_FORMAT_DATA_REQUEST* formatDataRequest) {
    CLIPRDR_FORMAT_DATA_RESPONSE formatDataResponse;
    
    LOGD("Server format data request: formatId=%d", formatDataRequest->requestedFormatId);
    
    if (!cliprdr)
        return ERROR_INVALID_PARAMETER;
    
    /* TODO: Get clipboard data from HarmonyOS pasteboard and send to server */
    ZeroMemory(&formatDataResponse, sizeof(CLIPRDR_FORMAT_DATA_RESPONSE));
    formatDataResponse.common.msgFlags = CB_RESPONSE_FAIL;
    formatDataResponse.common.dataLen = 0;
    formatDataResponse.requestedFormatData = NULL;
    
    return cliprdr->ClientFormatDataResponse(cliprdr, &formatDataResponse);
}

/* Server format data response callback */
static UINT harmonyos_cliprdr_server_format_data_response(CliprdrClientContext* cliprdr, const CLIPRDR_FORMAT_DATA_RESPONSE* formatDataResponse) {
    LOGD("Server format data response: flags=0x%04X, dataLen=%u", 
         formatDataResponse->common.msgFlags, formatDataResponse->common.dataLen);
    
    if (!cliprdr || !g_clipboardCtx)
        return ERROR_INVALID_PARAMETER;
    
    if (formatDataResponse->common.msgFlags != CB_RESPONSE_OK)
        return CHANNEL_RC_OK;
    
    /* Free previous data */
    if (g_clipboardCtx->lastReceivedData) {
        free(g_clipboardCtx->lastReceivedData);
        g_clipboardCtx->lastReceivedData = NULL;
        g_clipboardCtx->lastReceivedDataLength = 0;
    }
    
    if (formatDataResponse->common.dataLen > 0 && formatDataResponse->requestedFormatData) {
        /* Convert and store data */
        if (g_clipboardCtx->requestedFormatId == CF_UNICODETEXT) {
            /* Convert UTF-16 to UTF-8 */
            /* For simplicity, just copy as-is for now */
            /* TODO: Proper UTF-16 to UTF-8 conversion */
            g_clipboardCtx->lastReceivedData = (char*)malloc(formatDataResponse->common.dataLen + 1);
            if (g_clipboardCtx->lastReceivedData) {
                memcpy(g_clipboardCtx->lastReceivedData, formatDataResponse->requestedFormatData, formatDataResponse->common.dataLen);
                g_clipboardCtx->lastReceivedData[formatDataResponse->common.dataLen] = '\0';
                g_clipboardCtx->lastReceivedDataLength = formatDataResponse->common.dataLen;
                
                /* TODO: Notify ArkTS layer about clipboard change */
                LOGI("Clipboard data received: %zu bytes", g_clipboardCtx->lastReceivedDataLength);
            }
        }
    }
    
    return CHANNEL_RC_OK;
}

/* Initialize clipboard */
void harmonyos_cliprdr_init(harmonyosContext* afc, CliprdrClientContext* cliprdr) {
    if (!afc || !cliprdr)
        return;
    
    LOGI("Initializing clipboard");
    
    g_clipboardCtx = (harmonyosClipboardContext*)calloc(1, sizeof(harmonyosClipboardContext));
    if (!g_clipboardCtx)
        return;
    
    g_clipboardCtx->afc = afc;
    g_clipboardCtx->cliprdr = cliprdr;
    
    cliprdr->custom = g_clipboardCtx;
    cliprdr->ServerCapabilities = harmonyos_cliprdr_server_capabilities;
    cliprdr->MonitorReady = harmonyos_cliprdr_monitor_ready;
    cliprdr->ServerFormatList = harmonyos_cliprdr_server_format_list;
    cliprdr->ServerFormatListResponse = harmonyos_cliprdr_server_format_list_response;
    cliprdr->ServerFormatDataRequest = harmonyos_cliprdr_server_format_data_request;
    cliprdr->ServerFormatDataResponse = harmonyos_cliprdr_server_format_data_response;
}

/* Uninitialize clipboard */
void harmonyos_cliprdr_uninit(harmonyosContext* afc, CliprdrClientContext* cliprdr) {
    (void)afc;
    if (!cliprdr)
        return;
    
    LOGI("Uninitializing clipboard");
    
    if (g_clipboardCtx) {
        if (g_clipboardCtx->lastReceivedData) {
            free(g_clipboardCtx->lastReceivedData);
        }
        free(g_clipboardCtx);
        g_clipboardCtx = NULL;
    }
    
    cliprdr->custom = NULL;
}

/* Send clipboard data to server */
bool harmonyos_cliprdr_send_data(const char* data, size_t length) {
    (void)length;
    if (!g_clipboardCtx || !g_clipboardCtx->cliprdr || !data)
        return false;
    
    CLIPRDR_FORMAT_LIST formatList;
    CLIPRDR_FORMAT formats[1];
    
    ZeroMemory(&formatList, sizeof(CLIPRDR_FORMAT_LIST));
    ZeroMemory(&formats, sizeof(formats));
    
    formats[0].formatId = CF_UNICODETEXT;
    formats[0].formatName = NULL;
    
    formatList.numFormats = 1;
    formatList.formats = formats;
    
    /* Notify server that we have new clipboard data */
    UINT rc = g_clipboardCtx->cliprdr->ClientFormatList(g_clipboardCtx->cliprdr, &formatList);
    
    return (rc == CHANNEL_RC_OK);
}
