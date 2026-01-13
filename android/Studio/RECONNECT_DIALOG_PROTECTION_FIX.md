# 对话框显示保护修复

## 问题描述

**症状**：
- 被踢出 + 未勾选自动重连 + 解锁后
- 对话框应该显示让用户手动确认
- 但对话框看不到或一闪而过

## 根本原因

### 时序冲突

```
T0: 被踢出（锁屏时）
    ↓
    OnConnectionFailure
    ↓
    清除标志：has_active_session = false
    ↓
    uiHandler.post() 准备显示对话框（异步）
    ↓
T1: 用户解锁
    ↓
    onResume 执行（先于对话框显示）
    ↓
    检查：session != null && has_active_session = false
    ↓
    立即 finish() ← ⚠️ Activity 关闭！
    ↓
T2: 对话框尝试显示
    ↓
    但 Activity 已经 finishing/关闭
    ↓
    对话框无法显示或立即消失 ❌
```

### 问题分析

1. `OnConnectionFailure` 中使用 `uiHandler.post()` **异步**显示对话框
2. 用户解锁时，`onResume` **先于对话框显示**执行
3. `onResume` 检测到 `has_active_session = false`，立即 `finish()` Activity
4. 对话框还没来得及显示，Activity 就被关闭了

## 解决方案

添加 `kickedOutDialogShowing` 标志位，保护正在显示或准备显示的对话框。

### 修改内容

#### 1. 添加标志位变量

**位置**：第145行

```java
// ✅ Bug修复 #15: 添加对话框显示标志，防止onResume时关闭正在显示对话框的Activity
private volatile boolean kickedOutDialogShowing = false;
```

#### 2. 修改 OnConnectionFailure 中的对话框逻辑

**位置**：第3201-3245行

**添加内容**：
```java
// ✅ 设置对话框显示标志，防止onResume时关闭Activity
kickedOutDialogShowing = true;

uiHandler.post(new Runnable() {
    @Override
    public void run() {
        // 再次检查Activity状态
        if (isActivityDestroyed || isFinishing()) {
            Log.w(TAG, "Activity is finishing/destroyed, skip dialog");
            kickedOutDialogShowing = false;
            return;
        }
        
        AlertDialog.Builder builder = new AlertDialog.Builder(SessionActivity.this);
        // ... 对话框配置 ...
        builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                kickedOutDialogShowing = false;  // ✅ 清除标志
                dialog.dismiss();
                closeSessionActivity(RESULT_CANCELED);
            }
        });
        builder.show();
    }
});
```

#### 3. 修改 onResume 中的检查逻辑

**位置**：第506行

**修改前**：
```java
if (session != null && !rdpPrefs.getBoolean("has_active_session", false)) {
    finish();
    return;
}
```

**修改后**：
```java
if (session != null && !rdpPrefs.getBoolean("has_active_session", false) && !kickedOutDialogShowing) {
    Log.i(TAG, "onResume: Session exists but has_active_session=false, finishing activity");
    finish();
    return;
}
```

## 修复后的流程

```
T0: 被踢出（锁屏时）
    ↓
    OnConnectionFailure
    ↓
    清除标志：has_active_session = false
    ↓
    设置：kickedOutDialogShowing = true ✅
    ↓
    uiHandler.post() 准备显示对话框
    ↓
T1: 用户解锁
    ↓
    onResume 执行
    ↓
    检查：session != null && has_active_session = false && kickedOutDialogShowing = false
          ↑                                                    ↑
          true                                               false (因为 = true)
    ↓
    条件不满足（kickedOutDialogShowing = true）
    ↓
    ✅ 不 finish()，Activity 继续运行
    ↓
T2: 对话框显示
    ↓
    ✅ 用户看到对话框并可以点击
    ↓
T3: 用户点击 OK
    ↓
    kickedOutDialogShowing = false ✅
    ↓
    closeSessionActivity() ✅
```

## 为什么需要 3 处修改？

| 修改 | 位置 | 作用 |
|------|------|------|
| **添加标志位** | 第145行 | 存储对话框状态，实现跨方法通信 |
| **设置/清除标志** | OnConnectionFailure | 标记对话框生命周期 |
| **检查标志** | onResume | 避免关闭正在显示对话框的Activity |

## 为什么使用 volatile？

```java
private volatile boolean kickedOutDialogShowing = false;
```

- `volatile` 确保多线程可见性
- `OnConnectionFailure` 可能在后台线程执行
- `onResume` 在主线程执行
- `uiHandler.post()` 中的 Runnable 在主线程执行
- 需要确保所有线程看到的值是最新的

## 完整的对话框保护机制

### 1. 设置保护
```java
kickedOutDialogShowing = true;  // 立即设置，保护对话框
```

### 2. onResume 检查
```java
if (...条件... && !kickedOutDialogShowing) {  // 检查标志
    finish();  // 只有标志为 false 时才关闭
}
```

### 3. 双重检查
```java
if (isActivityDestroyed || isFinishing()) {
    kickedOutDialogShowing = false;  // Activity已关闭，清除标志
    return;  // 不显示对话框
}
```

### 4. 清除保护
```java
kickedOutDialogShowing = false;  // 用户点击OK后清除
closeSessionActivity();  // 然后关闭
```

## 影响分析

### ✅ 不影响其它场景

| 场景 | kickedOutDialogShowing | onResume 行为 | 影响 |
|------|----------------------|--------------|------|
| **首次连接** | `false` | 正常执行 | ✅ 正常 |
| **被踢出+未勾选+锁屏** | `true` | 不 finish() | ✅ 保护对话框 |
| **被踢出+未勾选+解锁** | `true` | 不 finish() | ✅ **显示对话框** |
| **被踢出+已勾选** | `false` | 正常执行 | ✅ 正常重连 |
| **网络断开** | `false` | 正常执行 | ✅ 正常重连 |
| **正常解锁** | `false` | 正常执行 | ✅ 正常恢复 |

## 修复日期

- **发现日期**：2025-01-06
- **修复日期**：2025-01-06
- **修复编号**：Bug Fix #15

## 相关文档

- `RECONNECT_SETTING_FINAL_FIX.md` - 完整修复总结（修复1+2+3）
- `RECONNECT_FIRST_CONNECT_FIX.md` - 首次连接修复（修复3的补充）
- `RECONNECT_ONRESUME_FIX.md` - onResume 修复详细说明

## 验证要点

### 测试场景

| 场景 | 操作 | 预期结果 |
|------|------|---------|
| 1 | 被踢出+未勾选+锁屏不解锁 | ❌ 不重连 |
| 2 | 被踢出+未勾选+立即解锁 | **✅ 显示对话框，等待确认** |
| 3 | 被踢出+未勾选+延迟解锁 | **✅ 显示对话框，等待确认** |
| 4 | 点击对话框OK | ✅ Activity关闭 |
| 5 | 被踢出+已勾选 | ✅ 正常重连 |
| 6 | 首次连接 | ✅ 正常连接 |

### 关键日志

**成功显示对话框的日志**：
```
OnConnectionFailure: ❌ 被踢出且未勾选 - 停止心跳并显示对话框
✓ Cleared session flags to prevent reconnection triggers
Session.onResume
✅ 不会出现 "finishing activity"（因为 kickedOutDialogShowing = true）
AlertDialog showing...
```

**用户点击OK的日志**：
```
User clicked OK on kicked out dialog
closeSessionActivity(RESULT_CANCELED)
```

## 总结

通过添加 `kickedOutDialogShowing` 标志位，解决了对话框显示的时序问题：

1. **问题**：onResume 在对话框显示前执行，导致 Activity 被关闭，对话框无法显示
2. **方案**：使用标志位保护对话框，onResume 检查到标志时不关闭 Activity
3. **效果**：用户解锁后能看到并确认对话框

这是"被踢出+未勾选自动重连"修复系列的第5个修复，确保用户体验完整。
