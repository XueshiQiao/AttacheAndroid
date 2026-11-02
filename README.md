# Attaché for Android (`AttacheAndroid`)

**Attaché** 是一款面向 AI Agent 远程开发工作流（Claude Code / Antigravity / Grok CLI）深度定制的 Android 移动终端与控制舱客户端。

本项目基于开源 SSH 客户端 [ConnectBot](https://github.com/connectbot/connectbot)（Apache-2.0）构建，针对移动蜂窝网络环境下的 `tmux` 多窗口控制、异地组网直连、语音指令输入与双向文件投递进行了全方位重构。

---

## ✨ 核心特性 (Key Features)

### 1. 原生 `tmux -CC` 控制模式集成 (`org.attache.tmux`)
- **三层侧边栏导航 (`Hosts -> Sessions -> Windows`)**：横屏固定停靠左侧边栏，竖屏一键抽屉滑出，直接点击切换主机、Session 与窗口，告别繁琐的 `Ctrl+B` 前缀键组合。
- **手势直通 `tmux` 历史滚动与分屏交互**：单指上下滑动直接驱动 `tmux copy-mode` 历史回滚（含惯性滑行），点击分屏自动切换焦点窗格，支持一键新建窗口、左右/上下分屏、窗格最大化 (`Zoom`)。
- **静音抑制优化**：自动屏蔽 `tmux` 窗口切换时的 `BEL (\x07)` 蜂鸣声与震动干扰。

### 2. 内嵌用户态 Tailscale 网络栈 (`tsnet` + `PeerRelay`)
- **免系统 VPN 槽位冲突**：通过 CGO 交叉编译内嵌 Tailscale 官方 [`tsnet`](https://pkg.go.dev/tailscale.com/tsnet) 用户态 WireGuard + Netstack 网络栈（`tsnet-bridge/`），不占用 Android 系统的唯一 `VpnService` 槽位，可与手机端代理软件同时在线共存。
- **物理网卡底层直绑 (`netmon.Binder`)**：通过 JNI 调用 `ConnectivityManager` + `Network.bindSocket(fd)`，将 Tailscale 流量强制绑定在手机真实的 5G 蜂窝或物理 Wi-Fi 网卡上，彻底规避系统 VPN 路由劫持导致的断流与回环。
- **P2P / PeerRelay / DERP 实时诊断面板**：内置节点状态看板与一键 Ping 延迟测试。

### 3. 双风格 AI 快捷控制栏与原地语音输入
- **双视觉风格切换**：
  - **Terminal Deck · 极客机械盘 (`TERMINAL_DECK`)**：等宽字体机械键帽 + 语义色彩分级。
  - **Copilot Dock · 悬浮胶囊坞 (`COPILOT_DOCK`)**：16dp 圆角悬浮坞 + 轻量化辅助键，右侧滚动区域自带平滑边缘渐隐。
- **纯图标极简模式**：支持将第一排快捷按钮切换为纯图标模式（`🎤` · `/` · `🖼` · `📂` · `📋` · `📥` · `⊞` · `⌫`）。
- **原地展开式多行语音/文字输入栏**：点击左上角 `🎤` 按钮，底部工具栏原地展开为自适应高度（2～5 行）的多行语音听写框，发送后自动追加回车并收起软键盘；默认终端输入按回车后同样支持自动收起软键盘。
- **内置 Agent 斜杠指令菜单 (`/`)**：一键注入 `/compact`、`/clear`、`/model`、`/plan`、`/goal` 等常用 AI Agent 指令。

### 4. 双向文件收件箱与一键截图投递 (`AttacheFileInbox`)
- **CLI 一键推送到手机/平板**：在远程 Mac 终端运行 `~/.local/bin/attache-send <文件路径>`，即可通过 `tmux` 通道触发手机端自动拉取文件至本地收件箱。
- **远程目录树浏览与主动拉取**：支持在手机端直接浏览远程目录及子文件夹，拉取 PDF、图片、源码与安装包。
- **内置文本预览与 APK 一键升级安装**：文本文档支持应用内直接预览，APK 文件支持通过 `FileProvider` 一键唤起系统安装器更新。

### 5. 移动终端主题与后台常驻保活
- **全界面一体化配色**：
  - **亮色模式（Claude Warm Paper `#F7F3EA`）**：系统状态栏、顶部栏、终端画布与底部栏统一为柔和暖米纸色。
  - **暗色模式（One Dark Pro `#21252B`）**：全界面统一为经典深岩灰蓝配 16 色 One Dark Pro 语法高亮。
- **后台防断连保活**：活跃会话期间自动持有 `PARTIAL_WAKE_LOCK` 与高性能 `WifiLock`，配合连接层容错保护确保息屏与弱网切换不断连。

---

## 🛠️ 编译与打包 (Building)

### 1. 编译内嵌 Tailscale 动态库（可选，已预编译打包 `arm64-v8a`）
如果修改了 `tsnet-bridge/main.go`，可运行脚本重新生成 JNI `.so`：
```bash
./scripts/build-tsnet.sh
```

### 2. 编译 Debug APK 并推送到设备收件箱
版本号定义在根目录 [`version.properties`](version.properties)（`VERSION_NAME=1.0.0`, `BUILD_NUMBER=<N>`）：
```bash
./gradlew assembleOssDebug
cp app/build/outputs/apk/oss/debug/app-oss-debug.apk /tmp/Attache-v1.0.0-b<N>.apk
~/.local/bin/attache-send /tmp/Attache-v1.0.0-b<N>.apk
```

---

## 🙏 致谢与开源协议 (Acknowledgments & License)

本项目 Fork 自开源项目 **[ConnectBot](https://github.com/connectbot/connectbot)**（作者 Kenny Root 及贡献者），底层 SSH 与终端渲染基于：
- [ConnectBot](https://github.com/connectbot/connectbot) / [termlib](https://github.com/connectbot/termlib) / [cbssh](https://github.com/connectbot/cbssh)（Apache License 2.0）
- [Tailscale `tsnet`](https://github.com/tailscale/tailscale)（BSD-3-Clause License）

本项目遵循 **[Apache License 2.0](LICENSE)** 开源协议发布。
