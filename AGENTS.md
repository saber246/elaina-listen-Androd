# AGENTS.md — SoftRead 项目总开发规范

## 1. 你的角色

你是一名高级 Android 工程师、Kotlin 工程师、移动端 AI 推理工程师、软件架构师和测试工程师。

你的任务是从零开发 SoftRead（轻语听书）。

你必须实际创建文件、编写代码、安装依赖、执行构建、运行测试、修复错误。

不能只输出教程、代码片段或开发计划。

不能声称未执行的构建成功，也不能声称未进行的真机测试已经通过。
没完成一次任务 提交一次git  https://github.com/saber246/elaina-listen-Androd.git

## 2. 项目目标

项目名称：elaina listen

平台：Android

目标设备：realme GT7

设备配置：12GB RAM + 256GB 存储

开发语言：Kotlin

UI：Jetpack Compose

核心功能：
- 网页浏览器
- 小说和文章正文提取
- 中文本地 TTS
- 两种中文女声音色
- 自动翻页
- 连续听书
- 后台和锁屏播放
- 本地收藏
- 阅读进度恢复
- 可选 MCP 服务

最终交付：
- 完整 Android Studio 项目
- 完整源代码
- 完整项目文档
- 测试报告
- 可安装 APK

## 3. 开发原则

1. 免费优先。
2. 语音模型完全本地运行。
3. 不使用收费 TTS API。
4. 不依赖云端语音生成。
5. 不从零训练语音模型。
6. 优先使用许可明确的开源模型。
7. 数据默认保存在手机本地。
8. 不提供浏览历史。
9. 不自动上传用户数据。
10. 不得使用虚假功能代替真实功能。

## 4. 开始开发前的环境检查

先检查：

- Windows 版本
- Java/JDK 版本
- Gradle 版本
- Android SDK
- Android SDK Platform-Tools
- Android SDK Build-Tools
- Kotlin 插件
- Git
- Node.js
- Python
- ADB 是否正常

执行必要的环境检查命令。

如果某项缺失，先告诉我缺少什么以及安装方法。

不得随意修改系统环境变量。

不得在未经确认的情况下删除用户文件。

## 5. 项目架构

使用清晰的分层结构：

- UI Layer
- Domain Layer
- Data Layer
- Browser Module
- TTS Module
- Playback Module
- Database Module
- MCP Module

优先采用稳定、兼容的 Android 官方技术。

所有依赖版本必须先确认兼容性。

不得编造依赖、SDK、API 或类名。

## 6. UI 设计

使用 Jetpack Compose。

整体风格：
- 温柔粉白色
- 年轻甜美
- 圆角卡片
- 柔和渐变
- 护眼
- 简洁
- 适配手机屏幕

页面：
- 首页
- 浏览器
- 播放器
- 声音选择
- 收藏
- 设置

## 7. 语音模型

优先研究 Kokoro-82M：

https://huggingface.co/litert-community/Kokoro-82M

优先测试：

kokoro_int8_fp16.litertlm

参考大小约 295MB。

必须：
1. 阅读官方 README。
2. 确认模型文件格式。
3. 确认 Android 运行时支持。
4. 确认 Kotlin/Android 接口。
5. 确认模型加载方法。
6. 验证中文普通话。
7. 验证实际语音输出。
8. 测试长文本分段。
9. 测试内存占用。
10. 测试断网朗读。

优先建立 CPU 推理基准，再测试 GPU Delegate。

不要假定 realme GT7 的 GPU 一定兼容。

如果模型无法直接集成，先调查官方替代运行方案，不得伪造接口。

## 8. 双女声

默认甜美女声：
- Voice ID：zf_xiaoni
- 语速：1.0
- 年轻女生感觉
- 甜美自然
- 标准普通话

第二种可爱女声：
- Voice ID：zf_xiaoxiao
- 语速：1.05
- 清脆活泼
- 可爱风格
- 标准普通话

以上为候选音色，必须实际试听验证。

提供：
- 声音试听
- 声音切换
- 默认声音设置
- 本地保存用户选择

不允许用预录音冒充实时 TTS。

## 9. 浏览器

使用 Android WebView。

实现：
- 地址栏
- 网址输入
- 网页加载
- 前进
- 后退
- 刷新
- 页面加载进度
- 加载失败提示

必须遵循 Android WebView 安全规范。

不得默认忽略 SSL 证书错误。

## 10. 正文提取

实现：
- 标题提取
- 正文识别
- 段落分割
- 章节识别
- 广告清理
- HTML 清理
- 长按段落立即朗读

研究 Readability 类算法。

支持手动选择文字作为备用方案。

不承诺所有网站都能自动识别。

## 11. 播放器

使用 AndroidX Media3。

实现：
- 播放
- 暂停
- 继续
- 快进10秒
- 快进30秒
- 快退10秒
- 快退30秒
- 进度条
- 拖动进度
- 0.8～2.5倍速
- 章节选择
- 声音切换

## 12. 自动翻页

当前页面朗读结束后：

1. 等待1秒。
2. 执行下一页。
3. 检测页面变化。
4. 等待新页面加载。
5. 提取新页面正文。
6. 验证内容有效。
7. 开始下一页朗读。

失败必须暂停并提示。

禁止无限点击。

禁止重复朗读。

手动翻页必须取消正在执行的自动翻页任务。

## 13. 后台播放

使用 Foreground Service、MediaSession 和 AndroidX Media3。

实现：
- 锁屏播放
- 通知栏控制
- 耳机控制
- 音频焦点管理
- 后台状态恢复

遵守 Android 当前权限要求。

## 14. 收藏和数据

使用 Room + SQLite。

保存：
- 文章标题
- 网页网址
- 当前段落
- 阅读位置
- 播放进度
- 声音选择
- 语速设置

支持收藏、删除、恢复阅读。

不提供浏览历史。

不自动上传用户数据。

## 15. MCP

MCP 为可选模块。

第一版先完成 Android App 功能接口。

之后开发独立 MCP Server。

工具：
- open_webpage
- extract_article
- start_reading
- pause_reading
- resume_reading
- seek_reading
- set_voice
- set_speed
- get_playback_status
- add_bookmark
- list_bookmarks
- delete_bookmark

遵守官方 MCP 规范。

MCP 默认关闭。

必须具备身份验证、权限检查和用户授权。

MCP 出现问题不能影响核心离线听书。

## 16. 开发阶段

P0：环境检查和项目初始化。

P1：基础 UI 和导航。

P2：Kokoro Android 集成。

P3：双女声试听。

P4：WebView 浏览器。

P5：正文提取。

P6：播放器。

P7：自动翻页。

P8：后台播放。

P9：收藏与阅读进度。

P10：MCP 服务。

P11：真机测试。

P12：正式 APK。

每个阶段必须构建、测试并记录结果。

## 17. 文档

维护：

- README.md
- AGENTS.md
- docs/architecture.md
- docs/development.md
- docs/testing.md
- docs/build.md
- docs/mcp.md

重要架构决策必须记录。

## 18. 最终交付

提供：

1. 完整源代码。
2. 完整 Gradle 配置。
3. Android Studio 项目。
4. Kokoro 模型集成。
5. 两种女声。
6. 浏览器。
7. 正文提取。
8. 播放器。
9. 自动翻页。
10. 后台播放。
11. 收藏系统。
12. MCP Server。
13. 项目文档。
14. 测试报告。
15. 正式签名 APK。

APK 输出目录：

app/build/outputs/apk/release/

只有真实编译、签名和安装验证成功，才能报告 APK 完成。

## 19. 现在开始

第一轮只执行 P0 和 P1。

先检查环境。

然后创建 Android Studio 工程。

完成基础 UI。

执行第一次 Gradle 构建。

最后向我汇报：

- 环境检查结果
- 创建的文件
- 使用的依赖
- 构建结果
- 当前问题
- 下一阶段计划

不要一次性生成全部模块。

必须实际执行工作，并以测试结果为准。