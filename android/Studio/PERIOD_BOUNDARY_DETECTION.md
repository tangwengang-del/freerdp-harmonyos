# 周期边界检测 - 300px竖线方案

## 更新日期
2025-12-27

## 改进内容

### 问题
之前使用固定范围（X±100px）检测虚实线，可能跨越周期边界，误检测到相邻周期的虚实线。

### 解决方案
检测300px高的竖线作为周期边界，在周期内检测虚实线。

## 核心逻辑

### 1. 检测竖线（周期边界）

```java
private boolean isVerticalBoundary(Bitmap bitmap, int x, int centerY)
{
    int minHeight = 300;  // 竖线最小高度
    int sampleInterval = 10;  // 每10px采样一次
    
    // 向上采样
    int upSamples = 0;
    for (int y = centerY; y >= 0; y -= sampleInterval)
    {
        if (isDarkPixel(bitmap.getPixel(x, y)))
            upSamples++;
        else
            break;  // 竖线中断
    }
    
    // 向下采样
    int downSamples = 0;
    for (int y = centerY + sampleInterval; y < bitmap.getHeight(); y += sampleInterval)
    {
        if (isDarkPixel(bitmap.getPixel(x, y)))
            downSamples++;
        else
            break;
    }
    
    // 估算高度
    int estimatedHeight = (upSamples + downSamples) * sampleInterval;
    
    return estimatedHeight >= minHeight;
}
```

**优化点：**
- 采样检测（每10px一次）而非逐像素，提高性能
- 向上向下双向扫描，确保完整高度
- 遇到非深色像素立即停止，避免误判

### 2. 查找周期边界

```java
private int[] getCurrentPeriodBounds(Bitmap bitmap, int centerX, int centerY)
{
    // 向左查找边界
    int leftBound = 0;
    for (int x = centerX - 1; x >= 0; x--)
    {
        if (isVerticalBoundary(bitmap, x, centerY))
        {
            leftBound = x;
            break;
        }
    }
    
    // 向右查找边界
    int rightBound = bitmap.getWidth();
    for (int x = centerX + 1; x < bitmap.getWidth(); x++)
    {
        if (isVerticalBoundary(bitmap, x, centerY))
        {
            rightBound = x;
            break;
        }
    }
    
    return new int[]{leftBound, rightBound};
}
```

### 3. 在周期内检测虚实线

```java
private boolean checkSingleLineAt(Bitmap bitmap, int y, int leftBound, int rightBound)
{
    // 使用周期边界，避免读到竖线本身
    int startX = leftBound + 3;   // +3 避免读到竖线
    int endX = rightBound - 3;    // -3 避免读到竖线
    int width = endX - startX;
    
    if (width <= 0) return false;
    
    int[] pixels = new int[width];
    bitmap.getPixels(pixels, 0, width, startX, y, width, 1);
    
    return hasLongShortPattern(pixels);
}
```

## 完整流程

```
1. 用户松开按钮 → 检测虚实线
   ↓
2. 获取指针位置 (X, Y)
   ↓
3. 查找周期边界：
   - 向左扫描 → 找到300px高竖线 → 左边界
   - 向右扫描 → 找到300px高竖线 → 右边界
   ↓
4. 在 [左边界+3, 右边界-3] 范围内扫描虚实线
   ↓
5. 找到虚实线 → 自动对齐并变红
```

## 参数说明

### 竖线高度阈值
```java
int minHeight = 300;  // 当前标准：300px
```

**调整指南：**
- 如果周期分隔线更高：增加到 350 或 400
- 如果检测不到边界：降低到 250
- 根据实际界面调整

### 采样间隔
```java
int sampleInterval = 10;  // 每10px采样一次
```

**性能影响：**
- `sampleInterval = 5`：更精确，但慢2倍
- `sampleInterval = 20`：快2倍，但可能漏检
- 当前值（10px）是平衡点

### 边界安全距离
```java
int startX = leftBound + 3;  // +3px 安全距离
int endX = rightBound - 3;   // -3px 安全距离
```

**作用：** 避免把竖线本身当作虚实线的一部分

## 对比

| 特性 | 旧方案 | 新方案 |
|------|--------|--------|
| 检测范围 | X ± 100px（固定） | [左边界, 右边界]（动态） |
| 跨周期问题 | ❌ 可能跨越 | ✅ 不会跨越 |
| 误检测 | ⚠️ 可能检测到邻近周期 | ✅ 只在当前周期 |
| 性能 | 快（固定范围） | 稍慢（需先找边界） |
| 准确性 | 一般 | **高** |

## 使用场景

### 场景1：指针在周期中央
```
周期: [100, 400]
指针: X=250
检测范围: [103, 397]
结果: 在当前周期内检测 ✓
```

### 场景2：指针靠近边界
```
周期: [100, 400]
指针: X=380
检测范围: [103, 397]
结果: 不会跨到右边周期 ✓
```

### 场景3：多个虚实线
```
周期1: [0, 100]   → 虚实线A
周期2: [100, 200] → 虚实线B
指针在周期2 (X=150)
结果: 只检测虚实线B，不会检测到虚实线A ✓
```

## 调试日志

启用DEBUG查看边界检测：
```java
private static final boolean DEBUG = true;
```

关键日志：
- `周期边界检测: X=XXX → 左边界=XXX, 右边界=XXX, 宽度=XXX`
- `检测到竖线边界 X=XXX, 估算高度=XXXpx`
- `✓ 虚实线检测成功 Y=XXX (dy=X), 周期=[XXX, XXX]`

## 性能优化

### 1. 采样检测
使用10px间隔采样，而非逐像素扫描：
- 300px高度只需检测 30个点（而非300个）
- 性能提升 10倍

### 2. 提前终止
遇到非深色像素立即停止扫描：
- 如果竖线在50px处就中断，只检测50个点
- 避免不必要的全高度扫描

### 3. 边界缓存（可选）
如果界面不变，可以缓存边界位置：
```java
private int[] cachedBounds = null;
private int cachedCenterX = -1;

// 如果指针在同一周期内，复用缓存
if (cachedBounds != null && 
    centerX >= cachedBounds[0] && 
    centerX <= cachedBounds[1])
{
    return cachedBounds;
}
```

## 测试建议

### 1. 边界检测测试
- 移动到周期边界附近
- 观察日志确认正确识别左/右边界
- 确认边界宽度合理

### 2. 虚实线检测测试
- 在周期内移动到虚实线附近
- 松开按钮
- 确认只检测当前周期的虚实线

### 3. 跨周期测试
- 在两个周期的边界附近松开按钮
- 确认不会误检测到相邻周期的虚实线

## 故障排除

### 问题1：检测不到边界
**症状：** leftBound=0, rightBound=width

**可能原因：**
- 竖线高度 < 300px
- 竖线颜色太浅（不是深色）

**解决：**
- 降低 `minHeight` 到 250
- 调整 `isDarkPixel()` 的亮度阈值

### 问题2：误判边界
**症状：** 在周期中间检测到"边界"

**可能原因：**
- 周期内有其它高竖线元素
- 采样间隔太大

**解决：**
- 增加 `minHeight` 到 350
- 降低 `sampleInterval` 到 5

### 问题3：检测范围太窄
**症状：** width < 20，检测失败

**可能原因：**
- 周期太窄
- 边界检测过于严格

**解决：**
- 检查实际周期宽度是否合理
- 调整边界查找逻辑

## 总结

### 核心改进
```
旧：固定范围 X ± 100px
新：动态范围 [左边界, 右边界]（周期内）
```

### 优势
1. ✅ 不跨周期检测
2. ✅ 准确识别当前周期
3. ✅ 避免相邻周期干扰
4. ✅ 采样优化，性能良好

### 标准
```
周期边界 = 竖线高度 ≥ 300px
检测范围 = [左边界+3, 右边界-3]
```

**简单、准确、高效！**


