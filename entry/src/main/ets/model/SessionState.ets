/*
 * HarmonyOS FreeRDP Session State
 * 
 * Manages the state of an RDP session
 * 
 * Copyright 2026 FreeRDP HarmonyOS Port
 */

import { BookmarkBase } from './BookmarkBase';
import { UIEventListener } from '../services/LibFreeRDP';
import image from '@ohos.multimedia.image';

/**
 * Session connection state
 */
export enum ConnectionState {
  DISCONNECTED = 0,
  CONNECTING = 1,
  CONNECTED = 2,
  DISCONNECTING = 3,
  RECONNECTING = 4
}

/**
 * Session State
 * Holds all state information for an active RDP session
 */
export class SessionState {
  // Session identification
  private instance: number = 0;
  private bookmark: BookmarkBase;
  
  // Connection state
  private connectionState: ConnectionState = ConnectionState.DISCONNECTED;
  
  // Display properties
  private desktopWidth: number = 0;
  private desktopHeight: number = 0;
  private colorDepth: number = 0;
  
  // Graphics buffer
  private pixelMap: image.PixelMap | null = null;
  
  // UI event listener
  private uiEventListener: UIEventListener | null = null;
  
  // Session statistics
  private connectTime: number = 0;
  private lastUpdateTime: number = 0;
  private updateCount: number = 0;
  
  // Background mode
  private isInBackground: boolean = false;
  private isScreenLocked: boolean = false;
  
  constructor(bookmark: BookmarkBase) {
    this.bookmark = bookmark.clone();
  }

  /**
   * Get the FreeRDP instance handle
   */
  getInstance(): number {
    return this.instance;
  }

  /**
   * Set the FreeRDP instance handle
   */
  setInstance(instance: number): void {
    this.instance = instance;
  }

  /**
   * Get the bookmark associated with this session
   */
  getBookmark(): BookmarkBase {
    return this.bookmark;
  }

  /**
   * Get current connection state
   */
  getConnectionState(): ConnectionState {
    return this.connectionState;
  }

  /**
   * Set connection state
   */
  setConnectionState(state: ConnectionState): void {
    this.connectionState = state;
    
    if (state === ConnectionState.CONNECTED) {
      this.connectTime = Date.now();
    }
  }

  /**
   * Check if session is connected
   */
  isConnected(): boolean {
    return this.connectionState === ConnectionState.CONNECTED;
  }

  /**
   * Get desktop width
   */
  getDesktopWidth(): number {
    return this.desktopWidth;
  }

  /**
   * Get desktop height
   */
  getDesktopHeight(): number {
    return this.desktopHeight;
  }

  /**
   * Get color depth
   */
  getColorDepth(): number {
    return this.colorDepth;
  }

  /**
   * Update display settings
   */
  updateDisplaySettings(width: number, height: number, bpp: number): void {
    this.desktopWidth = width;
    this.desktopHeight = height;
    this.colorDepth = bpp;
    
    console.info(`SessionState: Display settings updated: ${width}x${height}@${bpp}bpp`);
  }

  /**
   * Get the pixel map for rendering
   */
  getPixelMap(): image.PixelMap | null {
    return this.pixelMap;
  }

  /**
   * Set the pixel map
   */
  setPixelMap(pixelMap: image.PixelMap | null): void {
    // Release old pixel map if exists
    if (this.pixelMap) {
      this.pixelMap.release();
    }
    this.pixelMap = pixelMap;
  }

  /**
   * Create or recreate pixel map with current dimensions
   */
  async createPixelMap(): Promise<void> {
    if (this.desktopWidth <= 0 || this.desktopHeight <= 0) {
      console.warn('SessionState: Cannot create pixel map - invalid dimensions');
      return;
    }

    // Release old pixel map
    if (this.pixelMap) {
      this.pixelMap.release();
      this.pixelMap = null;
    }

    try {
      const initializationOptions: image.InitializationOptions = {
        size: {
          width: this.desktopWidth,
          height: this.desktopHeight
        },
        pixelFormat: image.PixelMapFormat.RGBA_8888,
        editable: true
      };

      this.pixelMap = await image.createPixelMap(new ArrayBuffer(0), initializationOptions);
      console.info(`SessionState: PixelMap created: ${this.desktopWidth}x${this.desktopHeight}`);
    } catch (error) {
      console.error('SessionState: Failed to create pixel map:', error);
    }
  }

  /**
   * Set UI event listener
   */
  setUIEventListener(listener: UIEventListener | null): void {
    this.uiEventListener = listener;
  }

  /**
   * Get UI event listener
   */
  getUIEventListener(): UIEventListener | null {
    return this.uiEventListener;
  }

  /**
   * Record a graphics update
   */
  recordUpdate(): void {
    this.lastUpdateTime = Date.now();
    this.updateCount++;
  }

  /**
   * Get connection duration in milliseconds
   */
  getConnectionDuration(): number {
    if (this.connectTime === 0) {
      return 0;
    }
    return Date.now() - this.connectTime;
  }

  /**
   * Get update count
   */
  getUpdateCount(): number {
    return this.updateCount;
  }

  /**
   * Get time since last update in milliseconds
   */
  getTimeSinceLastUpdate(): number {
    if (this.lastUpdateTime === 0) {
      return 0;
    }
    return Date.now() - this.lastUpdateTime;
  }

  /**
   * Set background mode
   */
  setInBackground(inBackground: boolean): void {
    this.isInBackground = inBackground;
    console.info(`SessionState: Background mode: ${inBackground}`);
  }

  /**
   * Check if in background mode
   */
  getInBackground(): boolean {
    return this.isInBackground;
  }

  /**
   * Set screen locked state
   */
  setScreenLocked(locked: boolean): void {
    this.isScreenLocked = locked;
    console.info(`SessionState: Screen locked: ${locked}`);
  }

  /**
   * Check if screen is locked
   */
  getScreenLocked(): boolean {
    return this.isScreenLocked;
  }

  /**
   * Check if graphics should be suppressed (background or locked)
   */
  shouldSuppressGraphics(): boolean {
    return this.isInBackground || this.isScreenLocked;
  }

  /**
   * Clean up session resources
   */
  cleanup(): void {
    console.info('SessionState: Cleaning up session resources');
    
    if (this.pixelMap) {
      this.pixelMap.release();
      this.pixelMap = null;
    }
    
    this.uiEventListener = null;
    this.connectionState = ConnectionState.DISCONNECTED;
    this.instance = 0;
  }

  /**
   * Get session info string for logging
   */
  toString(): string {
    return `SessionState[instance=${this.instance}, ` +
           `state=${ConnectionState[this.connectionState]}, ` +
           `${this.desktopWidth}x${this.desktopHeight}@${this.colorDepth}bpp, ` +
           `updates=${this.updateCount}]`;
  }
}

/**
 * Global session manager
 */
export class GlobalSessionManager {
  private static sessions: Map<number, SessionState> = new Map();

  /**
   * Register a new session
   */
  static registerSession(instance: number, session: SessionState): void {
    GlobalSessionManager.sessions.set(instance, session);
    console.info(`GlobalSessionManager: Registered session ${instance}`);
  }

  /**
   * Get a session by instance
   */
  static getSession(instance: number): SessionState | undefined {
    return GlobalSessionManager.sessions.get(instance);
  }

  /**
   * Remove a session
   */
  static removeSession(instance: number): void {
    const session = GlobalSessionManager.sessions.get(instance);
    if (session) {
      session.cleanup();
      GlobalSessionManager.sessions.delete(instance);
      console.info(`GlobalSessionManager: Removed session ${instance}`);
    }
  }

  /**
   * Get all active sessions
   */
  static getAllSessions(): SessionState[] {
    const result: SessionState[] = [];
    GlobalSessionManager.sessions.forEach((session: SessionState) => {
      result.push(session);
    });
    return result;
  }

  /**
   * Get count of active sessions
   */
  static getSessionCount(): number {
    return GlobalSessionManager.sessions.size;
  }

  /**
   * Check if any session is connected
   */
  static hasConnectedSession(): boolean {
    let hasConnected = false;
    GlobalSessionManager.sessions.forEach((session: SessionState) => {
      if (session.isConnected()) {
        hasConnected = true;
      }
    });
    return hasConnected;
  }

  /**
   * Clean up all sessions
   */
  static cleanupAll(): void {
    console.info('GlobalSessionManager: Cleaning up all sessions');
    GlobalSessionManager.sessions.forEach((session: SessionState) => {
      session.cleanup();
    });
    GlobalSessionManager.sessions.clear();
  }
}

export default SessionState;
