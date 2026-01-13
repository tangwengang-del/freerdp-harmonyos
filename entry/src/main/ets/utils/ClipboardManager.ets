/*
 * HarmonyOS FreeRDP Clipboard Manager
 * 
 * Manages clipboard synchronization between local and remote
 * 
 * Copyright 2026 FreeRDP HarmonyOS Port
 */

import pasteboard from '@ohos.pasteboard';
import { BusinessError } from '@ohos.base';
import LibFreeRDP from '../services/LibFreeRDP';

const TAG = 'ClipboardManager';

/**
 * Clipboard change listener
 */
export interface ClipboardChangeListener {
  onLocalClipboardChanged(data: string): void;
  onRemoteClipboardChanged(data: string): void;
}

/**
 * Clipboard Manager
 * Handles clipboard synchronization between HarmonyOS and remote desktop
 */
export class ClipboardManager {
  private instance: number = 0;
  private listener: ClipboardChangeListener | null = null;
  private lastLocalData: string = '';
  private lastRemoteData: string = '';
  private isEnabled: boolean = true;
  private pasteboardObserver: pasteboard.PasteboardObserver | null = null;

  /**
   * Initialize clipboard manager for a session
   */
  initialize(instance: number): void {
    this.instance = instance;
    this.startLocalClipboardMonitoring();
    console.info(`${TAG}: Initialized for instance ${instance}`);
  }

  /**
   * Uninitialize clipboard manager
   */
  uninitialize(): void {
    this.stopLocalClipboardMonitoring();
    this.instance = 0;
    console.info(`${TAG}: Uninitialized`);
  }

  /**
   * Set clipboard change listener
   */
  setListener(listener: ClipboardChangeListener): void {
    this.listener = listener;
  }

  /**
   * Enable or disable clipboard sync
   */
  setEnabled(enabled: boolean): void {
    this.isEnabled = enabled;
    console.info(`${TAG}: Clipboard sync ${enabled ? 'enabled' : 'disabled'}`);
  }

  /**
   * Check if clipboard sync is enabled
   */
  isClipboardEnabled(): boolean {
    return this.isEnabled;
  }

  /**
   * Start monitoring local clipboard changes
   */
  private startLocalClipboardMonitoring(): void {
    try {
      const systemPasteboard = pasteboard.getSystemPasteboard();
      
      // Create observer
      this.pasteboardObserver = {
        callback: () => {
          this.onLocalClipboardChanged();
        }
      };
      
      // Register observer
      systemPasteboard.on('update', this.pasteboardObserver.callback);
      console.info(`${TAG}: Local clipboard monitoring started`);
    } catch (error) {
      const err = error as BusinessError;
      console.error(`${TAG}: Failed to start clipboard monitoring: ${err.message}`);
    }
  }

  /**
   * Stop monitoring local clipboard changes
   */
  private stopLocalClipboardMonitoring(): void {
    try {
      if (this.pasteboardObserver) {
        const systemPasteboard = pasteboard.getSystemPasteboard();
        systemPasteboard.off('update', this.pasteboardObserver.callback);
        this.pasteboardObserver = null;
        console.info(`${TAG}: Local clipboard monitoring stopped`);
      }
    } catch (error) {
      const err = error as BusinessError;
      console.error(`${TAG}: Failed to stop clipboard monitoring: ${err.message}`);
    }
  }

  /**
   * Handle local clipboard change
   */
  private async onLocalClipboardChanged(): Promise<void> {
    if (!this.isEnabled || this.instance === 0) return;
    
    try {
      const systemPasteboard = pasteboard.getSystemPasteboard();
      const pasteData = await systemPasteboard.getData();
      
      if (!pasteData) return;
      
      // Get text content
      const primaryText = pasteData.getPrimaryText();
      if (!primaryText || primaryText === this.lastLocalData) return;
      
      this.lastLocalData = primaryText;
      
      // Send to remote
      if (primaryText !== this.lastRemoteData) {
        LibFreeRDP.sendClipboardData(this.instance, primaryText);
        console.info(`${TAG}: Sent local clipboard to remote (${primaryText.length} chars)`);
        
        if (this.listener) {
          this.listener.onLocalClipboardChanged(primaryText);
        }
      }
    } catch (error) {
      const err = error as BusinessError;
      console.error(`${TAG}: Failed to get local clipboard: ${err.message}`);
    }
  }

  /**
   * Handle remote clipboard change (called from native callback)
   */
  onRemoteClipboardReceived(data: string): void {
    if (!this.isEnabled || !data) return;
    
    // Avoid echo
    if (data === this.lastRemoteData || data === this.lastLocalData) return;
    
    this.lastRemoteData = data;
    
    // Update local clipboard
    this.setLocalClipboard(data);
    
    console.info(`${TAG}: Received remote clipboard (${data.length} chars)`);
    
    if (this.listener) {
      this.listener.onRemoteClipboardChanged(data);
    }
  }

  /**
   * Set local clipboard content
   */
  private async setLocalClipboard(data: string): Promise<void> {
    try {
      const pasteData = pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, data);
      const systemPasteboard = pasteboard.getSystemPasteboard();
      
      // Temporarily disable monitoring to avoid echo
      this.lastLocalData = data;
      
      await systemPasteboard.setData(pasteData);
      console.info(`${TAG}: Set local clipboard (${data.length} chars)`);
    } catch (error) {
      const err = error as BusinessError;
      console.error(`${TAG}: Failed to set local clipboard: ${err.message}`);
    }
  }

  /**
   * Get current local clipboard content
   */
  async getLocalClipboard(): Promise<string> {
    try {
      const systemPasteboard = pasteboard.getSystemPasteboard();
      const pasteData = await systemPasteboard.getData();
      
      if (pasteData) {
        return pasteData.getPrimaryText() || '';
      }
    } catch (error) {
      const err = error as BusinessError;
      console.error(`${TAG}: Failed to get local clipboard: ${err.message}`);
    }
    return '';
  }

  /**
   * Clear local clipboard
   */
  async clearLocalClipboard(): Promise<void> {
    try {
      const systemPasteboard = pasteboard.getSystemPasteboard();
      await systemPasteboard.clearData();
      this.lastLocalData = '';
      console.info(`${TAG}: Local clipboard cleared`);
    } catch (error) {
      const err = error as BusinessError;
      console.error(`${TAG}: Failed to clear local clipboard: ${err.message}`);
    }
  }

  /**
   * Send current local clipboard to remote
   */
  async syncToRemote(): Promise<void> {
    if (!this.isEnabled || this.instance === 0) return;
    
    const data = await this.getLocalClipboard();
    if (data && data !== this.lastRemoteData) {
      LibFreeRDP.sendClipboardData(this.instance, data);
      console.info(`${TAG}: Synced local clipboard to remote (${data.length} chars)`);
    }
  }
}

export default ClipboardManager;
