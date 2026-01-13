/*
 * HarmonyOS FreeRDP Bookmark Edit Page
 * 
 * Page for creating and editing bookmarks
 * 
 * Copyright 2026 FreeRDP HarmonyOS Port
 */

import router from '@ohos.router';
import promptAction from '@ohos.promptAction';
import { BookmarkBase, ManualBookmark } from '../model/BookmarkBase';

const TAG = 'BookmarkPage';

@Entry
@Component
struct BookmarkPage {
  @State mode: string = 'create';
  @State bookmark: BookmarkBase = new ManualBookmark();
  
  // Basic settings
  @State label: string = '';
  @State hostname: string = '';
  @State port: string = '3389';
  @State username: string = '';
  @State domain: string = '';
  @State password: string = '';
  
  // Screen settings
  @State screenWidth: string = '1920';
  @State screenHeight: string = '1080';
  @State colorDepth: string = '32';
  
  // Advanced settings
  @State consoleMode: boolean = false;
  @State security: number = 0;
  @State redirectSound: number = 0;
  @State redirectMicrophone: boolean = false;
  
  // Performance settings
  @State remoteFX: boolean = true;
  @State gfx: boolean = true;
  @State h264: boolean = true;
  @State wallpaper: boolean = false;
  @State theming: boolean = true;
  @State fontSmoothing: boolean = true;
  
  // Gateway settings
  @State gatewayEnabled: boolean = false;
  @State gatewayHostname: string = '';
  @State gatewayPort: string = '443';
  @State gatewayUsername: string = '';
  @State gatewayPassword: string = '';
  
  // UI state
  @State currentTab: number = 0;

  aboutToAppear(): void {
    console.info(`${TAG}: aboutToAppear`);
    
    const params = router.getParams() as Record<string, object | string>;
    if (params) {
      this.mode = (params.mode as string) || 'create';
      
      if (params.bookmark) {
        this.bookmark = BookmarkBase.fromJson(params.bookmark as object);
        this.loadFromBookmark();
      }
    }
  }

  /**
   * Load UI state from bookmark
   */
  private loadFromBookmark(): void {
    this.label = this.bookmark.label;
    this.hostname = this.bookmark.hostname;
    this.port = this.bookmark.port.toString();
    this.username = this.bookmark.username;
    this.domain = this.bookmark.domain;
    this.password = this.bookmark.password;
    
    this.screenWidth = this.bookmark.screenSettings.width.toString();
    this.screenHeight = this.bookmark.screenSettings.height.toString();
    this.colorDepth = this.bookmark.screenSettings.colors.toString();
    
    this.consoleMode = this.bookmark.advancedSettings.consoleMode;
    this.security = this.bookmark.advancedSettings.security;
    this.redirectSound = this.bookmark.advancedSettings.redirectSound;
    this.redirectMicrophone = this.bookmark.advancedSettings.redirectMicrophone;
    
    this.remoteFX = this.bookmark.performanceFlags.remoteFX;
    this.gfx = this.bookmark.performanceFlags.gfx;
    this.h264 = this.bookmark.performanceFlags.h264;
    this.wallpaper = this.bookmark.performanceFlags.wallpaper;
    this.theming = this.bookmark.performanceFlags.theming;
    this.fontSmoothing = this.bookmark.performanceFlags.fontSmoothing;
    
    this.gatewayEnabled = this.bookmark.gatewaySettings.enabled;
    this.gatewayHostname = this.bookmark.gatewaySettings.hostname;
    this.gatewayPort = this.bookmark.gatewaySettings.port.toString();
    this.gatewayUsername = this.bookmark.gatewaySettings.username;
    this.gatewayPassword = this.bookmark.gatewaySettings.password;
  }

  /**
   * Save UI state to bookmark
   */
  private saveToBookmark(): void {
    this.bookmark.label = this.label;
    this.bookmark.hostname = this.hostname;
    this.bookmark.port = parseInt(this.port) || 3389;
    this.bookmark.username = this.username;
    this.bookmark.domain = this.domain;
    this.bookmark.password = this.password;
    
    this.bookmark.screenSettings.width = parseInt(this.screenWidth) || 1920;
    this.bookmark.screenSettings.height = parseInt(this.screenHeight) || 1080;
    this.bookmark.screenSettings.colors = parseInt(this.colorDepth) || 32;
    
    this.bookmark.advancedSettings.consoleMode = this.consoleMode;
    this.bookmark.advancedSettings.security = this.security;
    this.bookmark.advancedSettings.redirectSound = this.redirectSound;
    this.bookmark.advancedSettings.redirectMicrophone = this.redirectMicrophone;
    
    this.bookmark.performanceFlags.remoteFX = this.remoteFX;
    this.bookmark.performanceFlags.gfx = this.gfx;
    this.bookmark.performanceFlags.h264 = this.h264;
    this.bookmark.performanceFlags.wallpaper = this.wallpaper;
    this.bookmark.performanceFlags.theming = this.theming;
    this.bookmark.performanceFlags.fontSmoothing = this.fontSmoothing;
    
    this.bookmark.gatewaySettings.enabled = this.gatewayEnabled;
    this.bookmark.gatewaySettings.hostname = this.gatewayHostname;
    this.bookmark.gatewaySettings.port = parseInt(this.gatewayPort) || 443;
    this.bookmark.gatewaySettings.username = this.gatewayUsername;
    this.bookmark.gatewaySettings.password = this.gatewayPassword;
  }

  /**
   * Validate and save
   */
  private save(): void {
    this.saveToBookmark();
    
    const errors = this.bookmark.validate();
    if (errors.length > 0) {
      promptAction.showToast({ message: errors[0] });
      return;
    }
    
    // Return bookmark to previous page
    router.back({
      url: 'pages/Index',
      params: {
        savedBookmark: this.bookmark.toJson()
      }
    });
  }

  @Builder
  InputField(label: string, value: string, placeholder: string, 
             onChange: (value: string) => void, inputType: InputType = InputType.Normal) {
    Column() {
      Text(label)
        .fontSize(14)
        .fontColor('#AAAAAA')
        .margin({ bottom: 8 })
      
      TextInput({ placeholder: placeholder, text: value })
        .width('100%')
        .height(48)
        .backgroundColor('#2D2D2D')
        .fontColor('#FFFFFF')
        .placeholderColor('#666666')
        .borderRadius(8)
        .type(inputType)
        .onChange(onChange)
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .margin({ bottom: 16 })
  }

  @Builder
  SwitchField(label: string, value: boolean, onChange: (value: boolean) => void) {
    Row() {
      Text(label)
        .fontSize(16)
        .fontColor('#FFFFFF')
        .layoutWeight(1)
      
      Toggle({ type: ToggleType.Switch, isOn: value })
        .selectedColor('#2196F3')
        .onChange(onChange)
    }
    .width('100%')
    .padding({ top: 12, bottom: 12 })
  }

  @Builder
  BasicSettingsTab() {
    Scroll() {
      Column() {
        this.InputField('连接名称', this.label, '可选，用于显示', 
          (v) => { this.label = v; })
        
        this.InputField('主机地址', this.hostname, '例如: 192.168.1.100', 
          (v) => { this.hostname = v; })
        
        this.InputField('端口', this.port, '默认: 3389', 
          (v) => { this.port = v; }, InputType.Number)
        
        this.InputField('用户名', this.username, '登录用户名', 
          (v) => { this.username = v; })
        
        this.InputField('域', this.domain, '可选', 
          (v) => { this.domain = v; })
        
        this.InputField('密码', this.password, '登录密码', 
          (v) => { this.password = v; }, InputType.Password)
      }
      .padding(16)
    }
    .width('100%')
    .layoutWeight(1)
  }

  @Builder
  ScreenSettingsTab() {
    Scroll() {
      Column() {
        this.InputField('宽度', this.screenWidth, '默认: 1920', 
          (v) => { this.screenWidth = v; }, InputType.Number)
        
        this.InputField('高度', this.screenHeight, '默认: 1080', 
          (v) => { this.screenHeight = v; }, InputType.Number)
        
        Text('颜色深度')
          .fontSize(14)
          .fontColor('#AAAAAA')
          .margin({ bottom: 8 })
        
        Row() {
          ForEach(['16', '24', '32'], (depth: string) => {
            Button(depth + ' 位')
              .width('30%')
              .height(40)
              .backgroundColor(this.colorDepth === depth ? '#2196F3' : '#424242')
              .margin(4)
              .onClick(() => { this.colorDepth = depth; })
          })
        }
        .width('100%')
        .justifyContent(FlexAlign.SpaceEvenly)
        .margin({ bottom: 16 })
        
        Text('预设分辨率')
          .fontSize(14)
          .fontColor('#AAAAAA')
          .margin({ top: 16, bottom: 8 })
        
        Column() {
          ForEach([
            { label: '1920x1080 (Full HD)', w: '1920', h: '1080' },
            { label: '1366x768 (HD)', w: '1366', h: '768' },
            { label: '1280x720 (720p)', w: '1280', h: '720' },
            { label: '2560x1440 (2K)', w: '2560', h: '1440' },
          ], (preset: { label: string, w: string, h: string }) => {
            Button(preset.label)
              .width('100%')
              .height(44)
              .backgroundColor('#424242')
              .margin({ bottom: 8 })
              .onClick(() => {
                this.screenWidth = preset.w;
                this.screenHeight = preset.h;
              })
          })
        }
      }
      .padding(16)
    }
    .width('100%')
    .layoutWeight(1)
  }

  @Builder
  AdvancedSettingsTab() {
    Scroll() {
      Column() {
        this.SwitchField('控制台/管理员会话', this.consoleMode, 
          (v) => { this.consoleMode = v; })
        
        Divider().color('#333333')
        
        Text('安全协议')
          .fontSize(14)
          .fontColor('#AAAAAA')
          .margin({ top: 16, bottom: 8 })
        
        Row() {
          ForEach([
            { label: '自动', value: 0 },
            { label: 'RDP', value: 1 },
            { label: 'TLS', value: 2 },
            { label: 'NLA', value: 3 },
          ], (opt: { label: string, value: number }) => {
            Button(opt.label)
              .width('22%')
              .height(36)
              .fontSize(12)
              .backgroundColor(this.security === opt.value ? '#2196F3' : '#424242')
              .margin(2)
              .onClick(() => { this.security = opt.value; })
          })
        }
        .width('100%')
        .margin({ bottom: 16 })
        
        Divider().color('#333333')
        
        Text('音频重定向')
          .fontSize(14)
          .fontColor('#AAAAAA')
          .margin({ top: 16, bottom: 8 })
        
        Row() {
          ForEach([
            { label: '本地', value: 0 },
            { label: '远程', value: 1 },
            { label: '禁用', value: 2 },
          ], (opt: { label: string, value: number }) => {
            Button(opt.label)
              .width('30%')
              .height(40)
              .backgroundColor(this.redirectSound === opt.value ? '#2196F3' : '#424242')
              .margin(4)
              .onClick(() => { this.redirectSound = opt.value; })
          })
        }
        .width('100%')
        .margin({ bottom: 16 })
        
        this.SwitchField('麦克风重定向', this.redirectMicrophone, 
          (v) => { this.redirectMicrophone = v; })
        
        Divider().color('#333333')
        
        Text('性能优化')
          .fontSize(14)
          .fontColor('#AAAAAA')
          .margin({ top: 16, bottom: 8 })
        
        this.SwitchField('RemoteFX', this.remoteFX, 
          (v) => { this.remoteFX = v; })
        this.SwitchField('GFX 管道', this.gfx, 
          (v) => { this.gfx = v; })
        this.SwitchField('H.264 编码', this.h264, 
          (v) => { this.h264 = v; })
        this.SwitchField('显示壁纸', this.wallpaper, 
          (v) => { this.wallpaper = v; })
        this.SwitchField('视觉主题', this.theming, 
          (v) => { this.theming = v; })
        this.SwitchField('字体平滑', this.fontSmoothing, 
          (v) => { this.fontSmoothing = v; })
      }
      .padding(16)
    }
    .width('100%')
    .layoutWeight(1)
  }

  @Builder
  GatewaySettingsTab() {
    Scroll() {
      Column() {
        this.SwitchField('启用 RD 网关', this.gatewayEnabled, 
          (v) => { this.gatewayEnabled = v; })
        
        if (this.gatewayEnabled) {
          Divider().color('#333333').margin({ top: 8, bottom: 16 })
          
          this.InputField('网关主机', this.gatewayHostname, '网关服务器地址', 
            (v) => { this.gatewayHostname = v; })
          
          this.InputField('网关端口', this.gatewayPort, '默认: 443', 
            (v) => { this.gatewayPort = v; }, InputType.Number)
          
          this.InputField('网关用户名', this.gatewayUsername, '网关登录用户', 
            (v) => { this.gatewayUsername = v; })
          
          this.InputField('网关密码', this.gatewayPassword, '网关登录密码', 
            (v) => { this.gatewayPassword = v; }, InputType.Password)
        }
      }
      .padding(16)
    }
    .width('100%')
    .layoutWeight(1)
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
        
        Text(this.mode === 'create' ? '新建连接' : '编辑连接')
          .fontSize(20)
          .fontWeight(FontWeight.Medium)
          .fontColor('#FFFFFF')
          .layoutWeight(1)
          .textAlign(TextAlign.Center)
        
        Button('保存')
          .height(36)
          .backgroundColor('#2196F3')
          .onClick(() => { this.save(); })
      }
      .width('100%')
      .padding({ left: 8, right: 16, top: 8, bottom: 8 })
      
      // Tabs
      Tabs({ barPosition: BarPosition.Start }) {
        TabContent() {
          this.BasicSettingsTab()
        }
        .tabBar('基本')
        
        TabContent() {
          this.ScreenSettingsTab()
        }
        .tabBar('屏幕')
        
        TabContent() {
          this.AdvancedSettingsTab()
        }
        .tabBar('高级')
        
        TabContent() {
          this.GatewaySettingsTab()
        }
        .tabBar('网关')
      }
      .barMode(BarMode.Fixed)
      .barBackgroundColor('#1E1E1E')
      .layoutWeight(1)
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#121212')
  }
}
