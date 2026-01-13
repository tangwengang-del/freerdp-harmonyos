# 交叉线检测增强 - 2026-01-04

## 📋 问题描述

### ❌ 原始问题

**场景：**
```
[红5px] [背景5px] [白色10px] [背景8px] [绿6px]
```

**错误行为（修改前）：**
```
segments:
- 线段5px（红）       ✓
- 断开5px（背景）     ✓
- 断开10px（白色）    ✗ 错误！白色应该被跳过
- 断开8px（背景）     ✓
→ 合并成23px断开（5+10+8）！
```

**问题原因：**
- 白色像素不是目标色（红/绿），被当作"断开"统计
- 导致多个断开被合并成一个长断开
- 虚实线模式被误判

---

## ✅ 修复方案

### **核心思路：**
> "断开处有其它颜色，要把它当作交叉线处理即可"

**逻辑：**
- 不论是虚实线的线段还是断开，都属于虚实线的部分
- 只要虚实线有交叉线都要跳过
- 断开处如果有非背景色且非目标色的像素 → 交叉线 → 跳过

---

## 🔧 具体修改

### **修改位置：** `TouchPointerView.java` 行902-929

### **修改内容：**

在**阶段1预扫描**中，增加对中心行交叉线的检测：

```java
// ============ 阶段1：预扫描 - 标记竖线区域（改进4+5） ============
boolean[] excludeX = new boolean[width];
int centerRowIndex = 4;

// 获取中心行的像素数据（用于检测水平交叉线）
if (multiRowPixels[centerRowIndex] == null) return false;
int[] centerPixels = multiRowPixels[centerRowIndex];

for (int x = 0; x < width; x++)
{
    // ====== 新增：检测中心行的交叉线（水平交叉） ======
    // 如果该像素既不是目标色（红/绿），也不是背景色 → 交叉线（如白色线条）
    if (x < centerPixels.length)
    {
        int centerPixel = centerPixels[x];
        if (!isTargetColorPixel(centerPixel) && !isBackgroundColor(centerPixel))
        {
            // 标记为排除区域，既不算线段也不算断开
            excludeX[x] = true;
            continue;  // 跳过后续的垂直交叉线检测
        }
    }
    // ================================================
    
    // ... 继续垂直交叉线检测 ...
}
```

---

## 📊 修改效果

### **测试场景：**
```
[红5px] [背景5px] [白色10px] [背景8px] [绿6px]
```

### **处理流程（修改后）：**

1. **红5px** 
   - `isTargetColorPixel(红) = true` 
   - ✅ 不排除，正常统计

2. **背景5px** 
   - `isBackgroundColor(背景) = true` 
   - ✅ 不排除，正常统计

3. **白色10px** 
   - `isTargetColorPixel(白) = false` 
   - `isBackgroundColor(白) = false` 
   - ✅ **标记为 excludeX[x] = true** → 跳过！

4. **背景8px** 
   - `isBackgroundColor(背景) = true` 
   - ✅ 不排除，正常统计

5. **绿6px** 
   - `isTargetColorPixel(绿) = true` 
   - ✅ 不排除，正常统计

### **最终结果：**
```
segments:
- 线段5px（红）
- 断开5px（背景）
- [跳过10px白色]  ← 交叉线，不统计
- 断开8px（背景）
- 线段6px（绿）
→ 断开只有5px和8px，分别统计 ✓
```

---

## 🎯 优势

### ✅ **统一的交叉线处理逻辑**
- 垂直交叉线：上下都有颜色 → 排除
- 水平交叉线：非目标色且非背景色 → 排除

### ✅ **精准的断开统计**
- 只统计真正的背景色断开
- 白色/其他颜色的线条不会干扰

### ✅ **更准确的虚实线检测**
- 避免断开被错误合并
- 提高模式匹配准确度

---

## 📝 判断条件总结

| 像素类型 | isTargetColorPixel | isBackgroundColor | 处理方式 |
|---------|-------------------|------------------|---------|
| 红色/绿色 | ✓ true | ✗ false | 统计为线段 |
| 背景色（灰色）| ✗ false | ✓ true | 统计为断开 |
| 白色/其他色 | ✗ false | ✗ false | **跳过（交叉线）** |

---

## 🔍 相关函数

### **`isTargetColorPixel()`**
检测是否是目标色（红/绿）：
```java
boolean isLime = (g > 180) && (r < 120) && (b < 120);  // 绿色
boolean isRed = (r > 180) && (g < 120) && (b < 120);   // 红色
return isLime || isRed;
```

### **`isBackgroundColor()`**
检测是否是背景色（灰色系）：
```java
// DarkGray (169, 169, 169)
// Gray (128, 128, 128)
// SlateGray (112, 128, 144)
// 允许容差±25
```

### **交叉线判断（新增）：**
```java
if (!isTargetColorPixel(pixel) && !isBackgroundColor(pixel))
{
    // 既不是目标色，也不是背景色 → 交叉线
    excludeX[x] = true;
}
```

---

## ✅ 测试建议

建议测试以下场景：

1. **白色交叉线场景**
   - `[红5px] [背景5px] [白色10px] [背景8px] [绿6px]`
   - 预期：白色被跳过，断开只有5px和8px

2. **多色交叉线场景**
   - `[红5px] [背景5px] [蓝色8px] [背景5px] [绿6px]`
   - 预期：蓝色被跳过，断开只有两个5px

3. **纯虚实线场景（无交叉）**
   - `[红5px] [背景5px] [绿10px] [背景8px] [红6px]`
   - 预期：正常检测，不受影响

4. **垂直交叉线场景**
   - 虚实线穿过垂直线条
   - 预期：垂直线被跳过（原有逻辑）

---

## 📅 更新日期
2026-01-04

## 👤 修改原因
用户反馈：断开处有其它颜色（如白色），应当作交叉线处理，不应统计为断开。
