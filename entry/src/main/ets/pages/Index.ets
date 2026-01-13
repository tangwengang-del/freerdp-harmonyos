/*
 * HarmonyOS FreeRDP Main Page
 * 
 * Home page with bookmark list and quick connect
 * 
 * Copyright 2026 FreeRDP HarmonyOS Port
 */

import router from '@ohos.router';
import preferences from '@ohos.data.preferences';
import promptAction from '@ohos.promptAction';
import { BookmarkBase, ManualBookmark, QuickConnectBookmark } from '../model/BookmarkBase';
import LibFreeRDP from '../services/LibFreeRDP';
import common from '@ohos.app.ability.common';

const TAG = 'IndexPage';
const BOOKMARKS_STORE = 'bookmarks';

@Entry
@Component
struct Index {
  @State bookmarks: BookmarkBase[] = [];
  @State isLoading: boolean = true;
  @State quickConnectHost: string = '';
  @State quickConnectPort: string = '3389';
  @State quickConnectUser: string = '';
  @State quickConnectPassword: string = '';
  @State showQuickConnect: boolean = false;
  
  private context = getContext(this) as common.UIAbilityContext;
  private preferencesHelper: preferences.Preferences | null = null;

  aboutToAppear(): void {
    console.info(`${TAG}: aboutToAppear`);
    this.loadBookmarks();
    
    // Show FreeRDP version
    const version = LibFreeRDP.getVersion();
    console.info(`${TAG}: FreeRDP version: ${version}`);
  }

  /**
   * Load bookmarks from preferences
   */
  async loadBookmarks(): Promise<void> {
    try {
      this.preferencesHelper = await preferences.getPreferences(this.context, BOOKMARKS_STORE);
      const bookmarksJson = await this.preferencesHelper.get('bookmarks', '[]') as string;
      const bookmarksData = JSON.parse(bookmarksJson) as object[];
      
      this.bookmarks = bookmarksData.map(data => BookmarkBase.fromJson(data));
      console.info(`${TAG}: Loaded ${this.bookmarks.length} bookmarks`);
    } catch (error) {
      console.error(`${TAG}: Failed to load bookmarks:`, error);
      this.bookmarks = [];
    } finally {
      this.isLoading = false;
    }
  }

  /**
   * Save bookmarks to preferences
   */
  async saveBookmarks(): Promise<void> {
    if (!this.preferencesHelper) return;
    
    try {
      const bookmarksJson = JSON.stringify(this.bookmarks.map(b => b.toJson()));
      await this.preferencesHelper.put('bookmarks', bookmarksJson);
      await this.preferencesHelper.flush();
      console.info(`${TAG}: Saved ${this.bookmarks.length} bookmarks`);
    } catch (error) {
      console.error(`${TAG}: Failed to save bookmarks:`, error);
    }
  }

  /**
   * Add a new bookmark
   */
  addBookmark(bookmark: BookmarkBase): void {
    bookmark.id = Date.now();
    this.bookmarks.push(bookmark);
    this.saveBookmarks();
  }

  /**
   * Delete a bookmark
   */
  deleteBookmark(index: number): void {
    this.bookmarks.splice(index, 1);
    this.saveBookmarks();
  }

  /**
   * Connect to a bookmark
   */
  connectToBookmark(bookmark: BookmarkBase): void {
    console.info(`${TAG}: Connecting to ${bookmark.hostname}:${bookmark.port}`);
    
    router.pushUrl({
      url: 'pages/SessionPage',
      params: {
        bookmark: bookmark.toJson()
      }
    });
  }

  /**
   * Quick connect
   */
  quickConnect(): void {
    if (!this.quickConnectHost) {
      promptAction.showToast({ message: '请输入主机地址' });
      return;
    }
    
    const bookmark = new QuickConnectBookmark();
    bookmark.hostname = this.quickConnectHost;
    bookmark.port = parseInt(this.quickConnectPort) || 3389;
    bookmark.username = this.quickConnectUser;
    bookmark.password = this.quickConnectPassword;
    
    const errors = bookmark.validate();
    if (errors.length > 0) {
      promptAction.showToast({ message: errors[0] });
      return;
    }
    
    this.connectToBookmark(bookmark);
    this.showQuickConnect = false;
  }

  /**
   * Edit bookmark
   */
  editBookmark(bookmark: BookmarkBase): void {
    router.pushUrl({
      url: 'pages/BookmarkPage',
      params: {
        bookmark: bookmark.toJson(),
        mode: 'edit'
      }
    });
  }

  /**
   * Create new bookmark
   */
  createBookmark(): void {
    router.pushUrl({
      url: 'pages/BookmarkPage',
      params: {
        mode: 'create'
      }
    });
  }

  /**
   * Open settings
   */
  openSettings(): void {
    router.pushUrl({
      url: 'pages/SettingsPage'
    });
  }

  /**
   * Open about page
   */
  openAbout(): void {
    router.pushUrl({
      url: 'pages/AboutPage'
    });
  }

  @Builder
  BookmarkItem(bookmark: BookmarkBase, index: number) {
    Row() {
      // Icon
      Column() {
        Image($r('app.media.startIcon'))
          .width(40)
          .height(40)
          .borderRadius(8)
      }
      .margin({ right: 16 })
      
      // Info
      Column() {
        Text(bookmark.getDisplayLabel())
          .fontSize(18)
          .fontWeight(FontWeight.Medium)
          .fontColor('#FFFFFF')
        
        Text(bookmark.getConnectionString())
          .fontSize(14)
          .fontColor('#AAAAAA')
          .margin({ top: 4 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
      
      // Actions
      Row() {
        Button({ type: ButtonType.Circle }) {
          Image($r('sys.media.ohos_ic_public_edit'))
            .width(20)
            .height(20)
            .fillColor('#FFFFFF')
        }
        .width(40)
        .height(40)
        .backgroundColor('#424242')
        .margin({ right: 8 })
        .onClick(() => {
          this.editBookmark(bookmark);
        })
        
        Button({ type: ButtonType.Circle }) {
          Image($r('sys.media.ohos_ic_public_delete'))
            .width(20)
            .height(20)
            .fillColor('#F44336')
        }
        .width(40)
        .height(40)
        .backgroundColor('#424242')
        .onClick(() => {
          promptAction.showDialog({
            title: '删除书签',
            message: `确定要删除 "${bookmark.getDisplayLabel()}" 吗？`,
            buttons: [
              { text: '取消', color: '#888888' },
              { text: '删除', color: '#F44336' }
            ]
          }).then((result) => {
            if (result.index === 1) {
              this.deleteBookmark(index);
            }
          });
        })
      }
    }
    .width('100%')
    .padding(16)
    .backgroundColor('#2D2D2D')
    .borderRadius(12)
    .margin({ bottom: 12 })
    .onClick(() => {
      this.connectToBookmark(bookmark);
    })
  }

  @Builder
  QuickConnectDialog() {
    Column() {
      Text('快速连接')
        .fontSize(24)
        .fontWeight(FontWeight.Bold)
        .fontColor('#FFFFFF')
        .margin({ bottom: 24 })
      
      TextInput({ placeholder: '主机地址', text: this.quickConnectHost })
        .width('100%')
        .height(48)
        .backgroundColor('#424242')
        .fontColor('#FFFFFF')
        .placeholderColor('#888888')
        .margin({ bottom: 12 })
        .onChange((value: string) => {
          this.quickConnectHost = value;
        })
      
      TextInput({ placeholder: '端口 (默认 3389)', text: this.quickConnectPort })
        .width('100%')
        .height(48)
        .backgroundColor('#424242')
        .fontColor('#FFFFFF')
        .placeholderColor('#888888')
        .type(InputType.Number)
        .margin({ bottom: 12 })
        .onChange((value: string) => {
          this.quickConnectPort = value;
        })
      
      TextInput({ placeholder: '用户名', text: this.quickConnectUser })
        .width('100%')
        .height(48)
        .backgroundColor('#424242')
        .fontColor('#FFFFFF')
        .placeholderColor('#888888')
        .margin({ bottom: 12 })
        .onChange((value: string) => {
          this.quickConnectUser = value;
        })
      
      TextInput({ placeholder: '密码', text: this.quickConnectPassword })
        .width('100%')
        .height(48)
        .backgroundColor('#424242')
        .fontColor('#FFFFFF')
        .placeholderColor('#888888')
        .type(InputType.Password)
        .margin({ bottom: 24 })
        .onChange((value: string) => {
          this.quickConnectPassword = value;
        })
      
      Row() {
        Button('取消')
          .width('45%')
          .height(48)
          .backgroundColor('#616161')
          .onClick(() => {
            this.showQuickConnect = false;
          })
        
        Button('连接')
          .width('45%')
          .height(48)
          .backgroundColor('#2196F3')
          .onClick(() => {
            this.quickConnect();
          })
      }
      .width('100%')
      .justifyContent(FlexAlign.SpaceBetween)
    }
    .width('90%')
    .padding(24)
    .backgroundColor('#1E1E1E')
    .borderRadius(16)
  }

  build() {
    Stack() {
      // Main content
      Column() {
        // Header
        Row() {
          Text('远程桌面')
            .fontSize(28)
            .fontWeight(FontWeight.Bold)
            .fontColor('#FFFFFF')
          
          Blank()
          
          Button({ type: ButtonType.Circle }) {
            Image($r('sys.media.ohos_ic_public_settings'))
              .width(24)
              .height(24)
              .fillColor('#FFFFFF')
          }
          .width(44)
          .height(44)
          .backgroundColor('#424242')
          .margin({ right: 8 })
          .onClick(() => {
            this.openSettings();
          })
          
          Button({ type: ButtonType.Circle }) {
            Image($r('sys.media.ohos_ic_public_help'))
              .width(24)
              .height(24)
              .fillColor('#FFFFFF')
          }
          .width(44)
          .height(44)
          .backgroundColor('#424242')
          .onClick(() => {
            this.openAbout();
          })
        }
        .width('100%')
        .padding({ left: 16, right: 16, top: 16, bottom: 16 })
        
        // Quick connect button
        Button('快速连接')
          .width('calc(100% - 32vp)')
          .height(56)
          .fontSize(18)
          .backgroundColor('#2196F3')
          .margin({ left: 16, right: 16, bottom: 16 })
          .onClick(() => {
            this.showQuickConnect = true;
          })
        
        // Bookmark list
        if (this.isLoading) {
          Column() {
            LoadingProgress()
              .width(48)
              .height(48)
              .color('#2196F3')
            Text('加载中...')
              .fontSize(16)
              .fontColor('#888888')
              .margin({ top: 16 })
          }
          .width('100%')
          .layoutWeight(1)
          .justifyContent(FlexAlign.Center)
        } else if (this.bookmarks.length === 0) {
          Column() {
            Image($r('sys.media.ohos_ic_public_folder_empty'))
              .width(80)
              .height(80)
              .fillColor('#666666')
            
            Text('没有保存的连接')
              .fontSize(18)
              .fontColor('#888888')
              .margin({ top: 16 })
            
            Text('点击"+"添加新连接')
              .fontSize(14)
              .fontColor('#666666')
              .margin({ top: 8 })
          }
          .width('100%')
          .layoutWeight(1)
          .justifyContent(FlexAlign.Center)
        } else {
          List() {
            ForEach(this.bookmarks, (bookmark: BookmarkBase, index: number) => {
              ListItem() {
                this.BookmarkItem(bookmark, index)
              }
            })
          }
          .width('100%')
          .layoutWeight(1)
          .padding({ left: 16, right: 16 })
        }
        
        // Add bookmark FAB
        Button({ type: ButtonType.Circle }) {
          Image($r('sys.media.ohos_ic_public_add'))
            .width(28)
            .height(28)
            .fillColor('#FFFFFF')
        }
        .width(56)
        .height(56)
        .backgroundColor('#4CAF50')
        .position({ x: '100%', y: '100%' })
        .translate({ x: -72, y: -72 })
        .shadow({ radius: 8, color: '#00000040', offsetX: 2, offsetY: 4 })
        .onClick(() => {
          this.createBookmark();
        })
      }
      .width('100%')
      .height('100%')
      .backgroundColor('#121212')
      
      // Quick connect dialog overlay
      if (this.showQuickConnect) {
        Column() {
          this.QuickConnectDialog()
        }
        .width('100%')
        .height('100%')
        .backgroundColor('#80000000')
        .justifyContent(FlexAlign.Center)
        .onClick(() => {
          this.showQuickConnect = false;
        })
      }
    }
    .width('100%')
    .height('100%')
  }
}
