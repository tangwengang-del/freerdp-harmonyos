# 红色/黑色状态修复

## 修复日期
2025-12-27

## 问题描述

之前的实现存在两个问题，导致"红色=可拖动"原则不一致：

### 问题1：红色状态不会清除
```java
// 之前的代码
else
{
    // 未找到虚实线
    if (DEBUG) Log.d(TAG, "✗ 未找到虚实线");
    // ❌ 没有清除 isOnDashedLine 状态
}
```

**场景：**
```
1. 第一次检测到虚实线 → 变红 ✓
2. 拖动到空白处（无虚实线）→ 松开按钮
3. 检测未找到虚实线
4. 但指针仍然是红色 ✗ （违反"红色=可拖动"）
```

### 问题2：cursorType == 4 也显示红色
```java
// 之前的代码
else if (currentCursorType == 4)  // RESIZE cursor
{
    currentBaseDrawable = R.drawable.touch_pointer_simple_default_resize;  // 红色
    // ❌ 与虚实线的红色混淆
}
```

**场景：**
```
即使 isOnDashedLine = false，如果 cursorType == 4，
指针也会显示红色，造成混淆。
```

## 修复方案

### 修复1：清除红色状态

```java
// detectDashedLine() - 修改后
else
{
    // ★ 未找到虚实线，清除旧状态，恢复黑色
    post(new Runnable() {
        @Override
        public void run()
        {
            isOnDashedLine = false;  // ★ 清除红色状态
            dashedLineY = -1;        // ★ 清除记录的位置
            updatePointerDrawable(); // ★ 恢复黑色
        }
    });
}
```

### 修复2：简化颜色逻辑

```java
// updatePointerDrawable() - 修改后
private void updatePointerDrawable()
{
    // ★ 只有在虚实线上才显示红色，其它情况都是黑色
    if (isOnDashedLine)
    {
        currentBaseDrawable = R.drawable.touch_pointer_simple_default_resize;  // 红色
    }
    else
    {
        // 所有其它情况都显示黑色（包括 cursorType == 4）
        currentBaseDrawable = R.drawable.touch_pointer_simple_default;  // 黑色
    }
    
    setImageResource(currentBaseDrawable);
}
```

## 状态管理流程

### 完整的状态生命周期

```
初始状态：isOnDashedLine = false（黑色）
    ↓
按钮松开 → 检测虚实线
    ↓
    ├─ 找到虚实线
    │   ↓
    │   自动对准（moveToTarget）
    │   ↓
    │   isOnDashedLine = true
    │   ↓
    │   变红 ✓（可拖动）
    │
    └─ 未找到虚实线
        ↓
        isOnDashedLine = false  ← ★ 关键：清除旧状态
        ↓
        变黑 ✓（不可拖动）
```

### isOnDashedLine 的设置点

只在3个地方设置：

1. **未找到虚实线** (第693行)
   ```java
   isOnDashedLine = false;  // 恢复黑色
   ```

2. **已在目标位置** (第945行)
   ```java
   if (deltaY < 1) {
       isOnDashedLine = true;  // 变红
   }
   ```

3. **移动动画完成** (第994行)
   ```java
   animator.addListener(new AnimatorListenerAdapter() {
       public void onAnimationEnd(Animator animation) {
           isOnDashedLine = true;  // 变红
       }
   });
   ```

## 修复效果

### ✅ 场景1：找到虚实线
```
1. 用户拖动虚实线
2. 松开按钮
3. 检测到虚实线 Y=100
4. 自动对准 → isOnDashedLine = true
5. 指针变红 ✓
6. 用户可以拖动 ✓
```

### ✅ 场景2：未找到虚实线
```
1. 用户移动到空白处
2. 松开按钮
3. 检测未找到虚实线
4. isOnDashedLine = false  ← ★ 清除旧状态
5. 指针变黑 ✓
6. 用户看到黑色，知道不能拖动 ✓
```

### ✅ 场景3：连续操作
```
第1次：
  检测到虚实线 → 红色 ✓
  
第2次：
  移动到空白处 → 检测
  未找到 → isOnDashedLine = false  ← ★ 清除第1次状态
  变黑 ✓
  
第3次：
  又找到虚实线 → 红色 ✓
  
上一次的状态不会影响下一次判断 ✓
```

## 核心原则

### 唯一规则：红色 = 可拖动虚实线

```java
if (isOnDashedLine)  // 在虚实线上
{
    显示红色;  // 表示可以拖动
}
else  // 不在虚实线上
{
    显示黑色;  // 表示不能拖动
}
```

### 状态清除原则

每次检测后，必须明确设置状态：
- 找到 → `isOnDashedLine = true`
- 未找到 → `isOnDashedLine = false`

**不允许状态保持不变（会导致上次影响下次）**

## 测试验证

### 测试点1：红色=可拖动
```
1. 移动到虚实线附近
2. 松开按钮
3. 观察：指针自动对准并变红
4. 验证：可以拖动虚实线 ✓
```

### 测试点2：黑色=不可拖动
```
1. 移动到空白处（无虚实线）
2. 松开按钮
3. 观察：指针保持黑色
4. 验证：尝试拖动无效果 ✓
```

### 测试点3：状态不残留
```
1. 第1次：找到虚实线 → 红色
2. 第2次：移动到空白处 → 应变黑色 ✓（不受第1次影响）
3. 第3次：又找到虚实线 → 应变红色 ✓（不受第2次影响）
```

### 测试点4：cursorType 不干扰
```
1. 远程光标类型变为 RESIZE (cursorType=4)
2. 如果不在虚实线上 → 应显示黑色 ✓
3. 只有在虚实线上才显示红色 ✓
```

## 调试日志

启用 DEBUG 查看状态变化：
```java
private static final boolean DEBUG = true;
```

关键日志：
- `✓ 找到虚实线 Y=XXX, 距离=Xpx, 自动移动对准...`
- `✗ 未找到虚实线，清除旧状态，恢复黑色`
- `updatePointerDrawable: RED CROSS (在虚实线上，可拖动)`
- `updatePointerDrawable: BLACK CROSS (不在虚实线上)`

## 总结

### 修复前
- ❌ 未找到虚实线时，不清除状态
- ❌ 红色状态会残留到下次检测
- ❌ cursorType == 4 也显示红色，造成混淆
- ❌ 违反"红色=可拖动"原则

### 修复后
- ✅ 未找到虚实线时，明确设置为黑色
- ✅ 每次检测都重新判断，上次不影响下次
- ✅ 只有虚实线显示红色，其它都是黑色
- ✅ 严格遵守"红色=可拖动"原则

### 核心改进
```
红色 = isOnDashedLine == true = 在虚实线上 = 可拖动
黑色 = isOnDashedLine == false = 不在虚实线上 = 不可拖动
```

**简单、明确、一致！**


