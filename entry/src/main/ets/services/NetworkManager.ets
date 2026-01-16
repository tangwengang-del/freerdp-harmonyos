/*
 * HarmonyOS FreeRDP Network Manager
 * 
 * Handles network state monitoring and auto-reconnect functionality
 * 
 * Copyright 2026 FreeRDP HarmonyOS Port
 */

import connection from '@ohos.net.connection';
import { BusinessError } from '@ohos.base';
import LibFreeRDP, { BookmarkSettings, ScreenSettings, AdvancedSettings, PerformanceFlags, DebugSettings } from './LibFreeRDP';

const TAG = 'NetworkManager';

// Reconnect configuration
const MAX_RECONNECT_ATTEMPTS = 10;
const RECONNECT_DELAYS = [1000, 5000, 10000, 20000, 20000, 20000, 20000, 20000, 20000, 20000];

// Network state
let isNetworkAvailable: boolean = false;
let netConnection: connection.NetConnection | null = null;

// Reconnect state
let reconnectAttempts: number = 0;
let isReconnecting: boolean = false;
let reconnectTimerId: number = -1;

// Callbacks
let onNetworkAvailableCallback: (() => void) | null = null;
let onNetworkLostCallback: (() => void) | null = null;
let onReconnectSuccessCallback: (() => void) | null = null;
let onReconnectFailureCallback: ((error: string) => void) | null = null;

// Session info for reconnection
export interface SessionInfo {
  instance: number;
  hostname: string;
  port: number;
  username: string;
  domain: string;
  password: string;
}

let currentSession: SessionInfo | null = null;

/**
 * Network Manager
 * Monitors network state and handles auto-reconnection
 */
export class NetworkManager {

  /**
   * Initialize network monitoring
   */
  static async initialize(): Promise<void> {
    console.info(`${TAG}: Initializing network manager`);

    try {
      // Create network connection object
      netConnection = connection.createNetConnection();

      // Register network state callback
      netConnection.register((error: BusinessError) => {
        if (error) {
          console.error(`${TAG}: Failed to register network callback: ${error.message}`);
          return;
        }
        console.info(`${TAG}: Network callback registered`);
      });

      // Subscribe to network available event
      netConnection.on('netAvailable', (netHandle: connection.NetHandle) => {
        console.info(`${TAG}: Network available: netId=${netHandle.netId}`);
        isNetworkAvailable = true;
        
        if (onNetworkAvailableCallback) {
          onNetworkAvailableCallback();
        }

        // Trigger reconnect if we have a session to reconnect
        if (currentSession && !isReconnecting) {
          NetworkManager.startReconnect();
        }
      });

      // Subscribe to network lost event
      netConnection.on('netLost', (netHandle: connection.NetHandle) => {
        console.warn(`${TAG}: Network lost: netId=${netHandle.netId}`);
        isNetworkAvailable = false;
        
        if (onNetworkLostCallback) {
          onNetworkLostCallback();
        }
      });

      // Note: netCapabilitiesChange callback removed due to API incompatibility

      // Check current network state
      const hasDefaultNet = await connection.hasDefaultNet();
      isNetworkAvailable = hasDefaultNet;
      console.info(`${TAG}: Initial network state: available=${isNetworkAvailable}`);

    } catch (error) {
      const err = error as BusinessError;
      console.error(`${TAG}: Failed to initialize network manager: ${err.code} - ${err.message}`);
    }
  }

  /**
   * Uninitialize network monitoring
   */
  static uninitialize(): void {
    console.info(`${TAG}: Uninitializing network manager`);

    if (netConnection) {
      try {
        netConnection.unregister((error: BusinessError) => {
          if (error) {
            console.error(`${TAG}: Failed to unregister network callback: ${error.message}`);
          }
        });
      } catch (e) {
        console.error(`${TAG}: Error unregistering network connection`, e);
      }
      netConnection = null;
    }

    NetworkManager.cancelReconnect();
    currentSession = null;
  }

  /**
   * Check if network is available
   */
  static isNetworkAvailable(): boolean {
    return isNetworkAvailable;
  }

  /**
   * Set session info for reconnection
   */
  static setSessionInfo(session: SessionInfo): void {
    currentSession = session;
    console.info(`${TAG}: Session info set for reconnection: ${session.hostname}:${session.port}`);
  }

  /**
   * Clear session info
   */
  static clearSessionInfo(): void {
    currentSession = null;
    NetworkManager.cancelReconnect();
    console.info(`${TAG}: Session info cleared`);
  }

  /**
   * Set callback for network available event
   */
  static setOnNetworkAvailableCallback(callback: () => void): void {
    onNetworkAvailableCallback = callback;
  }

  /**
   * Set callback for network lost event
   */
  static setOnNetworkLostCallback(callback: () => void): void {
    onNetworkLostCallback = callback;
  }

  /**
   * Set callback for reconnect success
   */
  static setOnReconnectSuccessCallback(callback: () => void): void {
    onReconnectSuccessCallback = callback;
  }

  /**
   * Set callback for reconnect failure
   */
  static setOnReconnectFailureCallback(callback: (error: string) => void): void {
    onReconnectFailureCallback = callback;
  }

  /**
   * Start reconnection process
   */
  static startReconnect(): void {
    if (isReconnecting) {
      console.info(`${TAG}: Already reconnecting, skip`);
      return;
    }

    if (!currentSession) {
      console.warn(`${TAG}: No session info for reconnection`);
      return;
    }

    if (!isNetworkAvailable) {
      console.warn(`${TAG}: Network not available, cannot reconnect`);
      return;
    }

    console.info(`${TAG}: Starting reconnection process`);
    isReconnecting = true;
    reconnectAttempts = 0;

    NetworkManager.attemptReconnect();
  }

  /**
   * Cancel reconnection process
   */
  static cancelReconnect(): void {
    if (reconnectTimerId !== -1) {
      clearTimeout(reconnectTimerId);
      reconnectTimerId = -1;
    }
    isReconnecting = false;
    reconnectAttempts = 0;
    console.info(`${TAG}: Reconnection cancelled`);
  }

  /**
   * Attempt to reconnect
   */
  private static attemptReconnect(): void {
    if (!isReconnecting || !currentSession) {
      return;
    }

    if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
      console.error(`${TAG}: Max reconnect attempts (${MAX_RECONNECT_ATTEMPTS}) reached`);
      isReconnecting = false;
      
      if (onReconnectFailureCallback) {
        onReconnectFailureCallback(`已尝试${MAX_RECONNECT_ATTEMPTS}次重连，均失败`);
      }
      return;
    }

    if (!isNetworkAvailable) {
      console.warn(`${TAG}: Network lost during reconnection, waiting...`);
      return;
    }

    reconnectAttempts++;
    const delay = RECONNECT_DELAYS[reconnectAttempts - 1] || 20000;

    console.info(`${TAG}: Reconnect attempt ${reconnectAttempts}/${MAX_RECONNECT_ATTEMPTS} in ${delay}ms`);

    const reconnectCallback = (): void => {
      NetworkManager.doReconnect();
    };
    reconnectTimerId = setTimeout(reconnectCallback, delay) as number;
  }

  /**
   * Perform the actual reconnection
   */
  private static async doReconnect(): Promise<void> {
    if (!currentSession) {
      console.error(`${TAG}: No session info for reconnection`);
      return;
    }

    console.info(`${TAG}: Attempting to reconnect to ${currentSession.hostname}:${currentSession.port}`);

    try {
      // Check if the old instance is still connected
      if (currentSession.instance && LibFreeRDP.isInstanceConnected(currentSession.instance)) {
        // Disconnect first
        LibFreeRDP.disconnect(currentSession.instance);
        // Wait a bit for clean disconnect
        await new Promise<void>((resolve: () => void) => setTimeout(resolve, 1000));
      }

      // Create new instance
      const newInstance = LibFreeRDP.newInstance();
      if (newInstance === 0) {
        throw new Error('Failed to create new FreeRDP instance');
      }

      // Set connection info
      const screenSettings: ScreenSettings = {
        width: 1920,
        height: 1080,
        colors: 32
      };
      
      const advancedSettings: AdvancedSettings = {
        consoleMode: false,
        security: 0,
        remoteProgram: '',
        workDir: '',
        redirectSDCard: false,
        redirectSound: 0,
        redirectMicrophone: false
      };
      
      const performanceFlags: PerformanceFlags = {
        remoteFX: true,
        gfx: true,
        h264: true,
        wallpaper: false,
        fullWindowDrag: false,
        menuAnimations: false,
        theming: true,
        fontSmoothing: true,
        desktopComposition: true
      };
      
      const debugSettings: DebugSettings = {
        asyncChannel: true,
        asyncUpdate: true,
        debugLevel: 'WARN'
      };
      
      const bookmark: BookmarkSettings = {
        hostname: currentSession.hostname,
        port: currentSession.port,
        username: currentSession.username,
        domain: currentSession.domain,
        password: currentSession.password,
        screenSettings: screenSettings,
        advancedSettings: advancedSettings,
        performanceFlags: performanceFlags,
        debugSettings: debugSettings
      };

      if (!LibFreeRDP.setConnectionInfo(newInstance, bookmark)) {
        throw new Error('Failed to set connection info');
      }

      // Connect
      if (!LibFreeRDP.connect(newInstance)) {
        throw new Error('Failed to connect');
      }

      // Update session instance
      currentSession.instance = newInstance;

      console.info(`${TAG}: Reconnection successful`);
      isReconnecting = false;
      reconnectAttempts = 0;

      if (onReconnectSuccessCallback) {
        onReconnectSuccessCallback();
      }

    } catch (error) {
      console.error(`${TAG}: Reconnection failed:`, error);

      // Schedule next attempt
      if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
        NetworkManager.attemptReconnect();
      } else {
        isReconnecting = false;
        if (onReconnectFailureCallback) {
          onReconnectFailureCallback(`重连失败: ${error}`);
        }
      }
    }
  }

  /**
   * Get current reconnect attempt count
   */
  static getReconnectAttempts(): number {
    return reconnectAttempts;
  }

  /**
   * Check if currently reconnecting
   */
  static isCurrentlyReconnecting(): boolean {
    return isReconnecting;
  }
}

export default NetworkManager;
