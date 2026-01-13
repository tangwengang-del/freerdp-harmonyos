# 解锁时重连触发修复

## 问题描述

**症状**：
- 用户被踢出后，设置里**未勾选"被踢出后自动重连"**
- 锁屏状态下正常（不重连）✅
- 一直解锁状态正常（不重连）✅
- **但一开锁，虽然显示被踢出对话框，但又激活了重连** ❌

## 根本原因

### 问题流程

```
T0: 用户被踢出（锁屏状态）
    ↓
    OnConnectionFailure 回调
    ↓
    检测到：被踢出 + 未勾选自动重连
    ↓
    【之前的代码】
    - sessionRunning = false ✅
    - 显示对话框 ✅
    - return ✅
    - ❌ 但 has_active_session 仍然是 true！
    ↓
    【对话框还未点击 OK，用户就解锁了】
    ↓
T1: 用户解锁屏幕
    ↓
    onResume 回调
    ↓
    【可能的触发路径】
    1. ServiceRestartReceiver 检测到：
       - has_active_session = true ✓
       - Activity 状态异常？
       → 尝试恢复/重启 → 触发重连 ❌
    
    2. OnDisconnected 也被触发（延迟回调）
       → 检查设置 → 可能触发重连 ❌
```

### 核心问题

在 `OnConnectionFailure` 中（第3191-3212行），当被踢出且未勾选自动重连时：

```java
if (!autoReconnectEnabled) {
    sessionRunning = false;  // ✅ 设置了这个
    
    // ❌ 但没有清除这些标志！
    // has_active_session 仍然是 true
    // session_instance 仍然存在
    // reconnect_in_progress 可能仍然存在
    
    // 显示对话框（等用户点 OK）
    uiHandler.post(...);
    return;
}
```

**closeSessionActivity() 只在用户点击对话框 OK 按钮时才调用**，期间 `has_active_session` 一直是 `true`，导致：
- ServiceRestartReceiver 认为会话还活跃
- 其它逻辑可能检测到异常状态并尝试恢复

## 解决方案

在显示对话框或关闭会话**之前**，立即清除所有会话标志，防止其它逻辑触发重连。

### 修改位置

**文件**：`SessionActivity.java`  
**修改点1**：第3191-3229行（OnConnectionFailure 中）  
**修改点2**：第3347-3368行（OnDisconnected 中）

### 修改内容

#### 修改点1：OnConnectionFailure（第3191-3229行）

```java
if (!autoReconnectEnabled) {
    // 未勾选 - 不重连，显示对话框（需要手动确认）
    Log.i(TAG, "❌ 被踢出且未勾选 - 停止心跳并显示对话框");
    sessionRunning = false;  // ✅ 停止会话，让RDP心跳自动停止
    
    // ✅ 新增：立即清除活跃会话标记，防止ServiceRestartReceiver或其它逻辑触发重连
    try {
        getSharedPreferences(RDP_STATE_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean("has_active_session", false)
            .putBoolean("reconnect_in_progress", false)
            .remove("session_instance")
            .remove("activity_state")
            .remove("activity_last_heartbeat")
            .apply();
        Log.d(TAG, "✓ Cleared session flags to prevent reconnection triggers");
    } catch (Exception e) {
        Log.w(TAG, "Failed to clear session flags", e);
    }
    
    // 显示对话框
    uiHandler.post(new Runnable() {
        @Override
        public void run() {
            AlertDialog.Builder builder = new AlertDialog.Builder(SessionActivity.this);
            builder.setTitle(R.string.dialog_kicked_out_title);
            builder.setMessage(R.string.dialog_kicked_out_message);
            builder.setCancelable(false);
            builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                    closeSessionActivity(RESULT_CANCELED);
                }
            });
            builder.show();
        }
    });
    return;
}
```

## 修复后的流程

```
T0: 用户被踢出（锁屏状态）
    ↓
    OnConnectionFailure 回调
    ↓
    检测到：被踢出 + 未勾选自动重连
    ↓
    【修复后的代码】
    1. sessionRunning = false ✅
    2. has_active_session = false ✅  ← 立即清除
    3. reconnect_in_progress = false ✅
    4. 清除 session_instance 等 ✅
    5. 显示对话框 ✅
    6. return ✅
    ↓
T1: 用户解锁屏幕
    ↓
    onResume 回调
    ↓
    ServiceRestartReceiver 检测：
    - has_active_session = false ✓
    → 跳过恢复 ✓
    ↓
    ✅ 不会触发重连！
```

## 影响分析

### ✅ 不影响其它重连机制

| 场景 | 进入的代码块 | 是否受影响 |
|------|-------------|-----------|
| **网络断开** | `else` 块（第3220行）| ❌ 不受影响 |
| **TCP超时** | `else` 块（第3220行）| ❌ 不受影响 |
| **RDP心跳失败** | 直接调用 `attemptReconnect` | ❌ 不受影响 |
| **被踢出+已勾选** | 第3215-3223行 | ❌ 不受影响 |
| **被踢出+未勾选** | 第3191-3212行 | ✅ 修改这里 |

**原因**：
1. 修改只在 `if (!autoReconnectEnabled)` 块内
2. 这个块有 `return` 语句（第3212行），不会执行后续的重连逻辑
3. 其它情况根本不会进入这个块

### 清除的标志说明

| 标志 | 作用 | 清除后的影响 |
|------|------|-------------|
| `has_active_session` | ServiceRestartReceiver 检查此标志决定是否重启 | ✅ 防止自动重启 |
| `reconnect_in_progress` | 标记重连正在进行 | ✅ 防止重复重连 |
| `session_instance` | 存储会话实例ID | ✅ 清除会话状态 |
| `activity_state` | Activity 状态（ready/paused/creating）| ✅ 清除状态标记 |
| `activity_last_heartbeat` | Activity 心跳时间戳 | ✅ 清除心跳记录 |

## 验证要点

测试场景：
1. ✅ **被踢出 + 未勾选 + 锁屏** → 不重连
2. ✅ **被踢出 + 未勾选 + 解锁** → **不重连**（本次修复）
3. ✅ **被踢出 + 未勾选 + 一直解锁** → 不重连
4. ✅ **被踢出 + 已勾选** → 正常重连（不受影响）
5. ✅ **网络断开** → 正常重连（不受影响）
6. ✅ **RDP心跳失败** → 正常重连（不受影响）

## 修复日期

- **日期**：2025-01-06
- **文件**：`freeRDPCore/src/main/java/com/freerdp/freerdpcore/presentation/SessionActivity.java`
- **修改行**：
  - 第3196-3209行（OnConnectionFailure - 新增标志清除代码）
  - 第3352-3365行（OnDisconnected - 新增标志清除代码）

## 相关文档

- `RECONNECT_SETTING_RESPECT_FIX.md` - 重连设置尊重修复（第一次修复：添加 sessionRunning = false）
- `RECONNECT_BUG_FIX_COMPLETE.md` - RDP心跳失败触发重连修复
- `RECONNECT_RACE_CONDITION_FIX.md` - 重连竞态条件修复
- `DUAL_KEEPALIVE_CONFIG.md` - 双重保活机制配置

## 为什么需要在两个地方修改？

被踢出时，FreeRDP 可能触发：
1. **只触发 OnDisconnected** → 需要在 OnDisconnected 中清除标志
2. **只触发 OnConnectionFailure** → 需要在 OnConnectionFailure 中清除标志
3. **两个都触发** → 两个地方都清除（重复清除没问题，幂等操作）

为了确保无论哪个回调被触发，都能立即清除标志，防止解锁时触发重连，**两个地方都需要修改**。

## 总结

本次修复解决了"被踢出且未勾选自动重连"情况下，解锁屏幕时仍然触发重连的问题。通过在 **OnConnectionFailure 和 OnDisconnected 两个回调** 中都立即清除所有会话标志，防止 ServiceRestartReceiver 或其它逻辑误判会话状态并触发重连。修改只影响特定场景，不会影响其它正常的重连机制。
