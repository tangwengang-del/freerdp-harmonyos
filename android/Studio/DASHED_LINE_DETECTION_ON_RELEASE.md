# 虚实线检测 - 按钮松开触发版本

## 更新日期
2025-12-27 (按钮松开触发)

## 核心机制

**触发时机**：只在鼠标左键或中间键松开后100ms进行检测

**目的**：避免与拖动操作冲突，在拖动完成后自动对准虚实线

## 检测流程

```
1. 用户按下左键/中间键
   ↓
2. 移动指针（拖动或移动）
   ↓
3. 松开按钮
   ↓
4. 延迟100ms（确保状态清除）
   ↓
5. 触发虚实线检测
   ↓
6. 扫描指针周围 Y±2px 范围
   ↓
7. 找到最近的虚实线？
   ├─ YES → 自动移动对准 → 变红
   └─ NO  → 保持当前位置
```

## 代码实现

### onUp() - 触发检测

```java
public boolean onUp(MotionEvent e)
{
    // ... 处理按钮点击/拖动 ...
    
    finally {
        // 记录是否有移动操作
        boolean wasMoving = pointerMoving || leftButtonDragging;
        
        // 清除状态
        pointerMoving = false;
        leftButtonDragging = false;
        
        // ★ 左键或中间键移动后，延迟100ms检测虚实线
        if (wasMoving)
        {
            postDelayed(new Runnable() {
                @Override
                public void run()
                {
                    detectDashedLine();  // 检测并自动对准
                }
            }, 100);  // 延迟100ms
        }
    }
}
```

### detectDashedLine() - 检测并对准

```java
private void detectDashedLine()
{
    // 1. 获取当前指针位置
    Point coord = getRemoteCoordinate();
    
    // 2. 在后台线程扫描
    new Thread(() -> {
        int foundY = -1;
        int minDistance = 999;
        
        // 【第一步】快速预扫描：Y±4px 范围（9行）× 左右5px（10px）
        for (int dy = -4; dy <= 4; dy++)
        {
            if (hasRedGreenPixelsInRange(bitmap, coord.y + dy, coord.x, 5))
            {
                candidateY = coord.y + dy;
                break;
            }
        }
        
        // 【第二步】详细判定：Y±2px 范围（5行）× 左右100px（200px）
        for (int dy = -2; dy <= 2; dy++)
        {
            if (isDashedLineAt(bitmap, coord.y + dy, coord.x))
            {
                // 找到虚实线，记录最近的
                int distance = Math.abs(dy);
                if (distance < minDistance) {
                    minDistance = distance;
                    foundY = coord.y + dy;
                }
            }
        }
        
        // 3. 如果找到，自动移动到最近的虚实线
        if (foundY != -1) {
            post(() -> {
                moveToTarget(coord.x, foundY);
                // moveToTarget()完成后会自动变红
            });
        }
    }).start();
}
```

### moveToTarget() - 自动对准并变红

```java
private void moveToTarget(int targetX, int targetY)
{
    // 平滑动画移动到目标位置
    ValueAnimator animator = ...;
    
    animator.addListener(new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation)
        {
            // 移动完成后
            isOnDashedLine = true;      // 设置红色状态
            dashedLineY = targetY;       // 记录虚实线位置
            updatePointerDrawable();     // 变红
        }
    });
    
    animator.start();
}
```

## 虚实线检测规则

### 3个条件

1. **颜色**：红色 (R>180) 或 绿色 (G>180)
2. **有断开**：至少1个断开（gapCount >= 1）
3. **长度限制**：最长线段 <= 30px（排除实线）

### 检测代码

```java
private boolean hasLongShortPattern(int[] pixels)
{
    // 分析段序列：线段(红/绿) - 断开 - 线段 - 断开 ...
    List<Segment> segments = analyzeSegments(pixels);
    
    // 统计
    int lineSegmentCount = 0;  // 红/绿线段数
    int gapCount = 0;          // 断开数
    int maxLineLength = 0;     // 最长线段
    
    for (Segment seg : segments) {
        if (seg.isDark) {  // 红/绿线段
            lineSegmentCount++;
            maxLineLength = Math.max(maxLineLength, seg.length);
        } else {  // 断开
            gapCount++;
        }
    }
    
    // 判断
    return (lineSegmentCount >= 2) &&  // 至少2个线段
           (gapCount >= 1) &&          // 至少1个断开
           (maxLineLength <= 30);      // 最长线段不超过30px
}
```

## 删除的旧机制

为避免冲突，已删除以下机制：

### ❌ 静止500ms检测
```java
// 已删除
private Handler stillDetectionHandler;
private Runnable stillDetectionRunnable;
private long lastMoveTime;
private void initStillDetection() { ... }
private void onPointerStill() { ... }
```

### ❌ 背景滚动监测
```java
// 已删除
private Point lastRemoteCoord;
private Handler coordCheckHandler;
private void initCoordinateMonitoring() { ... }
```

### ❌ 实时红色状态更新
```java
// 已删除
private void onPointerMove() { ... }
private void updateRedCrossState() { ... }
```

## 优势

### 1. 避免冲突
- ✅ 不在拖动过程中检测
- ✅ 不在移动过程中检测
- ✅ 只在操作完成后检测

### 2. 简单清晰
- ✅ 触发时机明确（按钮松开）
- ✅ 逻辑简单（一次检测，一次对准）
- ✅ 状态清晰（检测到就红，否则黑）

### 3. 性能友好
- ✅ 不需要持续监测
- ✅ 不需要后台线程轮询
- ✅ 只在必要时检测

## 使用场景

### 场景1：拖动虚实线
```
1. 用户按左键
2. 拖动虚实线到新位置
3. 松开左键
   ↓
4. 100ms后自动检测
5. 如果指针在虚实线附近 → 自动对准并变红
```

### 场景2：移动指针到虚实线
```
1. 用户按中间键
2. 移动指针到虚实线附近
3. 松开中间键
   ↓
4. 100ms后自动检测
5. 指针自动对准虚实线 → 变红
6. 用户按左键开始拖动
```

### 场景3：没有虚实线
```
1. 用户拖动完成
2. 松开按钮
   ↓
3. 100ms后检测
4. 没有找到虚实线
5. 指针保持黑色，位置不变
```

## 红色状态管理

### 何时变红
- ✅ 检测到虚实线并自动对准后
- ✅ moveToTarget()完成时设置

### 何时恢复黑色
- ✅ 下次按钮松开检测时，如果没找到虚实线
- ✅ 用户手动移动指针离开后，下次检测时

### 状态持久性
- 红色状态会保持到下次检测
- 不会在拖动过程中变化
- 确保"红色=可拖动"的一致性

## 参数配置

### 触发延迟
```java
postDelayed(..., 100);  // 100ms延迟
```

### 检测范围
```java
for (int dy = -2; dy <= 2; dy++)  // Y±2px，共5行
int startX = centerX - 100;       // X±100px，共200px
```

### 虚实线规则
```java
maxLineLength <= 30;  // 最长线段30px（实线最长25px）
gapCount >= 1;        // 至少1个断开
lineSegmentCount >= 2;  // 至少2个线段
```

## 调试

启用DEBUG日志：
```java
private static final boolean DEBUG = true;
```

关键日志：
- `移动操作结束，立即检测虚实线...`
- `✓ 找到虚实线 Y=XXX, 距离=Xpx, 自动移动对准...`
- `✗ 未找到虚实线`
- `自动移动完成: (X,Y), 目标Y=XXX`

## 总结

**核心改进**：从"静止检测"改为"按钮松开触发"

**优势**：
1. ✅ 不与拖动冲突
2. ✅ 触发时机明确
3. ✅ 逻辑简单清晰
4. ✅ 性能更好
5. ✅ 状态管理简单

**检测公式**：
```
按钮松开 → 100ms延迟 → 检测虚实线 → 最近对准 → 变红
```


