# Bug调试重现步骤

## 调试假设

基于代码分析，我们识别了以下5个关键假设：

### 假设A: 重连锁获取的竞态条件
**问题**: `tryAcquireReconnectLock()`中，检查persistent lock和memory lock之间没有原子性保证，两个线程可能同时通过检查并获取锁。

**验证方法**: 
- 监控`tryAcquireReconnectLock`的entry和CAS操作
- 检查是否有两个线程同时成功获取锁

### 假设B: CAS操作失败后的优先级检查竞态
**问题**: 当CAS失败时，检查优先级并强制获取锁的操作不是原子的，可能导致多个线程同时强制获取。

**验证方法**:
- 监控CAS失败后的优先级检查
- 检查是否有多个线程同时执行"force acquiring"

### 假设C: 优先级抢占的非原子操作
**问题**: `releaseReconnectLock()`和后续的锁获取之间没有原子性，可能被其他线程插入。

**验证方法**:
- 监控优先级抢占时的release和acquire操作
- 检查是否有其他线程在release和acquire之间获取了锁

### 假设D: attemptReconnect中的双重检查锁问题
**问题**: `isReconnectInProgress()`检查和`tryAcquireReconnectLock()`调用之间没有同步，可能在这之间锁被释放。

**验证方法**:
- 监控`attemptReconnect`的entry和`isReconnectInProgress`检查
- 检查是否有多个线程同时进入`attemptReconnect`

### 假设E: releaseReconnectLock的竞态条件
**问题**: `releaseReconnectLock()`中的CAS操作和SharedPreferences更新之间没有原子性，可能导致状态不一致。

**验证方法**:
- 监控`releaseReconnectLock`的entry和CAS操作
- 检查CAS成功后的SharedPreferences更新是否一致

---

## 重现步骤

### 场景1: 并发重连触发（模拟KeepaliveTimeout和OnDisconnected同时触发）

1. 启动应用并建立RDP连接
2. 将应用切换到后台（触发keepalive）
3. 等待约45秒（keepalive检测超时）
4. 同时模拟网络断开（触发OnDisconnected）
5. 观察日志文件：`.cursor/debug.log`

**预期问题**: 
- 两个线程可能同时获取重连锁
- 日志中应该看到两个线程都成功获取锁

### 场景2: Service重启时的并发重连

1. 启动应用并建立RDP连接
2. 手动杀死Service进程（模拟系统杀死）
3. ServiceRestartReceiver触发重连（优先级P3）
4. 同时OnDisconnected事件触发（优先级P2）
5. 观察日志文件

**预期问题**:
- 优先级抢占时可能出现竞态
- 低优先级可能覆盖高优先级

### 场景3: Activity重建时的状态不一致

1. 启动应用并建立连接
2. 触发重连（获取锁）
3. 快速旋转屏幕（触发Activity重建）
4. 在新的Activity实例中检查重连状态
5. 观察日志文件

**预期问题**:
- 内存状态和SharedPreferences状态可能不一致
- 新Activity可能看到过期的锁状态

---

## 日志分析

日志文件位置: `c:\freerdp318\client\Android\Studio\.cursor\debug.log`

### 关键日志点

1. **tryAcquireReconnectLock entry** (假设A)
   - 检查是否有多个线程同时进入
   - 检查source和thread信息

2. **Persistent lock check** (假设A)
   - 检查persistentLock状态
   - 检查lockTime和age

3. **Before CAS memory lock** (假设B)
   - 检查memoryLockBefore状态
   - 检查reconnectionSource

4. **CAS failed - lock already held** (假设B)
   - 检查existingPriority和newPriority
   - 检查是否有多个线程同时执行

5. **Force acquired lock** (假设C)
   - 检查优先级抢占是否成功
   - 检查是否有其他线程插入

6. **attemptReconnect entry** (假设D)
   - 检查是否有多个线程同时进入
   - 检查reason和thread信息

7. **isReconnectInProgress check** (假设D)
   - 检查inProgress状态
   - 检查reconnectionSource

8. **releaseReconnectLock entry** (假设E)
   - 检查lockHeldBefore状态
   - 检查reconnectionSource

9. **CAS succeeded - releasing lock** (假设E)
   - 检查锁释放是否成功
   - 检查lockHeldAfter状态

---

## 验证标准

### 假设A验证 (CONFIRMED/REJECTED/INCONCLUSIVE)
- **CONFIRMED**: 如果日志显示两个线程在相近时间（<100ms）都成功获取锁
- **REJECTED**: 如果日志显示所有CAS操作都正确序列化
- **INCONCLUSIVE**: 如果日志不完整或无法确定

### 假设B验证
- **CONFIRMED**: 如果日志显示CAS失败后，多个线程同时执行"force acquiring"
- **REJECTED**: 如果日志显示优先级检查正确阻止了低优先级线程
- **INCONCLUSIVE**: 如果无法确定

### 假设C验证
- **CONFIRMED**: 如果日志显示release和acquire之间有其他线程获取了锁
- **REJECTED**: 如果日志显示优先级抢占是原子的
- **INCONCLUSIVE**: 如果无法确定

### 假设D验证
- **CONFIRMED**: 如果日志显示多个线程同时进入`attemptReconnect`，且都通过了`isReconnectInProgress`检查
- **REJECTED**: 如果日志显示只有一个线程进入，或检查正确阻止了重复调用
- **INCONCLUSIVE**: 如果无法确定

### 假设E验证
- **CONFIRMED**: 如果日志显示CAS成功但SharedPreferences状态不一致
- **REJECTED**: 如果日志显示CAS和SharedPreferences更新是一致的
- **INCONCLUSIVE**: 如果无法确定

---

## 注意事项

1. **清除日志**: 每次测试前删除`.cursor/debug.log`文件
2. **时间戳**: 关注日志中的timestamp，检查操作的时间顺序
3. **线程信息**: 检查thread字段，确认不同线程的操作
4. **状态一致性**: 检查memory状态和persistent状态是否一致

---

## 修复建议

如果假设被确认，建议的修复方案：

1. **使用单一锁机制**: 将所有重连操作包装在`synchronized`块中
2. **使用CAS循环**: 在`tryAcquireReconnectLock()`中使用CAS循环确保原子性
3. **使用ReentrantLock**: 替换AtomicBoolean，使用ReentrantLock提供更细粒度的控制
4. **状态机模式**: 使用状态机管理重连状态，避免竞态条件


