# Attaché Android TODO

## SSH 后台保活与断连优化（待启用）

1. **[已完成] `TmuxControl` 禁用无用的 `%output` 广播（关键流量优化）**
   - **原因**：`tmux -u -C new-session -A -s Dev` 控制通道默认会向手机推送会话内所有窗口（例如 17 个窗口、25 个 pane）的 `%output` 实时输出流（当 `agy` / `claude` 刷屏时高达 150+ KB/s），而 [`TmuxControl.kt`](app/src/main/java/org/attache/tmux/TmuxControl.kt) 实际上只消费通知与命令响应，完全丢弃了 `%output`。当 App 切到后台被系统短暂挂起几秒钟时，未读取的 `%output` 会迅速塞满 SSH/TCP 接收缓冲区导致连接断开（`IO_ERROR`），并在服务端残留孤儿 `tmux -u -C` 进程。
   - **方案**：已在 [`TmuxControl.start()`](app/src/main/java/org/attache/tmux/TmuxControl.kt) 初始化时率先发送 `refresh-client -f no-output`（与 macOS 版 Attaché 一致），彻底关闭控制通道上的 `%output` 推送。

2. **修复 Tailscale VPN 下 `TerminalBridge` 网络探针的「IP 变更」误判（Bug）**
   - **原因**：[`TerminalBridge.captureNetworkState()`](app/src/main/java/org/connectbot/service/TerminalBridge.kt#L1426-L1449) 记录的是 SSH Socket 的本地 Tailscale 虚拟 IP（`100.x.y.z`），但 [`ConnectivityMonitor.getCurrentNetworkInfo()`](app/src/main/java/org/connectbot/service/ConnectivityMonitor.kt#L206-L216) 返回的是物理 Wi-Fi 的默认网络 IP（`192.168.x.x`）。后台一旦触发网络回调，`oldState.ipAddresses.intersect(newNetworkInfo.ipAddresses)` 恒为空，导致 [`TerminalBridge.onNetworkRestored()`](app/src/main/java/org/connectbot/service/TerminalBridge.kt#L1523-L1538) 误判为 IP 变更并主动调用 `dispatchDisconnect(DisconnectReason.NETWORK_LOST)` 掐断仍然存活的 Tailscale SSH 连接。
   - **方案**：在 `onNetworkRestored()` 中加入当前 `transport?.getLocalIpAddress()` 对比，对 Tailscale CGNAT 网段（`100.64.0.0/10`）或本地 Socket IP 未变的情况不主动断开；同时将 `networkGracePeriodJob` 宽限期从 60 秒延长至 10 分钟（`600_000L`）。

3. **后台 `PARTIAL_WAKE_LOCK` 与 SSH 心跳保活**
   - **原因**：[`ConnectivityMonitor.kt`](app/src/main/java/org/connectbot/service/ConnectivityMonitor.kt) 仅申请了 `WifiLock` 而未申请 `PowerManager.PARTIAL_WAKE_LOCK`；空闲时无 SSH/tmux 层心跳。
   - **方案**：在有活跃会话时持有带超时刷新的 `PARTIAL_WAKE_LOCK`，并在 `TmuxControl` 中每 25 秒发送一次轻量级保活命令。

4. **tmux 主机默认启用自动重连（`stayConnected`）**
   - **方案**：对 `TmuxTrial.targetFor(host.nickname) != null` 的主机默认启用 `AutoReconnect`，万一连接断开，切回前台时自动无感重连回原 tmux 窗口。
