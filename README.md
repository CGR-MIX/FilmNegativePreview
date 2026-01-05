# FilmNegativePreview 🎞️

**FilmNegativePreview** 是一款基于 Android 的实时底片扫描预览工具。利用 OpenCV 进行高性能图像处理，结合 CameraX 灵活的相机控制，帮助摄影师即时预览负片的正片效果。

## ✨ 功能特性

- **实时图像反转**：毫秒级实时反转底片色彩。
- **色罩手动采样 (Manual Mask Sampling)**：长按屏幕任意位置，精准提取底片片基颜色，消除色偏。
- **胶片风格模拟 (Film Stocks)**：内置多种经典胶片预设（如 Kodak Portra, Fuji Pro 400H 等）。
- **专业参数调节**：
  - **EV (曝光补偿)**：滑动调节画面亮度 (-3.0 至 +3.0)。
  - **TEMP (色温控制)**：支持手动调节 (3000K-11000K) 或 开启 **AUTO** 自动估算。
- **灵活的分辨率**：支持从 SD 到 4K (UHD) 以及设备原生最大分辨率切换。
- **多摄像头支持**：一键切换背部不同的摄像头（广角/主摄）。
- **现代 UI 设计**：完全基于 Jetpack Compose 构建，交互流畅。

## 🛠️ 技术实现

- **核心框架**: Jetpack Compose (UI), CameraX (Camera Interface)
- **图像处理**: OpenCV (用于色彩矩阵运算与色罩消除)
- **语言**: Kotlin + Coroutines
- **编译器配置**: Java 11 (VERSION_11), Compose BOM 2024.09.00

## 🚀 快速构建

### 环境要求

- Android Studio Giraffe (2022.3.1) 或更高
- JDK 11 (项目已配置 `sourceCompatibility = VERSION_11`)
- Android 设备 (API 26+)

### 运行

1. 克隆项目：
   bash git clone https://github.com/你的用户名/FilmNegativePreview.git 2. 在 Android Studio 中打开项目。
2. 等待 Gradle 同步完成（会自动下载 OpenCV 依赖）。
3. 连接设备并点击 `Run`。

## 📖 使用技巧

1. **采样色罩**：将底片空白处（片基）对准相机，**长按**该区域，App 会记住此颜色并作为补偿基准。
2. **重置色罩**：点击顶部出现的红色刷新图标即可重置采样。
3. **锁定自动色温**：点击 TEMP 上方的圆形按钮可开启自动色温估算，再次点击切换回手动控制。

## 📄 开源协议

本项目采用 [MIT License](LICENSE) 协议。
