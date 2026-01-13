# 回调冲突修复 - OnConnectionFailure 与 OnDisconnected 协调

## 问题描述

**症状**（用户反馈）：
- 被踢出 + 未勾选自动重连 + 解锁后
- ✅ 会出现"被踢"的提示信息（对话框显示）
- ✅ 点击确认后，会退出登录
- ❌ **但仍然会触发重连**

## 根本原因

### 双回调冲突

被踢出时，**两个回调都会被触发**：

1. **OnConnectionFailure**（第3101-3102行）
2. **OnDisconnected**（第3104-3106行）

但它们的处理逻辑不协调：

| 回调 | 处理逻辑 | kickedOutDialogShowing 检查 |
|------|----------|---------------------------|
| **OnConnectionFailure** | 显示对话框，等用户确认 | ✅ 设置为 true |
| **OnDisconnected** | **直接关闭 Activity** | ❌ **不检查** |

### 冲突流程

```
T0: 被踢出（锁屏时）
    ↓
T1: OnConnectionFailure 回调
    - kickedOutDialogShowing = true
    - uiHandler.post(显示对话框)  ← 准备显示
    - return
    ↓
T2: OnDisconnected 也被回调
    - sessionRunning = false
    - 清除标志
    - ⚠️ 检查 kickedOutDialogShowing？NO!
    - closeSessionActivity(RESULT_OK)  ← 立即关闭！
    ↓
结果：对话框显示后立即被关闭
    或 Activity 关闭导致对话框无法显示
    或 时序混乱导致其他问题
```

### OnDisconnected 的原始逻辑（第3380-3403行）

```java
if (!autoReconnectEnabled) {
    Log.i(TAG, "OnDisconnected: ✗ 未勾选自动重连，停止心跳并关闭会话");
    sessionRunning = false;
    
    // 清除标志...
    
    session.setUIEventListener(null);
    closeSessionActivity(RESULT_OK);  // ← 直接关闭，不检查对话框
    return;
}
```

**问题**：没有检查 `kickedOutDialogShowing`，导致与 OnConnectionFailure 冲突。

## 解决方案

在 **OnDisconnected** 中也检查 `kickedOutDialogShowing`，如果对话框正在显示，不要重复关闭 Activity，让对话框处理。

### 修改位置

**文件**：`SessionActivity.java`  
**行数**：第3380-3408行（OnDisconnected 中）

### 修改内容

**添加检查**：
```java
if (!autoReconnectEnabled) {
    Log.i(TAG, "OnDisconnected: ✗ 未勾选自动重连，停止心跳并关闭会话");
    sessionRunning = false;
    
    // 清除标志...
    
    // ✅ 新增：检查是否有对话框正在显示
    if (kickedOutDialogShowing) {
        Log.i(TAG, "OnDisconnected: Kicked out dialog already showing, let dialog handle closing");
        return;  // ← 不关闭，由对话框处理
    }
    
    session.setUIEventListener(null);
    closeSessionActivity(RESULT_OK);
    return;
}
```

## 修复后的协调机制

### 场景1：只触发 OnConnectionFailure

```
OnConnectionFailure
    ↓
kickedOutDialogShowing = true
    ↓
显示对话框
    ↓
用户点击 OK
    ↓
kickedOutDialogShowing = false
    ↓
closeSessionActivity() ✅
```

### 场景2：只触发 OnDisconnected

```
OnDisconnected
    ↓
检查 kickedOutDialogShowing = false
    ↓
直接 closeSessionActivity() ✅
```

### 场景3：两个都触发（修复后）

```
OnConnectionFailure（先触发）
    ↓
kickedOutDialogShowing = true
    ↓
uiHandler.post(对话框)
    ↓
OnDisconnected（后触发）
    ↓
检查 kickedOutDialogShowing = true
    ↓
✅ 发现对话框正在显示
    ↓
✅ return（不关闭）
    ↓
对话框正常显示
    ↓
用户点击 OK
    ↓
kickedOutDialogShowing = false
    ↓
closeSessionActivity() ✅
```

## 为什么需要这个检查？

### 回调触发的不确定性

| 情况 | OnConnectionFailure | OnDisconnected | 说明 |
|------|-------------------|----------------|------|
| **情况A** | ✅ 触发 | ❌ 不触发 | 只检测到连接失败 |
| **情况B** | ❌ 不触发 | ✅ 触发 | 只检测到断开 |
| **情况C** | ✅ 触发 | ✅ 触发 | 都检测到（常见）|

在情况C（两个都触发）时，需要协调机制避免冲突。

### 修改前后对比

| 触发情况 | 修改前 | 修改后 |
|---------|--------|--------|
| **只 OnConnectionFailure** | 显示对话框 ✅ | 显示对话框 ✅ |
| **只 OnDisconnected** | 直接关闭 ✅ | 直接关闭 ✅ |
| **两个都触发** | **冲突** ❌ | **协调** ✅ |

## 完整修复历程

本次是第**7次修复**，解决了双回调冲突问题：

| 修复 | 问题 | 解决方案 | 位置 |
|------|------|----------|------|
| 修复1 | RDP心跳继续运行 → 90秒后重连 | `sessionRunning = false` | OnConnectionFailure + OnDisconnected |
| 修复2 | ServiceRestartReceiver 触发重连 | 清除 `has_active_session` | OnConnectionFailure + OnDisconnected |
| 修复3 | onResume 重新设置标志 → 触发重连 | 检查并 `finish()` | onResume |
| 修复4 | 首次连接被阻止 | 添加 `session != null` 检查 | onResume |
| 修复5 | 对话框被 finish() 关闭 | 添加 `kickedOutDialogShowing` 标志 | OnConnectionFailure + onResume |
| 修复6 | 对话框显示时仍执行状态更新 | 提前 return，不执行更新 | onResume |
| **修复7** | **OnDisconnected 与对话框冲突** | **检查 kickedOutDialogShowing** | **OnDisconnected** |

## 修复日期

- **发现日期**：2025-01-06
- **修复日期**：2025-01-06
- **修复编号**：Bug Fix #17

## 相关文档

- `RECONNECT_DIALOG_RETURN_FIX.md` - 修复6：对话框显示时提前return
- `RECONNECT_DIALOG_PROTECTION_FIX.md` - 修复5：对话框显示保护
- `RECONNECT_FIRST_CONNECT_FIX.md` - 修复4：首次连接修复
- `RECONNECT_ONRESUME_FIX.md` - 修复3：onResume 检查
- `RECONNECT_SETTING_FINAL_FIX.md` - 修复1+2+3总结

## 验证要点

### 测试场景

| 场景 | 预期结果 |
|------|---------|
| 1. 被踢出+未勾选+锁屏不解锁 | ❌ 不重连 |
| 2. 被踢出+未勾选+解锁 | ✅ 显示对话框，**❌ 不重连** |
| 3. 对话框显示中 | ✅ 对话框保持显示，不被关闭 |
| 4. 点击对话框OK | ✅ 正常关闭 Activity |
| 5. 被踢出+已勾选 | ✅ 正常重连（不显示对话框）|
| 6. 首次连接 | ✅ 正常连接 |
| 7. 网络断开 | ✅ 正常重连 |

### 关键日志

**成功修复的日志（两个回调都触发）**：
```
OnConnectionFailure: ❌ 被踢出且未勾选 - 停止心跳并显示对话框
✓ Cleared session flags
kickedOutDialogShowing = true
OnDisconnected: ========== 开始检查是否重连 ==========
OnDisconnected: ✗ 未勾选自动重连，停止心跳并关闭会话
✓ Cleared session flags to prevent reconnection triggers (OnDisconnected)
OnDisconnected: Kicked out dialog already showing, let dialog handle closing
✅ 不会出现重复的 "closeSessionActivity"
AlertDialog showing...
User clicked OK
closeSessionActivity(RESULT_CANCELED)
```

## 总结

修复7解决了 OnConnectionFailure 和 OnDisconnected 两个回调冲突的问题：

**问题**：
- 被踢出时两个回调都触发
- OnConnectionFailure 显示对话框
- OnDisconnected 直接关闭 Activity
- 导致冲突和混乱

**方案**：
- 在 OnDisconnected 中检查 `kickedOutDialogShowing`
- 如果对话框正在显示，不关闭 Activity
- 让对话框的 OK 按钮处理关闭逻辑

**效果**：
- ✅ 对话框正常显示和确认
- ✅ 两个回调协调工作
- ✅ 不会触发重连
- ✅ 用户体验完整

通过7次迭代修复，"被踢出+未勾选自动重连"场景的所有问题应该都已彻底解决。
