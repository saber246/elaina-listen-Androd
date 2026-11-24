# 项目架构

应用使用一个 Android 模块 `:app`。`MainActivity` 管理 Android 应用入口，Compose UI、导航与主题放在 `com.elainalisten.ui`。Android 字符串资源提供页面标题、操作标签和状态说明。页面选中状态来自导航栈，系统返回与页面返回使用同一导航栈。

## Azure 语音

朗读与试听使用 Microsoft Azure Speech 的晓晓或云希。文本按用户设置的 80～200 字上限分段。旧 Kokoro 音色恢复为晓晓。应用用 Android Keystore 生成 AES-256 密钥，通过 AES-GCM 加密 Speech 凭据，保存到禁用备份的应用私有存储。保存表单在本地工作。云端合成使用指定区域 HTTPS 端点及 SSML，将返回的 24 kHz 单声道 PCM 交给现有音频管线。播放器最多预生成下一段，暂停时停止发起新请求。[Microsoft Speech REST API](https://learn.microsoft.com/zh-cn/azure/ai-services/speech-service/rest-text-to-speech)

## 网页与正文

应用进程管理最多五个 WebView 标签。每个标签保留独立的导航状态和地址稿，关闭时释放 WebView。地址与操作栏的收起状态保存在本机。朗读的自动翻页绑定原标签。地址栏接受 HTTPS 或可补全为 HTTPS 的域名。WebView 拒绝非 HTTPS 导航、取消错误证书的请求，并关闭文件访问、内容提供者访问、第三方 Cookie 和混合内容。网页使用系统 WebView 的 JavaScript 兼容能力，应用不暴露 JavaScript 桥。

用户触发提取时，应用从当前已完成加载的页面读取有长度上限的 DOM 快照。Jsoup 在本地去除噪声，参照 Readability 的段落评分及链接密度思路选择正文容器，再将标题、章节与段落交给正文预览。跨页面的迟到结果会被丢弃。无法识别的网页可通过系统选词或粘贴输入。应用不建立独立浏览历史。[Readability 实现](https://github.com/mozilla/readability/blob/main/Readability.js)

## 阅读播放

应用进程的 ViewModel 持有章节与 ExoPlayer。用户选择起始段后，语音逐段合成，首段就绪时送入 ExoPlayer。当前会话的 WAV 片段写入一个私有缓存文件，Media3 按每个片段的字节范围读取。进度条以当前可播音频时长为准。预计总时长在首段就绪前按文本计算，随后按已生成音频校正，并在界面应用当前倍速。估算范围为当前页面已提取的内容。切换音色会从当前语音片段继续合成。Media3 MediaSessionService 与界面共享同一个 ExoPlayer，支持前台服务、系统媒体通知、音频焦点和耳机断开暂停。媒体通知返回播放器。用户开启混音后，播放器不请求独占音频焦点，听书音量可单独静音。应用检测电话或通信音频模式时暂停并写入进度。[Media3 后台播放文档](https://developer.android.com/media/media3/session/background-playback)

## 自动翻页

完成当前页的音频后，等待一秒再尝试同源 HTTPS 的明确下一页或下一章链接。已访问网址、相同正文与单次最多 20 次跨页跳转限制了重复和循环。手动导航会取消待执行的自动翻页。加载或提取失败时暂停并提示。

## 收藏与进度

Room 管理应用私有 SQLite 数据库。用户添加收藏后，保存正文快照、网址、章节、段落、片段偏移、播放位置、音色和倍速。播放时每两秒记录一次进度，并在暂停及跳转时记录。播放检查点写入独立的单条最近听书记录，同时更新已存在的正文收藏。Room 通过自动迁移保留 v1、v2 记录。浏览器首页显示网址收藏，听书收藏页显示正文收藏。删除文件夹时，在同一事务内将其收藏移回未分类。架构文件保存在 `app/schemas`。[Android Room 文档](https://developer.android.com/jetpack/androidx/releases/room)

## MCP

独立 Node.js 服务使用官方 MCP SDK 的 stdio 传输。Android 的 `mcp` 包通过 ADB 转发接收回环连接，使用每次启用时生成的令牌和三个独立权限组。删除收藏需要应用内确认。手机端复用真实 WebView、正文提取器、播放器及 Room DAO。授权与连接状态独立于应用内播放，详见[MCP 接入](mcp.md)。

应用包名为 `com.elainalisten`，最低支持 Android 8.0（API 26），编译和目标版本均为 Android 17（API 37）。WebView 使用网络连接，正文解析在本地执行，语音合成通过 Azure Speech 执行。系统备份关闭。
