# FreeRDP HarmonyOS NEXT

基于 FreeRDP 3.18.0 的 HarmonyOS NEXT 6.0 远程桌面客户端。

## 功能特性

### 核心功能
- ✅ RDP 协议支持（FreeRDP 3.18.0）
- ✅ RemoteFX 编解码器
- ✅ GFX 图形管道
- ✅ H.264/AVC 视频编码
- ✅ 音频重定向（本地/远程）
- ✅ 剪贴板同步
- ✅ 触控指针模式
- ✅ 虚拟键盘

### 后台与锁屏功能
- ✅ 后台保持连接（长时任务）
- ✅ 锁屏下只传音频不传图像（SuppressOutput PDU）
- ✅ 音频流永不中断

### 网络保活功能
- ✅ TCP Keepalive（15秒间隔，防NAT超时）
- ✅ RDP Synchronize 心跳（30秒间隔）
- ✅ 断网自动检测
- ✅ 智能重连策略（最多10次，递增延迟）

## 项目结构

```
entry/
├── src/main/
│   ├── ets/
│   │   ├── entryability/
│   │   │   └── EntryAbility.ets
│   │   ├── pages/
│   │   │   ├── Index.ets           # 主页（书签列表）
│   │   │   ├── SessionPage.ets     # 会话页面
│   │   │   ├── BookmarkPage.ets    # 书签编辑
│   │   │   ├── SettingsPage.ets    # 设置
│   │   │   └── AboutPage.ets       # 关于
│   │   ├── components/
│   │   │   ├── SessionView.ets     # 远程桌面画布
│   │   │   ├── TouchPointerView.ets # 触控指针
│   │   │   └── VirtualKeyboard.ets # 虚拟键盘
│   │   ├── services/
│   │   │   ├── LibFreeRDP.ets      # N-API 封装
│   │   │   ├── RdpBackgroundService.ets # 后台服务
│   │   │   └── NetworkManager.ets  # 网络管理
│   │   ├── model/
│   │   │   ├── BookmarkBase.ets    # 书签模型
│   │   │   └── SessionState.ets    # 会话状态
│   │   └── utils/
│   │       ├── KeyboardMapper.ets  # 键盘映射
│   │       └── ClipboardManager.ets # 剪贴板管理
│   ├── cpp/
│   │   ├── CMakeLists.txt
│   │   ├── harmonyos_freerdp.cpp   # FreeRDP 核心封装
│   │   ├── harmonyos_napi.cpp      # N-API 绑定
│   │   ├── harmonyos_event.c       # 事件队列
│   │   └── harmonyos_cliprdr.c     # 剪贴板
│   └── resources/
└── libs/
    └── arm64-v8a/                  # Native 库文件
```

## 编译指南

### 1. 编译 Native 库

Native 库需要在 GitHub Actions 上编译：

1. Fork 仓库 `tangwengang-del/freerdp-harmonyos`
2. 推送代码触发 GitHub Actions
3. 下载编译好的 `.so` 文件

### 2. 配置签名（必需）

在虚拟服务器环境中，需要先配置签名才能构建应用：

#### 快速配置

```powershell
# 1. 运行签名设置脚本
.\setup-signing.ps1

# 2. 按照提示获取签名文件
# 从华为开发者平台下载 debug.p12, debug.cer, debug.p7b
# 放置到 .signing 目录

# 3. 验证配置
.\setup-signing.ps1
```

#### 详细说明

查看完整签名配置文档：
- **快速开始**: [SIGNING_QUICK_START.md](./SIGNING_QUICK_START.md)
- **详细指南**: [签名配置指南.md](./签名配置指南.md)

### 3. 编译 HarmonyOS 应用

配置签名后，使用以下方式构建：

#### 方式A：使用构建脚本（推荐）

```powershell
# 构建 debug 版本
.\build-and-sign.ps1

# 构建 release 版本
.\build-and-sign.ps1 -Release

# 清理后构建
.\build-and-sign.ps1 -Clean
```

#### 方式B：使用 hvigorw 命令

```bash
# 使用 DevEco Studio 打开项目
# 或使用命令行

hvigorw assembleHap
```

### 4. 安装测试

```bash
hdc install entry/build/default/outputs/default/entry-default-signed.hap
```

### 5. 云调试部署

构建完成后，HAP 文件可以：
- 上传到华为云调试平台
- 部署到远程测试服务器
- 通过 DevEco Studio 远程调试

## 技术架构

```
┌─────────────────────────────────────────────────────────────┐
│                    ArkTS UI Layer                           │
│  (Index, SessionPage, BookmarkPage, SettingsPage, About)    │
├─────────────────────────────────────────────────────────────┤
│                   Service Layer                             │
│  (LibFreeRDP, RdpBackgroundService, NetworkManager)         │
├─────────────────────────────────────────────────────────────┤
│                    N-API Bridge                             │
│  (harmonyos_napi.cpp)                                       │
├─────────────────────────────────────────────────────────────┤
│                  Native FreeRDP                             │
│  (harmonyos_freerdp.cpp, event, cliprdr)                    │
├─────────────────────────────────────────────────────────────┤
│                 FreeRDP Libraries                           │
│  (libfreerdp3.so, libwinpr3.so, libfreerdp-client3.so)      │
├─────────────────────────────────────────────────────────────┤
│                 Dependency Libraries                        │
│  (OpenSSL, FFmpeg, cJSON)                                   │
└─────────────────────────────────────────────────────────────┘
```

## 后台音频实现

### SuppressOutput PDU

当应用进入后台或屏幕锁定时：

```typescript
// 停止图像传输，保持音频
LibFreeRDP.setClientDecoding(instance, false);

// 恢复图像传输
LibFreeRDP.setClientDecoding(instance, true);
```

### 屏幕状态监听

```typescript
screenLock.on('screenLockChange', (isLocked: boolean) => {
  LibFreeRDP.setClientDecoding(instance, !isLocked);
});
```

## 网络保活机制

### 双层保活

1. **TCP Keepalive**（内核层）
   - 空闲 15 秒后开始探测
   - 每 15 秒发送一次探测包
   - 3 次失败后断开

2. **RDP Synchronize**（应用层）
   - 每 30 秒发送一次心跳
   - 约 8 字节，不影响服务器

### 断网重连

```typescript
// 重连策略
const delays = [1000, 5000, 10000, 20000, ...];  // 最多 10 次
```

## 权限说明

| 权限 | 用途 |
|------|------|
| INTERNET | 连接远程桌面服务器 |
| GET_NETWORK_INFO | 监测网络状态变化 |
| KEEP_BACKGROUND_RUNNING | 后台保持音频连接 |
| RUNNING_LOCK | 防止系统休眠断开连接 |

## 许可证

Apache License 2.0

基于 FreeRDP 开源项目开发。

## 致谢

- [FreeRDP](https://www.freerdp.com/) - 免费的远程桌面协议实现
- [OpenSSL](https://www.openssl.org/) - 加密库
- [FFmpeg](https://ffmpeg.org/) - 多媒体框架
