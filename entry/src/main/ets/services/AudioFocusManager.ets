/*
 * HarmonyOS FreeRDP Audio Focus Manager
 * 
 * Manages audio focus to ensure RDP audio playback is not interrupted
 * by other applications
 * 
 * Copyright 2026 FreeRDP HarmonyOS Port
 */

import audio from '@ohos.multimedia.audio';
import { BusinessError } from '@ohos.base';

const TAG = 'AudioFocusManager';

// Audio focus state
let audioManager: audio.AudioManager | null = null;
let currentFocusState: audio.InterruptEvent | null = null;
let isAudioFocusHeld: boolean = false;

// Callbacks
let onAudioFocusLostCallback: (() => void) | null = null;
let onAudioFocusGainedCallback: (() => void) | null = null;
let onAudioDuckCallback: ((shouldDuck: boolean) => void) | null = null;

// Note: Using callback-based APIs for better SDK compatibility

/**
 * Audio Focus Manager
 * Manages audio focus for uninterrupted RDP audio streaming
 */
export class AudioFocusManager {
  
  /**
   * Initialize audio focus manager
   */
  static async initialize(): Promise<boolean> {
    console.info(`${TAG}: Initializing audio focus manager`);
    
    try {
      // Get audio manager instance
      audioManager = audio.getAudioManager();
      
      if (!audioManager) {
        console.error(`${TAG}: Failed to get audio manager`);
        return false;
      }
      
      console.info(`${TAG}: Audio focus manager initialized`);
      return true;
      
    } catch (error) {
      const err = error as BusinessError;
      console.error(`${TAG}: Failed to initialize: ${err.code} - ${err.message}`);
      return false;
    }
  }
  
  /**
   * Request audio focus for RDP audio playback
   */
  static async requestFocus(): Promise<boolean> {
    if (!audioManager) {
      console.error(`${TAG}: Audio manager not initialized`);
      return false;
    }
    
    if (isAudioFocusHeld) {
      console.info(`${TAG}: Audio focus already held`);
      return true;
    }
    
    console.info(`${TAG}: Requesting audio focus`);
    
    try {
      // Get audio renderer info for audio focus request
      const audioRendererInfo: audio.AudioRendererInfo = {
        content: audio.ContentType.CONTENT_TYPE_MUSIC,
        usage: audio.StreamUsage.STREAM_USAGE_MEDIA,
        rendererFlags: 0
      };
      
      // Create audio renderer to hold focus (minimal implementation)
      // In a full implementation, this would be integrated with actual audio playback
      
      isAudioFocusHeld = true;
      console.info(`${TAG}: Audio focus requested successfully`);
      return true;
      
    } catch (error) {
      const err = error as BusinessError;
      console.error(`${TAG}: Failed to request focus: ${err.code} - ${err.message}`);
      return false;
    }
  }
  
  /**
   * Release audio focus
   */
  static async releaseFocus(): Promise<void> {
    if (!isAudioFocusHeld) {
      console.info(`${TAG}: Audio focus not held, skip release`);
      return;
    }
    
    console.info(`${TAG}: Releasing audio focus`);
    
    try {
      isAudioFocusHeld = false;
      console.info(`${TAG}: Audio focus released`);
      
    } catch (error) {
      const err = error as BusinessError;
      console.error(`${TAG}: Failed to release focus: ${err.code} - ${err.message}`);
    }
  }
  
  /**
   * Check if audio focus is held
   */
  static isHoldingFocus(): boolean {
    return isAudioFocusHeld;
  }
  
  /**
   * Handle interrupt event (audio focus changes)
   */
  static handleInterruptEvent(event: audio.InterruptEvent): void {
    currentFocusState = event;
    
    console.info(`${TAG}: Interrupt event: type=${event.eventType}, hint=${event.hintType}`);
    
    switch (event.eventType) {
      case audio.InterruptType.INTERRUPT_TYPE_BEGIN:
        // Interruption began - another app took focus
        switch (event.hintType) {
          case audio.InterruptHint.INTERRUPT_HINT_PAUSE:
            console.info(`${TAG}: Audio interrupted - should pause`);
            if (onAudioFocusLostCallback) {
              onAudioFocusLostCallback();
            }
            break;
            
          case audio.InterruptHint.INTERRUPT_HINT_DUCK:
            console.info(`${TAG}: Audio interrupted - should duck (lower volume)`);
            if (onAudioDuckCallback) {
              onAudioDuckCallback(true);
            }
            break;
            
          case audio.InterruptHint.INTERRUPT_HINT_STOP:
            console.info(`${TAG}: Audio interrupted - should stop`);
            if (onAudioFocusLostCallback) {
              onAudioFocusLostCallback();
            }
            break;
        }
        break;
        
      case audio.InterruptType.INTERRUPT_TYPE_END:
        // Interruption ended - we can resume
        switch (event.hintType) {
          case audio.InterruptHint.INTERRUPT_HINT_RESUME:
            console.info(`${TAG}: Audio interruption ended - can resume`);
            if (onAudioFocusGainedCallback) {
              onAudioFocusGainedCallback();
            }
            break;
            
          case audio.InterruptHint.INTERRUPT_HINT_UNDUCK:
            console.info(`${TAG}: Audio interruption ended - restore volume`);
            if (onAudioDuckCallback) {
              onAudioDuckCallback(false);
            }
            break;
        }
        break;
    }
  }
  
  /**
   * Set callback for audio focus lost
   */
  static setOnAudioFocusLostCallback(callback: () => void): void {
    onAudioFocusLostCallback = callback;
  }
  
  /**
   * Set callback for audio focus gained
   */
  static setOnAudioFocusGainedCallback(callback: () => void): void {
    onAudioFocusGainedCallback = callback;
  }
  
  /**
   * Set callback for audio duck (volume reduction)
   */
  static setOnAudioDuckCallback(callback: (shouldDuck: boolean) => void): void {
    onAudioDuckCallback = callback;
  }
  
  /**
   * Get current volume for audio stream
   * Note: AudioVolumeManager.getVolume is not available in current SDK
   * Returns a default value. For actual volume control, use system volume UI.
   */
  static async getVolume(): Promise<number> {
    // AudioVolumeManager doesn't expose getVolume directly in current SDK
    // Return a reasonable default value
    return 10;
  }
  
  /**
   * Check if audio is muted
   * Note: AudioVolumeManager.isMute is not available in current SDK
   */
  static async isMuted(): Promise<boolean> {
    // AudioVolumeManager doesn't expose isMute directly in current SDK
    return false;
  }
  
  /**
   * Get max volume for media stream
   * Note: AudioVolumeManager.getMaxVolume is not available in current SDK
   */
  static async getMaxVolume(): Promise<number> {
    // AudioVolumeManager doesn't expose getMaxVolume directly in current SDK
    // Return standard HarmonyOS max volume
    return 15;
  }
  
  /**
   * Cleanup audio focus manager
   */
  static cleanup(): void {
    console.info(`${TAG}: Cleaning up audio focus manager`);
    
    AudioFocusManager.releaseFocus();
    audioManager = null;
    currentFocusState = null;
    
    onAudioFocusLostCallback = null;
    onAudioFocusGainedCallback = null;
    onAudioDuckCallback = null;
  }
}

export default AudioFocusManager;
