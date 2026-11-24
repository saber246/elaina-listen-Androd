# 开发环境

| 项目 | 首轮检查结果 |
| --- | --- |
| 系统 | Windows 11 家庭中文版，64 位，Build 26300 |
| Android Studio | 已安装 2026.2.2，含 Kotlin 和 Compose IDE 插件 |
| JDK | Studio 自带 JetBrains Runtime 25.0.4，`java` 和 `javac` 均可调用。系统 `JAVA_HOME` 指向失效的 JDK 17 路径 |
| Gradle | 系统 PATH 中无独立 Gradle，项目通过 Wrapper 管理 |
| Android SDK | 已安装 API 37.0（平台修订版 2）与 Build-Tools 36.0.0 |
| Platform-Tools | ADB 37.0.3 可用，Android 17 模拟器可连接 |
| 模拟器 | `Medium_Phone_API_37.0`，x86_64，WHPX 可用 |
| SDK 命令行工具 | 未安装。需要 `sdkmanager` 时，可在 Studio 的 **Tools → SDK Manager → SDK Tools** 安装 **Android SDK Command-line Tools (latest)** |
| Git、Node.js、Python | Git 2.54.0.windows.1、Node.js 24.19.0、Python 3.14.7 可调用 |

构建通过设置当前 PowerShell 进程的 `JAVA_HOME` 使用 Studio 自带 JDK，SDK 路径写在 Git 忽略的 `local.properties` 中。Node.js、Python 和独立 `kotlinc` 均不是 Android 构建依赖。

## 阶段计划

P0 初始化 Kotlin、Compose 和 Gradle 项目并验证启动。P1 完成粉白色基础界面及六页导航。后续阶段见根目录 `AGENTS.md`。

P2 使用 MediaPipe Tasks Audio 加载官方 Kokoro-82M CPU bundle，P3 提供两种普通话女声、默认音色设置和离线试听。CPU 测试使用 Android 17 模拟器。GPU Delegate 与真实设备性能需在 realme GT7 上另行测量。

下一阶段 P4 接入 Android WebView 浏览器。
