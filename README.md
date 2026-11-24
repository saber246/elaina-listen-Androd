# elaina listen（轻语听书）

elaina listen 是面向 Android 的中文听书项目，目标设备为 realme GT7。当前版本实现 P0 至 P9，支持在应用内浏览 HTTPS 网页、提取正文，并使用本地 Kokoro-82M 和 Media3 朗读。

从首页或设置打开「声音选择」，可试听默认的小妮（1.0×）和小小（1.05×）。可输入自己的文字，留空则使用同一段示例。应用保存默认音色。模型随 APK 安装，手机首次试听也可完全离线。

在「浏览器」顶部的固定地址栏输入网址，打开网页后点「提取正文」。正文页面可从头朗读，也可长按任一段落从该段开始。自动提取失败时，可长按原网页选择文字并朗读，或复制到「粘贴朗读」。

「播放器」支持暂停与继续、前后跳过 10 或 30 秒、拖动已合成音频的进度、0.8～2.5 倍速、切换章节和音色。读完章节后，播放器会等待一秒并尝试继续下一章，或打开同站的「下一页」「下一章」链接。可在设置关闭自动翻页。切到后台或锁屏后，使用系统媒体控件暂停和继续。

在正文或播放器点「添加收藏」。收藏保存正文和朗读进度，可在「收藏」打开并恢复上次的段落、音色与倍速。

可在「设置 → Azure 云端语音」保存 Microsoft Azure Speech 资源的区域和密钥，随后试听或选择 Azure 晓晓与云希。密钥在应用私有存储中加密保存，可更换或移除。云端音色使用时会发送文本并可能产生 Azure 费用。默认使用本地 Kokoro。[Azure 音色列表](https://learn.microsoft.com/zh-cn/azure/ai-services/speech-service/language-support?tabs=tts)

## 开发文档

- [构建与安装](docs/build.md)
- [开发环境与阶段计划](docs/development.md)
- [架构](docs/architecture.md)
- [测试记录](docs/testing.md)
- [MCP](docs/mcp.md)
