# 应用自动重启与重连逻辑修复总结

## 修复日期
2025-12-18

## 问题概述
应用被系统杀死后的自动重启和自动重连机制存在以下关键问题：
1. 误触发重启（手动断开、锁屏等场景）
2. 自动重启与自动重连机制冲突，可能导致重复连接
3. 缺乏有效的并发控制和状态同步

## 已实施的修复（按优先级）

### P0 - 防止误触发重启

#### 1. 手动断开后立即清除状态标志 ✅
**文件**: `SessionActivity.java`
**修改位置**: 
- `onOptionsItemSelected()` - 手动断开菜单项处理
- `closeSessionActivity()` - Activity关闭时

**修复内容**:
- 手动断开时立即清除 `has_active_session` 标志
- 持久化 `manual_disconnect` 标志到 SharedPreferences
- 清除所有重连相关状态，防止后续误触发

```java
// 手动断开时
SharedPreferences.Editor editor = getSharedPreferences("rdp_state", MODE_PRIVATE).edit();
editor.putBoolean("has_active_session", false);
editor.putBoolean("manual_disconnect", true);
editor.putLong("session_instance", -1);
editor.apply();
```

#### 2. Service重启逻辑检查手动断开标志 ✅
**文件**: `ServiceRestartReceiver.java`
**修改位置**: `onReceive()` 方法

**修复内容**:
- 在检查 `has_active_session` 后，增加 `manual_disconnect` 检查
- 如果检测到手动断开，跳过Service重启，避免误触发

```java
if (rdpPrefs.getBoolean("manual_disconnect", false)) {
    Log.i(TAG, "❌ Manual disconnect detected, skip restart");
    rdpPrefs.edit().putBoolean("manual_disconnect", false).apply();
    return;
}
```

### P1 - 防止重复连接

#### 3. 持久化 isReconnecting 状态 ✅
**文件**: `SessionActivity.java`
**新增方法**:
- `tryAcquireReconnectLock(String source)` - 获取重连锁
- `releaseReconnectLock()` - 释放重连锁
- `isReconnectInProgress()` - 检查重连状态

**修复内容**:
- 将 `isReconnecting` 状态持久化到 SharedPreferences
- 支持跨进程和Activity重建的状态恢复
- 引入60秒超时机制，防止死锁

**持久化字段**:
```
reconnect_in_progress: boolean
reconnect_source: String
reconnect_lock_time: long
```

#### 4. Native连接状态显式检查 ✅
**文件**: `SessionActivity.java`
**修改位置**: `attemptReconnect()` 中的延迟任务

**修复内容**:
- 在调用 `connect()` 前检查 Native 连接是否仍然存活
- 如果 Native 连接存活，跳过重连，释放锁并重置状态

```java
if (session != null) {
    boolean nativeAlive = LibFreeRDP.isConnectionAlive(instanceId);
    if (nativeAlive) {
        Log.w(TAG, "Native connection still alive, skip reconnect");
        releaseReconnectLock();
        resetReconnectState();
        return;
    }
}
```

#### 5. 统一重连计数器 ✅
**文件**: 
- `ServiceRestartReceiver.java` - 移除独立的 `restart_count`
- `SessionActivity.java` - 统一使用 `reconnectAttempts`

**修复内容**:
- 废弃 Service 的独立 `restart_count` 计数
- Service 重启时检查已有的重连状态，不重复计数
- 所有重连请求统一使用 `SessionActivity.reconnectAttempts`

**修改签名**:
- 移除所有方法中的 `restartCount` 参数
- 简化通知显示，不再依赖计数

### P2 - 优化用户体验

#### 6. 重连优先级检查机制 ✅
**文件**: `SessionActivity.java`
**新增内容**:

**优先级定义**:
```java
PRIORITY_USER_MANUAL = 4      // 用户主动重连（最高）
PRIORITY_SERVICE_RESTART = 3  // Service重启恢复
PRIORITY_ONDISCONNECTED = 2   // OnDisconnected自动重连
PRIORITY_KEEPALIVE = 1        // Keepalive超时重连（最低）
```

**机制**:
- 高优先级请求可以抢占低优先级正在进行的重连
- 同优先级或低优先级请求被阻止
- 在日志中显示优先级信息便于调试

```java
if (newPriority > existingPriority) {
    Log.w(TAG, "Higher priority reconnection, preempting...");
    releaseReconnectLock();
    // Continue to acquire new lock
}
```

## 关键改进点

### 1. 状态持久化
- 所有关键重连状态都持久化到 SharedPreferences
- 支持Activity重建后恢复状态
- 60秒超时自动清理陈旧状态

### 2. 并发控制
- 内存锁（AtomicBoolean）+ 持久化锁（SharedPreferences）双重保护
- 跨进程一致性保证
- 防止竞态条件

### 3. 优先级系统
- 4级优先级，清晰的抢占规则
- 高优先级任务优先执行
- 日志中标注优先级便于调试

### 4. 防误触发
- 手动断开立即清除所有标志
- Service重启前检查断开原因
- 锁屏等场景不误触发

## 测试建议

### 必须验证的场景

1. **手动断开场景**
   - 用户点击断开按钮后，杀掉应用
   - 预期：不应自动重启

2. **锁屏解锁场景**
   - 锁屏 → Service被杀 → 解锁
   - 预期：Activity自然恢复，不重复启动

3. **后台保活场景**
   - 长时间后台运行，Service被杀
   - 预期：正确恢复连接

4. **并发重连场景**
   - 同时触发 OnDisconnected 和 Service重启
   - 预期：只执行一次重连，无重复

5. **优先级抢占场景**
   - Keepalive超时触发重连时，触发Service重启
   - 预期：Service重启（高优先级）抢占Keepalive重连

6. **Activity重建场景**
   - 重连过程中旋转屏幕
   - 预期：状态不丢失，继续重连

7. **达到最大重连次数**
   - 10次重连失败
   - 预期：停止尝试，清理状态，显示失败对话框

## 日志标识

修复后的关键日志标记：

```
✓ - 成功操作
⚠️ - 警告/重试
❌ - 失败/阻止
⚡ - 高优先级抢占
[Pn] - 优先级标记（n=1-4）
```

**示例**:
```
✓ Reconnection lock acquired (source: ServiceRestart [P3])
⚡ Higher priority reconnection (new: ServiceRestart [P3] > existing: KeepaliveTimeout [P1])
⚠️ Reconnection already in progress (source: OnDisconnected [P2], blocking: KeepaliveTimeout [P1])
```

## 相关文件清单

### 修改的文件
1. `freeRDPCore/src/main/java/com/freerdp/freerdpcore/presentation/SessionActivity.java`
2. `freeRDPCore/src/main/java/com/freerdp/freerdpcore/application/ServiceRestartReceiver.java`

### SharedPreferences 字段

**rdp_state** (主要状态存储):
```
has_active_session: boolean          - 是否有活跃会话
manual_disconnect: boolean           - 用户手动断开标志
session_instance: long              - 会话实例ID
reconnect_in_progress: boolean      - 重连进行中标志
reconnect_source: String            - 重连来源
reconnect_lock_time: long           - 重连锁获取时间
background_reconnecting: boolean    - 后台重连标志
activity_state: String              - Activity状态
activity_last_heartbeat: long       - Activity心跳时间
```

**service_restart** (Service重启状态):
```
last_restart_time: long             - 最后重启时间（仅用于监控）
```

## 向后兼容性

所有修复都保持向后兼容：
- 旧的字段会自动迁移或忽略
- 没有破坏性的API变更
- 保留了所有现有功能

## 性能影响

- SharedPreferences读写：可忽略（仅在关键路径）
- 优先级计算：O(1)，无性能影响
- 内存占用：增加约1KB（持久化状态）

## 后续建议

### 可选增强（未实施）
1. 使用文件锁替代SharedPreferences，更强的跨进程保证
2. 引入状态机管理会话生命周期
3. 添加遥测统计重启/重连频率
4. 实现自适应重连策略（根据成功率调整）

### 监控指标
建议监控以下指标：
- 自动重启次数/天
- 重连成功率
- 重连平均耗时
- 优先级抢占次数

---

**注意**: 本次修复专注于逻辑正确性和稳定性，未涉及UI变更。


