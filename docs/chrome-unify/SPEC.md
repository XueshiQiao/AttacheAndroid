# 顶栏 × 左侧栏 统一方案 SPEC（Chrome Unify）

> 目的：解决平板横屏常驻侧栏 / 竖屏抽屉打开时，顶栏与侧栏“两块拼起来”的违和感。
> 产物：`docs/chrome-unify/mockups.html`（单文件高保真对比页，供用户挑选一个方案）。

## 现状诊断（来自代码 + 用户截图 2026-11-03）
- 顶栏 `TopAppBar` 全宽（ConsoleScreen.kt:2095+，tmux 时 34dp），侧栏 `AttacheSidebar`（TmuxWindowStrip.kt:347-736）放在内容 Column 里 → 从顶栏**下方**开始。
- 侧栏自带 36dp `ATTACHÉ 🌙 +Window ✕` 头部（#18191B / #EBE9E3）+ 分隔线 → 左上角两条头部带叠在一起。
- 三套色系：终端/顶栏 One Dark `#21252B` / 奶油 `#F7F3EA`；侧栏 JetBrains 灰 `#1E1F22` / `#F3F2EE`；顶栏 chip 第三套 `#1C212B`/`#2B323C`（浅 `#EDE7DA`/`#D8D0C1`）。
- 侧栏右边 1dp 线止于顶栏底 → T 字接缝；顶栏下无分隔线。
- 🌙/☀️ 主题按钮重复（顶栏 + 侧栏头）。
- 竖屏：遮罩与抽屉都从顶栏下开始，顶栏不被压暗。
- 选中窗口行实心蓝 `#4E7FB8`/`#3B6EA8`，与纸感主题冲突，顶栏无呼应。

## 统一原则（所有方案共用）
1. 侧栏色系并入终端色系（同一色相，仅明度微差），废弃独立的 JetBrains 灰。
2. 主题按钮只保留一个（顶栏）。
3. 按钮统一一种样式（同高、同圆角）。
4. 选中行改为低饱和 tint 胶囊 + 左侧强调条/强调色文字（方案可变体）。
5. 竖屏抽屉必须覆盖顶栏（全高，从状态栏下沿或状态栏起），遮罩盖住顶栏。

## 五套方案（保守 → 大胆）
| # | 名称 | 参考 | 核心做法 |
|---|------|------|---------|
| A | 同色同缝 · Quiet Merge | Android Settings 双栏 / 微调 | 结构不变；侧栏换成终端同色系，删掉侧栏头部，`+Window` 移入列表顶部 SESSIONS 标题行右侧；侧栏右边线向上贯穿顶栏到状态栏，顶栏底加同色 1dp 线 → 形成干净的“┤”网格。 |
| B | 全高侧栏 · Split View | macOS NavigationSplitView / iPadOS 侧栏 / Finder | 侧栏全高（从状态栏下起），顶栏只覆盖终端区；侧栏头与顶栏同高同基线，连成一条头部线；◧ 切换按钮在侧栏打开时位于侧栏头右侧，收起时移到顶栏最左（像 Notes/Finder）。 |
| C | L 形统一底盘 · Unified Chrome | macOS Tahoe / Arc 浏览器 / Chrome 侧栏 | 顶栏 + 侧栏 = 同一块底盘色，构成 L 形外框；终端区是嵌入的“纸面”，左上角 12dp 圆角、无边线，仅明度差区分。侧栏头部消失，顶栏左段（侧栏宽度之上）即侧栏标题区。 |
| D | 浮动玻璃面板 · Floating Glass | iPadOS 26 Liquid Glass / visionOS | 终端全出血铺满；侧栏是内缩 8dp 的圆角浮动面板（毛玻璃+柔和阴影），顶栏变成两组浮动胶囊（左：← 会话标题；右：A- A+ 🌙 ▦ ⋮）。侧栏打开时左胶囊“吸附”进侧栏顶部。 |
| E | 导航轨 + 展开 · Rail Expand | Material 3 Expressive / Android 16 / Gmail 平板 | 横屏常驻 64dp 导航轨（会话首字母头像 + 窗口数徽标），顶部汉堡与顶栏同一行；点击展开为完整侧栏（轨道原地变宽）。竖屏无轨道，抽屉从左侧全高展开，头部沿用轨道样式。 |

## HTML 对比页要求
- 单文件、无外链依赖（字体用系统 monospace / system-ui）。
- 顶部切换：方案 A–E、横屏/竖屏、浅色/深色；可点击 ◧/☰ 打开/收起侧栏，竖屏点遮罩关闭，带动画。
- 设备框：平板横屏 2000×1250、竖屏 1250×2000 的等比缩放（适配浏览器窗口）。
- 内容用截图里的真实数据（主机 tmux Daily / tmux Dev，会话 DAILY 4、DEV 18 个窗口，底部键盘工具条、tmux 状态行）。
- 每个方案配一张说明卡：参考来源、改动点、优点、代价/实现成本（对应 Compose 改动范围）。
- 默认浅色（用户当前用浅色奶油主题）。

## 落地决策记录（mockups.html v1，2026-11-03）
- 比例：设备逻辑 2000×1250 / 1250×2000 按 1/2 渲染（CSS 1000×625）；状态栏 40px、顶栏行 38px（即头部底线 y=78），横屏侧栏 244px，竖屏抽屉 288px（D 300 / E 300）。
- B / C / E 侧栏与抽屉采用 edge-to-edge：背景从屏幕 y=0 起铺到状态栏下（状态栏图标浮在上面），A 竖屏抽屉从状态栏下沿起。
- 统一选中态：浅色 tint 用不透明 `#DEE6F0`（深色 `#2A3A4E`）——半透明蓝叠在奶油底上会发灰；C 用纸色胶囊；E 用 M3 indicator `#DAE3F1`。
- 新方案按钮统一：28px 高、8dp 圆角、无边框 tonal（`rgba(ink,.065)`），图标用单色线性 SVG；主题按钮只在顶栏。
- 新方案侧栏文案改中文：「主机」「会话 · 窗口」「＋ 窗口」；现状页保留原英文。
- 竖屏点窗口行后 180ms 自动收起抽屉。

## ✅ 用户定稿（2026-11-03 14:02）：方案 D · 浮动玻璃面板 Floating Glass
- 实现严格以 `mockups.html` 中 `?s=D` 的横屏/竖屏、浅色/深色、开/合状态为视觉基准。
- 其余方案 A/B/C/E 不实现。共用原则（单一主题按钮、tint 选中行、竖屏遮罩覆盖顶栏、侧栏并入终端色系）依然适用。

## 实现记录 · 方案 D（Build 16，2026-11-03）
- 新文件 `app/src/main/java/org/attache/tmux/AttacheFloatingChrome.kt`：`AttacheGlassPalette`（浅 `#F7F3EA` / 深 `#21252B` 同色系；accent `#3B6EA8`/`#61AFEF`；选中行 = accent 16% tint）、`AttacheChrome` 几何常量、`AttacheFloatingChrome`（左胶囊 ← 标题 ◧ / 右胶囊 / 竖屏遮罩 / 浮动面板）、`GlassCapsule`、`CapsuleActionButton`、`SidebarGlyph`。
- 几何：胶囊高 40dp，顶 = 状态栏 + 4dp；终端顶部 inset = 状态栏 + 4 + 40 + 6dp（tmux 主机时由 chrome 通过 `onBarHeightChanged` 写回 `titleBarHeight`）。面板内缩 8dp、圆角 22dp；横屏宽 256dp，终端左侧让出 `LandscapePanelSlot = 272dp`（Row 里的 Spacer，沿用 expand/shrink 动画）；竖屏宽 min(300dp, 屏宽-56dp)。面板头部 46dp 空位 + 1dp hairline，供左胶囊“吸附”。
- Z 序：右胶囊 < 竖屏遮罩（全屏，含状态栏/胶囊） < 面板 < 左胶囊。左胶囊在面板打开时即使顶栏自动隐藏也显示（它是面板标题行）。
- 面板与胶囊统一绘制在 Scaffold content 末尾的 `AttacheFloatingChrome`（原横屏 Row 内侧栏、竖屏手写遮罩+抽屉已删除）。非 tmux 主机仍用原 `TopAppBar`（actions 抽成 `topBarActions` 共用）。
- `AttacheSidebar` 只渲染面板内容（主机 / 会话 · 窗口 +「＋ 窗口」/ 页脚），已删除旧头部带与侧栏主题按钮及 `onToggleTheme` 参数；主题按钮只在右胶囊。
- 竖屏点窗口/主机/新窗口/收件箱后延迟 180ms 关闭抽屉（ConsoleScreen 里的 `onDismiss`）。
- 毛玻璃：未做真实 backdrop blur（Compose 只能模糊自身内容，需额外截取终端层，成本高）；改用高不透明度 tint（胶囊/横屏面板玻璃 alpha ≈ 0.93；竖屏面板改为不透明，因为 0.95 时终端文字透出明显，Robolectric 渲染验证）+ `shadow` + 0.5dp 发丝边框。半透明面上的 elevation 阴影会透出一点，所以 alpha 不要降得太低。
- 视觉回归：`AttacheFloatingChromeTest`（Robolectric NATIVE 图形）；设 `ATTACHE_SHOTS_DIR=/path` 可导出横/竖屏、浅/深 PNG 与 mockups.html 对比。

## ✅ 用户追加（2026-11-03 14:37）：底部工具键区同样改为浮动玻璃风格
- 底部两行工具按钮（🎤说 /cmd tmux +img inbox copy paste ^U / CTRL ALT ESC TAB ▲▼◀▶ HOME END PGUP PGDN F1 IME ⌨）改为与顶部胶囊一致：同一 `AttacheChromeColors` 玻璃底色、阴影、hairline 边框、圆角，内缩悬浮于终端之上（不再是贴底实色条），与顶部胶囊形成上下呼应。
- 按键本身去掉独立边框（遵守“最多 1 层框”），按颜色/tint 区分功能键；保留现有彩色语义（说=黄、/cmd tmux=蓝、paste=绿、^U=红、ESC=橙）但降为柔和 tint。
