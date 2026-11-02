package org.attache.tailscale

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * Compact Tailscale status pill that opens [TailscaleDialog] when tapped.
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

@Composable
fun TailscaleDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        TailscaleManager.init(context)
        TailscaleManager.refreshStatusNow()
    }
    val state by TailscaleManager.uiState.collectAsState()

    var hostname by remember(state.hostname) { mutableStateOf(state.hostname) }
    var authKey by remember(state.authKey) { mutableStateOf(state.authKey) }
    var controlUrl by remember(state.controlUrl) { mutableStateOf(state.controlUrl) }
    var showAdvanced by remember { mutableStateOf(state.authKey.isNotEmpty() || state.controlUrl.isNotEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Embedded Tailscale (tsnet)",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "仅作用于本 App · 自动分流 100.x / *.ts.net · 兼容全局 VPN",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.enabled,
                    enabled = state.nativeAvailable,
                    onCheckedChange = { enabled ->
                        TailscaleManager.updateConfig(context, hostname, authKey, controlUrl)
                        TailscaleManager.setEnabled(context, enabled)
                    },
                    modifier = Modifier.testTag("tailscaleEnableSwitch"),
                )
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (!state.nativeAvailable) {
                    Text(
                        text = "当前设备架构未包含 libattache_tsnet.so (需要 arm64-v8a)。",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                    )
                }

                // Status card
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(10.dp),
                ) {
                    val statusText = when {
                        !state.enabled -> "已关闭 (Stopped)"
                        state.status.running -> "已连接 (Running)"
                        state.status.backendState == "NeedsLogin" -> "等待登录授权 (NeedsLogin)"
                        else -> state.status.backendState
                    }
                    Text(
                        text = "状态: $statusText",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.5.sp,
                    )
                    if (state.status.selfIpv4.isNotEmpty()) {
                        Text(
                            text = "本机 Tailscale IP: ${state.status.selfIpv4}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.5.sp,
                        )
                    }
                    if (state.status.selfDnsName.isNotEmpty()) {
                        Text(
                            text = "MagicDNS: ${state.status.selfDnsName}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.status.tailnetName.isNotEmpty()) {
                        Text(
                            text = "Tailnet: ${state.status.tailnetName}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.status.error.isNotEmpty()) {
                        Text(
                            text = "错误: ${state.status.error}",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                        )
                    }
                }

                // Login / Logout actions
                if (state.enabled) {
                    if (!state.status.running) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Button(
                                enabled = !state.isStartingLogin,
                                onClick = {
                                    scope.launch {
                                        TailscaleManager.updateConfig(context, hostname, authKey, controlUrl)
                                        val url = TailscaleManager.startInteractiveLogin(context)
                                        if (url.isNotBlank()) {
                                            openBrowserUrl(context, url)
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "正在获取 Tailscale 授权链接，请稍候再试…",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                if (state.isStartingLogin) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text("浏览器登录授权")
                            }
                            if (state.status.authUrl.isNotBlank()) {
                                OutlinedButton(
                                    onClick = {
                                        copyToClipboard(context, "Tailscale Auth URL", state.status.authUrl)
                                    },
                                ) {
                                    Text("复制链接")
                                }
                            }
                        }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "已发现 ${state.status.peers.size} 台 Tailnet 设备",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            TextButton(onClick = { TailscaleManager.logout() }) {
                                Text("退出登录 (Logout)", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                // Peers list when running
                if (state.status.peers.isNotEmpty()) {
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (peer in state.status.peers) {
                            val primaryIp = peer.ips.firstOrNull().orEmpty()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                    .clickable {
                                        if (primaryIp.isNotEmpty()) {
                                            copyToClipboard(context, "Tailscale Peer IP", primaryIp)
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (peer.online) Color(0xFF4CAF50) else Color(0xFF7A7D85)),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = peer.hostname.ifBlank { peer.dnsName },
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val routeDesc = TailscaleManager.formatPeerRoute(peer)
                                    Text(
                                        text = "$primaryIp · $routeDesc",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = "复制 IP",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }

                // Advanced settings (Hostname / AuthKey / Headscale Control URL)
                HorizontalDivider()
                TextButton(
                    onClick = { showAdvanced = !showAdvanced },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (showAdvanced) "收起高级设置 (节点名 / Auth Key / Headscale)" else "展开高级设置 (节点名 / Auth Key / Headscale)")
                }

                if (showAdvanced) {
                    OutlinedTextField(
                        value = hostname,
                        onValueChange = { hostname = it },
                        label = { Text("节点名称 (Hostname)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = authKey,
                        onValueChange = { authKey = it },
                        label = { Text("Auth Key (可选, 如 tskey-auth-...)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = controlUrl,
                        onValueChange = { controlUrl = it },
                        label = { Text("Control URL (可选, Headscale 地址)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    TailscaleManager.updateConfig(context, hostname, authKey, controlUrl)
                    onDismiss()
                },
            ) {
                Text("保存并关闭")
            }
        },
    )
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
