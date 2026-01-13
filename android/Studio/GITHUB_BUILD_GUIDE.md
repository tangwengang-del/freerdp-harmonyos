# GitHub编译指南

## ✅ **代码已推送到GitHub**

- **仓库**: https://github.com/tangwengang-del/freerdp-android
- **分支**: main
- **提交**: 577c289 - Add TCP keepalive (15s) support for NAT traversal

---

## 🔄 **GitHub Actions正在自动编译**

推送后，GitHub Actions会自动开始编译：
- ✅ **armeabi-v7a** (32位ARM)
- ✅ **arm64-v8a** (64位ARM)

### **查看编译进度**：

1. 打开浏览器访问：
   ```
   https://github.com/tangwengang-del/freerdp-android/actions
   ```

2. 找到最新的 **"Build FreeRDP Android (Complete - ABI Unified)"** workflow

3. 点击进入查看详细进度：
   - ⏳ **黄色图标** = 正在编译
   - ✅ **绿色勾** = 编译成功
   - ❌ **红色X** = 编译失败

4. 预计编译时间：**15-25分钟**（两个架构并行编译）

---

## 📦 **下载编译好的库文件**

### **方式1：通过GitHub网页下载**

1. 打开：https://github.com/tangwengang-del/freerdp-android/actions

2. 点击最新的成功编译（绿色勾）

3. 滚动到页面底部 **Artifacts** 区域

4. 下载以下两个文件：
   - `freerdp-armeabi-v7a-complete.zip` (32位)
   - `freerdp-arm64-v8a-complete.zip` (64位)

### **方式2：使用GitHub CLI** (可选)

```bash
# 安装GitHub CLI（如果还没有）
# https://cli.github.com/

# 列出artifacts
gh run list --repo tangwengang-del/freerdp-android

# 下载artifacts
gh run download <RUN_ID> --repo tangwengang-del/freerdp-android
```

---

## 📂 **安装编译好的库文件**

### **步骤**：

1. **解压下载的zip文件**：
   ```
   freerdp-armeabi-v7a-complete.zip
   freerdp-arm64-v8a-complete.zip
   ```

2. **每个zip包含17个.so文件**：
   - `libfreerdp-android.so` ⭐ (包含新的TCP keepalive JNI函数)
   - `libfreerdp3.so`
   - `libfreerdp-client3.so`
   - `libwinpr3.so`
   - `libcrypto.so`
   - `libssl.so`
   - `libcjson.so`
   - `libavcodec.so`
   - `libavdevice.so`
   - `libavfilter.so`
   - `libavformat.so`
   - `libavutil.so`
   - `libswresample.so`
   - `libswscale.so`

3. **替换到项目目录**：
   ```
   C:\freerdp318\client\Android\Studio\freeRDPCore\src\main\jniLibs\armeabi-v7a\
   C:\freerdp318\client\Android\Studio\freeRDPCore\src\main\jniLibs\arm64-v8a\
   ```

4. **启用TCP Keepalive调用**：
   
   打开 `LibFreeRDP.java`，将这段代码：
   ```java
   // ========== 临时禁用TCP Keepalive JNI调用 ==========
   // TODO: 调试C++编译问题后重新启用
   android.util.Log.w("LibFreeRDP", "TCP keepalive temporarily disabled due to JNI linking issue");
   ```
   
   **改为**：
   ```java
   // ========== 双层保活配置：TCP (15s) + RDP Sync (30s) ==========
   boolean tcpKeepaliveResult = setTcpKeepalive(
       inst, 
       true,   // enabled
       15,     // delay: 15秒空闲后开始探测
       15,     // interval: 每15秒发送一次探测包
       3       // retries: 重试3次
   );
   
   if (!tcpKeepaliveResult)
   {
       android.util.Log.w("LibFreeRDP", "Failed to enable TCP keepalive");
   }
   ```

5. **重新编译APK**：
   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
   cd C:\freerdp318\client\Android\Studio
   .\gradlew.bat assembleDebug
   ```

6. **安装并测试**：
   ```powershell
   adb install -r aFreeRDP\build\outputs\apk\debug\aFreeRDP-debug.apk
   ```

---

## 🧪 **验证TCP Keepalive生效**

连接服务器后，查看日志应该看到：

```
✓ TCP Keepalive enabled: delay=15s, interval=15s, retries=3
✓ Dual-layer keepalive started: TCP@15s (NAT) + RDP Sync@30s (heartbeat)
```

---

## ⚠️ **注意事项**

1. **必须替换两个架构的库**：
   - armeabi-v7a (32位手机/模拟器)
   - arm64-v8a (64位手机/模拟器)

2. **备份旧库**：
   建议先备份当前的jniLibs目录

3. **完整替换**：
   建议替换所有17个.so文件，确保版本一致

4. **测试验证**：
   替换后务必测试连接是否正常

---

## 📊 **编译内容总结**

本次编译包含以下功能：

### ✅ **已实现**：
1. **TCP Keepalive (15秒)** - NAT穿透保活
2. **RDP Synchronize Event (30秒)** - 应用层心跳
3. **透明系统栏** - 状态栏和导航栏透明显示
4. **断网重连** - 自动检测并重连

### 🔧 **技术细节**：
- JNI函数：`freerdp_set_tcp_keepalive()`
- TCP配置：IDLE=15s, INTVL=15s, CNT=3
- 双层架构：内核TCP + 应用RDP

---

## 📝 **相关文件**

- 修改文件1: `client/Android/Studio/freeRDPCore/src/main/cpp/android_freerdp.c`
- 修改文件2: `client/Android/Studio/freeRDPCore/src/main/java/.../LibFreeRDP.java`
- 修改文件3: `client/Android/Studio/freeRDPCore/src/main/java/.../SessionActivity.java`

---

**编译时间**: 2025-12-19  
**提交ID**: 577c289  
**GitHub Actions**: https://github.com/tangwengang-del/freerdp-android/actions



