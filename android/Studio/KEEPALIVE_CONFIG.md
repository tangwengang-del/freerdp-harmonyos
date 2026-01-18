# 保活配置说明

## ✅ **当前配置（2025-12-19）**

### **保活机制**：TCP Keepalive（纯内核层）

| 参数 | 值 | 说明 |
|------|-----|------|
| **机制** | TCP Keepalive | Linux内核SO_KEEPALIVE |
| **间隔** | 15秒 | TCP_KEEPIDLE=15s |
| **探测间隔** | 15秒 | TCP_KEEPINTVL=15s |
| **重试次数** | 3次 | TCP_KEEPCNT=3 |
| **总超时** | 60秒 | 15 + 15×3 = 60秒 |
| **作用域** | 前台+后台 | 全程启用 |
| **开销** | 极低 | 内核处理，无应用层开销 |

---

## 🔄 **演进历史**

### **v1.0：鼠标微动心跳**（已废弃）
```
方式：每45秒发送鼠标微动
问题：触发服务器UI逻辑，有副作用
状态：已移除
```

### **v2.0：RDP Synchronize Event心跳**（已禁用）
```
方式：每30秒发送Synchronize Event
优点：轻量级（8字节），标准协议
问题：仍需应用层处理
状态：已禁用（本次更新）
```

### **v3.0：TCP Keepalive**（当前版本 ✅）
```
方式：TCP层15秒keepalive
优点：内核层处理，零应用开销，NAT友好
状态：当前使用
```

---

## 📋 **修改内容（本次）**

### **1. 启用TCP Keepalive**（LibFreeRDP.java）

```java
// 在freerdp_parse_arguments后立即设置
boolean tcpKeepaliveResult = setTcpKeepalive(
    inst, 
    true,   // enabled
    15,     // delay: 15秒空闲后开始探测
    15,     // interval: 每15秒发送一次探测包
    3       // retries: 重试3次
);

if (tcpKeepaliveResult) {
    Log.i("LibFreeRDP", "✓ TCP Keepalive enabled: 15s interval (NAT-friendly)");
} else {
    Log.e("LibFreeRDP", "✗ Failed to enable TCP keepalive!");
}
```

**位置**：
- `setConnectionInfo(Context, long, BookmarkBase)` - 第477行
- `setConnectionInfo(Context, long, Uri)` - 第563行

### **2. 禁用RDP心跳**（SessionActivity.java）

```java
private void startBackgroundKeepalive(final long inst) {
    // ✅ RDP心跳已禁用：改用TCP keepalive（15秒内核层保活）
    Log.i(TAG, "RDP heartbeat disabled - Using TCP keepalive@15s instead");
    return;
    
    /* 原RDP心跳代码已禁用
    ...
    */
}
```

**位置**：`startBackgroundKeepalive()` - 第690行

---

## 🎯 **技术优势**

### **TCP Keepalive vs RDP Heartbeat**

| 对比项 | TCP Keepalive ✅ | RDP Heartbeat |
|--------|-----------------|---------------|
| **处理层** | 内核（驱动层） | 应用层（Java） |
| **CPU占用** | 0%（内核自动） | 微量（Handler调度） |
| **唤醒次数** | 0（无需唤醒应用） | 每30秒唤醒一次 |
| **电池影响** | 极低 | 低 |
| **网络开销** | TCP ACK（40-60字节） | RDP Sync（8字节+RDP头） |
| **NAT穿透** | 优秀（专为NAT设计） | 良好 |
| **Doze模式** | 不受影响 | 可能被延迟 |
| **实现复杂度** | 低（一次设置） | 中（需要状态管理） |

---

## 🧪 **预期日志**

### **连接时**：
```
I/LibFreeRDP: ✓ TCP Keepalive enabled: 15s interval (NAT-friendly)
I/FreeRDP.SessionActivity: RDP heartbeat disabled - Using TCP keepalive@15s instead
```

### **运行时**：
```
（无应用层日志，内核静默处理）
```

### **如果JNI失败**：
```
E/LibFreeRDP: ✗ Failed to enable TCP keepalive!
```

---

## 📦 **依赖库文件**

需要使用GitHub Actions编译的新库（已包含TCP keepalive JNI函数）：

### **必需文件**：
```
jniLibs/armeabi-v7a/libfreerdp-android.so  ⭐ (包含JNI函数)
jniLibs/arm64-v8a/libfreerdp-android.so    ⭐ (包含JNI函数)
```

### **配套文件**（建议同时更新）：
```
libfreerdp3.so
libfreerdp-client3.so
libwinpr3.so
libcrypto.so
libssl.so
libcjson.so
libavcodec.so
libavdevice.so
libavfilter.so
libavformat.so
libavutil.so
libswresample.so
libswscale.so
```

**GitHub Actions**: https://github.com/tangwengang-del/freerdp-android/actions

---

## 🔍 **验证方法**

### **方法1：查看日志**
```bash
adb logcat | grep -i "tcp keepalive"
```
应该看到：
```
✓ TCP Keepalive enabled: 15s interval (NAT-friendly)
```

### **方法2：网络抓包**
使用Wireshark观察TCP keepalive探测包：
- 15秒空闲后开始
- 每15秒一次TCP ACK包
- 标志：`[TCP Keep-Alive]`

### **方法3：锁屏测试**
1. 连接服务器成功
2. 锁屏手机/模拟器
3. 等待5-10分钟
4. 解锁，连接应该保持 ✅

---

## ⚠️ **注意事项**

1. **必须使用新库**：
   - 旧库不包含 `freerdp_set_tcp_keepalive` JNI函数
   - 会看到错误日志：`✗ Failed to enable TCP keepalive!`

2. **NAT超时时间**：
   - 家用路由器NAT超时通常：30-120秒
   - 15秒间隔可确保在所有环境都不超时

3. **服务器配置**：
   - Windows Server默认RDP超时：120秒
   - TCP keepalive（60秒超时）<< 120秒，安全

4. **Doze模式**：
   - TCP keepalive由内核处理，不受Doze影响
   - 前台服务仍然保留（用于音频等其他功能）

---

## 🔄 **回退方案**

如果TCP keepalive出现问题，可以回退到RDP心跳：

**步骤**：
1. 打开 `SessionActivity.java`
2. 删除 `startBackgroundKeepalive()` 开头的 `return` 语句
3. 取消注释下方的RDP心跳代码
4. 重新编译

---

## 📝 **相关文件**

- **配置文件1**: `LibFreeRDP.java` (第477、563行)
- **配置文件2**: `SessionActivity.java` (第690行)
- **JNI实现**: `android_freerdp.c` (第1231行)
- **编译配置**: `.github/workflows/build-android.yml`

---

**配置日期**: 2025-12-19  
**配置人员**: AI Assistant + tangwengang-del  
**测试状态**: 待验证  
**预期效果**: 锁屏5-10分钟连接不断开


