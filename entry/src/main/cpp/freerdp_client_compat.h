/*
 * FreeRDP Client Compatibility Layer for HarmonyOS
 * Header file
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
 * Core client context functions (replacement for freerdp-client library)
 */

/* Create a new client context */
rdpContext* freerdp_client_context_new(const RDP_CLIENT_ENTRY_POINTS* pEntryPoints);

/* Free client context */
void freerdp_client_context_free(rdpContext* context);

/* Start client (initialize channels) */
int freerdp_client_start(rdpContext* context);

/* Stop client (cleanup channels) */
int freerdp_client_stop(rdpContext* context);

/* Parse command line settings */
int freerdp_client_settings_parse_command_line(rdpSettings* settings, int argc, 
                                                char** argv, BOOL allowUnknown);

/* Channel event handlers */
void freerdp_client_OnChannelConnectedEventHandler(void* context, 
                                                    const ChannelConnectedEventArgs* e);
void freerdp_client_OnChannelDisconnectedEventHandler(void* context,
                                                       const ChannelDisconnectedEventArgs* e);

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

#ifdef __cplusplus
}
#endif

#endif /* FREERDP_CLIENT_COMPAT_H */
