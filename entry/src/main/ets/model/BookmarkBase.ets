/*
 * HarmonyOS FreeRDP Bookmark Model
 * 
 * Data model for RDP connection bookmarks
 * 
 * Copyright 2026 FreeRDP HarmonyOS Port
 */

/**
 * Screen settings for the remote desktop
 */
export interface ScreenSettings {
  width: number;
  height: number;
  colors: number;  // Color depth: 8, 16, 24, 32
}

/**
 * Advanced connection settings
 */
export interface AdvancedSettings {
  consoleMode: boolean;        // Admin/console session
  security: number;            // 0=Auto, 1=RDP, 2=TLS, 3=NLA
  remoteProgram: string;       // Remote application to launch
  workDir: string;             // Working directory for remote app
  redirectSDCard: boolean;     // Redirect local storage
  redirectSound: number;       // 0=Local, 1=Remote, 2=Disable
  redirectMicrophone: boolean; // Enable microphone redirection
}

/**
 * Performance optimization flags
 */
export interface PerformanceFlags {
  remoteFX: boolean;           // RemoteFX codec
  gfx: boolean;                // GFX pipeline
  h264: boolean;               // H.264/AVC codec
  wallpaper: boolean;          // Show wallpaper
  fullWindowDrag: boolean;     // Full window drag
  menuAnimations: boolean;     // Menu animations
  theming: boolean;            // Visual themes
  fontSmoothing: boolean;      // Font smoothing
  desktopComposition: boolean; // Desktop composition (Aero)
}

/**
 * Debug and logging settings
 */
export interface DebugSettings {
  asyncChannel: boolean;  // Async channel processing
  asyncUpdate: boolean;   // Async update processing
  debugLevel: string;     // Log level: TRACE, DEBUG, INFO, WARN, ERROR
}

/**
 * Gateway settings for RD Gateway connections
 */
export interface GatewaySettings {
  enabled: boolean;
  hostname: string;
  port: number;
  username: string;
  domain: string;
  password: string;
}

/**
 * Bookmark types
 */
export enum BookmarkType {
  MANUAL = 0,       // Manually configured bookmark
  QUICK_CONNECT = 1 // Quick connect (temporary)
}

/**
 * Base bookmark class
 */
export class BookmarkBase {
  // Bookmark metadata
  id: number = 0;
  label: string = '';
  type: BookmarkType = BookmarkType.MANUAL;

  // Connection settings
  hostname: string = '';
  port: number = 3389;
  username: string = '';
  domain: string = '';
  password: string = '';

  // Screen settings
  screenSettings: ScreenSettings = {
    width: 1920,
    height: 1080,
    colors: 32
  };

  // Advanced settings
  advancedSettings: AdvancedSettings = {
    consoleMode: false,
    security: 0,
    remoteProgram: '',
    workDir: '',
    redirectSDCard: false,
    redirectSound: 0,
    redirectMicrophone: false
  };

  // Performance flags
  performanceFlags: PerformanceFlags = {
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

  // Debug settings
  debugSettings: DebugSettings = {
    asyncChannel: true,
    asyncUpdate: true,
    debugLevel: 'WARN'
  };

  // Gateway settings
  gatewaySettings: GatewaySettings = {
    enabled: false,
    hostname: '',
    port: 443,
    username: '',
    domain: '',
    password: ''
  };

  constructor() {}

  /**
   * Create a bookmark from JSON object
   */
  static fromJson(json: object): BookmarkBase {
    const bookmark = new BookmarkBase();
    const data = json as Record<string, Object>;
    
    // Copy basic properties
    if (data['id'] !== undefined) bookmark.id = data['id'] as number;
    if (data['label'] !== undefined) bookmark.label = data['label'] as string;
    if (data['type'] !== undefined) bookmark.type = data['type'] as BookmarkType;
    if (data['hostname'] !== undefined) bookmark.hostname = data['hostname'] as string;
    if (data['port'] !== undefined) bookmark.port = data['port'] as number;
    if (data['username'] !== undefined) bookmark.username = data['username'] as string;
    if (data['domain'] !== undefined) bookmark.domain = data['domain'] as string;
    if (data['password'] !== undefined) bookmark.password = data['password'] as string;
    
    // Copy screen settings
    if (data['screenSettings'] !== undefined) {
      const ss = data['screenSettings'] as Record<string, Object>;
      if (ss['width'] !== undefined) bookmark.screenSettings.width = ss['width'] as number;
      if (ss['height'] !== undefined) bookmark.screenSettings.height = ss['height'] as number;
      if (ss['colors'] !== undefined) bookmark.screenSettings.colors = ss['colors'] as number;
    }
    
    // Copy advanced settings
    if (data['advancedSettings'] !== undefined) {
      const as_ = data['advancedSettings'] as Record<string, Object>;
      if (as_['consoleMode'] !== undefined) bookmark.advancedSettings.consoleMode = as_['consoleMode'] as boolean;
      if (as_['security'] !== undefined) bookmark.advancedSettings.security = as_['security'] as number;
      if (as_['remoteProgram'] !== undefined) bookmark.advancedSettings.remoteProgram = as_['remoteProgram'] as string;
      if (as_['workDir'] !== undefined) bookmark.advancedSettings.workDir = as_['workDir'] as string;
      if (as_['redirectSDCard'] !== undefined) bookmark.advancedSettings.redirectSDCard = as_['redirectSDCard'] as boolean;
      if (as_['redirectSound'] !== undefined) bookmark.advancedSettings.redirectSound = as_['redirectSound'] as number;
      if (as_['redirectMicrophone'] !== undefined) bookmark.advancedSettings.redirectMicrophone = as_['redirectMicrophone'] as boolean;
    }
    
    // Copy performance flags
    if (data['performanceFlags'] !== undefined) {
      const pf = data['performanceFlags'] as Record<string, Object>;
      if (pf['remoteFX'] !== undefined) bookmark.performanceFlags.remoteFX = pf['remoteFX'] as boolean;
      if (pf['gfx'] !== undefined) bookmark.performanceFlags.gfx = pf['gfx'] as boolean;
      if (pf['h264'] !== undefined) bookmark.performanceFlags.h264 = pf['h264'] as boolean;
      if (pf['wallpaper'] !== undefined) bookmark.performanceFlags.wallpaper = pf['wallpaper'] as boolean;
      if (pf['fullWindowDrag'] !== undefined) bookmark.performanceFlags.fullWindowDrag = pf['fullWindowDrag'] as boolean;
      if (pf['menuAnimations'] !== undefined) bookmark.performanceFlags.menuAnimations = pf['menuAnimations'] as boolean;
      if (pf['theming'] !== undefined) bookmark.performanceFlags.theming = pf['theming'] as boolean;
      if (pf['fontSmoothing'] !== undefined) bookmark.performanceFlags.fontSmoothing = pf['fontSmoothing'] as boolean;
      if (pf['desktopComposition'] !== undefined) bookmark.performanceFlags.desktopComposition = pf['desktopComposition'] as boolean;
    }
    
    // Copy debug settings
    if (data['debugSettings'] !== undefined) {
      const ds = data['debugSettings'] as Record<string, Object>;
      if (ds['asyncChannel'] !== undefined) bookmark.debugSettings.asyncChannel = ds['asyncChannel'] as boolean;
      if (ds['asyncUpdate'] !== undefined) bookmark.debugSettings.asyncUpdate = ds['asyncUpdate'] as boolean;
      if (ds['debugLevel'] !== undefined) bookmark.debugSettings.debugLevel = ds['debugLevel'] as string;
    }
    
    // Copy gateway settings
    if (data['gatewaySettings'] !== undefined) {
      const gs = data['gatewaySettings'] as Record<string, Object>;
      if (gs['enabled'] !== undefined) bookmark.gatewaySettings.enabled = gs['enabled'] as boolean;
      if (gs['hostname'] !== undefined) bookmark.gatewaySettings.hostname = gs['hostname'] as string;
      if (gs['port'] !== undefined) bookmark.gatewaySettings.port = gs['port'] as number;
      if (gs['username'] !== undefined) bookmark.gatewaySettings.username = gs['username'] as string;
      if (gs['domain'] !== undefined) bookmark.gatewaySettings.domain = gs['domain'] as string;
      if (gs['password'] !== undefined) bookmark.gatewaySettings.password = gs['password'] as string;
    }
    
    return bookmark;
  }

  /**
   * Convert bookmark to JSON object
   */
  toJson(): Record<string, Object> {
    const result: Record<string, Object> = {};
    result['id'] = this.id;
    result['label'] = this.label;
    result['type'] = this.type;
    result['hostname'] = this.hostname;
    result['port'] = this.port;
    result['username'] = this.username;
    result['domain'] = this.domain;
    result['password'] = this.password;
    result['screenSettings'] = this.screenSettings;
    result['advancedSettings'] = this.advancedSettings;
    result['performanceFlags'] = this.performanceFlags;
    result['debugSettings'] = this.debugSettings;
    result['gatewaySettings'] = this.gatewaySettings;
    return result;
  }

  /**
   * Get display label (hostname if no label set)
   */
  getDisplayLabel(): string {
    return this.label || this.hostname || 'Unknown';
  }

  /**
   * Get connection string for display
   */
  getConnectionString(): string {
    let str = this.hostname;
    if (this.port !== 3389) {
      str += `:${this.port}`;
    }
    if (this.username) {
      str = `${this.username}@${str}`;
    }
    return str;
  }

  /**
   * Clone this bookmark
   */
  clone(): BookmarkBase {
    const cloned = new BookmarkBase();
    cloned.id = this.id;
    cloned.label = this.label;
    cloned.type = this.type;
    cloned.hostname = this.hostname;
    cloned.port = this.port;
    cloned.username = this.username;
    cloned.domain = this.domain;
    cloned.password = this.password;
    
    // Clone screen settings
    cloned.screenSettings.width = this.screenSettings.width;
    cloned.screenSettings.height = this.screenSettings.height;
    cloned.screenSettings.colors = this.screenSettings.colors;
    
    // Clone advanced settings
    cloned.advancedSettings.consoleMode = this.advancedSettings.consoleMode;
    cloned.advancedSettings.security = this.advancedSettings.security;
    cloned.advancedSettings.remoteProgram = this.advancedSettings.remoteProgram;
    cloned.advancedSettings.workDir = this.advancedSettings.workDir;
    cloned.advancedSettings.redirectSDCard = this.advancedSettings.redirectSDCard;
    cloned.advancedSettings.redirectSound = this.advancedSettings.redirectSound;
    cloned.advancedSettings.redirectMicrophone = this.advancedSettings.redirectMicrophone;
    
    // Clone performance flags
    cloned.performanceFlags.remoteFX = this.performanceFlags.remoteFX;
    cloned.performanceFlags.gfx = this.performanceFlags.gfx;
    cloned.performanceFlags.h264 = this.performanceFlags.h264;
    cloned.performanceFlags.wallpaper = this.performanceFlags.wallpaper;
    cloned.performanceFlags.fullWindowDrag = this.performanceFlags.fullWindowDrag;
    cloned.performanceFlags.menuAnimations = this.performanceFlags.menuAnimations;
    cloned.performanceFlags.theming = this.performanceFlags.theming;
    cloned.performanceFlags.fontSmoothing = this.performanceFlags.fontSmoothing;
    cloned.performanceFlags.desktopComposition = this.performanceFlags.desktopComposition;
    
    // Clone debug settings
    cloned.debugSettings.asyncChannel = this.debugSettings.asyncChannel;
    cloned.debugSettings.asyncUpdate = this.debugSettings.asyncUpdate;
    cloned.debugSettings.debugLevel = this.debugSettings.debugLevel;
    
    // Clone gateway settings
    cloned.gatewaySettings.enabled = this.gatewaySettings.enabled;
    cloned.gatewaySettings.hostname = this.gatewaySettings.hostname;
    cloned.gatewaySettings.port = this.gatewaySettings.port;
    cloned.gatewaySettings.username = this.gatewaySettings.username;
    cloned.gatewaySettings.domain = this.gatewaySettings.domain;
    cloned.gatewaySettings.password = this.gatewaySettings.password;
    
    return cloned;
  }

  /**
   * Validate bookmark settings
   */
  validate(): string[] {
    const errors: string[] = [];

    if (!this.hostname || this.hostname.trim() === '') {
      errors.push('主机名不能为空');
    }

    if (this.port < 1 || this.port > 65535) {
      errors.push('端口号必须在1-65535之间');
    }

    if (this.screenSettings.width < 640 || this.screenSettings.width > 8192) {
      errors.push('屏幕宽度必须在640-8192之间');
    }

    if (this.screenSettings.height < 480 || this.screenSettings.height > 8192) {
      errors.push('屏幕高度必须在480-8192之间');
    }

    if (![8, 16, 24, 32].includes(this.screenSettings.colors)) {
      errors.push('颜色深度必须是8、16、24或32');
    }

    if (this.gatewaySettings.enabled) {
      if (!this.gatewaySettings.hostname || this.gatewaySettings.hostname.trim() === '') {
        errors.push('网关主机名不能为空');
      }
      if (this.gatewaySettings.port < 1 || this.gatewaySettings.port > 65535) {
        errors.push('网关端口号必须在1-65535之间');
      }
    }

    return errors;
  }
}

/**
 * Manual bookmark (saved connection)
 */
export class ManualBookmark extends BookmarkBase {
  constructor() {
    super();
    this.type = BookmarkType.MANUAL;
  }
}

/**
 * Quick connect bookmark (temporary)
 */
export class QuickConnectBookmark extends BookmarkBase {
  constructor() {
    super();
    this.type = BookmarkType.QUICK_CONNECT;
  }
}

export default BookmarkBase;
