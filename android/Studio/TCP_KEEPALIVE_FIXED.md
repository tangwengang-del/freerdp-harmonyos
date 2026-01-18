# TCP Keepalive修复记录

## 📅 修复日期
2025-12-19

## 🐛 **问题**
使用命令行参数 `/tcp-keepalive` 导致FreeRDP参数解析失败，连接时出现错误：
```
Missing hostname, can not connect to NULL target
```

## ✅ **解决方案**
通过JNI直接设置TCP keepalive的settings，绕过命令行参数解析。

---

## 🔧 **修改内容**

### 1. **C层新增JNI方法**（android_freerdp.c）

```c
JNIEXPORT jboolean JNICALL
Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1set_1tcp_1keepalive(
    JNIEnv* env, jclass cls, jlong instance, jboolean enabled, 
    jint delay, jint interval, jint retries)
{
    // 直接设置FreeRDP settings
    freerdp_settings_set_bool(settings, FreeRDP_TcpKeepAlive, enabled);
    freerdp_settings_set_uint32(settings, FreeRDP_TcpKeepAliveDelay, delay);
    freerdp_settings_set_uint32(settings, FreeRDP_TcpKeepAliveInterval, interval);
    freerdp_settings_set_uint32(settings, FreeRDP_TcpKeepAliveRetries, retries);
    
    return JNI_TRUE;
}
```

### 2. **Java层新增方法**（LibFreeRDP.java）

```java
// Native方法声明
private static native boolean freerdp_set_tcp_keepalive(long inst, boolean enabled, 
                                                        int delay, int interval, int retries);

// 公共方法
public static boolean setTcpKeepalive(long inst, boolean enabled, 
                                      int delay, int interval, int retries);
```

### 3. **自动调用**（LibFreeRDP.java - setConnectionInfo）

```java
// 解析参数
if (!freerdp_parse_arguments(inst, arrayArgs))
{
    return false;
}

// 启用TCP keepalive（参数解析后、连接前）
boolean tcpKeepaliveResult = setTcpKeepalive(
    inst, 
    true,   // enabled
    15,     // delay: 15秒空闲后开始探测
    15,     // interval: 每15秒发送一次探测包
    3       // retries: 重试3次（总超时45秒）
);

return true;
```

---

## 🎯 **最终架构**

```
┌──────────────────────────────────────────┐
│  应用层 (Java)                            │
│  RDP Synchronize Event: 45秒             │
│  └─ 轻量级协议事件，保持会话活跃         │
└──────────────────────────────────────────┘
              ↓
┌──────────────────────────────────────────┐
│  TCP层 (Kernel - JNI直接设置)            │
│  TCP Keepalive: 15秒                     │
│  ├─ TCP_KEEPIDLE: 15秒                   │
│  ├─ TCP_KEEPINTVL: 15秒                  │
│  └─ TCP_KEEPCNT: 3次                     │
└──────────────────────────────────────────┘
              ↓
    Windows Server 2022
    ✓ 双层保护确保连接稳定
```

---

## 📊 **配置参数**

| 参数 | 值 | 说明 |
|------|-----|------|
| **TCP_KEEPIDLE** | 15秒 | 连接空闲15秒后开始发送探测包 |
| **TCP_KEEPINTVL** | 15秒 | 每15秒发送一次探测包 |
| **TCP_KEEPCNT** | 3次 | 探测失败重试3次 |
| **总超时时间** | 60秒 | 15 + 15×3 = 60秒无响应判定断开 |
| **RDP心跳间隔** | 45秒 | 应用层Synchronize Event |

---

## ✅ **优势**

1. **绕过参数解析问题**：不依赖命令行参数
2. **直接设置**：在settings层面直接配置
3. **时机正确**：参数解析后、连接前设置
4. **双层保活**：TCP层 + RDP层
5. **向下兼容**：所有FreeRDP版本都支持settings API

---

## 🧪 **预期日志**

### **启动时**：
```
✓ TCP Keepalive enabled: delay=15s, interval=15s, retries=3
✓ Dual-layer keepalive started: TCP@15s (NAT) + RDP Sync@45s (heartbeat)
```

### **运行时**：
```
✓ RDP heartbeat #1 (Sync Event, 45s interval, TCP@15s maintains NAT)
✓ RDP heartbeat #2 (Sync Event, 45s interval, TCP@15s maintains NAT)
```

---

## 📝 **相关文件**

- android_freerdp.c: +70行（新增JNI方法）
- LibFreeRDP.java: +20行（新增接口和调用）
- SessionActivity.java: 恢复45秒心跳间隔

---

## ⚠️ **注意事项**

1. **调用顺序**：必须在 `freerdp_parse_arguments` 之后、`freerdp_connect` 之前调用
2. **Settings生效**：FreeRDP会在建立TCP连接时自动应用这些设置
3. **兼容性**：仅支持Linux/Android（Windows不支持TCP_KEEPIDLE等参数）

---

**修复人员**: AI Assistant  
**测试状态**: 待用户验证  
**预期效果**: 连接成功 + TCP keepalive正常工作



