# 项目架构

应用使用一个 Android 模块 `:app`。`MainActivity` 管理 Android 应用入口，Compose UI、导航与主题放在 `com.elainalisten.ui`。Android 字符串资源提供页面标题、操作标签和状态说明。页面选中状态来自导航栈，系统返回与页面返回使用同一导航栈。

## 离线语音

`com.elainalisten.tts` 封装 Kokoro 模型加载、文本分段、本地音色偏好和试听播放。构建时校验并打包 CPU 版 Kokoro-82M 模型，首次试听将模型复制到应用私有目录。MediaPipe Tasks Audio 通过本地模型路径加载 LiteRT-LM bundle，并使用 CPU Delegate 产生 24 kHz 单声道音频。

文本优先按句末和分句符号切分，每段最多 80 个 Unicode 码点。应用逐段合成与播放，限制驻留的音频数据。一次试听复用一个模型实例，退出时释放。按下停止时会停止音频输出，并在当前有界的原生合成调用结束后释放模型。默认音色通过本地 SharedPreferences 保存。

试听页面采用 Android AudioTrack。后续播放器和后台媒体会话在 P6、P8 接入。

## 后续分层

浏览器、媒体播放器、数据库和 MCP 由对应阶段实现。具体模块边界随实际数据流和依赖确定。

应用包名为 `com.elainalisten`，最低支持 Android 8.0（API 26），编译和目标版本均为 Android 17（API 37）。当前界面无需网络与运行时权限，系统备份默认关闭。
