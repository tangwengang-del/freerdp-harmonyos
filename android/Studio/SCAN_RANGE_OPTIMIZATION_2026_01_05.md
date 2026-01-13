# 虚实线检测扫描范围优化 - 2026-01-05

## 📋 修改内容

### ✅ 缩小垂直扫描范围：从 Y±4像素（9行） → Y±2像素（5行）

**优化目标：**
- 提高检测精度
- 减少误判
- 提升性能（减少扫描像素数）
- 更精准的竖线检测

---

## 🔧 具体修改

### **1. 代码修改（TouchPointerView.java）**

#### 修改1：主检测循环（行693-694）
**修改前：**
```java
// 扫描垂直范围：Y-4 到 Y+4（9行）
for (int dy = -4; dy <= 4; dy++)
```

**修改后：**
```java
// 扫描垂直范围：Y-2 到 Y+2（5行）
for (int dy = -2; dy <= 2; dy++)
```

---

#### 修改2：多行像素数组（行782-803）
**修改前：**
```java
// 改进(4)(5): 读取多行数据用于竖线检测
int[][] multiRowPixels = new int[9][];
int validRows = 0;

for (int dy = -4; dy <= 4; dy++)
{
    int rowY = y + dy;
    if (rowY < 0 || rowY >= bitmap.getHeight()) continue;
    
    int rowIndex = dy + 4;  // 索引0-8
    multiRowPixels[rowIndex] = new int[width];
    // ...
}
```

**修改后：**
```java
// 改进(4)(5): 读取多行数据用于竖线检测
int[][] multiRowPixels = new int[5][];
int validRows = 0;

for (int dy = -2; dy <= 2; dy++)
{
    int rowY = y + dy;
    if (rowY < 0 || rowY >= bitmap.getHeight()) continue;
    
    int rowIndex = dy + 2;  // 索引0-4
    multiRowPixels[rowIndex] = new int[width];
    // ...
}
```

---

#### 修改3：中心行索引（行902-906）
**修改前：**
```java
// 中心行索引是4（对应Y坐标）
int centerRowIndex = 4;
```

**修改后：**
```java
// 中心行索引是2（对应Y坐标）
int centerRowIndex = 2;
```

---

#### 修改4：竖线检测逻辑（行928-959）
**修改前：**
```java
// 扫描上方4行（Y-4到Y-1，对应索引0-3）
for (int rowIndex = 0; rowIndex < centerRowIndex; rowIndex++)
{
    // ... 统计coloredRowCountAbove
}

// 扫描下方4行（Y+1到Y+4，对应索引5-8）
for (int rowIndex = centerRowIndex + 1; rowIndex < 9; rowIndex++)
{
    // ... 统计coloredRowCountBelow
}

// 上方至少2行 且 下方至少2行 → 判定为竖线
if (coloredRowCountAbove >= 2 && coloredRowCountBelow >= 2)
{
    excludeX[x] = true;  // 排除
}
```

**修改后：**
```java
// 扫描上方2行（Y-2到Y-1，对应索引0-1）
for (int rowIndex = 0; rowIndex < centerRowIndex; rowIndex++)
{
    // ... 统计coloredRowCountAbove
}

// 扫描下方2行（Y+1到Y+2，对应索引3-4）
for (int rowIndex = centerRowIndex + 1; rowIndex < 5; rowIndex++)
{
    // ... 统计coloredRowCountBelow
}

// 上方至少1行 且 下方至少1行 → 判定为竖线
if (coloredRowCountAbove >= 1 && coloredRowCountBelow >= 1)
{
    excludeX[x] = true;  // 排除
}
```

---

### **2. 文档更新**

已更新以下文档中所有相关内容：

✅ `DASHED_LINE_DETECTION_FINAL.md` - 最终版本说明
✅ `DASHED_LINE_COLOR_FILTER.md` - 颜色过滤说明
✅ `DETECTION_LOGIC_FIX_2026.md` - 逻辑修复说明
✅ `DETECTION_RANGE_OPTIMIZATION.md` - 范围优化说明
✅ `BACKGROUND_COLOR_QUICK_REF.md` - 快速参考
✅ `IMPLEMENTATION_COMPLETE_REPORT.md` - 完整报告
✅ `DASHED_LINE_DETECTION_IMPROVEMENTS_2026.md` - 改进说明
✅ `DASHED_LINE_DETECTION_ON_RELEASE.md` - 发布版本说明
✅ `DASHED_LINE_FIXES.md` - 修复说明
✅ `DASHED_LINE_SNAP_FEATURE.md` - 对齐功能说明
✅ `test_dashed_line.html` - 测试页面

---

## 📊 优化效果对比

| 项目 | 修改前 | 修改后 | 改善 |
|------|--------|--------|------|
| **垂直扫描范围** | Y±4像素 | Y±2像素 | 减少50% |
| **扫描行数** | 9行 | 5行 | 减少44% |
| **扫描总像素** | 200×9=1800px | 200×5=1000px | 减少44% |
| **内存占用** | 9行×200px×4字节≈7.2KB | 5行×200px×4字节≈4KB | 减少44% |
| **中心行索引** | 4 | 2 | - |
| **竖线判定阈值** | 上下各≥2行 | 上下各≥1行 | 更精准 |

---

## 🎯 优化原理

### **为什么缩小到±2像素？**

1. **精度提升**：
   - 虚实线通常是1-3像素高
   - ±4像素范围（9行）过大，容易包含相邻的其他线条
   - ±2像素范围（5行）更聚焦于目标线条

2. **性能提升**：
   - 扫描像素减少44%（1800→1000）
   - 内存占用减少44%（7.2KB→4KB）
   - 处理速度提升约1.8倍

3. **误判减少**：
   - 范围越大，相邻元素干扰越多
   - 缩小范围，减少误检测相邻线条
   - 提高检测准确率

4. **竖线检测优化**：
   - 从检测9行降低到5行
   - 判定阈值从上下各2行降低到上下各1行
   - 在5行范围内，上下各1行的阈值更合理
   - 避免误排除真实的虚实线

---

## 🎯 关键逻辑说明

### **竖线检测规则调整**

**旧规则（9行）：**
- 扫描中心行上方4行（索引0-3）
- 扫描中心行下方4行（索引5-8）
- 如果上方≥2行 且 下方≥2行有颜色 → 判定为竖线

**新规则（5行）：**
- 扫描中心行上方2行（索引0-1）
- 扫描中心行下方2行（索引3-4）
- 如果上方≥1行 且 下方≥1行有颜色 → 判定为竖线

**为什么调整阈值？**
- 9行范围内要求上下各2行（占比22%）
- 5行范围内要求上下各1行（占比20%）
- 保持相似的比例，避免过于宽松或严格

---

## 📐 检测逻辑示意图

```
指针位置 (centerX, centerY)
        ↓
    Y-2 ─────  索引0  ─────  上方第2行
    Y-1 ─────  索引1  ─────  上方第1行
→ → Y   ─────  索引2  ─────  中心行（虚实线）
    Y+1 ─────  索引3  ─────  下方第1行
    Y+2 ─────  索引4  ─────  下方第2行

竖线检测：
- 如果 X 位置在索引0-1中至少1行有颜色（上方）
  且在索引3-4中至少1行有颜色（下方）
  → 判定为竖线，排除该 X 位置

在这5行×200px范围内：
- 标记交叉线/竖线区域（excludeX）
- 统计线段（3-15px，跳过excludeX）
- 统计断开（3-9px，跳过excludeX）
```

---

## 🧪 测试场景

### **应该正常检测的情况：**
✅ 虚实线在指针上方1-2像素
✅ 虚实线在指针下方1-2像素
✅ 虚实线正好在指针位置
✅ 虚实线中间有竖线（排除区域）

### **应该忽略的情况：**
❌ 虚实线在指针上方3像素（超出范围）
❌ 虚实线在指针下方3像素（超出范围）
✅ 竖线（上下都有颜色）

---

## ✅ 编译状态

- ✅ 编译通过
- ✅ 无linter错误
- ✅ 逻辑完整
- ✅ 所有文档已更新

---

## 📝 修改位置总结

**代码文件：**
- `freeRDPCore/src/main/java/com/freerdp/freerdpcore/presentation/TouchPointerView.java`
  - 行693-694：主检测循环
  - 行782-803：多行像素数组
  - 行902-906：中心行索引
  - 行928-963：竖线检测逻辑

**文档文件：**
- 共更新11个Markdown文档
- 更新1个HTML测试文件

---

## 🎯 预期效果

1. **性能提升**：
   - 扫描像素减少44%
   - 内存占用减少44%
   - 处理速度提升约1.8倍

2. **精度提升**：
   - 减少相邻线条干扰
   - 更精准的目标定位
   - 更合理的竖线判定

3. **用户体验**：
   - 更快的响应速度
   - 更准确的检测结果
   - 更少的误判

---

**修改日期：** 2026-01-05  
**修改原因：** 优化虚实线检测扫描范围，提高精度和性能  
**预期效果：** 检测更精准，响应更快速，误判更少
