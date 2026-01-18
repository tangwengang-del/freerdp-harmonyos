# 使用 OHOS NDK 重新编译 FreeRDP + 依赖（方案 A）

本方案目标：用 **OHOS NDK** 重新编译 FreeRDP 与原生依赖库，替换 `entry/libs/arm64-v8a` 下的旧库，确保 HarmonyOS 运行时稳定加载并可连接 RDP 服务器。

## 前置条件

- 已安装 DevEco Studio（含 HarmonyOS SDK/NDK）
- `cmake` 与 `ninja` 可用
- 网络可下载依赖源码（OpenSSL / zlib / cJSON）

### Windows 环境额外要求（用于 OpenSSL）

- 安装 `Perl`（建议 Strawberry Perl）
- 安装 `make`（建议使用 WSL 或 MSYS2）

> 如果你没有 `make`，建议在 WSL/Linux 环境执行依赖编译，然后回到 Windows 执行 FreeRDP 与 NAPI wrapper 编译。

---

## 1）设置 NDK 路径

在 PowerShell 中设置：

```powershell
$env:OHOS_NDK_HOME="C:\path\to\HarmonyOS-NEXT\...\native"
```

---

## 2）编译依赖（OpenSSL / zlib / cJSON）

```powershell
cd C:\huawei
.\scripts\rebuild-ohos-deps.ps1 -NdkRoot $env:OHOS_NDK_HOME -Arch arm64-v8a
```

产物会被安装到：

```
C:\huawei\native-install\deps\arm64-v8a\
  ├─ openssl\
  ├─ zlib\
  └─ cjson\
```

---

## 3）编译 FreeRDP + NAPI Wrapper

```powershell
cd C:\huawei
.\scripts\rebuild-ohos-native.ps1 `
  -NdkRoot $env:OHOS_NDK_HOME `
  -Arch arm64-v8a `
  -OpenSslRoot C:\huawei\native-install\deps\arm64-v8a\openssl `
  -ZlibRoot C:\huawei\native-install\deps\arm64-v8a\zlib `
  -CJsonRoot C:\huawei\native-install\deps\arm64-v8a\cjson
```

脚本会自动把新库复制到：

```
C:\huawei\entry\libs\arm64-v8a\
```

---

## 4）重新构建 HAP 并安装测试

```powershell
cd C:\huawei
.\build.ps1
```

构建后安装：

```bash
hdc install entry\build\default\outputs\default\entry-default-signed.hap
```

---

## 5）测试目标

只要库能正确加载、连接服务器成功即可判定方案 A 生效：

- 应用启动不再报 `Native library not loaded`
- 能建立 RDP 连接并显示远程桌面

---

## 常见问题

### Q1：OpenSSL 构建失败
- 确认已安装 Perl 与 make
- 推荐在 WSL/Linux 下执行 `rebuild-ohos-deps.ps1`

### Q2：FreeRDP 编译找不到 OpenSSL 头文件
- 检查 `-OpenSslRoot` 是否正确
- 确保 `OpenSslRoot/include/openssl` 存在

### Q3：运行仍提示 native 模块为 undefined
- 确认 `entry/libs/arm64-v8a` 已被新库覆盖
- 重新构建并安装 HAP

---

### 关键构建参数说明
为避免 `libfreerdp-client3.so` 缺失导致安装失败，FreeRDP 构建需要：

- `-DBUILD_SHARED_LIBS=ON`
- `-DWITH_CLIENT_COMMON=ON`

这些参数已在 `scripts/rebuild-ohos-native.ps1` 内置。

如果构建或连接失败，把最新日志给我，我继续跟进到“能连上服务器”为止。 
