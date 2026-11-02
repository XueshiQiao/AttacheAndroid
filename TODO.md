# Attaché Android TODO

## SSH 后台保活与断连优化（待启用）

1. **[已完成] `TmuxControl` 禁用无用的 `%output` 广播（关键流量优化）**
   - **原因**：`tmux -u -C new-session -A -s Dev` 控制通道默认会向手机推送会话内所有窗口（例如 17 个窗口、25 个 pane）的 `%output` 实时输出流（当 `agy` / `claude` 刷屏时高达 150+ KB/s），而 [`TmuxControl.kt`](app/src/main/java/org/attache/tmux/TmuxControl.kt) 实际上只消费通知与命令响应，完全丢弃了 `%output`。当 App 切到后台被系统短暂挂起几秒钟时，未读取的 `%output` 会迅速塞满 SSH/TCP 接收缓冲区导致连接断开（`IO_ERROR`），并在服务端残留孤儿 `tmux -u -C` 进程。
   - **方案**：已在 [`TmuxControl.start()`](app/src/main/java/org/attache/tmux/TmuxControl.kt) 初始化时率先发送 `refresh-client -f no-output`（与 macOS 版 Attaché 一致），彻底关闭控制通道上的 `%output` 推送。

2. **修复 Tailscale VPN 下 `TerminalBridge` 网络探针的「IP 变更」误判（Bug）**
   - **原因**：[`TerminalBridge.captureNetworkState()`](app/src/main/java/org/connectbot/service/TerminalBridge.kt#L1426-L1449) 记录的是 SSH Socket 的本地 Tailscale 虚拟 IP（`100.x.y.z`），但 [`ConnectivityMonitor.getCurrentNetworkInfo()`](app/src/main/java/org/connectbot/service/ConnectivityMonitor.kt#L206-L216) 返回的是物理 Wi-Fi 的默认网络 IP（`192.168.x.x`）。后台一旦触发网络回调，`oldState.ipAddresses.intersect(newNetworkInfo.ipAddresses)` 恒为空，导致 [`TerminalBridge.onNetworkRestored()`](app/src/main/java/org/connectbot/service/TerminalBridge.kt#L1523-L1538) 误判为 IP 变更并主动调用 `dispatchDisconnect(DisconnectReason.NETWORK_LOST)` 掐断仍然存活的 Tailscale SSH 连接。
   - **方案**：在 `onNetworkRestored()` 中加入当前 `transport?.getLocalIpAddress()` 对比，对 Tailscale CGNAT 网段（`100.64.0.0/10`）或本地 Socket IP 未变的情况不主动断开；同时将 `networkGracePeriodJob` 宽限期从 60 秒延长至 10 分钟（`600_000L`）。

3. **后台 `PARTIAL_WAKE_LOCK`、`WIFI_MODE_FULL_HIGH_PERF` 与 SSH 心跳保活（待排查手机 vs Pad 差异）**
   - **现象对比**：执行 [`scripts/configure-background-keepalive.sh`](scripts/configure-background-keepalive.sh) 后，安卓手机端退到后台已不再断连，但安卓 Pad 退到后台过一会儿仍会断开连接。
   - **原因与手机/Pad 差异排查点**：
     1. [`ConnectivityMonitor.kt`](app/src/main/java/org/connectbot/service/ConnectivityMonitor.kt) 仅调用了 `wifiManager.createWifiLock(TAG)`（旧的 `WIFI_MODE_FULL` 在 Android 10+ 上已被系统视为 No-op，需改为 `WIFI_MODE_FULL_HIGH_PERF`），且全工程未申请 `PowerManager.PARTIAL_WAKE_LOCK`。
     2. 纯 Wi-Fi 平板（无蜂窝基带常驻唤醒）在退后台/锁屏时，系统对 Wi-Fi 芯片省电休眠（`wifi_sleep_policy` / 厂商平板深度睡眠）及 CPU 挂起比手机端更激进。
     3. `TmuxControl` 开启 `no-output` 后空闲时无数据包流动，需补充 25 秒轻量级心跳保活。
   - **方案**：在有活跃会话时持有 `PARTIAL_WAKE_LOCK` + `WIFI_MODE_FULL_HIGH_PERF`，并在 `TmuxControl` 中每 25 秒发送一次轻量级保活命令，同时对比手机与 Pad 的 `dumpsys deviceidle` / `wifi` 休眠策略差异。

4. **tmux 主机默认启用自动重连（`stayConnected`）**
   - **方案**：对 `TmuxTrial.targetFor(host.nickname) != null` 的主机默认启用 `AutoReconnect`，万一连接断开，切回前台时自动无感重连回原 tmux 窗口。
