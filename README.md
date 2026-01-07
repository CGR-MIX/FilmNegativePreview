# FilmNegativePreview 🎞️

**FilmNegativePreview** 是一款专为摄影师打造的高性能、专业级实时底片扫描预览工具。通过深度整合 **OpenCV** 图像引擎与 **CameraX** 相机架构，本应用能将您的手机瞬间转变为一台高清底片扫描仪。

## ✨ 核心功能特性

### 🚀 极致性能
- **60fps 丝滑预览**：通过 Camera2Interop 强制请求高帧率流，提供无延迟的视觉反馈。
- **ROI 智能扫描策略**：每 8 帧进行一次全局 ROI 扫描，成功锁定后进入低耗能跟踪模式，平衡性能与精度。
- **体积极致优化**：通过 ABI Filters 精简原生库，仅保留 `arm64-v8a` 架构，减少约 70% 的包体积。

### 🎨 专业色彩与构图处理
- **自动定位与校正**：基于 OpenCV 的文档级扫描算法，实时识别底片边缘并吸附绿色扫描框。
- **3:2 标准输出**：拍照时自动执行**透视校正（Warp Perspective）**与裁剪，输出标准的 36×24mm 比例高清图像。
- **手动采样校准**：**长按**屏幕底片边缘或齿孔处，即可完成最精准的去色罩校准。
- **三大模式**：COLOR (彩色)、BLACK & WHITE (黑白)、NORMAL VIEW (正常取景)。

### ⚙️ 专业级相机操控 (Camera-UI)
- **物理级绝对对称布局**：
    - **左侧双轨**：上半部 EV (±3.0) 补偿，下半部 FOCUS (手动对焦) 滑块。
    - **右侧单轨**：Kelvin (2000K-10000K) 色温控制，与左侧双轨完美对齐。
- **快捷倍率切换**：底部提供 0.6x、1x、3x 快捷变焦按钮，支持自动切换物理广角/长焦镜头。
- **自动化锁定按钮 (顶部)**：
    - **AF**：自动/手动对焦切换。
    - **AWB**：自动/手动白平衡切换。
    - **AE**：一键锁定当前曝光值。

### 📱 现代化交互
- **相机快门风格**：底部中央白色圆环快门，带来极佳的拍摄手感。
- **即时预览相册**：快门左侧显示上一张拍摄照片的**圆形缩略图**，点击直接调用系统查看器全屏查看大图。
- **多机型兼容**：针对小米 13、三星 S23 Ultra、OPPO 等机型进行了管线加固，解决黑屏与转圈问题。

## 🛠️ 技术栈
- **UI**: Jetpack Compose (Declarative UI)
- **Engine**: OpenCV for Android (C++ Optimized)
- **Camera**: CameraX + Camera2 Interop (CaptureRequest Level Control)
- **Storage**: MediaStore API (Scoped Storage Compatible)

## 🚀 快速开始

### 构建与运行
1. `git clone https://github.com/YourUsername/FilmNegativePreview.git`
2. 点击 **Sync Project with Gradle Files**（会自动配置 ndk 过滤与依赖）。
3. 连接手机并点击 **Run**。生成的 APK 已针对 64 位系统进行瘦身。

## 📖 进阶使用技巧
1. **精准去色罩**：长按底片边缘橙色片基，画面色彩会瞬间恢复通透。
2. **锁定焦点**：开启 AF 找准后点击顶部 AF 按钮切换为 MF 锁定焦点，防止呼吸效应。
3. **查看结果**：点击快门左侧圆形预览图，直接在系统大图中确认扫描细节。

## 📄 开源协议
本项目采用 [MIT License](LICENSE) 协议。
