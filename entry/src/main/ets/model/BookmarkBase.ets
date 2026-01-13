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
    Object.assign(bookmark, json);
    return bookmark;
  }

  /**
   * Convert bookmark to JSON object
   */
  toJson(): object {
    return {
      id: this.id,
      label: this.label,
      type: this.type,
      hostname: this.hostname,
      port: this.port,
      username: this.username,
      domain: this.domain,
      password: this.password,
      screenSettings: this.screenSettings,
      advancedSettings: this.advancedSettings,
      performanceFlags: this.performanceFlags,
      debugSettings: this.debugSettings,
      gatewaySettings: this.gatewaySettings
    };
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
    cloned.screenSettings = { ...this.screenSettings };
    cloned.advancedSettings = { ...this.advancedSettings };
    cloned.performanceFlags = { ...this.performanceFlags };
    cloned.debugSettings = { ...this.debugSettings };
    cloned.gatewaySettings = { ...this.gatewaySettings };
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
