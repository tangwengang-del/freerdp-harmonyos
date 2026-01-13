/*
 * HarmonyOS FreeRDP Session Page
 * 
 * Remote desktop session view with full interaction support
 * 
 * Copyright 2026 FreeRDP HarmonyOS Port
 */

import router from '@ohos.router';
import promptAction from '@ohos.promptAction';
import screenLock from '@ohos.screenLock';
import window from '@ohos.window';
import common from '@ohos.app.ability.common';
import image from '@ohos.multimedia.image';
import { BookmarkBase } from '../model/BookmarkBase';
import { SessionState, ConnectionState, GlobalSessionManager } from '../model/SessionState';
import LibFreeRDP, { UIEventListener, EventListener } from '../services/LibFreeRDP';
import { RdpBackgroundService } from '../services/RdpBackgroundService';
import { NetworkManager } from '../services/NetworkManager';
import { SessionView, SessionViewListener } from '../components/SessionView';
import { TouchPointerView, TouchPointerListener } from '../components/TouchPointerView';
import { VirtualKeyboard, VirtualKeyboardListener } from '../components/VirtualKeyboard';

const TAG = 'SessionPage';

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
  @State scale: number = 1.0;
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
    const params = router.getParams() as Record<string, object>;
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
    
    // Setup screen lock listener
    this.setupScreenLockListener();
    
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
    
    // Set up event listener
    LibFreeRDP.setEventListener({
      OnPreConnect: (instance: number) => {
        console.info(`${TAG}: OnPreConnect`);
        this.statusMessage = '正在建立连接...';
      },
      OnConnectionSuccess: (instance: number) => {
        console.info(`${TAG}: OnConnectionSuccess`);
        this.onConnectionSuccess(instance);
      },
      OnConnectionFailure: (instance: number) => {
        console.error(`${TAG}: OnConnectionFailure`);
        this.onConnectionFailure(instance);
      },
      OnDisconnecting: (instance: number) => {
        console.info(`${TAG}: OnDisconnecting`);
        this.statusMessage = '正在断开连接...';
      },
      OnDisconnected: (instance: number) => {
        console.info(`${TAG}: OnDisconnected`);
        this.onDisconnected(instance);
      }
    } as EventListener);
    
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
    if (!LibFreeRDP.setConnectionInfo(instance, {
      hostname: bookmark.hostname,
      port: bookmark.port,
      username: bookmark.username,
      domain: bookmark.domain,
      password: bookmark.password,
      screenSettings: bookmark.screenSettings,
      advancedSettings: bookmark.advancedSettings,
      performanceFlags: bookmark.performanceFlags,
      debugSettings: bookmark.debugSettings,
      gatewaySettings: bookmark.gatewaySettings?.enabled ? bookmark.gatewaySettings : undefined
    })) {
      this.statusMessage = '错误：连接参数无效';
      this.connectionState = ConnectionState.DISCONNECTED;
      return;
    }
    
    // Set session info for reconnection
    NetworkManager.setSessionInfo({
      instance: instance,
      hostname: bookmark.hostname,
      port: bookmark.port,
      username: bookmark.username,
      domain: bookmark.domain,
      password: bookmark.password
    });
    
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
   */
  private setupScreenLockListener(): void {
    try {
      screenLock.on('screenLockChange', (isLocked: boolean) => {
        console.info(`${TAG}: Screen lock changed: ${isLocked}`);
        this.isScreenLocked = isLocked;
        
        if (this.session) {
          this.session.setScreenLocked(isLocked);
          
          // Suppress graphics when locked, resume when unlocked
          if (this.session.getInstance() !== 0) {
            LibFreeRDP.setClientDecoding(this.session.getInstance(), !isLocked);
          }
        }
      });
    } catch (error) {
      console.error(`${TAG}: Failed to setup screen lock listener:`, error);
    }
  }

  /**
   * Start heartbeat timer
   */
  private startHeartbeat(): void {
    this.stopHeartbeat();
    
    this.heartbeatTimer = setInterval(() => {
      if (this.session && this.session.getInstance() !== 0 && 
          this.connectionState === ConnectionState.CONNECTED) {
        LibFreeRDP.sendHeartbeat(this.session.getInstance());
      }
    }, HEARTBEAT_INTERVAL_MS) as unknown as number;
    
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
   * Schedule toolbar auto-hide
   */
  private scheduleToolbarHide(): void {
    if (this.toolbarHideTimer !== -1) {
      clearTimeout(this.toolbarHideTimer);
    }
    
    this.toolbarHideTimer = setTimeout(() => {
      this.showToolbar = false;
    }, SessionPage.TOOLBAR_HIDE_DELAY) as unknown as number;
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
    
    // Remove screen lock listener
    try {
      screenLock.off('screenLockChange');
    } catch (e) {
      // Ignore
    }
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
        Image($r('sys.media.ohos_ic_public_keyboard'))
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
          scale: this.scale,
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
