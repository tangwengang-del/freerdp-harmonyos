# 🚀 快速测试指南 - RDP心跳优化

## ⚡ 快速编译

```bash
cd /d c:\freerdp318\client\Android\Studio
gradlew clean assembleDebug
```

---

## 🔍 快速验证

### 1. 查看启动日志（30秒）

```bash
adb logcat | grep "Dual-layer keepalive"
```

**期望输出**:
```
✓ Dual-layer keepalive started: TCP@15s (NAT) + RDP Sync@45s (heartbeat)
```

### 2. 查看心跳日志（3分钟）

```bash
adb logcat | grep "RDP heartbeat"
```

**期望输出**（每45秒一次）:
```
✓ RDP heartbeat #1 (Sync Event, 45s interval, TCP@15s maintains NAT)
✓ RDP heartbeat #2 (Sync Event, 45s interval, TCP@15s maintains NAT)
✓ RDP heartbeat #3 (Sync Event, 45s interval, TCP@15s maintains NAT)
```

### 3. 验证旧代码已清理

```bash
adb logcat | grep "offset="
```

**期望结果**: 无输出（旧的鼠标微动日志已不存在）

---

## ✅ 测试清单

- [ ] **编译成功** - 无错误
- [ ] **启动成功** - 看到 "Dual-layer keepalive started"
- [ ] **心跳正常** - 每45秒一次 "RDP heartbeat"
- [ ] **后台保活** - 锁屏30分钟连接保持
- [ ] **断网重连** - 飞行模式后自动重连
- [ ] **屏幕唤醒** - 解锁后屏幕立即刷新

---

## 🐛 常见问题

### Q: 编译失败，找不到 sendHeartbeat 方法
**A**: 确保已重新编译整个项目:
```bash
gradlew clean
gradlew assembleDebug
```

### Q: 运行时崩溃，JNI错误
**A**: 检查 android_freerdp.c 是否正确添加了新方法

### Q: 看不到 "Dual-layer" 日志
**A**: 检查是否进入后台（需要锁屏或Home键）

---

## 📊 性能对比

| 指标 | 旧方案 | 新方案 | 改善 |
|------|--------|--------|------|
| 唤醒频率 | 4次/分 | 1.33次/分 | ↓67% |
| 数据量 | 48字节/分 | 10.64字节/分 | ↓78% |
| 电池 | 基准 | 预计 | ↓50-60% |

---

## 🔗 详细文档

完整说明见: **HEARTBEAT_OPTIMIZATION.md**



