# 对话框显示时提前返回修复

## 问题描述

**症状**：
- 被踢出 + 未勾选自动重连 + 解锁后
- 对话框能显示了（修复5已解决）
- 但仍然会触发重连 ❌

## 根本原因

### 修复5后的问题

修复5添加了 `kickedOutDialogShowing` 标志保护对话框，但只是避免了 `finish()`：

```java
// 修复5的逻辑
if (session != null && !has_active_session && !kickedOutDialogShowing) {
    finish();  // ← 对话框显示时（kickedOutDialogShowing = true）不执行
    return;
}

// ⚠️ 继续往下执行...
updateActivityState("ready");  // ← 问题：重新设置状态！
startActivityHeartbeat();
```

**问题流程**：

```
T0: 被踢出 + 未勾选
    ↓
    OnConnectionFailure
    ↓
    has_active_session = false
    kickedOutDialogShowing = true
    ↓
T1: 用户解锁 → onResume
    ↓
    检查：session != null && !has_active_session && !kickedOutDialogShowing
           true             true                    false (因为 = true)
    ↓
    条件不满足，不执行 finish()
    ↓
    ⚠️ 继续往下执行...
    ↓
    updateActivityState("ready")  ← 重新设置 activity_state!
    startActivityHeartbeat()      ← 启动心跳!
    ↓
    可能触发 ServiceRestartReceiver 或其它恢复逻辑 ❌
```

### 核心问题

**对话框显示时，onResume 没有提前返回**，导致：
1. `updateActivityState("ready")` 被执行
2. 重新设置了 `activity_state` 和 `activity_last_heartbeat`
3. 可能触发各种恢复/重连逻辑

## 解决方案

当对话框显示时，也应该**提前返回**，不执行后续的状态更新逻辑。

### 修改位置

**文件**：`SessionActivity.java`  
**行数**：第504-521行（onResume 中）

### 修改内容

**修改前（修复5）**：
```java
if (session != null && !rdpPrefs.getBoolean("has_active_session", false) && !kickedOutDialogShowing) {
    Log.i(TAG, "onResume: Session exists but has_active_session=false, finishing activity");
    finish();
    return;
}

// ⚠️ 如果 kickedOutDialogShowing = true，继续往下执行
updateActivityState("ready");  // ← 问题所在
```

**修改后（修复6）**：
```java
if (session != null && !rdpPrefs.getBoolean("has_active_session", false)) {
    // 会话已结束（被踢出或手动断开）
    
    if (!kickedOutDialogShowing) {
        // 情况1：没有对话框显示
        // 直接关闭Activity（手动退出等情况）
        Log.i(TAG, "onResume: Session exists but has_active_session=false, finishing activity");
        finish();
        return;
    } else {
        // 情况2：对话框正在显示（被踢出且未勾选）
        // 提前返回，避免执行 updateActivityState 等逻辑
        // 对话框仍然会通过 uiHandler 正常显示
        Log.i(TAG, "onResume: Kicked out dialog showing, skip state update to avoid reconnection triggers");
        return;  // ← 关键：提前返回！
    }
}

// ✅ 只有正常情况（has_active_session = true）才会执行到这里
updateActivityState("ready");
```

## 修复后的流程

```
T0: 被踢出 + 未勾选
    ↓
    OnConnectionFailure
    ↓
    has_active_session = false
    kickedOutDialogShowing = true
    ↓
T1: 用户解锁 → onResume
    ↓
    检查：session != null && !has_active_session
           true             true
    ↓
    进入检查块
    ↓
    检查：!kickedOutDialogShowing
           false (因为 = true)
    ↓
    进入 else 块
    ↓
    ✅ 提前 return
    ↓
    ✅ 不执行 updateActivityState("ready")
    ✅ 不执行 startActivityHeartbeat()
    ✅ 不触发任何恢复逻辑
    ↓
T2: 对话框显示（通过 uiHandler）
    ↓
    ✅ 用户看到并确认对话框
    ↓
T3: 用户点击 OK
    ↓
    kickedOutDialogShowing = false
    ↓
    closeSessionActivity() ✅
```

## 修改对比

### 逻辑结构

**修复5（单层判断）**：
```java
if (条件1 && 条件2 && !对话框显示) {
    finish();
    return;
}
// 对话框显示时继续执行 ❌
```

**修复6（嵌套判断）**：
```java
if (条件1 && 条件2) {
    if (!对话框显示) {
        finish();
        return;
    } else {
        return;  // ← 对话框显示时也返回 ✅
    }
}
// 只有正常情况才执行 ✅
```

### 执行路径

| 场景 | 修复5 | 修复6 |
|------|-------|-------|
| **has_active_session = true** | 继续执行 updateActivityState ✅ | 继续执行 updateActivityState ✅ |
| **has_active_session = false, 无对话框** | finish() ✅ | finish() ✅ |
| **has_active_session = false, 有对话框** | **继续执行 updateActivityState** ❌ | **提前 return** ✅ |

## 影响分析

### ✅ 对话框仍然正常显示

**关键点**：对话框通过 `uiHandler.post()` **独立显示**，不依赖 onResume 的后续逻辑。

```java
// OnConnectionFailure 中
kickedOutDialogShowing = true;
uiHandler.post(new Runnable() {  // ← 对话框已排队
    public void run() {
        builder.show();  // ← 独立显示
    }
});

// onResume 中
return;  // ← 提前返回不影响 uiHandler 队列
```

### ✅ 不影响其它重连机制

| 场景 | session | has_active_session | kickedOutDialogShowing | onResume 行为 |
|------|---------|-------------------|----------------------|--------------|
| **首次连接** | `null` | `false` | `false` | 不进入检查块，正常执行 |
| **被踢出+未勾选+解锁** | `!= null` | `false` | `true` | **提前 return** ✅ |
| **被踢出+已勾选** | `!= null` | `true` | `false` | 不进入检查块，正常重连 |
| **网络断开** | `!= null` | `true` | `false` | 不进入检查块，正常重连 |
| **手动退出+解锁** | `!= null` | `false` | `false` | finish() |
| **正常解锁** | `!= null` | `true` | `false` | 不进入检查块，正常恢复 |

## 修复历程

本次是第**6次修复**，完善了对话框显示时的逻辑：

| 修复 | 问题 | 解决方案 |
|------|------|----------|
| 修复1 | RDP心跳继续运行 → 90秒后重连 | `sessionRunning = false` |
| 修复2 | ServiceRestartReceiver 触发重连 | 清除 `has_active_session` |
| 修复3 | onResume 重新设置标志 → 触发重连 | 检查并 `finish()` |
| 修复4 | 首次连接被阻止 | 添加 `session != null` 检查 |
| 修复5 | 对话框被 finish() 关闭 | 添加 `kickedOutDialogShowing` 标志 |
| **修复6** | **对话框显示时仍执行状态更新** | **提前 return，不执行更新** |

## 修复日期

- **发现日期**：2025-01-06
- **修复日期**：2025-01-06
- **修复编号**：Bug Fix #16

## 相关文档

- `RECONNECT_DIALOG_PROTECTION_FIX.md` - 修复5：对话框显示保护
- `RECONNECT_FIRST_CONNECT_FIX.md` - 修复4：首次连接修复
- `RECONNECT_ONRESUME_FIX.md` - 修复3：onResume 检查
- `RECONNECT_SETTING_FINAL_FIX.md` - 修复1+2+3总结

## 验证要点

### 测试场景

| 场景 | 预期结果 | 验证方法 |
|------|----------|---------|
| 1 | 被踢出+未勾选+锁屏不解锁 | ❌ 不重连 |
| 2 | 被踢出+未勾选+解锁 | ✅ 显示对话框，**❌ 不重连** |
| 3 | 点击对话框OK | ✅ Activity关闭 |
| 4 | 被踢出+已勾选 | ✅ 正常重连 |
| 5 | 首次连接 | ✅ 正常连接 |
| 6 | 网络断开 | ✅ 正常重连 |

### 关键日志

**成功修复的日志**：
```
OnConnectionFailure: ❌ 被踢出且未勾选 - 停止心跳并显示对话框
✓ Cleared session flags
kickedOutDialogShowing = true
Session.onResume
onResume: Kicked out dialog showing, skip state update to avoid reconnection triggers
✅ 不会出现 "updateActivityState"
✅ 不会出现 "attemptReconnect"
AlertDialog showing...
```

## 总结

修复6完善了修复5的逻辑，确保当对话框显示时，onResume 也会提前返回，不执行任何可能触发重连的状态更新逻辑。

**关键改进**：
- ✅ 对话框显示时提前返回
- ✅ 不执行 `updateActivityState("ready")`
- ✅ 不启动 `ActivityHeartbeat`
- ✅ 彻底阻止所有可能的重连触发路径

通过6次迭代修复，"被踢出+未勾选自动重连+解锁"场景下的所有问题应该都已解决。
