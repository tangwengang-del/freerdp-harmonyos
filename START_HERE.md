# 🚀 开始使用 - HarmonyOS 应用签名配置

> **配置完成！** 您的项目已准备好进行签名和构建。

## ✅ 已完成的配置

您的项目现在包含：

- ✅ **签名配置** - `build-profile.json5` 已配置自动签名
- ✅ **构建脚本** - 自动化构建和签名流程
- ✅ **安全配置** - `.gitignore` 保护签名文件
- ✅ **CI/CD** - GitHub Actions 自动构建
- ✅ **完整文档** - 中英文使用指南

## 📋 接下来的三个步骤

### 步骤 1️⃣：获取签名文件 ⚠️ **您还没有这些文件**

您需要从华为开发者平台获取三个文件：

#### 📖 详细步骤请查看：[`获取签名文件详细步骤.md`](./获取签名文件详细步骤.md)

#### 快速流程：

1. **访问** AppGallery Connect
   - 网址：https://developer.huawei.com/consumer/cn/service/josp/agc/index.html
   
2. **登录** 华为开发者账号
   - 如没有账号，需先注册并实名认证

3. **创建项目和应用**
   - 项目名称：FreeRDP-HarmonyOS（或其他名称）
   - 应用包名：`com.example.myapplication` （必须与项目一致）

4. **生成调试证书**
   - 进入"证书"菜单
   - 生成调试证书
   - 设置密码（建议记住，如：`123456`）

5. **下载三个文件**
   - `xxx.p12` → 重命名为 `debug.p12` （密钥库）
   - `xxx.cer` → 重命名为 `debug.cer` （证书）
   - `xxx.p7b` → 重命名为 `debug.p7b` （Profile）

6. **放置文件**
   - 将三个文件放到：`c:\huawei\.signing\`

**为什么需要这些文件？**
- 这是 HarmonyOS 应用发布的必需签名
- 用于验证应用来源
- 云调试和测试部署都需要

**需要帮助？** 查看完整图文步骤：[获取签名文件详细步骤.md](./获取签名文件详细步骤.md)

### 步骤 2️⃣：验证配置

在 PowerShell 中运行验证脚本：

```powershell
.\setup-signing.ps1
```

**预期结果：**
```
[OK] Found P12 file: debug.p12
[OK] Found CER file: debug.cer
[OK] Found P7B file: debug.p7b
Signing configuration complete!
```

### 步骤 3️⃣：构建应用

运行构建脚本：

```powershell
.\build-and-sign.ps1
```

**成功后：**
- HAP 文件位置：`entry\build\default\outputs\default\entry-default-signed.hap`
- 可以上传到云调试平台
- 可以部署到测试服务器

## 📚 文档导航

根据您的需求选择合适的文档：

| 文档 | 适用场景 | 路径 |
|------|---------|------|
| 🚀 **快速开始** | 第一次配置签名 | [`SIGNING_QUICK_START.md`](./SIGNING_QUICK_START.md) |
| 📖 **详细指南** | 深入了解配置细节 | [`签名配置指南.md`](./签名配置指南.md) |
| 📝 **简明指南** | 快速查阅命令 | [`HOW_TO_SIGN_AND_BUILD.txt`](./HOW_TO_SIGN_AND_BUILD.txt) |
| 🔧 **签名说明** | 签名文件相关 | [`.signing/README.md`](./.signing/README.md) |
| 📋 **项目文档** | 项目整体说明 | [`README.md`](./README.md) |

## 🛠️ 常用命令

```powershell
# 验证签名配置
.\setup-signing.ps1

# 构建应用（debug）
.\build-and-sign.ps1

# 构建应用（release）
.\build-and-sign.ps1 -Release

# 查看帮助
.\build-and-sign.ps1 -Help

# 清理构建
.\build-and-sign.ps1 -Clean
```

## ❓ 常见问题

### Q1: 我在虚拟服务器上，无法使用 DevEco Studio GUI，怎么办？

**A:** 这正是我们为您配置的！使用命令行方式：
1. 从华为开发者平台网页获取签名文件
2. 使用我们提供的 PowerShell 脚本构建
3. 无需 GUI，完全命令行操作

### Q2: 签名文件会被提交到 Git 吗？

**A:** 不会。已配置 `.gitignore` 忽略所有签名文件，保护您的安全。

### Q3: 构建失败怎么办？

**A:** 按顺序检查：
1. 运行 `.\setup-signing.ps1` 确认签名文件存在
2. 检查文件名是否正确（`debug.p12`, `debug.cer`, `debug.p7b`）
3. 查看构建日志中的具体错误信息

### Q4: 我可以直接使用 hvigorw 命令吗？

**A:** 可以！配置完成后，这两个命令都可以：
```powershell
.\hvigorw.bat assembleHap        # 标准方式
.\build-and-sign.ps1             # 推荐方式（有额外检查）
```

## 🎯 项目特点

本配置方案专为虚拟服务器环境设计：

- ✅ **无需 GUI** - 完全命令行化
- ✅ **自动化** - 一键构建和签名
- ✅ **安全** - 签名文件保护
- ✅ **易用** - 详细的提示和文档
- ✅ **CI/CD** - 支持 GitHub Actions
- ✅ **云调试** - 适配远程测试

## 📞 获取帮助

### 技术资源
- 📚 [HarmonyOS 开发文档](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/)
- 🔐 [应用签名指南](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/ide-signing-V5)
- ☁️ [AppGallery Connect](https://developer.huawei.com/consumer/cn/service/josp/agc/index.html)

### 配置记录
详细的配置过程记录在：
- [`聊天记录/signing_configuration_summary.md`](./聊天记录/signing_configuration_summary.md)

## 🎉 准备就绪

一切准备就绪！现在就开始：

1. 获取签名文件（约 5 分钟）
2. 验证配置（运行 `.\setup-signing.ps1`）
3. 构建应用（运行 `.\build-and-sign.ps1`）

**祝您构建顺利！** 🚀

---

> 💡 **提示**: 如果这是您第一次配置，建议先阅读 [`SIGNING_QUICK_START.md`](./SIGNING_QUICK_START.md)
