/*
 * HarmonyOS FreeRDP Settings Page
 * 
 * Application settings
 * 
 * Copyright 2026 FreeRDP HarmonyOS Port
 */

import router from '@ohos.router';
import preferences from '@ohos.data.preferences';
import common from '@ohos.app.ability.common';

const TAG = 'SettingsPage';
const SETTINGS_STORE = 'settings';

@Entry
@Component
struct SettingsPage {
  // General settings
  @State clientName: string = '';
  @State autoReconnect: boolean = true;
  @State maxReconnectAttempts: number = 10;
  
  // Display settings
  @State defaultWidth: string = '1920';
  @State defaultHeight: string = '1080';
  @State defaultColorDepth: string = '32';
  
  // Performance settings
  @State enableRemoteFX: boolean = true;
  @State enableGfx: boolean = true;
  @State enableH264: boolean = true;
  
  // Network settings
  @State tcpKeepaliveEnabled: boolean = true;
  @State tcpKeepaliveInterval: number = 15;
  @State heartbeatInterval: number = 30;
  
  // Debug settings
  @State debugLevel: string = 'WARN';
  @State showDebugInfo: boolean = false;
  
  private context = getContext(this) as common.UIAbilityContext;
  private preferencesHelper: preferences.Preferences | null = null;

  aboutToAppear(): void {
    console.info(`${TAG}: aboutToAppear`);
    this.loadSettings();
  }

  /**
   * Load settings from preferences
   */
  async loadSettings(): Promise<void> {
    try {
      this.preferencesHelper = await preferences.getPreferences(this.context, SETTINGS_STORE);
      
      this.clientName = await this.preferencesHelper.get('clientName', '') as string;
      this.autoReconnect = await this.preferencesHelper.get('autoReconnect', true) as boolean;
      this.maxReconnectAttempts = await this.preferencesHelper.get('maxReconnectAttempts', 10) as number;
      
      this.defaultWidth = await this.preferencesHelper.get('defaultWidth', '1920') as string;
      this.defaultHeight = await this.preferencesHelper.get('defaultHeight', '1080') as string;
      this.defaultColorDepth = await this.preferencesHelper.get('defaultColorDepth', '32') as string;
      
      this.enableRemoteFX = await this.preferencesHelper.get('enableRemoteFX', true) as boolean;
      this.enableGfx = await this.preferencesHelper.get('enableGfx', true) as boolean;
      this.enableH264 = await this.preferencesHelper.get('enableH264', true) as boolean;
      
      this.tcpKeepaliveEnabled = await this.preferencesHelper.get('tcpKeepaliveEnabled', true) as boolean;
      this.tcpKeepaliveInterval = await this.preferencesHelper.get('tcpKeepaliveInterval', 15) as number;
      this.heartbeatInterval = await this.preferencesHelper.get('heartbeatInterval', 30) as number;
      
      this.debugLevel = await this.preferencesHelper.get('debugLevel', 'WARN') as string;
      this.showDebugInfo = await this.preferencesHelper.get('showDebugInfo', false) as boolean;
      
      console.info(`${TAG}: Settings loaded`);
    } catch (error) {
      console.error(`${TAG}: Failed to load settings:`, error);
    }
  }

  /**
   * Save settings to preferences
   */
  async saveSettings(): Promise<void> {
    if (!this.preferencesHelper) return;
    
    try {
      await this.preferencesHelper.put('clientName', this.clientName);
      await this.preferencesHelper.put('autoReconnect', this.autoReconnect);
      await this.preferencesHelper.put('maxReconnectAttempts', this.maxReconnectAttempts);
      
      await this.preferencesHelper.put('defaultWidth', this.defaultWidth);
      await this.preferencesHelper.put('defaultHeight', this.defaultHeight);
      await this.preferencesHelper.put('defaultColorDepth', this.defaultColorDepth);
      
      await this.preferencesHelper.put('enableRemoteFX', this.enableRemoteFX);
      await this.preferencesHelper.put('enableGfx', this.enableGfx);
      await this.preferencesHelper.put('enableH264', this.enableH264);
      
      await this.preferencesHelper.put('tcpKeepaliveEnabled', this.tcpKeepaliveEnabled);
      await this.preferencesHelper.put('tcpKeepaliveInterval', this.tcpKeepaliveInterval);
      await this.preferencesHelper.put('heartbeatInterval', this.heartbeatInterval);
      
      await this.preferencesHelper.put('debugLevel', this.debugLevel);
      await this.preferencesHelper.put('showDebugInfo', this.showDebugInfo);
      
      await this.preferencesHelper.flush();
      console.info(`${TAG}: Settings saved`);
    } catch (error) {
      console.error(`${TAG}: Failed to save settings:`, error);
    }
  }

  @Builder
  SectionHeader(title: string) {
    Text(title)
      .fontSize(14)
      .fontColor('#2196F3')
      .fontWeight(FontWeight.Medium)
      .width('100%')
      .padding({ left: 16, top: 24, bottom: 8 })
  }

  @Builder
  SwitchItem(label: string, description: string, value: boolean, onChange: (value: boolean) => void) {
    Row() {
      Column() {
        Text(label)
          .fontSize(16)
          .fontColor('#FFFFFF')
        Text(description)
          .fontSize(12)
          .fontColor('#888888')
          .margin({ top: 4 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
      
      Toggle({ type: ToggleType.Switch, isOn: value })
        .selectedColor('#2196F3')
        .onChange((isOn: boolean) => {
          onChange(isOn);
          this.saveSettings();
        })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
    .backgroundColor('#1E1E1E')
  }

  @Builder
  InputItem(label: string, value: string, onChange: (value: string) => void) {
    Row() {
      Text(label)
        .fontSize(16)
        .fontColor('#FFFFFF')
        .width('40%')
      
      TextInput({ text: value })
        .layoutWeight(1)
        .height(40)
        .backgroundColor('#2D2D2D')
        .fontColor('#FFFFFF')
        .borderRadius(8)
        .onChange((v: string) => {
          onChange(v);
          this.saveSettings();
        })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 8, bottom: 8 })
    .backgroundColor('#1E1E1E')
  }

  @Builder
  SelectItem(label: string, options: string[], value: string, onChange: (value: string) => void) {
    Column() {
      Text(label)
        .fontSize(16)
        .fontColor('#FFFFFF')
        .width('100%')
        .margin({ bottom: 8 })
      
      Row() {
        ForEach(options, (option: string) => {
          Button(option)
            .height(36)
            .fontSize(12)
            .backgroundColor(value === option ? '#2196F3' : '#424242')
            .margin({ right: 8 })
            .onClick(() => {
              onChange(option);
              this.saveSettings();
            })
        })
      }
      .width('100%')
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
    .backgroundColor('#1E1E1E')
    .alignItems(HorizontalAlign.Start)
  }

  build() {
    Column() {
      // Header
      Row() {
        Button({ type: ButtonType.Circle }) {
          Image($r('sys.media.ohos_ic_public_arrow_left'))
            .width(24)
            .height(24)
            .fillColor('#FFFFFF')
        }
        .width(44)
        .height(44)
        .backgroundColor('transparent')
        .onClick(() => { router.back(); })
        
        Text('设置')
          .fontSize(20)
          .fontWeight(FontWeight.Medium)
          .fontColor('#FFFFFF')
          .layoutWeight(1)
          .textAlign(TextAlign.Center)
        
        // Placeholder for symmetry
        Column().width(44)
      }
      .width('100%')
      .padding({ left: 8, right: 8, top: 8, bottom: 8 })
      
      // Settings list
      Scroll() {
        Column() {
          // General settings
          this.SectionHeader('常规')
          
          this.InputItem('客户端名称', this.clientName, (v) => { this.clientName = v; })
          
          this.SwitchItem('自动重连', '断线后自动尝试重新连接', 
            this.autoReconnect, (v) => { this.autoReconnect = v; })
          
          // Display settings
          this.SectionHeader('显示')
          
          this.SelectItem('默认颜色深度', ['16', '24', '32'], 
            this.defaultColorDepth, (v) => { this.defaultColorDepth = v; })
          
          // Performance settings
          this.SectionHeader('性能')
          
          this.SwitchItem('RemoteFX', '启用 RemoteFX 编解码器', 
            this.enableRemoteFX, (v) => { this.enableRemoteFX = v; })
          
          this.SwitchItem('GFX 管道', '启用图形管道优化', 
            this.enableGfx, (v) => { this.enableGfx = v; })
          
          this.SwitchItem('H.264', '启用 H.264/AVC 编码', 
            this.enableH264, (v) => { this.enableH264 = v; })
          
          // Network settings
          this.SectionHeader('网络')
          
          this.SwitchItem('TCP Keepalive', '启用 TCP 保活（防止 NAT 超时）', 
            this.tcpKeepaliveEnabled, (v) => { this.tcpKeepaliveEnabled = v; })
          
          // Debug settings
          this.SectionHeader('调试')
          
          this.SelectItem('日志级别', ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR'], 
            this.debugLevel, (v) => { this.debugLevel = v; })
          
          this.SwitchItem('显示调试信息', '在界面上显示连接状态和性能信息', 
            this.showDebugInfo, (v) => { this.showDebugInfo = v; })
          
          // Spacer
          Column().height(32)
        }
      }
      .layoutWeight(1)
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#121212')
  }
}
