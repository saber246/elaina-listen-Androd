# 项目架构

P0/P1 采用一个 Android 应用模块 `:app`。`MainActivity` 管理 Android 应用入口，Compose UI、导航与主题放在 `com.elainalisten.ui`。Android 字符串资源提供页面标题、操作标签和状态说明。页面选中状态来自导航栈，系统返回与页面返回使用同一导航栈。

## 后续分层

需要接入业务能力时，再引入领域层和数据层。浏览器、TTS、播放、数据库和 MCP 由对应阶段实现。具体模块边界随实际数据流和依赖确定。

应用包名为 `com.elainalisten`，最低支持 Android 8.0（API 26），编译和目标版本均为 Android 17（API 37）。当前界面无需网络与运行时权限，系统备份默认关闭。
