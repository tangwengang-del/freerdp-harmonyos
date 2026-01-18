# "被踢出后自动重连"设置最终完整修复

## 问题总结

用户在高级设置中**未勾选"被踢出后自动重连"**时，在以下场景仍然会触发自动重连：
1. ❌ 锁屏状态下，90秒后触发重连
2. ❌ 解锁屏幕时，立即触发重连

## 完整修复历程

### 🔧 修复1：停止RDP心跳

**问题**：被踢出且未勾选时，`sessionRunning` 仍为 `true`，RDP心跳继续运行，90秒后检测到2次连续失败，触发重连。

**解决**：设置 `sessionRunning = false`

**位置**：
- `OnConnectionFailure` 第3194行
- `OnDisconnected` 第3350行

```java
if (!autoReconnectEnabled) {
    sessionRunning = false;  // ✅ 停止会话，让RDP心跳自动停止
    // ...
}
```

**效果**：✅ RDP心跳停止，不会因心跳失败触发重连

---

### 🔧 修复2：清除会话标志

**问题**：虽然 RDP 心跳停止了，但 `has_active_session` 等标志仍为 `true`。解锁时，ServiceRestartReceiver 检测到会话活跃但连接断开，触发恢复/重连。

**解决**：立即清除所有会话标志

**位置**：
- `OnConnectionFailure` 第3196-3209行
- `OnDisconnected` 第3352-3365行

```java
if (!autoReconnectEnabled) {
    sessionRunning = false;
    
    // ✅ 立即清除活跃会话标记
    try {
        getSharedPreferences(RDP_STATE_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean("has_active_session", false)
            .putBoolean("reconnect_in_progress", false)
            .remove("session_instance")
            .remove("activity_state")
            .remove("activity_last_heartbeat")
            .apply();
    } catch (Exception e) {
        Log.w(TAG, "Failed to clear session flags", e);
    }
    
    // ... 显示对话框或关闭会话 ...
}
```

**效果**：✅ ServiceRestartReceiver 不会触发，但解锁时仍然重连...

---

### 🔧 修复3：防止 onResume 重新设置标志（最终修复）⭐

**问题**：虽然在 `OnConnectionFailure`/`OnDisconnected` 中清除了标志，但解锁时 `onResume` 会**无条件**调用 `updateActivityState("ready")`，重新设置 `activity_state` 和 `activity_last_heartbeat`，导致 ServiceRestartReceiver 再次检测到异常状态，触发重连。

**解决**：在 `onResume` 开始时检查 `has_active_session`，如果为 `false`（会话已结束），立即关闭 Activity，不执行后续逻辑。

**位置**：`onResume` 第502-509行

```java
@Override protected void onResume()
{
    super.onResume();
    Log.v(TAG, "Session.onResume");
    
    // ✅ 检查会话是否已结束（被踢出且未勾选自动重连，或手动断开）
    SharedPreferences rdpPrefs = getSharedPreferences(RDP_STATE_PREFS, MODE_PRIVATE);
    if (!rdpPrefs.getBoolean("has_active_session", false)) {
        Log.i(TAG, "onResume: No active session, finishing activity");
        finish();
        return;  // ← 关键：不执行 updateActivityState("ready")
    }
    
    // ... 原有逻辑 ...
    
    updateActivityState("ready");  // ← 只有会话活跃时才执行
}
```

**效果**：✅ 彻底解决解锁时触发重连的问题！

---

## 完整修复流程图

```
┌─────────────────────────────────────────────────────────┐
│  用户被踢出 + 未勾选"自动重连"                           │
└─────────────────────────────────────────────────────────┘
                          ↓
          ┌───────────────────────────────┐
          │  OnConnectionFailure 或        │
          │  OnDisconnected 回调           │
          └───────────────────────────────┘
                          ↓
          ┌───────────────────────────────┐
          │  检测：被踢出 + 未勾选          │
          │  autoReconnectEnabled = false  │
          └───────────────────────────────┘
                          ↓
          ┌───────────────────────────────┐
          │  🔧 修复1：停止RDP心跳          │
          │  sessionRunning = false        │
          └───────────────────────────────┘
                          ↓
          ┌───────────────────────────────┐
          │  🔧 修复2：清除会话标志         │
          │  has_active_session = false    │
          │  activity_state = (removed)    │
          │  activity_last_heartbeat = (removed) │
          └───────────────────────────────┘
                          ↓
          ┌───────────────────────────────┐
          │  显示对话框 或 关闭会话         │
          │  (用户还未点击OK)              │
          └───────────────────────────────┘
                          ↓
          ┌───────────────────────────────┐
          │  用户解锁屏幕                  │
          │  onResume() 被调用             │
          └───────────────────────────────┘
                          ↓
          ┌───────────────────────────────┐
          │  🔧 修复3：检查会话状态         │
          │  has_active_session = false?   │
          └───────────────────────────────┘
                          ↓
                        【是】
                          ↓
          ┌───────────────────────────────┐
          │  立即 finish() Activity         │
          │  return (不执行后续逻辑)       │
          └───────────────────────────────┘
                          ↓
          ┌───────────────────────────────┐
          │  ✅ 不会调用                    │
          │     updateActivityState("ready")│
          └───────────────────────────────┘
                          ↓
          ┌───────────────────────────────┐
          │  ✅ 不会重新设置标志            │
          └───────────────────────────────┘
                          ↓
          ┌───────────────────────────────┐
          │  ✅ ServiceRestartReceiver      │
          │     不会被触发                 │
          └───────────────────────────────┘
                          ↓
                 ✅ 不会触发重连！
```

## 修复对比

| 时间点 | 修复前 | 修复1 | 修复1+2 | 修复1+2+3 ✅ |
|--------|--------|-------|---------|-------------|
| **被踢出后（锁屏）** | ❌ 90秒后重连 | ✅ 不重连 | ✅ 不重连 | ✅ 不重连 |
| **被踢出后（解锁）** | ❌ 立即重连 | ❌ 立即重连 | ❌ 立即重连 | ✅ **不重连** |

## 修改位置总览

### 文件
`freeRDPCore/src/main/java/com/freerdp/freerdpcore/presentation/SessionActivity.java`

### 修改点

| 修复 | 位置 | 行数 | 修改内容 |
|------|------|------|---------|
| **修复1** | OnConnectionFailure | 3194 | 添加 `sessionRunning = false;` |
| **修复1** | OnDisconnected | 3350 | 添加 `sessionRunning = false;` |
| **修复2** | OnConnectionFailure | 3196-3209 | 添加清除标志代码块 |
| **修复2** | OnDisconnected | 3352-3365 | 添加清除标志代码块 |
| **修复3** | onResume | 502-509 | 添加会话检查并 finish() |

## 影响分析

### ✅ 不影响其它功能

| 场景 | has_active_session | 行为 | 状态 |
|------|-------------------|------|------|
| **被踢出+未勾选** | `false` | 不重连，finish() | ✅ 已修复 |
| **被踢出+已勾选** | `true` | 正常重连 | ✅ 正常 |
| **网络断开** | `true` | 正常重连 | ✅ 正常 |
| **TCP超时** | `true` | 正常重连 | ✅ 正常 |
| **RDP心跳失败** | `true` | 正常重连 | ✅ 正常 |
| **手动退出** | `false` | 不重连，finish() | ✅ 正常 |
| **正常锁屏解锁** | `true` | 正常恢复 | ✅ 正常 |

### 清除的标志说明

| 标志 | 修复1 | 修复2 | 修复3 | 作用 |
|------|-------|-------|-------|------|
| `sessionRunning` | ✅ 设为 false | - | - | 停止RDP心跳 |
| `has_active_session` | - | ✅ 设为 false | ✅ 检查此标志 | 防止自动重启 |
| `reconnect_in_progress` | - | ✅ 设为 false | - | 防止重复重连 |
| `session_instance` | - | ✅ 清除 | - | 清除会话ID |
| `activity_state` | - | ✅ 清除 | ✅ 防止重设 | 清除Activity状态 |
| `activity_last_heartbeat` | - | ✅ 清除 | ✅ 防止重设 | 清除心跳记录 |

## 验证场景

### 完整测试矩阵

| 测试场景 | 操作 | 预期结果 | 状态 |
|----------|------|---------|------|
| 1 | 被踢出+未勾选+锁屏不解锁 | ❌ 不重连 | ✅ 应该已修复 |
| 2 | 被踢出+未勾选+立即解锁 | ❌ 不重连，Activity关闭 | ✅ **已修复** |
| 3 | 被踢出+未勾选+延迟解锁 | ❌ 不重连，Activity关闭 | ✅ **已修复** |
| 4 | 被踢出+已勾选+任意操作 | ✅ 正常重连 | ✅ 应该正常 |
| 5 | 网络断开+解锁 | ✅ 正常重连 | ✅ 应该正常 |
| 6 | 手动退出+解锁 | ❌ 不重连，Activity关闭 | ✅ 应该正常 |
| 7 | 正常使用+锁屏解锁 | ✅ 正常恢复画面 | ✅ 应该正常 |

### 关键日志

**成功修复的日志**（解锁时）：
```
Session.onResume
onResume: No active session (kicked out without auto-reconnect or manually disconnected), finishing activity
```

**不应出现的日志**（解锁时）：
```
❌ updateActivityState("ready")  // 不应该执行到这里
❌ ServiceRestartReceiver: Activity fully recovered  // 不应该触发恢复
❌ attemptReconnect  // 不应该触发重连
```

## 修复日期

- **开始日期**：2025-01-06
- **最终修复日期**：2025-01-06
- **修复编号**：Bug Fix #11, #12, #13

## 相关文档

- `RECONNECT_ONRESUME_FIX.md` - 修复3详细说明（本次修复）
- `RECONNECT_SETTING_FIX_COMPLETE.md` - 修复1+修复2总结
- `RECONNECT_SETTING_RESPECT_FIX.md` - 修复1详细说明
- `RECONNECT_UNLOCK_TRIGGER_FIX.md` - 修复2详细说明
- `RECONNECT_BUG_FIX_COMPLETE.md` - RDP心跳失败触发重连修复
- `RECONNECT_RACE_CONDITION_FIX.md` - 重连竞态条件修复

## 总结

通过**三次修复**，彻底解决了"被踢出且未勾选自动重连"时的所有重连触发问题：

1. **修复1**：停止 RDP 心跳（设置 `sessionRunning = false`）
   - 解决：锁屏下90秒后心跳失败触发重连

2. **修复2**：清除会话标志（清除 `has_active_session` 等）
   - 解决：ServiceRestartReceiver 检测到会话活跃触发重连

3. **修复3**：防止 onResume 重新设置标志（检查并 finish()）⭐
   - 解决：解锁时 updateActivityState 重新设置标志触发重连
   - **最终完整解决问题**

三个修复在不同层次上阻止了重连：
- **修复1**：阻止 RDP 心跳层
- **修复2**：阻止 ServiceRestartReceiver 层
- **修复3**：阻止 Activity 生命周期层

修改只影响特定场景，不会影响其它正常的重连机制。
