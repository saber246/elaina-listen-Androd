# 构建与安装

在 Android Studio 中打开仓库根目录，选择 Studio Embedded JDK 作为 Gradle JDK，然后同步项目。工程需要 Android SDK Platform 37.0 和 Build-Tools 36.0.0。

在 Windows PowerShell 中也可以直接构建。下方使用标准安装路径，实际路径以本机为准。

```powershell
$env:JAVA_HOME = "$env:ProgramFiles\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

首次运行会下载 Gradle 发行包和依赖。Wrapper 使用固定发行版本并校验 SHA-256。项目使用 Android Gradle Plugin 9.3.1、Gradle 9.5.0、Kotlin 与 Compose Compiler 2.4.21、Compose BOM 2026.09.00、Activity Compose 1.13.0 和 Navigation Compose 2.9.7。Java 和 Kotlin 的字节码目标为 17。

## 语音资源

构建会下载并校验锁定版本的 Matcha 模型、声码器、词典和 Sherpa-ONNX 1.12.21 AAR，再把语音资源打进 APK。已验证的下载文件缓存于 `.gradle/matcha`。首次使用 Matcha 可在断网状态完成。

默认构建 `arm64-v8a` 手机 APK。在 x86_64 模拟器上运行时，将 `'-PelainaAbi=x86_64'` 加到 Gradle 命令。

Azure 晓晓与云希继续使用应用内的资源区域和密钥。上游 Matcha 模型卡注明训练数据限非商业使用。[Matcha 模型卡](https://huggingface.co/csukuangfj/matcha-icefall-zh-baker) · [Sherpa-ONNX 1.12.21](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.12.21)

## 验证

P4 至 P6 增加 Jsoup 1.24.1、NIO core library desugaring 2.1.5，以及 AndroidX Media3 ExoPlayer 1.11.1。P8 使用 Media3 Session 1.11.1，P9 使用 Room 2.8.5 与 KSP 2.3.12。[Room 发布说明](https://developer.android.com/jetpack/androidx/releases/room) · [KSP 2.3.12](https://github.com/google/ksp/releases/tag/2.3.12)

版本依据见 [AGP 9.3 版本说明](https://developer.android.com/build/releases/agp-9-3-0-release-notes)、[Kotlin Gradle 兼容性表](https://kotlinlang.org/docs/gradle-configure-project.html)和 [Compose 编译器配置](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)。

启动 Android Studio 的模拟器后，可安装并运行界面测试。测试依赖包括 Compose UI Test（由 BOM 管理）、AndroidX Test Runner 1.7.0 和 Espresso 3.7.0。

```powershell
.\gradlew.bat :app:installDebug :app:connectedDebugAndroidTest
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。正式签名及发布验证安排在 P12。
