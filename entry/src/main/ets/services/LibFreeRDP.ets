/*
 * HarmonyOS FreeRDP Service
 * 
 * ArkTS wrapper for the FreeRDP native library
 * Provides the same interface as the Android LibFreeRDP.java
 * 
 * Copyright 2026 FreeRDP HarmonyOS Port
 */

import freerdpNative from 'libfreerdp_harmonyos.so';

// Cursor type constants (matching C layer definitions)
export const CURSOR_TYPE_UNKNOWN = 0;
export const CURSOR_TYPE_DEFAULT = 1;      // 默认箭头
export const CURSOR_TYPE_HAND = 2;         // 手型（链接）
export const CURSOR_TYPE_IBEAM = 3;        // I型（文本）
export const CURSOR_TYPE_SIZE_NS = 4;      // 上下双箭头（调整行高）
export const CURSOR_TYPE_SIZE_WE = 5;      // 左右双箭头（调整列宽）
export const CURSOR_TYPE_SIZE_NWSE = 6;    // 斜向双箭头（左上-右下）
export const CURSOR_TYPE_SIZE_NESW = 7;    // 斜向双箭头（右上-左下）
export const CURSOR_TYPE_CROSS = 8;        // 十字（移动）
export const CURSOR_TYPE_WAIT = 9;         // 等待（沙漏）

// Certificate verification flags
export const VERIFY_CERT_FLAG_NONE = 0x00;
export const VERIFY_CERT_FLAG_LEGACY = 0x02;
export const VERIFY_CERT_FLAG_REDIRECT = 0x10;
export const VERIFY_CERT_FLAG_GATEWAY = 0x20;
export const VERIFY_CERT_FLAG_CHANGED = 0x40;
export const VERIFY_CERT_FLAG_MISMATCH = 0x80;
export const VERIFY_CERT_FLAG_MATCH_LEGACY_SHA1 = 0x100;
export const VERIFY_CERT_FLAG_FP_IS_PEM = 0x200;

// Mouse event flags
export const PTR_FLAGS_MOVE = 0x0800;
export const PTR_FLAGS_DOWN = 0x8000;
export const PTR_FLAGS_BUTTON1 = 0x1000;  // Left button
export const PTR_FLAGS_BUTTON2 = 0x2000;  // Right button
export const PTR_FLAGS_BUTTON3 = 0x4000;  // Middle button
export const PTR_FLAGS_WHEEL = 0x0200;
export const PTR_FLAGS_WHEEL_NEGATIVE = 0x0100;

// Event listener interface
export interface EventListener {
  OnPreConnect(instance: number): void;
  OnConnectionSuccess(instance: number): void;
  OnConnectionFailure(instance: number): void;
  OnDisconnecting(instance: number): void;
  OnDisconnected(instance: number): void;
}

// UI event listener interface
export interface UIEventListener {
  OnSettingsChanged(width: number, height: number, bpp: number): void;
  OnAuthenticate(username: string, domain: string, password: string): boolean;
  OnGatewayAuthenticate(username: string, domain: string, password: string): boolean;
  OnVerifyCertificateEx(host: string, port: number, commonName: string, subject: string, 
                        issuer: string, fingerprint: string, flags: number): number;
  OnVerifyChangedCertificateEx(host: string, port: number, commonName: string, subject: string,
                               issuer: string, fingerprint: string, oldSubject: string,
                               oldIssuer: string, oldFingerprint: string, flags: number): number;
  OnGraphicsUpdate(x: number, y: number, width: number, height: number): void;
  OnGraphicsResize(width: number, height: number, bpp: number): void;
  OnRemoteClipboardChanged(data: string): void;
  OnCursorTypeChanged(cursorType: number): void;
}

// Bookmark settings interfaces
export interface ScreenSettings {
  width: number;
  height: number;
  colors: number;
}

export interface AdvancedSettings {
  consoleMode: boolean;
  security: number;
  remoteProgram: string;
  workDir: string;
  redirectSDCard: boolean;
  redirectSound: number;
  redirectMicrophone: boolean;
}

export interface PerformanceFlags {
  remoteFX: boolean;
  gfx: boolean;
  h264: boolean;
  wallpaper: boolean;
  fullWindowDrag: boolean;
  menuAnimations: boolean;
  theming: boolean;
  fontSmoothing: boolean;
  desktopComposition: boolean;
}

export interface DebugSettings {
  asyncChannel: boolean;
  asyncUpdate: boolean;
  debugLevel: string;
}

export interface GatewaySettings {
  enabled: boolean;
  hostname: string;
  port: number;
  username: string;
  domain: string;
  password: string;
}

export interface BookmarkSettings {
  hostname: string;
  port: number;
  username: string;
  domain: string;
  password: string;
  screenSettings: ScreenSettings;
  advancedSettings: AdvancedSettings;
  performanceFlags: PerformanceFlags;
  debugSettings: DebugSettings;
  gatewaySettings?: GatewaySettings;
}

// Instance state tracking
const instanceState: Map<number, boolean> = new Map();

// Global event listener
let eventListener: EventListener | null = null;

// H.264 support flag
let hasH264Support: boolean = false;

// Initialize native callbacks
function initCallbacks(): void {
  freerdpNative.setOnConnectionSuccess((instance: number) => {
    console.info(`[LibFreeRDP] OnConnectionSuccess: instance=${instance}`);
    instanceState.set(instance, true);
    if (eventListener) {
      eventListener.OnConnectionSuccess(instance);
    }
  });

  freerdpNative.setOnConnectionFailure((instance: number) => {
    console.error(`[LibFreeRDP] OnConnectionFailure: instance=${instance}`);
    instanceState.delete(instance);
    if (eventListener) {
      eventListener.OnConnectionFailure(instance);
    }
  });

  freerdpNative.setOnPreConnect((instance: number) => {
    console.info(`[LibFreeRDP] OnPreConnect: instance=${instance}`);
    if (eventListener) {
      eventListener.OnPreConnect(instance);
    }
  });

  freerdpNative.setOnDisconnecting((instance: number) => {
    console.info(`[LibFreeRDP] OnDisconnecting: instance=${instance}`);
    if (eventListener) {
      eventListener.OnDisconnecting(instance);
    }
  });

  freerdpNative.setOnDisconnected((instance: number) => {
    console.info(`[LibFreeRDP] OnDisconnected: instance=${instance}`);
    instanceState.delete(instance);
    if (eventListener) {
      eventListener.OnDisconnected(instance);
    }
  });

  // Check H.264 support
  hasH264Support = freerdpNative.freerdpHasH264();
  console.info(`[LibFreeRDP] H.264 support: ${hasH264Support}`);
}

// Initialize on module load
initCallbacks();

export class LibFreeRDP {
  private static TAG = 'LibFreeRDP';

  /**
   * Check if H.264 is supported
   */
  static hasH264Support(): boolean {
    return hasH264Support;
  }

  /**
   * Set the event listener
   */
  static setEventListener(listener: EventListener): void {
    eventListener = listener;
  }

  /**
   * Create a new FreeRDP instance
   */
  static newInstance(): number {
    const instance: number = freerdpNative.freerdpNew() as number;
    console.info(`${LibFreeRDP.TAG}: newInstance() = ${instance}`);
    return instance;
  }

  /**
   * Free a FreeRDP instance
   */
  static freeInstance(inst: number): void {
    console.info(`${LibFreeRDP.TAG}: freeInstance(${inst})`);
    
    // Disconnect if still connected
    if (instanceState.get(inst)) {
      try {
        freerdpNative.freerdpDisconnect(inst);
      } catch (e) {
        console.error(`${LibFreeRDP.TAG}: Failed to disconnect instance ${inst}`, e);
      }
    }
    
    // Wait for disconnect with timeout
    const startTime = Date.now();
    const timeout = 30000; // 30 seconds
    
    while (instanceState.get(inst)) {
      const remaining = timeout - (Date.now() - startTime);
      if (remaining <= 0) {
        console.error(`${LibFreeRDP.TAG}: Timeout waiting for instance ${inst} to disconnect`);
        break;
      }
      // Busy wait - in production, use proper async handling
    }
    
    try {
      freerdpNative.freerdpFree(inst);
    } catch (e) {
      console.error(`${LibFreeRDP.TAG}: Failed to free instance ${inst}`, e);
    }
    
    instanceState.delete(inst);
  }

  /**
   * Connect to a remote desktop
   */
  static connect(inst: number): boolean {
    if (instanceState.get(inst)) {
      throw new Error('Instance already connected');
    }
    return freerdpNative.freerdpConnect(inst);
  }

  /**
   * Disconnect from a remote desktop
   */
  static disconnect(inst: number): boolean {
    if (instanceState.get(inst)) {
      return freerdpNative.freerdpDisconnect(inst);
    }
    return true;
  }

  /**
   * Cancel an ongoing connection
   */
  static cancelConnection(inst: number): boolean {
    if (instanceState.get(inst)) {
      return freerdpNative.freerdpDisconnect(inst);
    }
    return true;
  }

  /**
   * Set connection info from bookmark settings
   */
  static setConnectionInfo(inst: number, bookmark: BookmarkSettings, clientName: string = ''): boolean {
    const args: string[] = [];
    
    args.push('FreeRDP');
    args.push('/gdi:sw');
    
    if (clientName) {
      args.push(`/client-hostname:${clientName}`);
    }
    
    // Host and port
    args.push(`/v:${bookmark.hostname}`);
    args.push(`/port:${bookmark.port}`);
    
    // Credentials
    if (bookmark.username) {
      args.push(`/u:${bookmark.username}`);
    }
    if (bookmark.domain) {
      args.push(`/d:${bookmark.domain}`);
    }
    if (bookmark.password) {
      args.push(`/p:${bookmark.password}`);
    }
    
    // Screen settings
    args.push(`/size:${bookmark.screenSettings.width}x${bookmark.screenSettings.height}`);
    args.push(`/bpp:${bookmark.screenSettings.colors}`);
    
    // Advanced settings
    if (bookmark.advancedSettings.consoleMode) {
      args.push('/admin');
    }
    
    switch (bookmark.advancedSettings.security) {
      case 3: args.push('/sec:nla'); break;
      case 2: args.push('/sec:tls'); break;
      case 1: args.push('/sec:rdp'); break;
    }
    
    // Performance flags
    const perf = bookmark.performanceFlags;
    if (perf.remoteFX) {
      args.push('/rfx');
      args.push('/network:auto');
    }
    if (perf.gfx) {
      args.push('/gfx');
      args.push('/network:auto');
    }
    if (perf.h264 && hasH264Support) {
      args.push('/gfx:AVC444');
      args.push('/network:auto');
    }
    
    args.push(perf.wallpaper ? '+wallpaper' : '-wallpaper');
    args.push(perf.fullWindowDrag ? '+window-drag' : '-window-drag');
    args.push(perf.menuAnimations ? '+menu-anims' : '-menu-anims');
    args.push(perf.theming ? '+themes' : '-themes');
    args.push(perf.fontSmoothing ? '+fonts' : '-fonts');
    args.push(perf.desktopComposition ? '+aero' : '-aero');
    
    // Mouse motion
    args.push('+mouse-motion');
    
    // Remote program
    if (bookmark.advancedSettings.remoteProgram) {
      args.push(`/shell:${bookmark.advancedSettings.remoteProgram}`);
    }
    if (bookmark.advancedSettings.workDir) {
      args.push(`/shell-dir:${bookmark.advancedSettings.workDir}`);
    }
    
    // Debug settings
    args.push(bookmark.debugSettings.asyncChannel ? '+async-channels' : '-async-channels');
    args.push(bookmark.debugSettings.asyncUpdate ? '+async-update' : '-async-update');
    
    // SD Card redirect
    if (bookmark.advancedSettings.redirectSDCard) {
      // TODO: Get proper storage path for HarmonyOS
      args.push('/drive:sdcard,/data/storage');
    }
    
    // Clipboard
    args.push('/clipboard');
    
    // Gateway settings
    if (bookmark.gatewaySettings?.enabled) {
      const gw = bookmark.gatewaySettings;
      let gwArg = `/gateway:g:${gw.hostname}:${gw.port}`;
      if (gw.username) gwArg += `,u:${gw.username}`;
      if (gw.domain) gwArg += `,d:${gw.domain}`;
      if (gw.password) gwArg += `,p:${gw.password}`;
      args.push(gwArg);
    }
    
    // Audio
    args.push(`/audio-mode:${bookmark.advancedSettings.redirectSound}`);
    if (bookmark.advancedSettings.redirectSound === 0) {
      args.push('/sound:latency:150,quality:medium');
    }
    
    if (bookmark.advancedSettings.redirectMicrophone) {
      args.push('/microphone');
    }
    
    // Keyboard and certificate
    args.push('/kbd:unicode:on');
    args.push('/cert:ignore');
    args.push(`/log-level:${bookmark.debugSettings.debugLevel}`);
    
    // Parse arguments
    if (!freerdpNative.freerdpParseArguments(inst, args)) {
      return false;
    }
    
    // Enable TCP keepalive (15s interval for NAT traversal)
    const tcpKeepaliveResult = LibFreeRDP.setTcpKeepalive(inst, true, 15, 15, 3);
    if (tcpKeepaliveResult) {
      console.info(`${LibFreeRDP.TAG}: TCP Keepalive enabled: 15s interval (NAT-friendly)`);
    } else {
      console.error(`${LibFreeRDP.TAG}: Failed to enable TCP keepalive!`);
    }
    
    return true;
  }

  /**
   * Send a cursor/mouse event
   */
  static sendCursorEvent(inst: number, x: number, y: number, flags: number): boolean {
    if (inst === 0) {
      return false;
    }
    return freerdpNative.freerdpSendCursorEvent(inst, x, y, flags);
  }

  /**
   * Send a key event
   */
  static sendKeyEvent(inst: number, keycode: number, down: boolean): boolean {
    return freerdpNative.freerdpSendKeyEvent(inst, keycode, down);
  }

  /**
   * Send a Unicode key event
   */
  static sendUnicodeKeyEvent(inst: number, keycode: number, down: boolean): boolean {
    return freerdpNative.freerdpSendUnicodeKeyEvent(inst, keycode, down);
  }

  /**
   * Set TCP Keepalive settings (must be called before connect)
   */
  static setTcpKeepalive(inst: number, enabled: boolean, delay: number, interval: number, retries: number): boolean {
    return freerdpNative.freerdpSetTcpKeepalive(inst, enabled, delay, interval, retries);
  }

  /**
   * Send a synchronize event (used for heartbeat)
   */
  static sendSynchronizeEvent(inst: number, flags: number): boolean {
    return freerdpNative.freerdpSendSynchronizeEvent(inst, flags);
  }

  /**
   * Send a heartbeat (using Synchronize Event)
   */
  static sendHeartbeat(inst: number): boolean {
    return LibFreeRDP.sendSynchronizeEvent(inst, 0);
  }

  /**
   * Send clipboard data
   */
  static sendClipboardData(inst: number, data: string): boolean {
    return freerdpNative.freerdpSendClipboardData(inst, data);
  }

  /**
   * Set client decoding (for background mode)
   * When disabled, stops receiving graphics updates but keeps audio
   */
  static setClientDecoding(inst: number, enable: boolean): number {
    return freerdpNative.freerdpSetClientDecoding(inst, enable);
  }

  /**
   * Get the last error string
   */
  static getLastErrorString(inst: number): string {
    return freerdpNative.freerdpGetLastErrorString(inst);
  }

  /**
   * Get FreeRDP version
   */
  static getVersion(): string {
    return freerdpNative.freerdpGetVersion();
  }

  /**
   * Check if an instance is connected
   */
  static isInstanceConnected(inst: number): boolean {
    return instanceState.get(inst) ?? false;
  }

  /**
   * Get the count of active connections
   */
  static getActiveConnectionCount(): number {
    return instanceState.size;
  }

  /**
   * Check if instance is alive with timeout verification
   */
  static isInstanceAliveWithTimeout(inst: number, timeoutMs: number): boolean {
    if (!LibFreeRDP.isInstanceConnected(inst)) {
      return false;
    }
    // TODO: Add heartbeat verification
    return true;
  }
}

export default LibFreeRDP;
