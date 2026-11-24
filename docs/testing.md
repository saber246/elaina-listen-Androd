# 测试记录

## 2026-11-24 · P0 工程初始化

| 检查 | 结果 |
| --- | --- |
| Gradle Wrapper | Wrapper JAR 与 Gradle 9.5.0 发行包通过官方 SHA-256 校验 |
| 首次成功构建 | `:app:assembleDebug` 成功，36 个任务执行 |
| 调试 APK 安装 | 在 `Medium_Phone_API_37.0`（Android 17，x86_64）上通过 ADB 安装成功 |
| 冷启动 | `adb shell am start -W` 返回 `Status: ok`，应用进程存在 |

下载 Wrapper JAR 和通过 Java 下载 Gradle 发行包时遇到连接超时。使用 GitHub 官方 Gradle 仓库 API 获取 Wrapper JAR，通过 curl 下载同一发行包，校验后写入本地 Wrapper 缓存，随后由 Wrapper 完成构建。

## 2026-11-24 · P1 基础界面和导航

初次执行界面测试时，Compose UI Test 传递引入的 Espresso 3.5.0 调用了 Android 17 已移除的输入接口，测试未能进入用例。显式升级测试依赖至 Espresso 3.7.0 并切换到 Compose 测试 v2 入口。Lint 还发现导航栏主题属性超出最低 API 26，以及 Windows SDK 路径没有转义盘符。导航栏样式由 Activity 的 edge-to-edge 接口处理，本机 SDK 路径已修正。Android 12 及以上的备份和设备迁移使用显式的数据提取规则。

| 最终检查 | 结果 |
| --- | --- |
| `:app:assembleDebug` | 通过，生成 `app/build/outputs/apk/debug/app-debug.apk` |
| `:app:lintDebug` | 通过，0 个错误。保留已验证的 Gradle 9.5.0，Lint 提示存在更新版本 |
| `:app:connectedDebugAndroidTest` | Android 17 模拟器上 3 项通过，0 失败，0 跳过 |
| APK 签名、安装及冷启动 | `apksigner verify` 通过，ADB 安装成功，冷启动返回 `Status: ok` |

界面测试覆盖四个底栏入口、首页到浏览器的快捷入口、首页和设置到声音选择的入口、页面与系统返回，以及 Activity 重建后恢复选中的页面。首页另通过模拟器截图检查粉白主题、卡片和导航栏布局。

本轮使用调试签名和 Android 17 模拟器验证。realme GT7 真机测试安排在 P11，正式签名发布安排在 P12。

## 2026-11-24 · P2 Kokoro Android 集成

在 `Medium_Phone_API_37.0`（Android 17，x86_64，约 4 GB 模拟器内存）关闭 Wi-Fi 和移动数据后，`dumpsys connectivity` 显示无默认网络。三项 Kokoro 仪器测试通过，应用清单无联网权限。测试期间使用 CPU Delegate 和随 APK 打包的官方 `kokoro_int8_fp16.litertlm`。

| 样本 | 输出时长 | 合成耗时 | 采样点 |
| --- | ---: | ---: | ---: |
| 小妮 `zf_xiaoni`（1.0×） | 5.08 秒 | 5.01 秒 | 122,026 |
| 小小 `zf_xiaoxiao`（1.05×） | 4.68 秒 | 4.21 秒 | 112,213 |
| 228 字中文长文 | 46.76 秒 | 24.76 秒 | 1,122,354 |

合成耗时包含本次模型实例的加载，不含播放耗时。首次长文测试包含模型解包和缓存准备，耗时 35.92 秒。长文分 3 段，另验证了补充平面汉字的代理对不会被切断。离线通过批次的最高进程驻留内存（`VmHWM`）为 1,184,252 KiB（约 1.13 GiB）。

MediaPipe 依赖曾传递引入联网权限。已排除 DataTransport 的 CCT 后端，移除网络状态权限，并通过最终 APK 和无网络测试核验。当前模拟器使用 SwiftShader 且未提供 OpenCL，realme GT7 的 GPU Delegate 与实机性能尚待验证。

## 2026-11-24 · P3 双女声试听与图标

| 检查 | 结果 |
| --- | --- |
| Gradle Debug 构建 | 通过，版本 0.2.0，调试 APK 约 451 MB |
| Lint | 通过，0 错误。警告涉及 Gradle 更新、自适应图标目录限定符和单色主题图标 |
| Android 17 模拟器 | 8 项通过，0 失败，0 跳过。覆盖 P1 导航、P2 离线语音和 P3 输入文字、试听、停止及默认音色恢复 |
| APK 签名及安装 | 调试签名和 16 KiB ZIP 对齐检查通过，ADB 安装与冷启动成功 |
| 应用图标 | APK 的启动图标指向自适应图标资源，内嵌 JPEG 与用户提供的原图字节一致 |

两段样音由 Android 端实时合成，经 AudioTrack 播放，并导出 24 kHz 单声道 WAV 至 `app/build/outputs/kokoro-samples/`。两段 WAV 均在 Windows 上实际播放完成。本机离线转写识别出两段样音的主要句意，应用名和小妮样音中的少量同音字出现误转写。

首次安装后的模型准备和实际合成均在设备本地执行。真机与正式签名 APK 验证仍按项目阶段安排。
