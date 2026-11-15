package org.attache.tailscale

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Compact Tailscale status pill that opens the Tailscale page ([TailscaleScreen]) when tapped.
 */
@Composable
fun TailscaleStatusBadge(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        TailscaleManager.init(context)
    }
    val state by TailscaleManager.uiState.collectAsState()
    val dotColor = when {
        !state.enabled -> Color(0xFF7A7D85)
        state.status.running -> Color(0xFF4CAF50)
        state.status.backendState == "NeedsLogin" -> Color(0xFFFFB300)
        else -> Color(0xFF42A5F5)
    }
    val label = when {
        !state.enabled -> "Tailscale"
        state.status.running && state.status.selfIpv4.isNotEmpty() -> "TS ${state.status.selfIpv4}"
        state.status.running -> "TS Online"
        state.status.backendState == "NeedsLogin" -> "TS Login"
        else -> "TS …"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("tailscaleStatusBadge"),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * The Tailscale page: a destination of its own in the app's navigation,
 * pushed in from the right like any other page (the TS pill on the host
 * list, the terminal's settings). It was first a full-screen Dialog, which
 * the owner rejected on sight: a dialog is its own window, so the system
 * dimmed the status and navigation bars around it and it arrived with no
 * push — not a page of this app.
 *
 * Laid out as the owner chose on 2026-11-15 from
 * `docs/tailscale-page-designs.html`: design 1's identity card on top —
 * the node's name and addresses are what matter there, not a score — then
 * design 3's check list, which answers "can I reach my computer, and if
 * not, which step is wrong and what do I change". The list exists because
 * of that day: FlClash would not let the app bypass it, every packet rode
 * its tunnel with UDP blocked, chat never connected, and nothing on screen
 * said why.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TailscaleScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        TailscaleManager.init(context)
        TailscaleManager.refreshStatusNow()
    }
    val state by TailscaleManager.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tailscale") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Switch(
                        checked = state.enabled,
                        enabled = state.nativeAvailable,
                        onCheckedChange = { TailscaleManager.setEnabled(context, it) },
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .testTag("tailscaleEnableSwitch"),
                    )
                },
            )
        },
    ) { inner ->
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            if (!state.nativeAvailable) {
                Callout("这台设备的架构没有带 libattache_tsnet.so（需要 arm64-v8a），内置 Tailscale 用不了。")
            }
            IdentityCard(state)
            if (state.status.error.isNotEmpty()) Callout("错误：${state.status.error}")
            if (state.enabled) {
                SectionLabel("连接检查")
                CheckList(state)
                if (state.status.peers.isNotEmpty()) {
                    SectionLabel("设备 · ${state.status.peers.count { it.online }} 台在线")
                    PeerList(state.status.peers)
                }
            } else {
                Text(
                    "打开右上角的开关，Attaché 会在 app 里面连上你的 Tailnet，不影响手机上的其他 app。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            AdvancedSettings(state)
        }
    }
}

// ---------------------------------------------------------------- identity

/** Design 1's top card: who this node is on the tailnet. */
@Composable
private fun IdentityCard(state: TailscaleUiState) {
    val context = LocalContext.current
    val st = state.status
    val (dot, label) = when {
        !state.enabled -> Color(0xFF9E9EA6) to "已关闭"
        st.running -> Color(0xFF34A853) to "已连接"
        st.backendState == "NeedsLogin" -> Color(0xFFE8A200) to "等待登录"
        else -> Color(0xFF42A5F5) to "正在启动"
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceContainerHigh),
                ),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = dot)
        }
        Text(
            st.selfHostname.ifEmpty { state.hostname },
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (st.selfIpv4.isNotEmpty()) {
            Text(
                st.selfIpv4,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { copyToClipboard(context, "Tailscale IP", st.selfIpv4) },
            )
        }
        Spacer(Modifier.height(4.dp))
        // One line each, no separator between them (asked for).
        if (st.tailnetName.isNotEmpty()) {
            Text(st.tailnetName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (st.selfDnsName.isNotEmpty()) {
            Text(
                st.selfDnsName,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { copyToClipboard(context, "MagicDNS", st.selfDnsName) },
            )
        }
    }
}

// --------------------------------------------------------------- checklist

private enum class Mark { GOOD, BAD, WARN, PENDING }

private data class Check(
    val mark: Mark,
    val title: String,
    val detail: String = "",
    val mono: Boolean = false,
    /** What the step needs below it: a fix, or the login buttons. */
    val extra: (@Composable () -> Unit)? = null,
)

/** The four steps between this app and a computer on the tailnet. */
@Composable
private fun CheckList(state: TailscaleUiState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = state.status
    val net = state.network
    val bind = st.bind

    val login = when {
        st.running -> Check(Mark.GOOD, "Tailscale 已登录", st.tailnetName)
        st.backendState == "NeedsLogin" -> Check(Mark.BAD, "还没有登录 Tailscale", "用浏览器登录一次，之后会记住") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(
                    enabled = !state.isStartingLogin,
                    onClick = {
                        scope.launch {
                            val url = TailscaleManager.startInteractiveLogin(context)
                            if (url.isNotBlank()) {
                                openBrowserUrl(context, url)
                            } else {
                                Toast.makeText(context, "正在获取登录链接，请稍候再试…", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                ) {
                    if (state.isStartingLogin) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("用浏览器登录")
                }
                if (st.authUrl.isNotBlank()) {
                    OutlinedButton(onClick = { copyToClipboard(context, "Tailscale Auth URL", st.authUrl) }) {
                        Text("复制链接")
                    }
                }
            }
        }
        else -> Check(Mark.PENDING, "Tailscale 正在启动", st.backendState)
    }

    val physical = if (net.physical.isNotEmpty()) {
        Check(Mark.GOOD, "选到物理网络：${net.physical.substringBefore(" · ")}", net.physical.substringAfter(" · ", ""), mono = true)
    } else {
        Check(Mark.BAD, "没有可用的物理网络", "Wi-Fi 和移动数据都没有连上")
    }

    val vpnWhere = (listOf(net.vpnInterface.ifEmpty { "未知网卡" }) + net.vpnAddresses).joinToString(" · ")
    val blocked = net.vpnApplies && (bind.last == "failed" || (bind.last != "ok" && net.vpnBypassable == false))
    val vpn = when {
        !net.vpnApplies -> Check(Mark.GOOD, "没有 VPN 挡路", "Attaché 的流量不经过任何 VPN")
        blocked -> Check(
            Mark.BAD,
            "没能绕开 VPN",
            listOfNotNull(
                vpnWhere,
                if (net.vpnBypassable == false) "不允许绕开" else null,
                if (bind.failed > 0) "失败 ${bind.failed} 次" else null,
                bind.lastError.ifEmpty { null },
            ).joinToString(" · "),
            mono = true,
        ) {
            Callout(
                "Tailscale 只能经过这个 VPN 走，UDP 常常不通，所以连不上电脑或者很慢。" +
                    "请在这个 VPN app 里把 Attaché 设成不走代理，或者打开它的「允许应用绕过 VPN」，然后重新打开 Attaché。",
                action = "打开 VPN 设置" to { openVpnSettings(context) },
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        bind.last == "ok" -> Check(Mark.GOOD, "有 VPN，已经绕开", vpnWhere, mono = true)
        else -> Check(Mark.PENDING, "有 VPN，还不知道能不能绕开", vpnWhere, mono = true)
    }

    val active = st.peers.filter { it.online && it.active }
    val route = when {
        !st.running -> Check(Mark.PENDING, "到其他设备的线路", "登录以后再看")
        login.mark == Mark.BAD || physical.mark == Mark.BAD || vpn.mark == Mark.BAD -> Check(
            Mark.PENDING,
            "到其他设备的线路",
            "修好上面那一步以后再看" + (active.firstOrNull()?.let { "；现在 ${it.hostname} 走的是 ${TailscaleManager.formatPeerRoute(it)}" } ?: ""),
        )
        active.isEmpty() -> Check(Mark.PENDING, "还没有连过其他设备", "连上一台主机以后，这里会显示走的是哪条线路")
        active.any { it.direct.isBlank() && it.peerRelay.isBlank() } -> {
            val p = active.first { it.direct.isBlank() && it.peerRelay.isBlank() }
            Check(Mark.WARN, "${p.hostname} 只能走 DERP 中转（${p.relay}）", "没能直连，也没走 Peer Relay，会比较慢")
        }
        active.any { it.direct.isBlank() } -> {
            val p = active.first { it.direct.isBlank() }
            Check(Mark.GOOD, "经 Peer Relay 连到 ${p.hostname}", p.peerRelay.substringBefore(":vni:"), mono = true)
        }
        else -> {
            val p = active.first()
            Check(Mark.GOOD, "直连 ${p.hostname}", p.direct, mono = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        val steps = listOf(login, physical, vpn, route)
        steps.forEachIndexed { i, c -> CheckRow(c, last = i == steps.lastIndex) }
    }
}

@Composable
private fun CheckRow(c: Check, last: Boolean) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val faint = c.mark == Mark.PENDING
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawRail(if (last) null else line),
    ) {
        MarkDisc(c.mark)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(top = 3.dp, bottom = if (last) 0.dp else 14.dp)) {
            Text(
                c.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = when {
                    c.mark == Mark.BAD -> MaterialTheme.colorScheme.error
                    faint -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            if (c.detail.isNotEmpty()) {
                Text(
                    c.detail,
                    fontSize = 12.sp,
                    fontFamily = if (c.mono) FontFamily.Monospace else FontFamily.Default,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            c.extra?.invoke()
        }
    }
}

/** The vertical line joining one step's mark to the next. */
private fun Modifier.drawRail(color: Color?): Modifier = if (color == null) {
    this
} else {
    this.then(
        Modifier.drawBehind {
            val x = 13.dp.toPx()
            drawLine(color, Offset(x, 28.dp.toPx()), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        },
    )
}

@Composable
private fun MarkDisc(mark: Mark) {
    val (bg, glyph) = when (mark) {
        Mark.GOOD -> Color(0xFF34A853) to "✓"
        Mark.BAD -> MaterialTheme.colorScheme.error to "✕"
        Mark.WARN -> Color(0xFFE8A200) to "!"
        Mark.PENDING -> Color(0xFFB5B5BF) to "·"
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(26.dp).clip(CircleShape).background(bg),
    ) {
        Text(glyph, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

// ------------------------------------------------------------------- peers

@Composable
private fun PeerList(peers: List<TailscalePeer>) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        peers.sortedByDescending { it.online }.forEach { peer ->
            val ip = peer.ips.firstOrNull().orEmpty()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = ip.isNotEmpty()) { copyToClipboard(context, "Tailscale Peer IP", ip) }
                    .padding(vertical = 9.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (peer.online) Color(0xFF34A853) else Color(0xFFA3A3AD)))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        peer.hostname.ifBlank { peer.dnsName },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOf(TailscaleManager.formatPeerRoute(peer), ip).filter { it.isNotEmpty() }.joinToString(" · "),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- settings

@Composable
private fun AdvancedSettings(state: TailscaleUiState) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var hostname by remember(state.hostname) { mutableStateOf(state.hostname) }
    var authKey by remember(state.authKey) { mutableStateOf(state.authKey) }
    var controlUrl by remember(state.controlUrl) { mutableStateOf(state.controlUrl) }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            Text("高级设置", fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(if (open) "收起" else "节点名 · Auth Key · Headscale", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            ) {
                OutlinedTextField(hostname, { hostname = it }, label = { Text("节点名称 (Hostname)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(authKey, { authKey = it }, label = { Text("Auth Key（可选，如 tskey-auth-…）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(controlUrl, { controlUrl = it }, label = { Text("Control URL（可选，Headscale 地址）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.status.running) {
                        TextButton(onClick = { TailscaleManager.logout() }) {
                            Text("退出登录", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        TailscaleManager.updateConfig(context, hostname, authKey, controlUrl)
                        Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                    }) { Text("保存") }
                }
            }
        }
    }
}

// ----------------------------------------------------------------- helpers

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

/** A red box: something is wrong, and what to do about it. */
@Composable
private fun Callout(text: String, modifier: Modifier = Modifier, action: Pair<String, () -> Unit>? = null) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text, fontSize = 12.5.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onErrorContainer)
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                action.first,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(onClick = action.second)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

private fun openVpnSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, "打不开系统的 VPN 设置", Toast.LENGTH_SHORT).show()
    }
}

private fun openBrowserUrl(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        copyToClipboard(context, "Tailscale Auth URL", url)
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "已复制: $text", Toast.LENGTH_SHORT).show()
}
