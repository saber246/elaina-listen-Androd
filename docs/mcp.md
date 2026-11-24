# MCP

P10 提供独立的 Node.js MCP 服务。MCP 客户端通过标准输入输出启动它，服务经 ADB 转发访问 Android 应用。Android 端只监听 `127.0.0.1:8765`。MCP 默认关闭，进程重启后恢复关闭。[MCP SDK](https://ts.sdk.modelcontextprotocol.io/v2/) · [ADB 端口转发](https://developer.android.com/tools/adb#forwardports)

## 连接

1. 安装调试 APK。用 USB 连接已开启 USB 调试的设备，或启动 Android 模拟器。在应用的「设置 → MCP 连接」勾选要授予的权限，点「启用本次连接」，再显示连接令牌。
2. 用 Node.js 20 或更新版本安装服务依赖。在仓库根目录执行下列 PowerShell 命令。如果同时连接多个设备，在 `adb` 后加 `-s <设备序列号>`。

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb devices
& $adb forward --no-rebind tcp:8765 tcp:8765
npm --prefix mcp-server install --ignore-scripts
```

3. 在支持 stdio 的 MCP 客户端中注册下列配置。将示例路径改为本机路径，并通过客户端的私有配置传入应用显示的令牌。

```json
{
  "mcpServers": {
    "elaina-listen": {
      "command": "node",
      "args": ["C:/Users/MR/Desktop/elaina listen/mcp-server/server.js"],
      "env": {
        "ELAINA_MCP_TOKEN": "<换成本次连接令牌>",
        "ELAINA_MCP_PORT": "8765"
      }
    }
  }
}
```

如果本机 8765 已占用，使用另一台电脑端口，例如将转发写为 `tcp:18765 tcp:8765`，同时将 `ELAINA_MCP_PORT` 改为 `18765`。

关闭时，在应用内点「断开并撤销令牌」。需要移除上面建立的转发时，运行 `adb forward --remove tcp:8765`。下次启用会生成新令牌。每次调用都会重新验证令牌与权限。

## 工具

| 权限 | 工具 | 输入与行为 |
| --- | --- | --- |
| 浏览器 | `open_webpage` | `url` 为 HTTPS 网址。开始在应用浏览器加载。 |
| 浏览器 | `extract_article` | 提取当前网页正文，返回 `articleId`、标题、网址、章节和段落。页面变更后需重新提取。 |
| 播放 | `start_reading` | `articleId` 来自提取结果。可选 `chapter`、`paragraph` 从 0 计数，可选 `voiceId`。 |
| 播放 | `pause_reading` | 暂停当前朗读。 |
| 播放 | `resume_reading` | 继续当前朗读。 |
| 播放 | `seek_reading` | `positionMs` 为已生成音频中的绝对毫秒位置。 |
| 播放 | `set_voice` | `voiceId` 为 `zh-CN-XiaoxiaoNeural`、`zh-CN-YunxiNeural` 或 `matcha-icefall-zh-baker`。有播放会话时切换该会话音色，空闲时保存为默认音色。 |
| 播放 | `set_speed` | `speed` 为 0.8～2.5 倍。 |
| 播放 | `get_playback_status` | 返回当前标题、播放状态、章节、段落、毫秒进度、倍速和音色。 |
| 收藏 | `add_bookmark` | 可选 `articleId`。省略时收藏当前朗读。 |
| 收藏 | `list_bookmarks` | 可选 `limit`（1～50，默认 20）与 `offset`（默认 0）。返回收藏元数据和进度。 |
| 收藏 | `delete_bookmark` | `id` 来自收藏列表。应用显示被请求删除的收藏标题，用户确认后才执行。 |

发起或继续朗读，以及请求删除收藏时，应用需处于前台。通过已授权的 MCP 播放使用所选音色。Azure 音色向 Azure Speech 提交正文，Matcha 在手机端合成。网页正文和收藏结果会返回给获授权的 MCP 客户端，客户端可能把工具结果提供给其模型服务。网页正文和收藏标题均为外部内容，不能当成工具指令。

服务使用官方 SDK 2.4.0。测试命令为 `npm --prefix mcp-server test`，Android 构建和测试入口见[构建文档](build.md)。[SDK 的 stdio 协议兼容说明](https://ts.sdk.modelcontextprotocol.io/v2/migration/support-2026-07-28)
