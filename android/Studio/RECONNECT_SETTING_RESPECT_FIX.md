# 重连设置尊重修复

## 问题描述

**症状**：
- 用户在高级设置中**未勾选"被踢出后自动重连"**
- 被踢出后，虽然 `OnDisconnected` 回调正确地跳过了重连逻辑
- 但 **45秒后 RDP 心跳失败，90秒后再次失败，触发了自动重连**
- **绕过了用户的设置！**

## 根本原因

在 `OnDisconnected` 和 `OnConnectionFailure` 中，当用户被踢出且未勾选"自动重连"时：

```java
if (!autoReconnectEnabled) {
    Log.i(TAG, "OnDisconnected: ✗ 未勾选自动重连，直接关闭会话");
    session.setUIEventListener(null);
    closeSessionActivity(RESULT_OK);
    return;
}
```

**问题**：
- ✅ 调用了 `closeSessionActivity()` 并返回
- ❌ **但没有设置 `sessionRunning = false`**
- ❌ RDP 心跳继续运行（第767行检查 `sessionRunning` 仍为 `true`）
- ❌ 90秒后心跳连续失败 → 触发重连 → **绕过设置检查**

## 问题场景重现

```
T0:   用户被踢出（管理员操作）
      - OnDisconnected 回调
      - 检查 autoReconnectOnKick = false
      - 调用 closeSessionActivity()
      - return ✓

T45秒: RDP心跳尝试发送 → 失败（连接已断）
       - consecutiveFailures = 1
       - sessionRunning 仍为 true，心跳继续

T90秒: RDP心跳再次失败 → consecutiveFailures = 2
       - 触发重连！← ⚠️ 绕过了设置检查！
       - attemptReconnect("RDP心跳连续失败")
```

## 解决方案

在被踢出且未勾选自动重连时，**先设置 `sessionRunning = false`**，让 RDP 心跳自动停止。

### 修改1：OnDisconnected（第3333-3334行）

```java
if (!autoReconnectEnabled) {
    // 未勾选：不重连，直接退出
    Log.i(TAG, "OnDisconnected: ✗ 未勾选自动重连，停止心跳并关闭会话");
    sessionRunning = false;  // ✅ 停止会话，让RDP心跳自动停止（第767行检查）
    session.setUIEventListener(null);
    closeSessionActivity(RESULT_OK);
    return;
}
```

### 修改2：OnConnectionFailure（第3193-3194行）

```java
if (!autoReconnectEnabled) {
    // 未勾选 - 不重连，显示对话框（需要手动确认）
    Log.i(TAG, "❌ 被踢出且未勾选 - 停止心跳并显示对话框");
    sessionRunning = false;  // ✅ 停止会话，让RDP心跳自动停止（第767行检查）
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

## 工作原理

添加 `sessionRunning = false` 后，RDP 心跳会在下次循环时自动停止：

```java
// 第767行：RDP心跳检查
if (!sessionRunning || session == null) {
    Log.d(TAG, "RDP heartbeat stopped: sessionRunning=" + sessionRunning);
    return;  // ← 心跳停止，不会再触发重连
}
```

## 修复后的完整逻辑

| 断开原因 | 检查条件 | 是否重连 | sessionRunning |
|----------|----------|----------|----------------|
| **手动退出** | `manualDisconnect = true` | ❌ **不重连** | - |
| **被踢出 + 未勾选** | `isKickedOut && !autoReconnectOnKick` | ❌ **不重连** | **设为 false** ✅ |
| **被踢出 + 已勾选** | `isKickedOut && autoReconnectOnKick` | ✅ **重连** | 保持 true |
| **网络断开** | 其他情况 | ✅ **始终重连** | 保持 true |
| **TCP超时** | 其他情况 | ✅ **始终重连** | 保持 true |
| **RDP心跳失败** | `consecutiveFailures >= 2` | ✅ **始终重连** | 保持 true |

## 验证要点

1. ✅ **被踢出 + 未勾选** → 立即停止心跳，不重连
2. ✅ **被踢出 + 已勾选** → 正常重连
3. ✅ **网络断开** → 正常重连（不受影响）
4. ✅ **手动退出** → 不重连（现有逻辑已正确）

## 修复日期

- **日期**：2025-01-06
- **文件**：`freeRDPCore/src/main/java/com/freerdp/freerdpcore/presentation/SessionActivity.java`
- **修改行**：3194, 3334

## 相关文档

- `RECONNECT_BUG_FIX_COMPLETE.md` - RDP心跳失败触发重连修复
- `RECONNECT_RACE_CONDITION_FIX.md` - 重连竞态条件修复
- `DUAL_KEEPALIVE_CONFIG.md` - 双重保活机制配置
