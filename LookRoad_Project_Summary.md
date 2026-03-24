# 看路 (LookRoad) — 项目结构与开发总结

> 一款基于 Android 系统悬浮窗的户外安全工具，让用户在使用手机时透过摄像头画面感知周围环境。

---

## 1. 项目概述

| 项目 | 内容 |
|---|---|
| 应用名称 | 看路 / LookRoad |
| 包名 | `com.example.kanlu_lookroad` |
| 开发语言 | Kotlin |
| 最低 SDK | API 30（Android 11） |
| 编译 SDK | API 35（Android 15） |
| 开发环境 | Android Studio 2024.2.1 Ladybug |
| 开源协议 | GPL v3.0（建议） |

---

## 2. 核心设计理念

- **触摸穿透**：摄像头图层覆盖全屏，但不拦截任何触摸事件，用户可正常操作底层应用
- **极简交互**：所有控制集中在一个可拖拽的悬浮面板，不干扰正常使用
- **低侵入性**：以前台服务运行，Activity 启动后立即关闭，仅留悬浮窗
- **资源友好**：相机被占用时主动释放并监听系统通知，避免轮询

---

## 3. 技术栈

| 模块 | 技术 |
|---|---|
| 相机预览 | CameraX 1.5.3（camera-camera2 / camera-lifecycle / camera-view） |
| 悬浮窗管理 | `WindowManager` + `TYPE_APPLICATION_OVERLAY` |
| 后台保活 | 前台服务（`ForegroundService`，类型：camera） |
| 生命周期 | `LifecycleRegistry`（Service 手动实现 `LifecycleOwner`） |
| 亮度控制 | `Settings.System.SCREEN_BRIGHTNESS` + `WRITE_SETTINGS` 权限 |
| 屏幕旋转 | `DisplayManager.DisplayListener` |
| 相机状态 | `CameraState` + `CameraManager.AvailabilityCallback` |

---

## 4. 权限清单

| 权限 | 用途 | 申请方式 |
|---|---|---|
| `CAMERA` | 摄像头预览 | 运行时弹窗 |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗显示 | 跳转系统设置手动开启 |
| `FOREGROUND_SERVICE` | 前台服务 | 静态声明，自动获得 |
| `FOREGROUND_SERVICE_CAMERA` | 前台服务使用相机 | 静态声明，自动获得 |
| `WRITE_SETTINGS` | 调节屏幕亮度 | 跳转系统设置手动开启 |

---

## 5. 文件结构

```
app/src/main/
├── java/com/example/kanlu_lookroad/
│   ├── MainActivity.kt         # 权限申请入口，三关顺序检查链
│   └── LookRoadService.kt      # 核心服务，管理所有图层和逻辑
├── res/
│   └── layout/
│       └── activity_main.xml   # 权限引导页 UI
└── AndroidManifest.xml         # 权限声明 + Service 注册
```

---

## 6. 系统架构：图层结构

```
┌─────────────────────────────────┐  ← 最高层
│   悬浮控制面板（可拖拽）          │  FLAG_NOT_FOCUSABLE（可点击）
│   ┌──────────┐                  │
│   │ 设置面板  │  展开/收起        │
│   │ · 亮度滑条│                  │
│   │ · 透明度 │                  │
│   │ · 退出   │                  │
│   └──────────┘                  │
│   [ ⚙️ 设置 ]  [ 🔦 手电筒 ]    │
├─────────────────────────────────┤
│   黑色暂停遮罩（相机被占用时显示）  │  FLAG_NOT_TOUCHABLE（穿透）
├─────────────────────────────────┤
│   摄像头预览层（PreviewView）     │  FLAG_NOT_TOUCHABLE（穿透）
│   · 全屏，包含状态栏和导航栏      │  alpha 可调（透明度控制）
└─────────────────────────────────┘  ← 底层
              ↓ 触摸穿透
        用户正在使用的其他 App
```

---

## 7. 核心逻辑说明

### 7.1 触摸穿透

```kotlin
FLAG_NOT_TOUCHABLE or FLAG_NOT_FOCUSABLE
```

摄像头层和遮罩层使用此 Flag 组合，所有触摸事件直接穿透至底层 App。

### 7.2 全屏覆盖（含状态栏）

```kotlin
val bounds = windowManager.currentWindowMetrics.bounds
params.width = bounds.width()
params.height = bounds.height()
params.fitInsetsTypes = 0  // 不避让系统栏
```

使用物理屏幕真实尺寸，而非内容区域尺寸，才能覆盖状态栏和导航栏。

### 7.3 透明度控制

```kotlin
// View.alpha 对 WindowManager 顶层窗口无效
// 必须用 LayoutParams.alpha + updateViewLayout
overlayParams?.alpha = progress / 100f
cameraOverlay?.let { windowManager.updateViewLayout(it, overlayParams) }
```

### 7.4 相机冲突处理

```
其他 App 打开相机
    ↓
CameraState = OPENING + ERROR_CAMERA_IN_USE
    ↓
1. 显示黑色遮罩
2. unbindAll() 停止 CameraX 重试
3. 注册 CameraManager.AvailabilityCallback
    ↓
系统通知「相机可用」（一次性触发）
    ↓
注销回调 → bindCamera() → 移除遮罩 → 恢复画面
```

### 7.5 屏幕旋转适配

```kotlin
DisplayManager.DisplayListener.onDisplayChanged()
    → 读取新的 currentWindowMetrics.bounds
    → updateViewLayout() 更新宽高
```

---

## 8. 开发路线图（已完成）

- [x] **Phase 1**：权限申请系统（三关顺序检查链，支持引导跳转）
- [x] **Phase 2**：红色方块验证触摸穿透逻辑
- [x] **Phase 3**：集成 CameraX，替换为实时摄像头画面
- [x] **Phase 4**：手电筒控制、相机冲突处理、屏幕旋转适配
- [x] **Phase 5**：悬浮控制面板（亮度/透明度滑条、退出按钮、拖拽定位）

---

## 9. 已知限制

| 问题 | 原因 | 状态 |
|---|---|---|
| 相机恢复有短暂延迟 | CameraX 内部重建 Surface 需要时间，系统级限制 | 用黑色遮罩缓解视觉影响 |
| 强光/暗光下底层内容难以辨认 | Alpha 混合数学特性，跨窗口无法使用高级混合模式 | 提供透明度滑条让用户手动调整 |
| 并发前后置摄像头 | 取决于手机硬件，多数设备不支持 | 未实现 |

---

## 10. 待开发功能（建议）

- [ ] 图标替换（自定义 launcher icon）
- [ ] 锁屏自动关闭摄像头节省电量
- [ ] 自动亮度模式（根据环境光传感器自动调节）
- [ ] 设置持久化（记住上次的亮度/透明度偏好）
- [ ] 低功耗模式（降低摄像头帧率）
- [ ] 打包发布（Google Play / 国内应用市场）

---

## 11. 发布流程（待完成）

```
1. 替换应用图标
   res/mipmap-*/ic_launcher.png（各密度尺寸）

2. 生成签名
   Build → Generate Signed Bundle/APK
   → 创建 .jks 密钥文件（妥善保存！）

3. 选择发布渠道
   · Google Play：$25 一次性注册费，审核 1-3 天
   · 华为/小米应用市场：免费，审核 3-7 天
   · 直接分发 APK：即时，适合自用或小范围测试

4. 准备材料
   · 隐私政策（说明摄像头数据不上传）
   · 应用截图
   · 应用描述
```

---

*文档生成时间：2026-03-23*
