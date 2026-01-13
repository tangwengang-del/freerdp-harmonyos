# 透明系统栏实施记录

## 📅 实施日期
2025-12-19

## 🎯 实施目标
将Android系统的状态栏（顶部信息栏）和导航栏（底部3个按钮）设置为**透明显示**，而不是隐藏。

---

## 🔄 修改对比

### 修改前
- **状态栏**: 根据设置完全隐藏（FLAG_FULLSCREEN）
- **导航栏**: 完全隐藏（SYSTEM_UI_FLAG_HIDE_NAVIGATION）
- **用户体验**: 系统栏不可见，但可能影响操作便利性

### 修改后
- **状态栏**: 透明显示，可见但不遮挡内容
- **导航栏**: 透明显示，可见但不遮挡内容
- **用户体验**: 系统栏始终可见，透明背景，不影响操作

---

## 🔧 修改的文件

### SessionActivity.java
**路径**: `freeRDPCore/src/main/java/com/freerdp/freerdpcore/presentation/SessionActivity.java`

#### 修改1: onCreate() 中的状态栏设置（约第293-298行）

**修改前**:
```java
// show status bar or make fullscreen?
if (ApplicationSettingsActivity.getHideStatusBar(this))
{
    getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                         WindowManager.LayoutParams.FLAG_FULLSCREEN);
}
```

**修改后**:
```java
// 设置透明状态栏和导航栏（不隐藏，仅透明显示）
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
{
    // Android 5.0+ 使用现代API设置透明系统栏
    Window window = getWindow();
    window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
    window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
    window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
}
else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
{
    // Android 4.4 使用半透明标志
    Window window = getWindow();
    window.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
                   WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
    window.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
                   WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
}
```

#### 修改2: UI可见性标志（3处相同修改）

**位置**:
- onCreate() 方法（约第402-404行）
- 某个键盘方法（约第1152-1153行）
- 某个重置方法（约第1341-1342行）

**修改前**:
```java
mDecor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                             View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
```

**修改后**:
```java
// 设置UI标志：让内容延伸到系统栏下方，但保持系统栏可见（透明显示）
int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
              | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
              | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
mDecor.setSystemUiVisibility(uiOptions);
```

#### 修改3: 添加import语句

```java
import android.view.Window;
```

---

## 🎨 技术实现说明

### Android版本兼容性

#### Android 5.0+ (API 21+) - 推荐方案
使用现代API实现完全透明：
```java
window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
window.setStatusBarColor(Color.TRANSPARENT);
window.setNavigationBarColor(Color.TRANSPARENT);
```

**特点**:
- ✅ 完全透明（alpha=0）
- ✅ 精确控制颜色
- ✅ 性能最优

#### Android 4.4 (API 19-20) - 兼容方案
使用半透明标志：
```java
window.setFlags(FLAG_TRANSLUCENT_STATUS, FLAG_TRANSLUCENT_STATUS);
window.setFlags(FLAG_TRANSLUCENT_NAVIGATION, FLAG_TRANSLUCENT_NAVIGATION);
```

**特点**:
- ⚠️ 半透明效果（略带黑色遮罩）
- ✅ 向下兼容

### UI布局标志说明

```java
View.SYSTEM_UI_FLAG_LAYOUT_STABLE           // 保持布局稳定
View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN       // 内容延伸到状态栏下方
View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION  // 内容延伸到导航栏下方
```

**效果**:
- ✅ 内容区域延伸到屏幕边缘
- ✅ 系统栏透明覆盖在内容上方
- ✅ 系统栏始终可见（不隐藏）

---

## 📊 视觉效果对比

### 修改前（隐藏系统栏）
```
┌────────────────────────┐
│                        │  ← 状态栏完全隐藏
│   RDP内容区域（全屏）  │
│                        │
│                        │
└────────────────────────┘  ← 导航栏完全隐藏
```

### 修改后（透明系统栏）
```
┌────────────────────────┐
│ [透明状态栏] 12:30 📶  │  ← 状态栏透明可见
│                        │
│   RDP内容区域          │
│   (延伸到屏幕边缘)     │
│                        │
└─ ◁  ○  ▷ [透明导航栏]─┘  ← 导航栏透明可见
```

---

## ✅ 优势

### 1. 用户体验改善
- ✅ **时间可见**: 始终能看到当前时间
- ✅ **电量可见**: 可以查看电池状态
- ✅ **信号可见**: 查看网络信号强度
- ✅ **操作便利**: 导航按钮随时可用

### 2. 视觉效果
- ✅ **现代美观**: 透明系统栏是Android现代设计语言
- ✅ **沉浸感**: 内容延伸到边缘，增强沉浸体验
- ✅ **信息完整**: 不牺牲系统信息显示

### 3. 功能保留
- ✅ **快速返回**: 导航栏的返回键随时可用
- ✅ **任务切换**: 最近任务按钮始终可访问
- ✅ **Home键**: 快速返回桌面

---

## 🧪 测试验证

### 测试环境
- Android 5.0+ (API 21+): 完全透明效果
- Android 4.4 (API 19-20): 半透明效果

### 测试项目

| 测试项 | 验证方法 | 预期结果 |
|--------|---------|---------|
| **状态栏显示** | 查看顶部 | 透明显示，可见时间、电量、信号 |
| **导航栏显示** | 查看底部 | 透明显示，3个按钮可见 |
| **内容延伸** | 观察RDP画面 | 内容延伸到屏幕边缘 |
| **按钮功能** | 点击导航按钮 | 返回、Home、任务切换正常 |
| **状态信息** | 下拉状态栏 | 通知面板正常显示 |

### 测试步骤

1. **编译安装**
```bash
cd /d c:\freerdp318\client\Android\Studio
gradlew clean assembleDebug
adb install -r freeRDPCore/build/outputs/apk/debug/freeRDPCore-debug.apk
```

2. **连接RDP并观察**
   - 启动应用并连接服务器
   - 观察状态栏（顶部）是否透明显示
   - 观察导航栏（底部）是否透明显示

3. **功能验证**
   - 点击返回键 → 应弹出退出确认
   - 点击Home键 → 应返回桌面
   - 下拉状态栏 → 应显示通知

---

## ⚠️ 注意事项

### 1. 布局适配
由于内容延伸到系统栏下方，某些UI元素可能需要调整padding以避免被系统栏遮挡。

**解决方案**（如需要）:
```xml
<View
    android:fitsSystemWindows="true"
    ... />
```

### 2. 透明度问题
在某些Android定制版本（如MIUI、EMUI）上，透明效果可能有细微差异。

### 3. 深色模式
Android 10+ 支持深色模式，可能需要根据主题调整系统栏图标颜色：
```java
// 浅色图标（深色背景）
View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
```

---

## 📝 相关标志说明

### Window Flags
| 标志 | 作用 | API等级 |
|------|------|---------|
| `FLAG_FULLSCREEN` | 完全隐藏状态栏 | 1+ |
| `FLAG_TRANSLUCENT_STATUS` | 半透明状态栏 | 19+ |
| `FLAG_TRANSLUCENT_NAVIGATION` | 半透明导航栏 | 19+ |
| `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS` | 允许绘制系统栏背景 | 21+ |

### System UI Flags
| 标志 | 作用 | API等级 |
|------|------|---------|
| `SYSTEM_UI_FLAG_HIDE_NAVIGATION` | 隐藏导航栏 | 14+ |
| `SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN` | 内容延伸到状态栏下 | 16+ |
| `SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION` | 内容延伸到导航栏下 | 16+ |
| `SYSTEM_UI_FLAG_LAYOUT_STABLE` | 保持布局稳定 | 16+ |
| `SYSTEM_UI_FLAG_IMMERSIVE_STICKY` | 沉浸模式（滑动显示） | 19+ |

---

## 🔗 相关文档

- Android官方文档: [System UI Visibility](https://developer.android.com/training/system-ui/immersive)
- Material Design: [Status Bar](https://material.io/design/platform-guidance/android-bars.html)

---

## ✅ 实施状态

- [x] 修改onCreate()中的Window设置
- [x] 修改所有setSystemUiVisibility()调用
- [x] 添加必要的import语句
- [x] 代码语法检查通过
- [ ] 编译测试
- [ ] 功能测试
- [ ] 多版本Android兼容性测试

---

**实施人员**: AI Assistant  
**修改文件**: SessionActivity.java  
**代码行数**: 约30行修改  
**向下兼容**: Android 4.4+



