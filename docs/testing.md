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
