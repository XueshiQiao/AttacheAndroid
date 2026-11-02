package org.attache.tailscale

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

data class TailscalePeer(
    val hostname: String,
    val dnsName: String,
    val ips: List<String>,
    val os: String,
    val online: Boolean,
    val active: Boolean,
    val relay: String,
    val peerRelay: String = "",
    val direct: String,
)

data class TailscaleStatus(
    val running: Boolean = false,
    val backendState: String = "Stopped",
    val authUrl: String = "",
    val selfHostname: String = "",
    val selfDnsName: String = "",
    val selfIpv4: String = "",
    val selfIpv6: String = "",
    val tailnetName: String = "",
    val magicDnsSuffix: String = "",
    val peers: List<TailscalePeer> = emptyList(),
    val health: List<String> = emptyList(),
    val error: String = "",
)

data class TailscaleUiState(
    val enabled: Boolean = false,
    val nativeAvailable: Boolean = TsnetNative.isAvailable,
    val hostname: String = TailscaleManager.defaultHostname(),
    val authKey: String = "",
    val controlUrl: String = "",
    val isStartingLogin: Boolean = false,
    val status: TailscaleStatus = TailscaleStatus(),
)

/**
 * App-level embedded Tailscale (`tsnet`) manager.
 *
 * Runs entirely in userspace inside AttacheAndroid without Android `VpnService`:
 * - Does not affect other apps on the phone/tablet.
 * - Binds underlying WireGuard/DERP sockets to the physical non-VPN network
 *   (`NET_CAPABILITY_NOT_VPN`) so it coexists cleanly with system-wide VPNs.
 * - Automatically routes SSH connections targeting Tailscale IPs (`100.64.0.0/10`,
 *   `fd7a:115c:a1e0::/48`), MagicDNS names (`*.ts.net`), or tailnet peer hostnames
 *   through a local loopback forwarder backed by `tsnet.Server.Dial`.
 */
object TailscaleManager {
    const val PREF_ENABLED = "attache_tailscale_enabled"
    const val PREF_HOSTNAME = "attache_tailscale_hostname"
    const val PREF_AUTH_KEY = "attache_tailscale_auth_key"
    const val PREF_CONTROL_URL = "attache_tailscale_control_url"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var networkCallbackRegistered = false

    private val availablePhysicalNetworks = ConcurrentHashMap<Network, Boolean>()

    private var pollJob: Job? = null

    private val _uiState = MutableStateFlow(TailscaleUiState())
    val uiState: StateFlow<TailscaleUiState> = _uiState.asStateFlow()

    fun defaultHostname(): String {
        val rawModel = runCatching { Build.MODEL }.getOrNull().orEmpty()
        val sanitized = rawModel
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return if (sanitized.isNotEmpty()) "attache-$sanitized" else "attache-android"
    }

    @Synchronized
    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        prefs = sp

        val enabled = sp.getBoolean(PREF_ENABLED, false)
        val hostname = sp.getString(PREF_HOSTNAME, null)?.takeIf { it.isNotBlank() } ?: defaultHostname()
        val authKey = sp.getString(PREF_AUTH_KEY, null).orEmpty()
        val controlUrl = sp.getString(PREF_CONTROL_URL, null).orEmpty()

        _uiState.update {
            it.copy(
                enabled = enabled,
                nativeAvailable = TsnetNative.isAvailable,
                hostname = hostname,
                authKey = authKey,
                controlUrl = controlUrl,
            )
        }

        registerPhysicalNetworkCallback(ctx)

        if (enabled && TsnetNative.isAvailable) {
            scope.launch {
                startInternal(ctx, hostname, authKey, controlUrl)
            }
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        if (appContext == null) {
            init(context)
        }
        val ctx = appContext ?: context.applicationContext
        prefs?.edit()?.putBoolean(PREF_ENABLED, enabled)?.apply()
        _uiState.update { it.copy(enabled = enabled) }

        scope.launch {
            if (enabled) {
                val state = _uiState.value
                startInternal(ctx, state.hostname, state.authKey, state.controlUrl)
            } else {
                stopInternal()
            }
        }
    }

    fun updateConfig(
        context: Context,
        hostname: String,
        authKey: String,
        controlUrl: String,
    ) {
        if (appContext == null) {
            init(context)
        }
        val ctx = appContext ?: context.applicationContext
        val cleanHostname = hostname.trim().ifEmpty { defaultHostname() }
        val cleanAuthKey = authKey.trim()
        val cleanControlUrl = controlUrl.trim()

        prefs?.edit()
            ?.putString(PREF_HOSTNAME, cleanHostname)
            ?.putString(PREF_AUTH_KEY, cleanAuthKey)
            ?.putString(PREF_CONTROL_URL, cleanControlUrl)
            ?.apply()

        _uiState.update {
            it.copy(
                hostname = cleanHostname,
                authKey = cleanAuthKey,
                controlUrl = cleanControlUrl,
            )
        }

        if (_uiState.value.enabled && TsnetNative.isAvailable) {
            scope.launch {
                startInternal(ctx, cleanHostname, cleanAuthKey, cleanControlUrl)
            }
        }
    }

    suspend fun startInteractiveLogin(context: Context): String = withContext(Dispatchers.IO) {
        if (appContext == null) {
            init(context)
        }
        if (!TsnetNative.isAvailable) return@withContext ""
        val ctx = appContext ?: context.applicationContext
        if (!_uiState.value.enabled) {
            prefs?.edit()?.putBoolean(PREF_ENABLED, true)?.apply()
            _uiState.update { it.copy(enabled = true) }
        }
        _uiState.update { it.copy(isStartingLogin = true) }
        try {
            val s = _uiState.value
            startInternal(ctx, s.hostname, s.authKey, s.controlUrl)
            val url = TsnetNative.nativeStartLoginInteractive()
            refreshStatusNow()
            url.ifBlank { _uiState.value.status.authUrl }
        } finally {
            _uiState.update { it.copy(isStartingLogin = false) }
        }
    }

    fun logout() {
        if (!TsnetNative.isAvailable) return
        scope.launch {
            val err = TsnetNative.nativeLogout()
            if (err.isNotEmpty()) {
                Timber.w("Tailscale logout warning: %s", err)
            }
            // Clear saved one-time auth key if any so it doesn't immediately re-register with a consumed key.
            prefs?.edit()?.putString(PREF_AUTH_KEY, "")?.apply()
            _uiState.update { it.copy(authKey = "") }
            refreshStatusNow()
        }
    }

    fun onHostNetworkChanged() {
        if (!TsnetNative.isAvailable || !_uiState.value.enabled) return
        scope.launch {
            val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                selectAndBindBestPhysicalNetwork(cm)
            } else {
                pushAndroidInterfaces()
            }
            refreshStatusNow()
        }
    }

    /**
     * Determines whether an SSH connection to [hostname] should be routed through
     * the embedded `tsnet` userspace stack.
     */
    fun shouldRouteViaTailscale(hostname: String): Boolean {
        val state = _uiState.value
        if (!state.enabled || !TsnetNative.isAvailable) return false

        // Fast check on IP ranges and *.ts.net first without blocking.
        if (isTailscaleAddressOrDomain(hostname, state.status.magicDnsSuffix)) {
            return true
        }

        // If peers list is empty because tsnet just started, refresh status once.
        if (state.status.peers.isEmpty()) {
            refreshStatusNow()
        }
        val latest = _uiState.value.status
        return isTailscaleTarget(hostname, latest.peers, latest.magicDnsSuffix)
    }

    /**
     * Opens a local `127.0.0.1:<port>` TCP forwarder that dials `targetHost:targetPort`
     * over the embedded `tsnet` stack.
     *
     * Returns the local port (`> 0`), or `< 0` if `tsnet` could not open the forwarder.
     */
    suspend fun openForwarder(targetHost: String, targetPort: Int): Int = withContext(Dispatchers.IO) {
        if (!TsnetNative.isAvailable || !_uiState.value.enabled) {
            return@withContext -1
        }
        val ctx = appContext
        if (ctx != null && !_uiState.value.status.running) {
            val s = _uiState.value
            startInternal(ctx, s.hostname, s.authKey, s.controlUrl)
            // Wait up to 6 seconds for tsnet backend to reach Running if it's Starting.
            repeat(12) {
                refreshStatusNow()
                val st = _uiState.value.status.backendState
                if (st == "Running" || st == "NeedsLogin" || st == "NeedsMachineAuth") {
                    return@repeat
                }
                delay(500)
            }
        }
        TsnetNative.nativeOpenTcpForwarder(targetHost, targetPort)
    }

    fun closeForwarder(localPort: Int) {
        if (localPort <= 0 || !TsnetNative.isAvailable) return
        scope.launch {
            TsnetNative.nativeCloseTcpForwarder(localPort)
        }
    }

    private suspend fun startInternal(
        context: Context,
        hostname: String,
        authKey: String,
        controlUrl: String,
    ) = withContext(Dispatchers.IO) {
        if (!TsnetNative.isAvailable) return@withContext
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            selectAndBindBestPhysicalNetwork(cm)
        } else {
            pushAndroidInterfaces()
        }
        val stateDir = File(context.filesDir, "tailscale").apply { mkdirs() }.absolutePath
        val err = TsnetNative.nativeStart(stateDir, hostname, authKey, controlUrl)
        if (err.isNotEmpty()) {
            Timber.e("Tailscale nativeStart failed: %s", err)
        }
        refreshStatusNow()
        ensurePolling()
    }

    private suspend fun stopInternal() = withContext(Dispatchers.IO) {
        pollJob?.cancel()
        pollJob = null
        if (TsnetNative.isAvailable) {
            TsnetNative.nativeStop()
        }
        _uiState.update {
            it.copy(status = TailscaleStatus(backendState = "Stopped"))
        }
    }

    private fun ensurePolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive && _uiState.value.enabled) {
                refreshStatusNow()
                val st = _uiState.value.status.backendState
                val intervalMs = if (st == "Starting" || st == "NeedsLogin") 1500L else 3000L
                delay(intervalMs)
            }
        }
    }

    fun refreshStatusNow() {
        if (!TsnetNative.isAvailable) return
        val raw = runCatching { TsnetNative.nativeStatusJson() }.getOrDefault("")
        if (raw.isBlank()) return
        val parsed = parseStatusJson(raw)
        _uiState.update { it.copy(status = parsed) }
    }

    internal fun parseStatusJson(raw: String): TailscaleStatus {
        return try {
            val obj = JSONObject(raw)
            val peersArr = obj.optJSONArray("peers")
            val peers = buildList {
                if (peersArr != null) {
                    for (i in 0 until peersArr.length()) {
                        val p = peersArr.optJSONObject(i) ?: continue
                        val ipsArr = p.optJSONArray("ips")
                        val ips = buildList {
                            if (ipsArr != null) {
                                for (j in 0 until ipsArr.length()) {
                                    val ip = ipsArr.optString(j)
                                    if (ip.isNotBlank()) add(ip)
                                }
                            }
                        }
                        add(
                            TailscalePeer(
                                hostname = p.optString("hostname"),
                                dnsName = p.optString("dnsName"),
                                ips = ips,
                                os = p.optString("os"),
                                online = p.optBoolean("online"),
                                active = p.optBoolean("active"),
                                relay = p.optString("relay"),
                                peerRelay = p.optString("peerRelay"),
                                direct = p.optString("direct"),
                            )
                        )
                    }
                }
            }
            val healthArr = obj.optJSONArray("health")
            val health = buildList {
                if (healthArr != null) {
                    for (i in 0 until healthArr.length()) {
                        val h = healthArr.optString(i)
                        if (h.isNotBlank()) add(h)
                    }
                }
            }
            TailscaleStatus(
                running = obj.optBoolean("running"),
                backendState = obj.optString("backendState", "Stopped"),
                authUrl = obj.optString("authUrl"),
                selfHostname = obj.optString("selfHostname"),
                selfDnsName = obj.optString("selfDnsName"),
                selfIpv4 = obj.optString("selfIpv4"),
                selfIpv6 = obj.optString("selfIpv6"),
                tailnetName = obj.optString("tailnetName"),
                magicDnsSuffix = obj.optString("magicDnsSuffix"),
                peers = peers,
                health = health,
                error = obj.optString("error"),
            )
        } catch (t: Throwable) {
            Timber.w(t, "Failed to parse Tailscale status JSON")
            TailscaleStatus(error = t.message.orEmpty())
        }
    }

    private fun registerPhysicalNetworkCallback(context: Context) {
        if (networkCallbackRegistered || !TsnetNative.isAvailable) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()

            cm.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        availablePhysicalNetworks[network] = true
                        scope.launch {
                            selectAndBindBestPhysicalNetwork(cm)
                        }
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                            networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        ) {
                            availablePhysicalNetworks[network] = true
                        } else {
                            availablePhysicalNetworks.remove(network)
                        }
                        scope.launch {
                            selectAndBindBestPhysicalNetwork(cm)
                        }
                    }

                    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                        availablePhysicalNetworks[network] = true
                        scope.launch {
                            selectAndBindBestPhysicalNetwork(cm)
                        }
                    }

                    override fun onLost(network: Network) {
                        availablePhysicalNetworks.remove(network)
                        scope.launch {
                            selectAndBindBestPhysicalNetwork(cm)
                        }
                    }
                }
            )
            networkCallbackRegistered = true
        } catch (t: Throwable) {
            Timber.w(t, "Failed to register non-VPN physical network callback")
        }
    }

    private fun selectAndBindBestPhysicalNetwork(cm: ConnectivityManager) {
        try {
            val candidates = LinkedHashSet<Network>(availablePhysicalNetworks.keys)
            runCatching { cm.allNetworks }.getOrNull()?.forEach { net ->
                candidates.add(net)
            }

            var bestNetwork: Network? = null
            var bestScore = -1

            for (net in candidates) {
                val caps = runCatching { cm.getNetworkCapabilities(net) }.getOrNull() ?: continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                val lp = runCatching { cm.getLinkProperties(net) }.getOrNull() ?: continue
                val ifName = lp.interfaceName.orEmpty()
                if (ifName.startsWith("tun") || ifName.startsWith("ppp")) continue

                val hasDefaultRoute = lp.routes.any { it.isDefaultRoute }
                // Only consider cellular networks that carry a default internet route (skip IMS-only rmnet interfaces).
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !hasDefaultRoute) {
                    continue
                }

                var score = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 300
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 200
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 100
                    else -> 50
                }
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    score += 20
                }
                if (hasDefaultRoute) {
                    score += 10
                }

                if (score > bestScore) {
                    bestScore = score
                    bestNetwork = net
                }
            }

            if (bestNetwork != null) {
                val lp = cm.getLinkProperties(bestNetwork)
                val ifName = lp?.interfaceName.orEmpty()
                val gw = lp?.routes
                    ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                    ?.gateway
                    ?.hostAddress
                    .orEmpty()
                TsnetNative.nativeSetNetworkHandle(bestNetwork.networkHandle, ifName, gw)
            } else {
                TsnetNative.nativeSetNetworkHandle(0L, "", "")
            }
            pushAndroidInterfaces()
        } catch (t: Throwable) {
            Timber.w(t, "Failed to update physical network handle for tsnet")
        }
    }

    /**
     * Formats the active connection path of a [TailscalePeer] for UI display,
     * matching Tailscale CLI's `tailscale status` semantics:
     * - Offline when `!peer.online` (even though `peer.relay` still holds its static home DERP region).
     * - Direct UDP (`peer.direct`), Peer Relay (`peer.peerRelay`), or DERP (`peer.relay`) when active.
     * - Idle when online with no active tunnel traffic.
     */
    internal fun formatPeerRoute(peer: TailscalePeer): String {
        if (!peer.online) {
            return "离线"
        }
        if (peer.direct.isNotBlank()) {
            return "直连 (${peer.direct})"
        }
        if (peer.peerRelay.isNotBlank()) {
            val endpoint = peer.peerRelay.substringBefore(":vni:")
            return "PeerRelay ($endpoint)"
        }
        if (peer.active && peer.relay.isNotBlank()) {
            return "DERP (${peer.relay})"
        }
        if (peer.active) {
            return "在线 · 活跃"
        }
        return "在线 · 空闲"
    }

    private fun pushAndroidInterfaces() {
        if (!TsnetNative.isAvailable) return
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val sb = StringBuilder()
            for (nif in interfaces) {
                val name = nif.name ?: continue
                // Skip Android VPN tun interfaces so tsnet never attempts to use a local VPN tunnel as an endpoint.
                if (name.startsWith("tun") || name.startsWith("ppp")) continue
                val up = if (runCatching { nif.isUp }.getOrDefault(false)) "1" else "0"
                val loopback = if (runCatching { nif.isLoopback }.getOrDefault(false)) "1" else "0"
                val broadcast = "1"
                val ptp = if (runCatching { nif.isPointToPoint }.getOrDefault(false)) "1" else "0"
                val multicast = if (runCatching { nif.supportsMulticast() }.getOrDefault(false)) "1" else "0"
                val mtu = runCatching { nif.mtu }.getOrDefault(1500).coerceAtLeast(1280)
                val cidrs = nif.interfaceAddresses.mapNotNull { ia ->
                    val hostAddr = ia.address?.hostAddress?.substringBefore('%') ?: return@mapNotNull null
                    val prefix = ia.networkPrefixLength.toInt()
                    if (prefix > 0) "$hostAddr/$prefix" else hostAddr
                }
                if (cidrs.isEmpty()) continue
                sb.append(name).append('|')
                    .append(nif.index).append('|')
                    .append(mtu).append('|')
                    .append(up).append('|')
                    .append(loopback).append('|')
                    .append(broadcast).append('|')
                    .append(ptp).append('|')
                    .append(multicast).append('|')
                    .append(cidrs.joinToString(","))
                    .append('\n')
            }
            TsnetNative.nativeUpdateInterfaces(sb.toString())
        } catch (t: Throwable) {
            Timber.w(t, "Failed to enumerate network interfaces for tsnet")
        }
    }

    /**
     * Pure helper checking if [hostname] is a Tailscale IP (`100.64.0.0/10` or
     * `fd7a:115c:a1e0::/48`) or MagicDNS domain (`*.ts.net` / `*.<magicDnsSuffix>`).
     */
    internal fun isTailscaleAddressOrDomain(
        hostname: String,
        magicDnsSuffix: String = "",
    ): Boolean {
        val clean = hostname.trim().removeSurrounding("[", "]").trimEnd('.').lowercase()
        if (clean.isEmpty() || clean == "localhost" || clean == "127.0.0.1" || clean == "::1") {
            return false
        }

        // 1. Check Tailscale IPv4 CGNAT 100.64.0.0/10 (100.64.0.0 - 100.127.255.255)
        val ipv4Parts = clean.split('.')
        if (ipv4Parts.size == 4 && ipv4Parts.all { it.toIntOrNull() in 0..255 }) {
            val o1 = ipv4Parts[0].toInt()
            val o2 = ipv4Parts[1].toInt()
            return o1 == 100 && o2 in 64..127
        }

        // 2. Check Tailscale IPv6 ULA fd7a:115c:a1e0::/48
        if (clean.startsWith("fd7a:115c:a1e0:")) {
            return true
        }

        // 3. Check MagicDNS domains (*.ts.net, *.beta.tailscale.net, or custom magicDnsSuffix)
        if (clean.endsWith(".ts.net") || clean.endsWith(".beta.tailscale.net")) {
            return true
        }
        val suffix = magicDnsSuffix.trim().trim('.').lowercase()
        if (suffix.isNotEmpty() && clean.endsWith(".$suffix")) {
            return true
        }

        return false
    }

    /**
     * Pure routing predicate determining whether [hostname] belongs to the user's tailnet.
     */
    internal fun isTailscaleTarget(
        hostname: String,
        peers: List<TailscalePeer>,
        magicDnsSuffix: String = "",
    ): Boolean {
        if (isTailscaleAddressOrDomain(hostname, magicDnsSuffix)) {
            return true
        }
        val clean = hostname.trim().removeSurrounding("[", "]").trimEnd('.').lowercase()
        if (clean.isEmpty()) return false

        for (peer in peers) {
            if (peer.hostname.lowercase() == clean) return true
            val dns = peer.dnsName.trimEnd('.').lowercase()
            if (dns.isNotEmpty()) {
                if (dns == clean) return true
                val shortDns = dns.substringBefore('.')
                if (shortDns == clean) return true
            }
            if (peer.ips.any { it.lowercase() == clean }) return true
        }
        return false
    }
}
