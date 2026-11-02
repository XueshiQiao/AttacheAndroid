# Android 后台保活与网络不受限配置指南 (ADB)

为了保证 ConnectBot / Attaché 在退到后台、切应用或锁屏期间依然能维持稳定的 SSH 与 `tmux -CC` 连接（不断连、不丢包），在通过 USB / 无线 ADB 连接新手机或平板后，可以直接使用本仓库提供的脚本一键完成电源与后台网络策略配置。

---

## 一键配置脚本

仓库内置了自动化脚本 [`scripts/configure-background-keepalive.sh`](../scripts/configure-background-keepalive.sh)：

```bash
# 默认针对 org.connectbot.debug（当前连接的唯一 ADB 设备）
./scripts/configure-background-keepalive.sh

# 指定包名与设备序列号（多设备同时连接时）
./scripts/configure-background-keepalive.sh org.connectbot.debug 403GAA008U65200

# 如果是正式签名版（非 debug 包）
./scripts/configure-background-keepalive.sh org.connectbot
```

---

## 具体执行的 ADB 策略说明

脚本只针对 ConnectBot 单个应用（不会修改系统全局省电策略），依次配置以下 6 项权限与白名单：

1. **加入 Doze 电池优化白名单（无限制电量）**
   ```bash
   adb shell dumpsys deviceidle whitelist +org.connectbot.debug
   ```
   豁免 Android Doze（低电耗模式）限制，避免锁屏后 CPU 或网络被系统挂起。

2. **放行后台运行与唤醒锁权限（AppOps）**
   ```bash
   adb shell cmd appops set org.connectbot.debug RUN_IN_BACKGROUND allow
   adb shell cmd appops set org.connectbot.debug RUN_ANY_IN_BACKGROUND allow
   adb shell cmd appops set org.connectbot.debug WAKE_LOCK allow
   ```
   允许应用退到后台后继续运行 Service 并持有 Partial WakeLock / Wi-Fi Lock。

3. **加入后台网络不受限白名单（NetPolicy）**
   ```bash
   # 先获取应用的 UID（例如 10282）
   adb shell dumpsys deviceidle whitelist | grep org.connectbot.debug
   # 将该 UID 加入后台网络白名单
   adb shell cmd netpolicy add restrict-background-whitelist <UID>
   ```
   即使设备开启了流量节省程序（Data Saver）或后台网络限制，仍允许该 UID 在后台保持 TCP/SSH 长连接收发数据。

4. **应用待机分桶设为最高活跃级别（Active Bucket）**
   ```bash
   adb shell am set-standby-bucket org.connectbot.debug active
   ```
   将 App Standby 分桶固定为 `active`（返回值 `5`），防止系统因长时间未交互而将其降级限流。

5. **授予前台服务常驻通知权限（Android 13+）**
   ```bash
   adb shell pm grant org.connectbot.debug android.permission.POST_NOTIFICATIONS
   ```
   确保 SSH 会话开启时前台服务通知正常展示，提升系统进程优先级。
