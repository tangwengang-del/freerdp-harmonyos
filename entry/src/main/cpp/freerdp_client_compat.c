/*
 * FreeRDP Client Compatibility Layer for HarmonyOS
 * 
 * This file provides implementations for freerdp-client functions
 * that are not available when building without WITH_CLIENT_COMMON.
 * 
 * Features:
 * - Context management
 * - Auto-reconnect support
 * - RDPSND audio channel support
 * 
 * Based on FreeRDP client/common/client.c
 */

#include <freerdp/freerdp.h>
#include <freerdp/settings.h>
#include <freerdp/channels/channels.h>
#include <freerdp/client/channels.h>
#include <freerdp/client/cmdline.h>

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

/* Simple context creation without the full client library */
rdpContext* freerdp_client_context_new(const RDP_CLIENT_ENTRY_POINTS* pEntryPoints)
{
    freerdp* instance;
    rdpContext* context;

    WINPR_ASSERT(pEntryPoints);

    instance = freerdp_new();
    if (!instance)
        return NULL;

    instance->ContextSize = pEntryPoints->ContextSize;
    instance->ContextNew = pEntryPoints->ClientNew;
    instance->ContextFree = pEntryPoints->ClientFree;

    if (!freerdp_context_new(instance))
    {
        freerdp_free(instance);
        return NULL;
    }

    context = instance->context;
    context->instance = instance;

    if (pEntryPoints->GlobalInit && !pEntryPoints->GlobalInit())
    {
        freerdp_context_free(instance);
        freerdp_free(instance);
        return NULL;
    }

    return context;
}

void freerdp_client_context_free(rdpContext* context)
{
    freerdp* instance;

    if (!context)
        return;

    instance = context->instance;

    if (instance)
    {
        freerdp_context_free(instance);
        freerdp_free(instance);
    }
}

/* Simplified client start - just initialize channels */
int freerdp_client_start(rdpContext* context)
{
    WINPR_ASSERT(context);
    /* Channels are initialized during PreConnect, nothing to do here */
    return CHANNEL_RC_OK;
}

/* Simplified client stop - just cleanup channels */
int freerdp_client_stop(rdpContext* context)
{
    WINPR_ASSERT(context);
    /* Channels are cleaned up during PostDisconnect, nothing to do here */
    return CHANNEL_RC_OK;
}

/* Minimal command line parsing - just set essential settings */
int freerdp_client_settings_parse_command_line(rdpSettings* settings, int argc, 
                                                char** argv, BOOL allowUnknown)
{
    WINPR_ASSERT(settings);

    if (argc < 1 || !argv)
        return -1;

    /* Parse command line arguments manually */
    for (int i = 0; i < argc; i++)
    {
        const char* arg = argv[i];
        if (!arg)
            continue;

        /* Server hostname */
        if (strncmp(arg, "/v:", 3) == 0)
        {
            const char* value = arg + 3;
            char* port = strchr(value, ':');
            if (port)
            {
                size_t len = port - value;
                char* host = (char*)malloc(len + 1);
                if (host)
                {
                    strncpy(host, value, len);
                    host[len] = '\0';
                    freerdp_settings_set_string(settings, FreeRDP_ServerHostname, host);
                    free(host);
                }
                freerdp_settings_set_uint32(settings, FreeRDP_ServerPort, atoi(port + 1));
            }
            else
            {
                freerdp_settings_set_string(settings, FreeRDP_ServerHostname, value);
            }
        }
        /* Username */
        else if (strncmp(arg, "/u:", 3) == 0)
        {
            freerdp_settings_set_string(settings, FreeRDP_Username, arg + 3);
        }
        /* Password */
        else if (strncmp(arg, "/p:", 3) == 0)
        {
            freerdp_settings_set_string(settings, FreeRDP_Password, arg + 3);
        }
        /* Domain */
        else if (strncmp(arg, "/d:", 3) == 0)
        {
            freerdp_settings_set_string(settings, FreeRDP_Domain, arg + 3);
        }
        /* Width */
        else if (strncmp(arg, "/w:", 3) == 0)
        {
            freerdp_settings_set_uint32(settings, FreeRDP_DesktopWidth, atoi(arg + 3));
        }
        /* Height */
        else if (strncmp(arg, "/h:", 3) == 0)
        {
            freerdp_settings_set_uint32(settings, FreeRDP_DesktopHeight, atoi(arg + 3));
        }
        /* Color depth */
        else if (strncmp(arg, "/bpp:", 5) == 0)
        {
            freerdp_settings_set_uint32(settings, FreeRDP_ColorDepth, atoi(arg + 5));
        }
        /* Port */
        else if (strncmp(arg, "/port:", 6) == 0)
        {
            freerdp_settings_set_uint32(settings, FreeRDP_ServerPort, atoi(arg + 6));
        }
        /* TLS security */
        else if (strcmp(arg, "/sec:tls") == 0)
        {
            freerdp_settings_set_bool(settings, FreeRDP_RdpSecurity, FALSE);
            freerdp_settings_set_bool(settings, FreeRDP_TlsSecurity, TRUE);
            freerdp_settings_set_bool(settings, FreeRDP_NlaSecurity, FALSE);
        }
        /* NLA security */
        else if (strcmp(arg, "/sec:nla") == 0)
        {
            freerdp_settings_set_bool(settings, FreeRDP_RdpSecurity, FALSE);
            freerdp_settings_set_bool(settings, FreeRDP_TlsSecurity, FALSE);
            freerdp_settings_set_bool(settings, FreeRDP_NlaSecurity, TRUE);
        }
        /* RDP security */
        else if (strcmp(arg, "/sec:rdp") == 0)
        {
            freerdp_settings_set_bool(settings, FreeRDP_RdpSecurity, TRUE);
            freerdp_settings_set_bool(settings, FreeRDP_TlsSecurity, FALSE);
            freerdp_settings_set_bool(settings, FreeRDP_NlaSecurity, FALSE);
        }
        /* Ignore certificate */
        else if (strcmp(arg, "/cert:ignore") == 0 || strcmp(arg, "/cert-ignore") == 0)
        {
            freerdp_settings_set_bool(settings, FreeRDP_IgnoreCertificate, TRUE);
        }
        /* GFX (graphics pipeline) */
        else if (strcmp(arg, "+gfx") == 0 || strcmp(arg, "/gfx") == 0)
        {
            freerdp_settings_set_bool(settings, FreeRDP_SupportGraphicsPipeline, TRUE);
        }
        else if (strcmp(arg, "-gfx") == 0)
        {
            freerdp_settings_set_bool(settings, FreeRDP_SupportGraphicsPipeline, FALSE);
        }
        /* Audio output */
        else if (strcmp(arg, "+sound") == 0 || strcmp(arg, "/sound") == 0)
        {
            freerdp_settings_set_bool(settings, FreeRDP_AudioPlayback, TRUE);
        }
        /* Clipboard */
        else if (strcmp(arg, "+clipboard") == 0 || strcmp(arg, "/clipboard") == 0)
        {
            freerdp_settings_set_bool(settings, FreeRDP_RedirectClipboard, TRUE);
        }
        else if (strcmp(arg, "-clipboard") == 0)
        {
            freerdp_settings_set_bool(settings, FreeRDP_RedirectClipboard, FALSE);
        }
    }

    /* Set default port if not specified */
    if (freerdp_settings_get_uint32(settings, FreeRDP_ServerPort) == 0)
    {
        freerdp_settings_set_uint32(settings, FreeRDP_ServerPort, 3389);
    }

    /* Set default color depth if not specified */
    if (freerdp_settings_get_uint32(settings, FreeRDP_ColorDepth) == 0)
    {
        freerdp_settings_set_uint32(settings, FreeRDP_ColorDepth, 32);
    }

    return 0;
}

/* Channel connected handler */
void freerdp_client_OnChannelConnectedEventHandler(void* context, 
                                                    const ChannelConnectedEventArgs* e)
{
    /* Placeholder - handled by application */
    (void)context;
    (void)e;
}

/* Channel disconnected handler */
void freerdp_client_OnChannelDisconnectedEventHandler(void* context,
                                                       const ChannelDisconnectedEventArgs* e)
{
    /* Placeholder - handled by application */
    (void)context;
    (void)e;
}

/*
 * ============================================================================
 * Auto-Reconnect Support
 * ============================================================================
 */

/* Get reconnect context from rdpContext */
static ClientReconnectContext* get_reconnect_context(rdpContext* context)
{
    if (!context || !context->instance)
        return NULL;
    
    /* Store in instance's unused field or allocate separately */
    return (ClientReconnectContext*)context->custom;
}

/* Initialize reconnect support */
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
    rctx->stopReconnect = FALSE;
    rctx->reconnectThread = NULL;
    
    context->custom = rctx;
    
    return TRUE;
}

/* Cleanup reconnect support */
void freerdp_client_reconnect_cleanup(rdpContext* context)
{
    ClientReconnectContext* rctx = get_reconnect_context(context);
    
    if (rctx)
    {
        rctx->stopReconnect = TRUE;
        
        if (rctx->reconnectThread)
        {
            WaitForSingleObject(rctx->reconnectThread, 5000);
            CloseHandle(rctx->reconnectThread);
        }
        
        free(rctx);
        context->custom = NULL;
    }
}

/* Attempt a single reconnection */
static BOOL attempt_reconnect(rdpContext* context)
{
    freerdp* instance;
    rdpSettings* settings;
    
    if (!context || !context->instance)
        return FALSE;
    
    instance = context->instance;
    settings = context->settings;
    
    /* Disconnect first if connected */
    if (freerdp_is_connected(instance))
    {
        freerdp_disconnect(instance);
    }
    
    /* Small delay before reconnecting */
    usleep(100000);  /* 100ms */
    
    /* Attempt to reconnect */
    if (!freerdp_connect(instance))
    {
        return FALSE;
    }
    
    return TRUE;
}

/* Perform reconnection with exponential backoff */
BOOL freerdp_client_auto_reconnect(rdpContext* context)
{
    ClientReconnectContext* rctx;
    UINT32 delay;
    UINT32 retries;
    
    if (!context)
        return FALSE;
    
    rctx = get_reconnect_context(context);
    if (!rctx || !rctx->reconnectEnabled)
    {
        /* Reconnect not configured, try once */
        return attempt_reconnect(context);
    }
    
    if (rctx->isReconnecting)
    {
        /* Already reconnecting */
        return FALSE;
    }
    
    rctx->isReconnecting = TRUE;
    delay = rctx->reconnectDelayMs;
    
    for (retries = 0; retries < rctx->reconnectMaxRetries && !rctx->stopReconnect; retries++)
    {
        rctx->reconnectCount = retries + 1;
        
        /* Log reconnection attempt */
        WLog_INFO("OHOS_RDP", "Reconnect attempt %u/%u (delay: %ums)", 
                  retries + 1, rctx->reconnectMaxRetries, delay);
        
        /* Wait before retry */
        if (retries > 0)
        {
            usleep(delay * 1000);
            
            /* Exponential backoff with max limit */
            delay = delay * 2;
            if (delay > RECONNECT_MAX_DELAY_MS)
                delay = RECONNECT_MAX_DELAY_MS;
        }
        
        /* Attempt reconnection */
        if (attempt_reconnect(context))
        {
            WLog_INFO("OHOS_RDP", "Reconnection successful after %u attempts", retries + 1);
            rctx->isReconnecting = FALSE;
            rctx->reconnectCount = 0;
            return TRUE;
        }
    }
    
    WLog_ERR("OHOS_RDP", "Reconnection failed after %u attempts", retries);
    rctx->isReconnecting = FALSE;
    return FALSE;
}

/* Check if reconnection is in progress */
BOOL freerdp_client_is_reconnecting(rdpContext* context)
{
    ClientReconnectContext* rctx = get_reconnect_context(context);
    return rctx ? rctx->isReconnecting : FALSE;
}

/* Get current reconnect attempt count */
UINT32 freerdp_client_get_reconnect_count(rdpContext* context)
{
    ClientReconnectContext* rctx = get_reconnect_context(context);
    return rctx ? rctx->reconnectCount : 0;
}

/* Stop ongoing reconnection */
void freerdp_client_stop_reconnect(rdpContext* context)
{
    ClientReconnectContext* rctx = get_reconnect_context(context);
    if (rctx)
    {
        rctx->stopReconnect = TRUE;
    }
}

/* Enable/disable auto-reconnect */
void freerdp_client_set_reconnect_enabled(rdpContext* context, BOOL enabled)
{
    ClientReconnectContext* rctx = get_reconnect_context(context);
    if (rctx)
    {
        rctx->reconnectEnabled = enabled;
    }
}

/*
 * ============================================================================
 * Audio (RDPSND) Support Helpers
 * ============================================================================
 */

/* Configure audio settings for RDPSND */
BOOL freerdp_client_configure_audio(rdpSettings* settings, BOOL playback, BOOL capture)
{
    if (!settings)
        return FALSE;
    
    /* Enable audio playback */
    if (playback)
    {
        freerdp_settings_set_bool(settings, FreeRDP_AudioPlayback, TRUE);
        freerdp_settings_set_bool(settings, FreeRDP_RemoteConsoleAudio, FALSE);
    }
    
    /* Enable audio capture (microphone) */
    if (capture)
    {
        freerdp_settings_set_bool(settings, FreeRDP_AudioCapture, TRUE);
    }
    
    return TRUE;
}

/* Set audio quality mode */
BOOL freerdp_client_set_audio_quality(rdpSettings* settings, int qualityMode)
{
    if (!settings)
        return FALSE;
    
    /* Quality modes:
     * 0 = Dynamic (auto-adjust based on bandwidth)
     * 1 = Medium 
     * 2 = High
     */
    switch (qualityMode)
    {
        case 0: /* Dynamic */
            freerdp_settings_set_uint32(settings, FreeRDP_ConnectionType, 
                                        CONNECTION_TYPE_AUTODETECT);
            break;
        case 1: /* Medium */
            freerdp_settings_set_uint32(settings, FreeRDP_ConnectionType, 
                                        CONNECTION_TYPE_BROADBAND_LOW);
            break;
        case 2: /* High */
            freerdp_settings_set_uint32(settings, FreeRDP_ConnectionType, 
                                        CONNECTION_TYPE_LAN);
            break;
        default:
            return FALSE;
    }
    
    return TRUE;
}

/*
 * ============================================================================
 * Connection Stability & Background Mode Support
 * ============================================================================
 */

/* Connection state tracking */
typedef struct {
    BOOL isConnected;
    BOOL isInBackground;
    BOOL audioOnlyMode;
    UINT64 lastActivityTime;
    UINT64 lastHeartbeatTime;
    UINT32 heartbeatIntervalMs;
    UINT32 connectionTimeoutMs;
    pOnConnectionLostCallback onConnectionLost;
    pOnReconnectingCallback onReconnecting;
    pOnReconnectedCallback onReconnected;
    void* callbackContext;
} ConnectionStateContext;

/* Callback types for connection events */
typedef void (*pOnConnectionLostCallback)(void* context, int errorCode);
typedef void (*pOnReconnectingCallback)(void* context, int attempt, int maxAttempts);
typedef void (*pOnReconnectedCallback)(void* context);

/* Get connection state context */
static ConnectionStateContext* get_connection_state(rdpContext* context)
{
    ClientReconnectContext* rctx = get_reconnect_context(context);
    if (!rctx)
        return NULL;
    
    /* Use the reconnect context to store connection state */
    return (ConnectionStateContext*)((char*)rctx + sizeof(ClientReconnectContext));
}

/* Initialize connection stability monitoring */
BOOL freerdp_client_init_connection_monitor(rdpContext* context, 
                                             UINT32 heartbeatIntervalMs,
                                             UINT32 connectionTimeoutMs)
{
    ClientReconnectContext* rctx;
    ConnectionStateContext* cstate;
    void* newMem;
    
    if (!context)
        return FALSE;
    
    rctx = get_reconnect_context(context);
    if (!rctx)
    {
        /* Initialize reconnect context first */
        if (!freerdp_client_reconnect_init(context, RECONNECT_MAX_RETRIES, RECONNECT_INITIAL_DELAY_MS))
            return FALSE;
        rctx = get_reconnect_context(context);
    }
    
    /* Extend the context to include connection state */
    newMem = realloc(rctx, sizeof(ClientReconnectContext) + sizeof(ConnectionStateContext));
    if (!newMem)
        return FALSE;
    
    context->custom = newMem;
    rctx = (ClientReconnectContext*)newMem;
    cstate = (ConnectionStateContext*)((char*)newMem + sizeof(ClientReconnectContext));
    
    memset(cstate, 0, sizeof(ConnectionStateContext));
    cstate->heartbeatIntervalMs = heartbeatIntervalMs > 0 ? heartbeatIntervalMs : 30000;
    cstate->connectionTimeoutMs = connectionTimeoutMs > 0 ? connectionTimeoutMs : 60000;
    cstate->isConnected = FALSE;
    cstate->isInBackground = FALSE;
    cstate->audioOnlyMode = FALSE;
    
    return TRUE;
}

/* Set connection event callbacks */
void freerdp_client_set_connection_callbacks(rdpContext* context,
                                              pOnConnectionLostCallback onLost,
                                              pOnReconnectingCallback onReconnecting,
                                              pOnReconnectedCallback onReconnected,
                                              void* callbackContext)
{
    ConnectionStateContext* cstate = get_connection_state(context);
    if (cstate)
    {
        cstate->onConnectionLost = onLost;
        cstate->onReconnecting = onReconnecting;
        cstate->onReconnected = onReconnected;
        cstate->callbackContext = callbackContext;
    }
}

/* Update connection state */
void freerdp_client_set_connected(rdpContext* context, BOOL connected)
{
    ConnectionStateContext* cstate = get_connection_state(context);
    if (cstate)
    {
        cstate->isConnected = connected;
        if (connected)
        {
            cstate->lastActivityTime = GetTickCount64();
            cstate->lastHeartbeatTime = cstate->lastActivityTime;
        }
    }
}

/* Check if connection is alive */
BOOL freerdp_client_check_connection_alive(rdpContext* context)
{
    ConnectionStateContext* cstate;
    UINT64 now;
    UINT64 timeSinceActivity;
    
    if (!context || !context->instance)
        return FALSE;
    
    /* Basic check via FreeRDP */
    if (freerdp_shall_disconnect_context(context))
        return FALSE;
    
    cstate = get_connection_state(context);
    if (!cstate || !cstate->isConnected)
        return FALSE;
    
    /* Check timeout */
    now = GetTickCount64();
    timeSinceActivity = now - cstate->lastActivityTime;
    
    if (timeSinceActivity > cstate->connectionTimeoutMs)
    {
        WLog_WARN("OHOS_RDP", "Connection timeout: no activity for %llu ms", timeSinceActivity);
        return FALSE;
    }
    
    return TRUE;
}

/* Update activity timestamp (call on any data received) */
void freerdp_client_update_activity(rdpContext* context)
{
    ConnectionStateContext* cstate = get_connection_state(context);
    if (cstate)
    {
        cstate->lastActivityTime = GetTickCount64();
    }
}

/*
 * ============================================================================
 * Background/Lockscreen Mode - Audio Only
 * ============================================================================
 */

/* Enter background mode (only audio, no graphics) */
BOOL freerdp_client_enter_background_mode(rdpContext* context)
{
    ConnectionStateContext* cstate;
    rdpUpdate* update;
    rdpSettings* settings;
    RECTANGLE_16 rect = { 0 };
    
    if (!context || !context->instance)
        return FALSE;
    
    cstate = get_connection_state(context);
    if (!cstate)
        return FALSE;
    
    if (cstate->isInBackground)
        return TRUE;  /* Already in background */
    
    update = context->update;
    settings = context->settings;
    
    if (!update || !settings)
        return FALSE;
    
    /* Suppress display output to save bandwidth */
    rect.left = 0;
    rect.top = 0;
    rect.right = (UINT16)freerdp_settings_get_uint32(settings, FreeRDP_DesktopWidth);
    rect.bottom = (UINT16)freerdp_settings_get_uint32(settings, FreeRDP_DesktopHeight);
    
    if (update->SuppressOutput)
    {
        /* FALSE = suppress display updates */
        if (!update->SuppressOutput(context, FALSE, &rect))
        {
            WLog_ERR("OHOS_RDP", "Failed to suppress output for background mode");
            return FALSE;
        }
    }
    
    /* Disable graphics decoding */
    freerdp_settings_set_bool(settings, FreeRDP_DeactivateClientDecoding, TRUE);
    
    cstate->isInBackground = TRUE;
    cstate->audioOnlyMode = TRUE;
    
    WLog_INFO("OHOS_RDP", "Entered background mode - audio only");
    return TRUE;
}

/* Exit background mode (resume graphics) */
BOOL freerdp_client_exit_background_mode(rdpContext* context)
{
    ConnectionStateContext* cstate;
    rdpUpdate* update;
    rdpSettings* settings;
    RECTANGLE_16 rect = { 0 };
    
    if (!context || !context->instance)
        return FALSE;
    
    cstate = get_connection_state(context);
    if (!cstate)
        return FALSE;
    
    if (!cstate->isInBackground)
        return TRUE;  /* Already in foreground */
    
    update = context->update;
    settings = context->settings;
    
    if (!update || !settings)
        return FALSE;
    
    /* Re-enable graphics decoding */
    freerdp_settings_set_bool(settings, FreeRDP_DeactivateClientDecoding, FALSE);
    
    /* Resume display output */
    rect.left = 0;
    rect.top = 0;
    rect.right = (UINT16)freerdp_settings_get_uint32(settings, FreeRDP_DesktopWidth);
    rect.bottom = (UINT16)freerdp_settings_get_uint32(settings, FreeRDP_DesktopHeight);
    
    if (update->SuppressOutput)
    {
        /* TRUE = allow display updates */
        if (!update->SuppressOutput(context, TRUE, &rect))
        {
            WLog_ERR("OHOS_RDP", "Failed to resume output from background mode");
            return FALSE;
        }
    }
    
    cstate->isInBackground = FALSE;
    cstate->audioOnlyMode = FALSE;
    
    WLog_INFO("OHOS_RDP", "Exited background mode - graphics resumed");
    return TRUE;
}

/* Check if in background mode */
BOOL freerdp_client_is_in_background(rdpContext* context)
{
    ConnectionStateContext* cstate = get_connection_state(context);
    return cstate ? cstate->isInBackground : FALSE;
}

/* Check if audio only mode is active */
BOOL freerdp_client_is_audio_only(rdpContext* context)
{
    ConnectionStateContext* cstate = get_connection_state(context);
    return cstate ? cstate->audioOnlyMode : FALSE;
}

/*
 * ============================================================================
 * Auto-reconnect with Connection Lost Detection
 * ============================================================================
 */

/* Handle connection lost event */
BOOL freerdp_client_on_connection_lost(rdpContext* context, int errorCode)
{
    ClientReconnectContext* rctx;
    ConnectionStateContext* cstate;
    
    if (!context)
        return FALSE;
    
    rctx = get_reconnect_context(context);
    cstate = get_connection_state(context);
    
    /* Mark as disconnected */
    if (cstate)
    {
        cstate->isConnected = FALSE;
        
        /* Notify callback */
        if (cstate->onConnectionLost)
        {
            cstate->onConnectionLost(cstate->callbackContext, errorCode);
        }
    }
    
    /* Attempt auto-reconnect if enabled */
    if (rctx && rctx->reconnectEnabled && !rctx->stopReconnect)
    {
        WLog_INFO("OHOS_RDP", "Connection lost (error=%d), attempting auto-reconnect...", errorCode);
        
        for (UINT32 i = 0; i < rctx->reconnectMaxRetries && !rctx->stopReconnect; i++)
        {
            /* Notify reconnecting callback */
            if (cstate && cstate->onReconnecting)
            {
                cstate->onReconnecting(cstate->callbackContext, i + 1, rctx->reconnectMaxRetries);
            }
            
            if (freerdp_client_auto_reconnect(context))
            {
                /* Reconnection successful */
                if (cstate)
                {
                    cstate->isConnected = TRUE;
                    
                    if (cstate->onReconnected)
                    {
                        cstate->onReconnected(cstate->callbackContext);
                    }
                }
                return TRUE;
            }
        }
        
        WLog_ERR("OHOS_RDP", "Auto-reconnect failed after all attempts");
    }
    
    return FALSE;
}
