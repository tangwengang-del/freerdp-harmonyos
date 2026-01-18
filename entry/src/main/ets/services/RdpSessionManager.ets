/*
 * HarmonyOS FreeRDP Session Manager
 * 
 * Comprehensive session management integrating:
 * - Background service (foreground service for audio)
 * - Audio focus management
 * - Network monitoring and auto-reconnect
 * - Battery optimization handling
 * - Screen lock/unlock handling
 * 
 * Copyright 2026 FreeRDP HarmonyOS Port
 */

import common from '@ohos.app.ability.common';
import { BusinessError } from '@ohos.base';
import power from '@ohos.power';
import screenLock from '@ohos.screenLock';
import batteryInfo from '@ohos.batteryInfo';

import LibFreeRDP from './LibFreeRDP';
import RdpBackgroundService from './RdpBackgroundService';
import NetworkManager, { SessionInfo } from './NetworkManager';
import AudioFocusManager from './AudioFocusManager';

const TAG = 'RdpSessionManager';

// Session state
let currentInstance: number = 0;
let isSessionActive: boolean = false;
let isInBackground: boolean = false;
let sessionIsScreenLocked: boolean = false;
let backgroundService: RdpBackgroundService | null = null;

// Keepalive timer
let keepaliveTimerId: number = -1;
const KEEPALIVE_INTERVAL_MS = 25000; // 25 seconds

// Callbacks
let onSessionStateChangedCallback: ((state: SessionState) => void) | null = null;
let onReconnectingCallback: ((attempt: number, maxAttempts: number) => void) | null = null;

/**
 * Session state enum
 */
export enum SessionState {
  DISCONNECTED = 0,
  CONNECTING = 1,
  CONNECTED = 2,
  RECONNECTING = 3,
  BACKGROUND = 4,
  LOCKED = 5,
  ERROR = 6
}

/**
 * RDP Session Manager
 * Central manager for RDP session lifecycle
 */
export class RdpSessionManager {
  private context: common.UIAbilityContext;
  
  constructor(context: common.UIAbilityContext) {
    this.context = context;
    backgroundService = new RdpBackgroundService(context);
  }
  
  /**
   * Initialize session manager and all subsystems
   */
  async initialize(): Promise<boolean> {
    console.info(`${TAG}: Initializing session manager`);
    
    try {
      // Initialize network manager
      await NetworkManager.initialize();
      
      // Initialize audio focus manager
      await AudioFocusManager.initialize();
      
      // Set up network callbacks
      NetworkManager.setOnNetworkLostCallback(() => {
        console.warn(`${TAG}: Network lost`);
        this.handleNetworkLost();
      });
      
      NetworkManager.setOnNetworkAvailableCallback(() => {
        console.info(`${TAG}: Network available`);
        this.handleNetworkRestored();
      });
      
      NetworkManager.setOnReconnectSuccessCallback(() => {
        console.info(`${TAG}: Reconnection successful`);
        this.notifyStateChanged(SessionState.CONNECTED);
      });
      
      NetworkManager.setOnReconnectFailureCallback((error: string) => {
        console.error(`${TAG}: Reconnection failed: ${error}`);
        this.notifyStateChanged(SessionState.ERROR);
      });
      
      // Set up audio focus callbacks
      AudioFocusManager.setOnAudioFocusLostCallback(() => {
        console.warn(`${TAG}: Audio focus lost`);
        // Don't stop audio, just log - RDP audio should continue
      });
      
      AudioFocusManager.setOnAudioFocusGainedCallback(() => {
        console.info(`${TAG}: Audio focus gained`);
      });
      
      // Set up background service restart callback
      RdpBackgroundService.setOnServiceRestartCallback(() => {
        console.info(`${TAG}: Background service restart triggered`);
        if (isSessionActive && backgroundService) {
          backgroundService.start();
        }
      });
      
      // Register screen lock listener
      await this.registerScreenLockListener();
      
      console.info(`${TAG}: Session manager initialized`);
      return true;
      
    } catch (error) {
      const err = error as BusinessError;
      console.error(`${TAG}: Failed to initialize: ${err.code} - ${err.message}`);
      return false;
    }
  }
  
  /**
   * Start a new RDP session
   */
  async startSession(instance: number, sessionInfo: SessionInfo): Promise<boolean> {
    console.info(`${TAG}: Starting session`);
    
    currentInstance = instance;
    isSessionActive = true;
    
    // Save session info for reconnection
    NetworkManager.setSessionInfo(sessionInfo);
    
    // Request audio focus
    await AudioFocusManager.requestFocus();
    
    // Configure TCP keepalive
    LibFreeRDP.setTcpKeepalive(instance, true, 30, 5, 3);
    
    // Configure auto-reconnect
    LibFreeRDP.setAutoReconnect(instance, true, 5, 2000);
    
    this.notifyStateChanged(SessionState.CONNECTED);
    
    console.info(`${TAG}: Session started`);
    return true;
  }
  
  /**
   * Stop the current RDP session
   */
  async stopSession(): Promise<void> {
    console.info(`${TAG}: Stopping session`);
    
    isSessionActive = false;
    
    // Stop keepalive timer
    this.stopKeepaliveTimer();
    
    // Stop background service
    if (backgroundService) {
      await backgroundService.stop();
    }
    
    // Release audio focus
    await AudioFocusManager.releaseFocus();
    
    // Clear session info
    NetworkManager.clearSessionInfo();
    
    // Disconnect if still connected
    if (currentInstance !== 0 && LibFreeRDP.isInstanceConnected(currentInstance)) {
      LibFreeRDP.disconnect(currentInstance);
    }
    
    currentInstance = 0;
    isInBackground = false;
    
    this.notifyStateChanged(SessionState.DISCONNECTED);
    
    console.info(`${TAG}: Session stopped`);
  }
  
  /**
   * Handle app going to background
   */
  async onBackground(): Promise<void> {
    if (!isSessionActive) {
      console.info(`${TAG}: No active session, skip background handling`);
      return;
    }
    
    console.info(`${TAG}: App going to background`);
    isInBackground = true;
    
    // Start background service for audio
    if (backgroundService) {
      const started = await backgroundService.start();
      if (started) {
        console.info(`${TAG}: Background service started`);
      }
    }
    
    // Enter background mode in native code
    if (currentInstance !== 0) {
      LibFreeRDP.enterBackgroundMode(currentInstance);
    }
    
    // Start keepalive timer
    this.startKeepaliveTimer();
    
    this.notifyStateChanged(SessionState.BACKGROUND);
  }
  
  /**
   * Handle app going to foreground
   */
  async onForeground(): Promise<void> {
    if (!isSessionActive) {
      console.info(`${TAG}: No active session, skip foreground handling`);
      return;
    }
    
    console.info(`${TAG}: App going to foreground`);
    isInBackground = false;
    
    // Stop keepalive timer
    this.stopKeepaliveTimer();
    
    // Exit background mode in native code
    if (currentInstance !== 0) {
      LibFreeRDP.exitBackgroundMode(currentInstance);
      
      // Request full screen refresh
      setTimeout(() => {
        if (currentInstance !== 0) {
          LibFreeRDP.requestRefresh(currentInstance);
        }
      }, 100);
    }
    
    // Stop background service (optional - can keep running for stability)
    // if (backgroundService) {
    //   await backgroundService.stop();
    // }
    
    this.notifyStateChanged(SessionState.CONNECTED);
  }
  
  /**
   * Handle screen lock
   */
  async onScreenLocked(): Promise<void> {
    if (!isSessionActive) {
      return;
    }
    
    console.info(`${TAG}: Screen locked`);
    sessionIsScreenLocked = true;
    
    // Start background service if not already running
    if (backgroundService && !RdpBackgroundService.isServiceRunning()) {
      await backgroundService.start();
    }
    
    // Enter background mode
    if (currentInstance !== 0) {
      LibFreeRDP.enterBackgroundMode(currentInstance);
    }
    
    // Start keepalive
    this.startKeepaliveTimer();
    
    this.notifyStateChanged(SessionState.LOCKED);
  }
  
  /**
   * Handle screen unlock
   */
  async onScreenUnlocked(): Promise<void> {
    if (!isSessionActive) {
      return;
    }
    
    console.info(`${TAG}: Screen unlocked`);
    sessionIsScreenLocked = false;
    
    // If app is in foreground, exit background mode
    if (!isInBackground) {
      this.stopKeepaliveTimer();
      
      if (currentInstance !== 0) {
        LibFreeRDP.exitBackgroundMode(currentInstance);
        
        // Request full screen refresh
        setTimeout(() => {
          if (currentInstance !== 0) {
            LibFreeRDP.requestRefresh(currentInstance);
          }
        }, 100);
      }
      
      this.notifyStateChanged(SessionState.CONNECTED);
    }
  }
  
  /**
   * Handle network lost
   */
  private handleNetworkLost(): void {
    if (!isSessionActive) {
      return;
    }
    
    console.warn(`${TAG}: Handling network lost`);
    
    // Update notification
    if (backgroundService && RdpBackgroundService.isServiceRunning()) {
      backgroundService.updateNotification('网络已断开，等待重连...');
    }
    
    this.notifyStateChanged(SessionState.RECONNECTING);
  }
  
  /**
   * Handle network restored
   */
  private handleNetworkRestored(): void {
    if (!isSessionActive) {
      return;
    }
    
    console.info(`${TAG}: Handling network restored`);
    
    // Update notification
    if (backgroundService && RdpBackgroundService.isServiceRunning()) {
      backgroundService.updateNotification('网络已恢复，正在重连...');
    }
  }
  
  /**
   * Start keepalive timer
   */
  private startKeepaliveTimer(): void {
    if (keepaliveTimerId !== -1) {
      return; // Already running
    }
    
    console.info(`${TAG}: Starting keepalive timer`);
    
    const keepaliveCallback = (): void => {
      if (isSessionActive && currentInstance !== 0) {
        LibFreeRDP.sendKeepalive(currentInstance);
        
        // Check connection health
        const status = LibFreeRDP.checkConnectionStatus(currentInstance);
        if (status < 10 && status !== -1) {
          console.warn(`${TAG}: Connection health degraded: ${status}`);
        }
      }
    };
    
    keepaliveTimerId = setInterval(keepaliveCallback, KEEPALIVE_INTERVAL_MS) as number;
  }
  
  /**
   * Stop keepalive timer
   */
  private stopKeepaliveTimer(): void {
    if (keepaliveTimerId !== -1) {
      clearInterval(keepaliveTimerId);
      keepaliveTimerId = -1;
      console.info(`${TAG}: Keepalive timer stopped`);
    }
  }
  
  /**
   * Register screen lock listener
   */
  private async registerScreenLockListener(): Promise<void> {
    try {
      // Check if screen is currently locked using callback API
      screenLock.isScreenLocked((err: BusinessError, isLocked: boolean) => {
        if (err) {
          console.error(`${TAG}: Failed to check screen lock: ${err.code}`);
          sessionIsScreenLocked = false;
        } else {
          console.info(`${TAG}: Initial screen lock state: ${isLocked}`);
          sessionIsScreenLocked = isLocked;
        }
      });
      
      // Note: screenLock event listener registration depends on API version
      // This is a placeholder for the actual implementation
      
    } catch (error) {
      const err = error as BusinessError;
      console.error(`${TAG}: Failed to register screen lock listener: ${err.code} - ${err.message}`);
    }
  }

  /**
   * Get current battery level
   */
  static getBatteryLevel(): number {
    try {
      return batteryInfo.batterySOC;
    } catch (error) {
      return -1;
    }
  }
  
  /**
   * Check if device is charging
   */
  static isCharging(): boolean {
    try {
      const status: batteryInfo.BatteryChargeState = batteryInfo.chargingStatus;
      return status === batteryInfo.BatteryChargeState.ENABLE || 
             status === batteryInfo.BatteryChargeState.FULL;
    } catch (error) {
      return false;
    }
  }
  
  /**
   * Check if screen is currently locked
   */
  static isScreenLocked(): boolean {
    // Return cached state since screenLock.isScreenLocked is async
    return sessionIsScreenLocked;
  }
  
  /**
   * Check if screen is currently locked (async version)
   */
  static async isScreenLockedAsync(): Promise<boolean> {
    return new Promise<boolean>((resolve) => {
      try {
        screenLock.isScreenLocked((err: BusinessError, isLocked: boolean) => {
          if (err) {
            resolve(false);
          } else {
            sessionIsScreenLocked = isLocked;
            resolve(isLocked);
          }
        });
      } catch (error) {
        resolve(false);
      }
    });
  }
  
  /**
   * Get current session state
   */
  static getSessionState(): SessionState {
    if (!isSessionActive) {
      return SessionState.DISCONNECTED;
    }
    
    if (sessionIsScreenLocked) {
      return SessionState.LOCKED;
    }
    
    if (isInBackground) {
      return SessionState.BACKGROUND;
    }
    
    if (NetworkManager.isCurrentlyReconnecting()) {
      return SessionState.RECONNECTING;
    }
    
    return SessionState.CONNECTED;
  }
  
  /**
   * Check if session is active
   */
  static isActive(): boolean {
    return isSessionActive;
  }
  
  /**
   * Get current instance
   */
  static getCurrentInstance(): number {
    return currentInstance;
  }
  
  /**
   * Set callback for session state changes
   */
  static setOnSessionStateChangedCallback(callback: (state: SessionState) => void): void {
    onSessionStateChangedCallback = callback;
  }
  
  /**
   * Set callback for reconnecting events
   */
  static setOnReconnectingCallback(callback: (attempt: number, maxAttempts: number) => void): void {
    onReconnectingCallback = callback;
  }
  
  /**
   * Notify state changed
   */
  private notifyStateChanged(state: SessionState): void {
    if (onSessionStateChangedCallback) {
      onSessionStateChangedCallback(state);
    }
  }
  
  /**
   * Cleanup session manager
   */
  async cleanup(): Promise<void> {
    console.info(`${TAG}: Cleaning up session manager`);
    
    await this.stopSession();
    
    NetworkManager.uninitialize();
    AudioFocusManager.cleanup();
    
    backgroundService = null;
  }
}

export default RdpSessionManager;
