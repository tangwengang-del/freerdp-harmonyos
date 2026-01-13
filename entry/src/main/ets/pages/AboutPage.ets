/*
 * HarmonyOS FreeRDP About Page
 * 
 * Application information and credits
 * 
 * Copyright 2026 FreeRDP HarmonyOS Port
 */

import router from '@ohos.router';
import LibFreeRDP from '../services/LibFreeRDP';

const TAG = 'AboutPage';

@Entry
@Component
struct AboutPage {
  @State freerdpVersion: string = '';
  @State hasH264: boolean = false;

  aboutToAppear(): void {
    console.info(`${TAG}: aboutToAppear`);
    this.freerdpVersion = LibFreeRDP.getVersion();
    this.hasH264 = LibFreeRDP.hasH264Support();
  }

  @Builder
  InfoItem(label: string, value: string) {
    Row() {
      Text(label)
        .fontSize(14)
        .fontColor('#888888')
        .width('40%')
      
      Text(value)
        .fontSize(14)
        .fontColor('#FFFFFF')
        .layoutWeight(1)
        .textAlign(TextAlign.End)
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
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
        
        Text('关于')
          .fontSize(20)
          .fontWeight(FontWeight.Medium)
          .fontColor('#FFFFFF')
          .layoutWeight(1)
          .textAlign(TextAlign.Center)
        
        Column().width(44)
      }
      .width('100%')
      .padding({ left: 8, right: 8, top: 8, bottom: 8 })
      
      // Content
      Scroll() {
        Column() {
          // App icon and name
          Column() {
            Image($r('app.media.startIcon'))
              .width(80)
              .height(80)
              .borderRadius(16)
            
            Text('FreeRDP 远程桌面')
              .fontSize(24)
              .fontWeight(FontWeight.Bold)
              .fontColor('#FFFFFF')
              .margin({ top: 16 })
            
            Text('HarmonyOS NEXT 版本')
              .fontSize(14)
              .fontColor('#888888')
              .margin({ top: 4 })
          }
          .width('100%')
          .padding({ top: 32, bottom: 32 })
          .alignItems(HorizontalAlign.Center)
          
          // Version info
          this.SectionHeader('版本信息')
          
          Column() {
            this.InfoItem('应用版本', '1.0.0')
            Divider().color('#333333')
            this.InfoItem('FreeRDP 版本', this.freerdpVersion)
            Divider().color('#333333')
            this.InfoItem('H.264 支持', this.hasH264 ? '是' : '否')
            Divider().color('#333333')
            this.InfoItem('平台', 'HarmonyOS NEXT 6.0')
          }
          .backgroundColor('#1E1E1E')
          .borderRadius(12)
          .margin({ left: 16, right: 16 })
          
          // Features
          this.SectionHeader('功能特性')
          
          Column() {
            this.InfoItem('RDP 协议', '支持')
            Divider().color('#333333')
            this.InfoItem('RemoteFX', '支持')
            Divider().color('#333333')
            this.InfoItem('GFX 管道', '支持')
            Divider().color('#333333')
            this.InfoItem('音频重定向', '支持')
            Divider().color('#333333')
            this.InfoItem('剪贴板', '支持')
            Divider().color('#333333')
            this.InfoItem('后台音频', '支持')
            Divider().color('#333333')
            this.InfoItem('自动重连', '支持')
          }
          .backgroundColor('#1E1E1E')
          .borderRadius(12)
          .margin({ left: 16, right: 16 })
          
          // Credits
          this.SectionHeader('致谢')
          
          Column() {
            Text('本应用基于 FreeRDP 开源项目开发')
              .fontSize(14)
              .fontColor('#AAAAAA')
              .width('100%')
              .padding({ left: 16, right: 16, top: 12 })
            
            Text('FreeRDP 是一个免费的远程桌面协议实现')
              .fontSize(14)
              .fontColor('#888888')
              .width('100%')
              .padding({ left: 16, right: 16, top: 4 })
            
            Text('https://www.freerdp.com')
              .fontSize(14)
              .fontColor('#2196F3')
              .width('100%')
              .padding({ left: 16, right: 16, top: 8, bottom: 12 })
          }
          .backgroundColor('#1E1E1E')
          .borderRadius(12)
          .margin({ left: 16, right: 16 })
          
          // License
          this.SectionHeader('许可证')
          
          Column() {
            Text('Apache License 2.0')
              .fontSize(14)
              .fontColor('#FFFFFF')
              .width('100%')
              .padding({ left: 16, right: 16, top: 12 })
            
            Text('本软件按"原样"提供，不附带任何形式的明示或暗示保证。')
              .fontSize(12)
              .fontColor('#888888')
              .width('100%')
              .padding({ left: 16, right: 16, top: 8, bottom: 12 })
          }
          .backgroundColor('#1E1E1E')
          .borderRadius(12)
          .margin({ left: 16, right: 16 })
          
          // Footer
          Column() {
            Text('© 2026 FreeRDP HarmonyOS Port')
              .fontSize(12)
              .fontColor('#666666')
          }
          .width('100%')
          .padding({ top: 32, bottom: 32 })
          .alignItems(HorizontalAlign.Center)
        }
      }
      .layoutWeight(1)
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#121212')
  }
}
