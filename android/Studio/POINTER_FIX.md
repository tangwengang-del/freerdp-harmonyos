# 鼠标指针显示修复

## 修复日期
2025-12-18

## 问题描述
在操作远程桌面时，远程桌面的鼠标指针会显示在屏幕上，导致同时出现两个指针：
1. 本地 Android 系统的触摸指针
2. 远程桌面服务器发送的鼠标指针

这会造成混淆，用户希望只显示本地指针，不显示远程指针。

## 问题原因

Android RDP 客户端从服务器接收远程指针图像数据，并在本地绘制。这在某些场景下是有用的，但在移动设备上使用触摸操作时会产生双重指针问题。

### 原有逻辑
1. `OnRemoteCursorUpdate()` 接收服务器发送的光标图像数据
2. `updateRemoteCursor()` 将光标数据保存为 Bitmap
3. `onDraw()` 方法在 Canvas 上绘制远程光标
4. `showDefaultCursor()` 在启动时显示默认箭头光标

## 修复方案

### 完全禁用远程指针显示

#### 修改 1: 忽略远程指针更新
**文件**: `SessionActivity.java`
**方法**: `OnRemoteCursorUpdate()`

```java
@Override public void OnRemoteCursorUpdate(byte[] bitmapData, int width, int height, int hotX, int hotY)
{
    // ✅ 完全禁用远程指针显示，只使用本地 Android 触摸指针
    // 不处理远程指针更新，避免在屏幕上显示远程桌面的光标
    Log.d(TAG, "Remote cursor update ignored (using local pointer only)");
    // Note: sessionView.updateRemoteCursor() not called
}
```

**说明**: 直接忽略来自服务器的光标更新，不调用 `updateRemoteCursor()`，避免创建和存储光标位图。

#### 修改 2: 禁用远程指针绘制
**文件**: `SessionView.java`
**方法**: `onDraw()`

```java
// ✅ 完全禁用远程指针绘制
// 不绘制远程桌面的光标，只使用本地 Android 触摸指针/TouchPointerView
// Remote cursor drawing disabled - use local Android pointer only
// if (remoteCursorBitmap != null && 
//     (touchPointerPaddingWidth == 0 && touchPointerPaddingHeight == 0))
// {
//     canvas.drawBitmap(remoteCursorBitmap, 
//                       remoteCursorX - remoteCursorHotX, 
//                       remoteCursorY - remoteCursorHotY, 
//                       null);
// }
```

**说明**: 注释掉 `onDraw()` 中绘制远程光标的代码，确保即使有光标数据也不会显示。

#### 修改 3: 禁用默认光标
**文件**: `SessionView.java`
**方法**: `showDefaultCursor()`

```java
public void showDefaultCursor()
{
    // ✅ Disabled: Do not show any remote cursor, use local Android pointer only
    Log.d(TAG, "Default cursor not shown (remote cursor display disabled)");
}
```

**说明**: 启动时不再显示默认箭头光标，避免在远程光标到达前显示占位符。

## 修复效果

### 修复前
- ❌ 显示远程桌面的鼠标指针
- ❌ 同时显示本地触摸指针和远程指针
- ❌ 两个指针会造成混淆

### 修复后
- ✅ 只显示本地 Android 触摸指针
- ✅ 使用 TouchPointerView（浮动指针）时只显示 TouchPointerView
- ✅ 清晰的单一指针显示
- ✅ 节省内存（不创建和存储远程光标位图）

## 使用场景

### 普通触摸操作
- 用户触摸屏幕时，只看到 Android 系统的触摸反馈
- 无远程指针干扰

### TouchPointerView 模式
- 启用浮动指针时，只显示 TouchPointerView
- 远程指针完全不可见

### 外接鼠标
- 连接外接鼠标时，只显示 Android 系统的鼠标指针
- 无远程指针重叠

## 性能优化

修复带来的额外好处：
1. **减少内存占用**: 不创建和存储远程光标 Bitmap
2. **减少 CPU 开销**: 不处理光标更新事件
3. **减少绘制开销**: `onDraw()` 中不绘制光标
4. **简化逻辑**: 移除复杂的光标同步和绘制逻辑

## 回滚方案

如果需要恢复显示远程指针，取消以下代码的注释：

### SessionActivity.java
```java
@Override public void OnRemoteCursorUpdate(byte[] bitmapData, int width, int height, int hotX, int hotY)
{
    if (sessionView != null)
    {
        sessionView.updateRemoteCursor(bitmapData, width, height, hotX, hotY);
    }
}
```

### SessionView.java - onDraw()
```java
if (remoteCursorBitmap != null && 
    (touchPointerPaddingWidth == 0 && touchPointerPaddingHeight == 0))
{
    canvas.drawBitmap(remoteCursorBitmap, 
                      remoteCursorX - remoteCursorHotX, 
                      remoteCursorY - remoteCursorHotY, 
                      null);
}
```

### SessionView.java - showDefaultCursor()
恢复原有的默认光标创建逻辑。

## 相关文件

### 修改的文件
1. `freeRDPCore/src/main/java/com/freerdp/freerdpcore/presentation/SessionActivity.java`
2. `freeRDPCore/src/main/java/com/freerdp/freerdpcore/presentation/SessionView.java`

### 相关接口
- `OnRemoteCursorUpdate()` - 光标更新回调接口
- `OnCursorTypeChanged()` - 光标类型改变回调（仍保留，用于 TouchPointerView）

## 测试建议

1. **触摸操作测试**
   - 触摸屏幕任意位置
   - 验证只显示触摸点反馈，无额外光标

2. **TouchPointerView 测试**
   - 启用浮动指针
   - 移动浮动指针
   - 验证只显示浮动指针，无远程光标

3. **外接鼠标测试**
   - 连接蓝牙或 USB 鼠标
   - 移动鼠标
   - 验证只显示系统鼠标指针，无远程光标

4. **锁屏恢复测试**
   - 锁屏后解锁
   - 验证恢复后无光标残留

5. **长时间使用测试**
   - 连接远程桌面并使用1小时
   - 验证无内存泄漏，性能稳定

## 注意事项

1. **TouchPointerView 不受影响**: `OnCursorTypeChanged()` 仍然工作，TouchPointerView 可以根据远程光标类型改变样式

2. **不影响鼠标事件**: 鼠标点击、移动、滚轮等事件仍正常发送到服务器

3. **服务器端无变化**: 这是纯客户端的显示优化，不影响服务器端的光标处理

---

**修复完成**: 远程指针现已完全禁用，所有操作只显示本地指针。


