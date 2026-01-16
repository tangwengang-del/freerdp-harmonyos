/*
 * HarmonyOS FreeRDP Session Page
 * 
 * Remote desktop session view with full interaction support
 * 
 * Copyright 2026 FreeRDP HarmonyOS Port
 */

import router from '@ohos.router';
import promptAction from '@ohos.promptAction';
import window from '@ohos.window';
import common from '@ohos.app.ability.common';
import image from '@ohos.multimedia.image';
import { BookmarkBase } from '../model/BookmarkBase';
import { SessionState, ConnectionState, GlobalSessionManager } from '../model/SessionState';
import LibFreeRDP, { EventListener, BookmarkSettings, ScreenSettings, AdvancedSettings, PerformanceFlags, DebugSettings, GatewaySettings } from '../services/LibFreeRDP';
import { RdpBackgroundService } from '../services/RdpBackgroundService';
import { NetworkManager, SessionInfo } from '../services/NetworkManager';
import { SessionView } from '../components/SessionView';
import { TouchPointerView } from '../components/TouchPointerView';
import { VirtualKeyboard } from '../components/VirtualKeyboard';

/**
 * Router params interface
 */
interface SessionPageParams {
  bookmark?: object;
}

const TAG = 'SessionPage';

// Global callbacks for event listener (workaround for ArkTS struct limitations)
let globalOnPreConnectCallback: ((instance: number) => void) | null = null;
let globalOnConnectionSuccessCallback: ((instance: number) => void) | null = null;
let globalOnConnectionFailureCallback: ((instance: number) => void) | null = null;
let globalOnDisconnectingCallback: ((instance: number) => void) | null = null;
let globalOnDisconnectedCallback: ((instance: number) => void) | null = null;

/**
 * Session event listener implementation class
 */
class SessionEventListenerImpl implements EventListener {
  OnPreConnect(instance: number): void {
    if (globalOnPreConnectCallback) {
      globalOnPreConnectCallback(instance);
    }
  }

  OnConnectionSuccess(instance: number): void {
    if (globalOnConnectionSuccessCallback) {
      globalOnConnectionSuccessCallback(instance);
    }
  }

  OnConnectionFailure(instance: number): void {
    if (globalOnConnectionFailureCallback) {
      globalOnConnectionFailureCallback(instance);
    }
  }

  OnDisconnecting(instance: number): void {
    if (globalOnDisconnectingCallback) {
      globalOnDisconnectingCallback(instance);
    }
  }

  OnDisconnected(instance: number): void {
    if (globalOnDisconnectedCallback) {
      globalOnDisconnectedCallback(instance);
    }
  }
}

// Heartbeat interval for keeping connection alive
const HEARTBEAT_INTERVAL_MS = 30000;

@Entry
@Component
struct SessionPage {
  // Session state
  @State session: SessionState | null = null;
  @State connectionState: ConnectionState = ConnectionState.DISCONNECTED;
  @State statusMessage: string = '正在连接...';
  
  // Display state
  @State desktopWidth: number = 1920;
  @State desktopHeight: number = 1080;
  @State pixelMap: image.PixelMap | null = null;
  @State viewScale: number = 1.0;
  @State offsetX: number = 0;
  @State offsetY: number = 0;
  
  // UI state
  @State showToolbar: boolean = true;
  @State showKeyboard: boolean = false;
  @State cursorType: number = 1;
  @State isInBackground: boolean = false;
  @State isScreenLocked: boolean = false;
  
  // Components
  private sessionView: SessionView | null = null;
  private touchPointerView: TouchPointerView | null = null;
  private virtualKeyboard: VirtualKeyboard | null = null;
  
  // Services
  private backgroundService: RdpBackgroundService | null = null;
  private context = getContext(this) as common.UIAbilityContext;
  
  // Heartbeat timer
  private heartbeatTimer: number = -1;
  
  // Auto-hide toolbar timer
  private toolbarHideTimer: number = -1;
  private static readonly TOOLBAR_HIDE_DELAY = 4000;

  aboutToAppear(): void {
    console.info(`${TAG}: aboutToAppear`);
    
    // Get bookmark from router params
    const params = router.getParams() as SessionPageParams;
    if (params && params.bookmark) {
      const bookmark = BookmarkBase.fromJson(params.bookmark);
      this.initSession(bookmark);
    } else {
      console.error(`${TAG}: No bookmark provided`);
      this.statusMessage = '错误：未提供连接信息';
    }
    
    // Initialize network manager
    NetworkManager.initialize();
    NetworkManager.setOnNetworkLostCallback(() => {
      this.onNetworkLost();
    });
    NetworkManager.setOnReconnectSuccessCallback(() => {
      this.onReconnectSuccess();
    });
    
    // Keep screen on
    this.setKeepScreenOn(true);
  }

  aboutToDisappear(): void {
    console.info(`${TAG}: aboutToDisappear`);
    
    this.cleanup();
  }

  onPageShow(): void {
    console.info(`${TAG}: onPageShow`);
    this.isInBackground = false;
    
    if (this.session) {
      this.session.setInBackground(false);
      // Resume graphics
      if (this.session.getInstance() !== 0) {
        LibFreeRDP.setClientDecoding(this.session.getInstance(), true);
      }
    }
  }

  onPageHide(): void {
    console.info(`${TAG}: onPageHide`);
    this.isInBackground = true;
    
    if (this.session) {
      this.session.setInBackground(true);
      // Suppress graphics but keep audio
      if (this.session.getInstance() !== 0) {
        LibFreeRDP.setClientDecoding(this.session.getInstance(), false);
      }
    }
  }

  /**
   * Initialize session with bookmark
   */
  private async initSession(bookmark: BookmarkBase): Promise<void> {
    console.info(`${TAG}: Initializing session for ${bookmark.hostname}`);
    
    // Create session state
    this.session = new SessionState(bookmark);
    this.connectionState = ConnectionState.CONNECTING;
    this.statusMessage = '正在连接...';
    
    // Set up event listener callbacks
    globalOnPreConnectCallback = (instance: number): void => {
      console.info(`${TAG}: OnPreConnect`);
      this.statusMessage = '正在建立连接...';
    };
    globalOnConnectionSuccessCallback = (instance: number): void => {
      console.info(`${TAG}: OnConnectionSuccess`);
      this.onConnectionSuccess(instance);
    };
    globalOnConnectionFailureCallback = (instance: number): void => {
      console.error(`${TAG}: OnConnectionFailure`);
      this.onConnectionFailure(instance);
    };
    globalOnDisconnectingCallback = (instance: number): void => {
      console.info(`${TAG}: OnDisconnecting`);
      this.statusMessage = '正在断开连接...';
    };
    globalOnDisconnectedCallback = (instance: number): void => {
      console.info(`${TAG}: OnDisconnected`);
      this.onDisconnected(instance);
    };
    
    // Set up event listener
    const eventListener = new SessionEventListenerImpl();
    LibFreeRDP.setEventListener(eventListener);
    
    // Create FreeRDP instance
    const instance = LibFreeRDP.newInstance();
    if (instance === 0) {
      this.statusMessage = '错误：无法创建连接实例';
      this.connectionState = ConnectionState.DISCONNECTED;
      return;
    }
    
    this.session.setInstance(instance);
    GlobalSessionManager.registerSession(instance, this.session);
    
    // Set connection info
    const connectionInfo: BookmarkSettings = this.createConnectionInfo(bookmark);
    if (!LibFreeRDP.setConnectionInfo(instance, connectionInfo)) {
      this.statusMessage = '错误：连接参数无效';
      this.connectionState = ConnectionState.DISCONNECTED;
      return;
    }
    
    // Set session info for reconnection
    const sessionInfo: SessionInfo = {
      instance: instance,
      hostname: bookmark.hostname,
      port: bookmark.port,
      username: bookmark.username,
      domain: bookmark.domain,
      password: bookmark.password
    };
    NetworkManager.setSessionInfo(sessionInfo);
    
    // Connect
    if (!LibFreeRDP.connect(instance)) {
      this.statusMessage = '错误：连接失败';
      this.connectionState = ConnectionState.DISCONNECTED;
    }
  }

  /**
   * Handle connection success
   */
  private async onConnectionSuccess(instance: number): Promise<void> {
    this.connectionState = ConnectionState.CONNECTED;
    this.statusMessage = '已连接';
    
    if (this.session) {
      this.session.setConnectionState(ConnectionState.CONNECTED);
    }
    
    // Start background service
    this.backgroundService = new RdpBackgroundService(this.context);
    await this.backgroundService.start();
    
    // Start heartbeat
    this.startHeartbeat();
    
    // Auto-hide toolbar
    this.scheduleToolbarHide();
    
    promptAction.showToast({ message: '连接成功' });
  }

  /**
   * Handle connection failure
   */
  private onConnectionFailure(instance: number): void {
    const errorStr = LibFreeRDP.getLastErrorString(instance);
    this.statusMessage = `连接失败: ${errorStr}`;
    this.connectionState = ConnectionState.DISCONNECTED;
    
    if (this.session) {
      this.session.setConnectionState(ConnectionState.DISCONNECTED);
    }
    
    promptAction.showToast({ message: `连接失败: ${errorStr}` });
  }

  /**
   * Handle disconnection
   */
  private async onDisconnected(instance: number): Promise<void> {
    this.connectionState = ConnectionState.DISCONNECTED;
    this.statusMessage = '已断开连接';
    
    if (this.session) {
      this.session.setConnectionState(ConnectionState.DISCONNECTED);
    }
    
    // Stop background service
    if (this.backgroundService) {
      await this.backgroundService.stop();
    }
    
    // Stop heartbeat
    this.stopHeartbeat();
    
    promptAction.showToast({ message: '连接已断开' });
  }

  /**
   * Handle network lost
   */
  private onNetworkLost(): void {
    console.warn(`${TAG}: Network lost`);
    this.statusMessage = '网络连接丢失，正在等待重连...';
    this.connectionState = ConnectionState.RECONNECTING;
  }

  /**
   * Handle reconnect success
   */
  private onReconnectSuccess(): void {
    console.info(`${TAG}: Reconnect success`);
    this.statusMessage = '已重新连接';
    this.connectionState = ConnectionState.CONNECTED;
    promptAction.showToast({ message: '已重新连接' });
  }

  /**
   * Setup screen lock listener
   * Note: screenLock.on/off API is not available in current SDK version
   */
  private setupScreenLockListener(): void {
    // Screen lock monitoring is not available in current SDK
    console.info(`${TAG}: Screen lock listener setup skipped (API not available)`);
  }

  /**
   * Start heartbeat timer
   */
  private startHeartbeat(): void {
    this.stopHeartbeat();
    
    const heartbeatCallback = (): void => {
      if (this.session && this.session.getInstance() !== 0 && 
          this.connectionState === ConnectionState.CONNECTED) {
        LibFreeRDP.sendHeartbeat(this.session.getInstance());
      }
    };
    this.heartbeatTimer = setInterval(heartbeatCallback, HEARTBEAT_INTERVAL_MS) as number;
    
    console.info(`${TAG}: Heartbeat started (interval: ${HEARTBEAT_INTERVAL_MS}ms)`);
  }

  /**
   * Stop heartbeat timer
   */
  private stopHeartbeat(): void {
    if (this.heartbeatTimer !== -1) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = -1;
      console.info(`${TAG}: Heartbeat stopped`);
    }
  }

  /**
   * Create connection info from bookmark
   */
  private createConnectionInfo(bookmark: BookmarkBase): BookmarkSettings {
    // Create screen settings with explicit type
    const screenSettings: ScreenSettings = {
      width: bookmark.screenSettings.width,
      height: bookmark.screenSettings.height,
      colors: bookmark.screenSettings.colors
    };

    // Create advanced settings with explicit type
    const advancedSettings: AdvancedSettings = {
      consoleMode: bookmark.advancedSettings.consoleMode,
      security: bookmark.advancedSettings.security,
      remoteProgram: bookmark.advancedSettings.remoteProgram,
      workDir: bookmark.advancedSettings.workDir,
      redirectSDCard: bookmark.advancedSettings.redirectSDCard,
      redirectSound: bookmark.advancedSettings.redirectSound,
      redirectMicrophone: bookmark.advancedSettings.redirectMicrophone
    };

    // Create performance flags with explicit type
    const performanceFlags: PerformanceFlags = {
      remoteFX: bookmark.performanceFlags.remoteFX,
      gfx: bookmark.performanceFlags.gfx,
      h264: bookmark.performanceFlags.h264,
      wallpaper: bookmark.performanceFlags.wallpaper,
      fullWindowDrag: bookmark.performanceFlags.fullWindowDrag,
      menuAnimations: bookmark.performanceFlags.menuAnimations,
      theming: bookmark.performanceFlags.theming,
      fontSmoothing: bookmark.performanceFlags.fontSmoothing,
      desktopComposition: bookmark.performanceFlags.desktopComposition
    };

    // Create debug settings with explicit type
    const debugSettings: DebugSettings = {
      asyncChannel: bookmark.debugSettings.asyncChannel,
      asyncUpdate: bookmark.debugSettings.asyncUpdate,
      debugLevel: bookmark.debugSettings.debugLevel
    };

    // Create bookmark settings
    const info: BookmarkSettings = {
      hostname: bookmark.hostname,
      port: bookmark.port,
      username: bookmark.username,
      domain: bookmark.domain,
      password: bookmark.password,
      screenSettings: screenSettings,
      advancedSettings: advancedSettings,
      performanceFlags: performanceFlags,
      debugSettings: debugSettings
    };

    // Add gateway settings if enabled
    if (bookmark.gatewaySettings && bookmark.gatewaySettings.enabled) {
      const gatewaySettings: GatewaySettings = {
        enabled: bookmark.gatewaySettings.enabled,
        hostname: bookmark.gatewaySettings.hostname,
        port: bookmark.gatewaySettings.port,
        username: bookmark.gatewaySettings.username,
        domain: bookmark.gatewaySettings.domain,
        password: bookmark.gatewaySettings.password
      };
      info.gatewaySettings = gatewaySettings;
    }
    return info;
  }

  /**
   * Schedule toolbar auto-hide
   */
  private scheduleToolbarHide(): void {
    if (this.toolbarHideTimer !== -1) {
      clearTimeout(this.toolbarHideTimer);
    }
    
    const hideCallback = (): void => {
      this.showToolbar = false;
    };
    this.toolbarHideTimer = setTimeout(hideCallback, SessionPage.TOOLBAR_HIDE_DELAY) as number;
  }

  /**
   * Toggle toolbar visibility
   */
  private toggleToolbar(): void {
    this.showToolbar = !this.showToolbar;
    if (this.showToolbar) {
      this.scheduleToolbarHide();
    }
  }

  /**
   * Disconnect
   */
  private disconnect(): void {
    if (this.session && this.session.getInstance() !== 0) {
      LibFreeRDP.disconnect(this.session.getInstance());
    }
  }

  /**
   * Set keep screen on
   */
  private async setKeepScreenOn(keepOn: boolean): Promise<void> {
    try {
      const win = await window.getLastWindow(this.context);
      await win.setWindowKeepScreenOn(keepOn);
    } catch (error) {
      console.error(`${TAG}: Failed to set keep screen on:`, error);
    }
  }

  /**
   * Cleanup resources
   */
  private async cleanup(): Promise<void> {
    console.info(`${TAG}: Cleaning up`);
    
    // Stop timers
    this.stopHeartbeat();
    if (this.toolbarHideTimer !== -1) {
      clearTimeout(this.toolbarHideTimer);
    }
    
    // Disconnect
    if (this.session && this.session.getInstance() !== 0) {
      LibFreeRDP.freeInstance(this.session.getInstance());
      GlobalSessionManager.removeSession(this.session.getInstance());
    }
    
    // Stop background service
    if (this.backgroundService) {
      await this.backgroundService.stop();
    }
    
    // Clear network manager
    NetworkManager.clearSessionInfo();
    
    // Allow screen to turn off
    this.setKeepScreenOn(false);
    
    // Screen lock listener cleanup not needed (API not available)
  }

  /**
   * Go back
   */
  private goBack(): void {
    promptAction.showDialog({
      title: '断开连接',
      message: '确定要断开远程桌面连接吗？',
      buttons: [
        { text: '取消', color: '#888888' },
        { text: '断开', color: '#F44336' }
      ]
    }).then((result) => {
      if (result.index === 1) {
        this.disconnect();
        router.back();
      }
    });
  }

  @Builder
  Toolbar() {
    Row() {
      // Back button
      Button({ type: ButtonType.Circle }) {
        Image($r('sys.media.ohos_ic_public_arrow_left'))
          .width(24)
          .height(24)
          .fillColor('#FFFFFF')
      }
      .width(44)
      .height(44)
      .backgroundColor('#80424242')
      .onClick(() => {
        this.goBack();
      })
      
      Blank()
      
      // Status
      Text(this.statusMessage)
        .fontSize(14)
        .fontColor('#FFFFFF')
        .backgroundColor('#80000000')
        .padding({ left: 12, right: 12, top: 6, bottom: 6 })
        .borderRadius(16)
      
      Blank()
      
      // Keyboard button
      Button({ type: ButtonType.Circle }) {
        Image($r('sys.media.ohos_ic_public_edit'))
          .width(24)
          .height(24)
          .fillColor('#FFFFFF')
      }
      .width(44)
      .height(44)
      .backgroundColor('#80424242')
      .margin({ right: 8 })
      .onClick(() => {
        this.showKeyboard = !this.showKeyboard;
      })
      
      // More options
      Button({ type: ButtonType.Circle }) {
        Image($r('sys.media.ohos_ic_public_more'))
          .width(24)
          .height(24)
          .fillColor('#FFFFFF')
      }
      .width(44)
      .height(44)
      .backgroundColor('#80424242')
      .onClick(() => {
        // TODO: Show more options menu
      })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 8, bottom: 8 })
    .backgroundColor('#40000000')
  }

  build() {
    Stack() {
      // Session view
      if (this.connectionState === ConnectionState.CONNECTED) {
        SessionView({
          instance: this.session?.getInstance() ?? 0,
          desktopWidth: this.desktopWidth,
          desktopHeight: this.desktopHeight,
          pixelMap: this.pixelMap
        })
      } else {
        // Connection status view
        Column() {
          if (this.connectionState === ConnectionState.CONNECTING ||
              this.connectionState === ConnectionState.RECONNECTING) {
            LoadingProgress()
              .width(64)
              .height(64)
              .color('#2196F3')
          } else {
            Image($r('sys.media.ohos_ic_public_fail'))
              .width(64)
              .height(64)
              .fillColor('#F44336')
          }
          
          Text(this.statusMessage)
            .fontSize(18)
            .fontColor('#FFFFFF')
            .margin({ top: 24 })
            .textAlign(TextAlign.Center)
          
          if (this.connectionState === ConnectionState.DISCONNECTED) {
            Button('返回')
              .width(120)
              .height(44)
              .backgroundColor('#2196F3')
              .margin({ top: 24 })
              .onClick(() => {
                router.back();
              })
          }
        }
        .width('100%')
        .height('100%')
        .backgroundColor('#121212')
        .justifyContent(FlexAlign.Center)
      }
      
      // Touch pointer (when connected)
      if (this.connectionState === ConnectionState.CONNECTED) {
        TouchPointerView({
          instance: this.session?.getInstance() ?? 0,
          viewWidth: 0, // Will be set by SessionView
          viewHeight: 0,
          desktopWidth: this.desktopWidth,
          desktopHeight: this.desktopHeight,
          viewScale: this.viewScale,
          offsetX: this.offsetX,
          offsetY: this.offsetY
        })
      }
      
      // Toolbar
      if (this.showToolbar) {
        Column() {
          this.Toolbar()
        }
        .width('100%')
        .position({ x: 0, y: 0 })
      }
      
      // Virtual keyboard
      if (this.showKeyboard) {
        Column() {
          VirtualKeyboard({
            instance: this.session?.getInstance() ?? 0
          })
        }
        .width('100%')
        .position({ x: 0, y: '100%' })
        .translate({ y: -200 })
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#000000')
    .onClick(() => {
      this.toggleToolbar();
    })
  }
}
