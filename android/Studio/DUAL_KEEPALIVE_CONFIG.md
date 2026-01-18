# 双保险保活配置说明

## ✅ **当前配置（2025-12-19 - 最终版）**

### **保活机制**：双层保险（TCP + RDP）

| 层级 | 机制 | 间隔 | 说明 |
|------|------|------|------|
| **内核层** | TCP Keepalive | 15秒 | Linux内核SO_KEEPALIVE |
| **应用层** | RDP Synchronize Event | 45秒 | FreeRDP协议事件 |
| **作用域** | 前台+后台 | 全程 | 两者都持续运行 |

---

## 🎯 **双保险架构**

```
┌─────────────────────────────────────────────┐
│  应用层（Java）                              │
│  RDP Synchronize Event: 45秒                │
│  └─ 轻量级协议事件，保持会话活跃            │
│  └─ 可检测连接状态，失败时触发重连          │
└─────────────────────────────────────────────┘
              ↓ (协同工作)
┌─────────────────────────────────────────────┐
│  内核层（Kernel）                            │
│  TCP Keepalive: 15秒                        │
│  ├─ TCP_KEEPIDLE: 15秒                      │
│  ├─ TCP_KEEPINTVL: 15秒                     │
│  └─ TCP_KEEPCNT: 3次                        │
│  └─ 维持NAT映射，防止路由器断开             │
└─────────────────────────────────────────────┘
              ↓
    ┌──────────────────────┐
    │ Windows Server 2022   │
    │ RDP Session           │
    └──────────────────────┘
```

---

## 🔄 **为什么使用双保险？**

### **单独TCP Keepalive的问题**：
```
❌ Android Doze模式可能暂停网络（深度休眠>30分钟）
❌ 应用层无法检测失败（内核层静默处理）
❌ 某些NAT设备可能忽略TCP keepalive
```

### **单独RDP心跳的问题**：
```
❌ Doze模式下Handler可能被延迟
❌ 应用层唤醒有电池开销
❌ NAT超时前可能有45秒空窗期
```

### **双保险的优势**：
```
✅ TCP保底：确保NAT不超时（15秒 << 30-120秒）
✅ RDP保障：应用层可检测并重连
✅ 互补容错：一个失效时另一个接管
✅ 全场景覆盖：前台、后台、锁屏、Doze
```

---

## 📊 **时间线对比**

### **单一机制（风险）**：
```
0s ───── 15s ───── 30s ───── 45s ───── 60s
     TCP      TCP      TCP      TCP
或
0s ───────────── 45s ────────────── 90s
              RDP                RDP
```
**风险**：单点失效

### **双保险（安全）**：
```
0s ─ 15s ─ 30s ─ 45s ─ 60s ─ 75s ─ 90s
    TCP   TCP   TCP   TCP   TCP   TCP
                 RDP               RDP
```
**优势**：
- 15秒内必有TCP探测
- 45秒内必有RDP心跳
- 任何时刻都有保护

---

## 🔧 **实现细节**

### **1. TCP Keepalive启用**（LibFreeRDP.java - 第477行）

```java
boolean tcpKeepaliveResult = setTcpKeepalive(
    inst, 
    true,   // enabled
    15,     // delay: 15秒空闲后开始探测
    15,     // interval: 每15秒发送一次探测包
    3       // retries: 重试3次（总超时60秒）
);

if (tcpKeepaliveResult) {
    Log.i("LibFreeRDP", "✓ TCP Keepalive enabled: 15s interval (NAT-friendly)");
}
```

**生效时机**：连接建立时（freerdp_parse_arguments之后）

---

### **2. RDP心跳启用**（SessionActivity.java - 第690行）

```java
private void startBackgroundKeepalive(final long inst) {
    keepaliveTask = new Runnable() {
        @Override
        public void run() {
            if (!sessionRunning || session == null) return;
            
            // 发送Synchronize Event
            boolean success = LibFreeRDP.sendHeartbeat(inst);
            
            if (success) {
                Log.d(TAG, "✓ RDP heartbeat (TCP@15s+RDP@45s双保险)");
            }
            
            // 继续下一次心跳
            keepaliveHandler.postDelayed(this, 45000);
        }
    };
    
    keepaliveHandler.post(keepaliveTask);
}
```

**生效时机**：
- 连接成功后（OnGraphicsResize回调）
- onResume时确保运行
- onPause时确保运行

---

## 🧪 **预期日志**

### **连接时**：
```
I/LibFreeRDP: ✓ TCP Keepalive enabled: 15s interval (NAT-friendly)
I/FreeRDP.SessionActivity: ✓ 双保险启动: TCP keepalive@15s (内核层) + RDP Sync@45s (应用层)
```

### **前台运行**：
```
V/FreeRDP.SessionActivity: ✓ RDP heartbeat #1 (前台, TCP@15s+RDP@45s双保险)
V/FreeRDP.SessionActivity: ✓ RDP heartbeat #2 (前台, TCP@15s+RDP@45s双保险)
```

### **后台/锁屏**：
```
D/FreeRDP.SessionActivity: ✓ RDP heartbeat #3 (后台/锁屏, TCP@15s+RDP@45s双保险)
D/FreeRDP.SessionActivity: ✓ RDP heartbeat #4 (后台/锁屏, TCP@15s+RDP@45s双保险)
```

---

## 📈 **性能开销**

| 指标 | TCP Keepalive | RDP心跳 | 合计 |
|------|--------------|---------|------|
| **间隔** | 15秒 | 45秒 | - |
| **单次数据** | 40-60字节 | ~8字节 | - |
| **每分钟** | 4次 × 50字节 | 1.33次 × 8字节 | ~210字节/分钟 |
| **每小时** | 240次 | 80次 | ~12KB/小时 |
| **CPU唤醒** | 0次（内核处理） | 80次/小时 | 可忽略 |
| **电池影响** | 极低 | 极低 | **总计：极低** |

**结论**：开销完全可以接受，换来的是最高稳定性。

---

## ⚙️ **参数调优建议**

### **当前配置（推荐）**：
```
TCP: 15秒 (NAT安全边界)
RDP: 45秒 (平衡性能与稳定性)
```

### **如果需要更激进保活**：
```
TCP: 10秒 (极端NAT环境)
RDP: 30秒 (更频繁检测)
```

### **如果需要省电优化**：
```
TCP: 15秒 (保持不变，NAT必需)
RDP: 60秒 (降低应用层频率)
```

---

## 🔍 **故障排查**

### **Q1: 锁屏后仍然断开**

**检查项**：
1. TCP Keepalive是否启用？
   ```bash
   adb logcat | grep "TCP Keepalive enabled"
   ```
   应该看到：`✓ TCP Keepalive enabled: 15s interval`

2. RDP心跳是否运行？
   ```bash
   adb logcat | grep "RDP heartbeat"
   ```
   应该每45秒看到一次

3. 前台服务是否正常？
   ```bash
   adb logcat | grep "Foreground service"
   ```

**可能原因**：
- 库文件未更新（TCP Keepalive JNI函数缺失）
- 网络环境特殊（超严格防火墙）
- 服务器端超时设置过短

---

### **Q2: RDP心跳失败**

**日志**：
```
⚠ RDP heartbeat #5 failed
```

**原因**：
- 连接已经断开（TCP层问题）
- session实例无效
- 网络暂时中断

**处理**：应用会自动触发重连，无需担心

---

### **Q3: TCP Keepalive启用失败**

**日志**：
```
✗ Failed to enable TCP keepalive!
```

**原因**：库文件未包含JNI函数

**解决**：
1. 检查.so文件版本
2. 确认已从GitHub下载最新库
3. 重新编译native库

---

## 🎯 **测试检查清单**

### **前台测试**：
- [ ] 连接成功
- [ ] 看到"双保险启动"日志
- [ ] 每45秒看到RDP心跳日志
- [ ] 长时间不操作（10分钟）连接保持

### **后台测试**：
- [ ] 按Home键切换应用
- [ ] 日志显示"后台/锁屏"
- [ ] 5分钟后返回，连接保持

### **锁屏测试**：
- [ ] 锁屏5-10分钟
- [ ] 解锁后连接保持
- [ ] 日志持续显示心跳

### **深度Doze测试**：
- [ ] 锁屏30分钟以上
- [ ] 解锁后连接保持（或自动重连）
- [ ] 查看日志确认机制运行

---

## 📝 **相关文件**

- **TCP配置**: `LibFreeRDP.java` (第477、563行)
- **RDP心跳**: `SessionActivity.java` (第690-760行)
- **JNI实现**: `android_freerdp.c` (第1231行)
- **间隔配置**: `SessionActivity.java` (第1813-1814行)

---

## 🔄 **版本历史**

| 版本 | 配置 | 状态 |
|------|------|------|
| v1.0 | 鼠标微动45秒 | 已废弃 |
| v2.0 | RDP心跳30秒 | 已替换 |
| v3.0 | TCP keepalive 15秒 | 已增强 |
| **v4.0** | **TCP 15秒 + RDP 45秒** | **当前版本** ✅ |

---

**配置日期**: 2025-12-19  
**配置人员**: AI Assistant + tangwengang-del  
**测试状态**: 待验证  
**预期效果**: 最高稳定性，任何场景都不断开


