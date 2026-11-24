# 项目架构

应用使用一个 Android 模块 `:app`。`MainActivity` 管理 Android 应用入口，Compose UI、导航与主题放在 `com.elainalisten.ui`。Android 字符串资源提供页面标题、操作标签和状态说明。页面选中状态来自导航栈，系统返回与页面返回使用同一导航栈。

## 离线语音

`com.elainalisten.tts` 封装 Kokoro 模型加载、文本分段、本地音色偏好和试听播放。构建时校验并打包 CPU 版 Kokoro-82M 模型，首次试听将模型复制到应用私有目录。MediaPipe Tasks Audio 通过本地模型路径加载 LiteRT-LM bundle，并使用 CPU Delegate 产生 24 kHz 单声道音频。

文本优先按句末和分句符号切分，每段最多 80 个 Unicode 码点。应用逐段合成与播放，限制驻留的音频数据。同一应用进程复用一个 CPU 引擎，切音色时按片段传入音色 ID，使用 Media3 Sonic 保持音高并应用音色默认语速。停止时会停止音频输出，当前有界的原生调用返回后停止后续合成。默认音色通过本地 SharedPreferences 保存。

试听页面采用 Android AudioTrack。正式阅读使用 Media3 ExoPlayer。

## 网页与正文

Activity 持有 WebView 浏览会话，标签切换保留当前页面与前后导航状态。地址栏接受 HTTPS 或可补全为 HTTPS 的域名。WebView 拒绝非 HTTPS 导航、取消错误证书的请求，并关闭文件访问、内容提供者访问、第三方 Cookie 和混合内容。网页使用系统 WebView 的 JavaScript 兼容能力，应用不暴露 JavaScript 桥。

用户触发提取时，应用从当前已完成加载的页面读取有长度上限的 DOM 快照。Jsoup 在本地去除噪声，参照 Readability 的段落评分及链接密度思路选择正文容器，再将标题、章节与段落交给正文预览。跨页面的迟到结果会被丢弃。无法识别的网页可通过系统选词或粘贴输入。应用不建立独立浏览历史。[Readability 实现](https://github.com/mozilla/readability/blob/main/Readability.js)

## 阅读播放

Activity 的 ViewModel 持有章节和播放器状态。用户选择起始段后，Kokoro 逐段合成，首段就绪时送入 ExoPlayer。当前会话的 WAV 片段写入一个私有缓存文件，Media3 按每个片段的字节范围读取。进度条以当前可播音频时长为准。切换音色会从当前语音片段继续合成。离开应用时暂停前台播放。

## 后续分层

自动翻页、后台媒体会话、收藏数据库及 MCP 由对应阶段实现。

应用包名为 `com.elainalisten`，最低支持 Android 8.0（API 26），编译和目标版本均为 Android 17（API 37）。WebView 使用网络连接，正文解析及语音合成在本地执行。系统备份默认关闭。
