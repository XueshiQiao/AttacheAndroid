package org.attache.tailscale

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TailscaleManagerTest {

    private val samplePeers = listOf(
        TailscalePeer(
            hostname = "mac-studio",
            dnsName = "mac-studio.tail1234.ts.net",
            ips = listOf("100.101.102.103", "fd7a:115c:a1e0::1234"),
            os = "macOS",
            online = true,
            active = true,
            relay = "",
            direct = "192.168.1.10:41641",
        ),
        TailscalePeer(
            hostname = "homelab-nas",
            dnsName = "homelab-nas.tail1234.ts.net",
            ips = listOf("100.88.1.20"),
            os = "linux",
            online = false,
            active = false,
            relay = "tok",
            direct = "",
        ),
    )

    @Test
    fun `routes Tailscale CGNAT IPv4 100_64_0_0 slash 10 via Tailscale`() {
        assertTrue(TailscaleManager.isTailscaleTarget("100.64.0.1", emptyList()))
        assertTrue(TailscaleManager.isTailscaleTarget("100.100.100.100", emptyList()))
        assertTrue(TailscaleManager.isTailscaleTarget("100.127.255.254", emptyList()))

        // Outside 100.64.0.0/10
        assertFalse(TailscaleManager.isTailscaleTarget("100.63.255.255", emptyList()))
        assertFalse(TailscaleManager.isTailscaleTarget("100.128.0.1", emptyList()))
    }

    @Test
    fun `does not route LAN or public IPs via Tailscale`() {
        assertFalse(TailscaleManager.isTailscaleTarget("192.168.1.10", samplePeers))
        assertFalse(TailscaleManager.isTailscaleTarget("10.0.0.15", samplePeers))
        assertFalse(TailscaleManager.isTailscaleTarget("172.16.0.5", samplePeers))
        assertFalse(TailscaleManager.isTailscaleTarget("127.0.0.1", samplePeers))
        assertFalse(TailscaleManager.isTailscaleTarget("localhost", samplePeers))
        assertFalse(TailscaleManager.isTailscaleTarget("1.1.1.1", samplePeers))
    }

    @Test
    fun `routes Tailscale IPv6 ULA via Tailscale`() {
        assertTrue(TailscaleManager.isTailscaleTarget("fd7a:115c:a1e0::1", emptyList()))
        assertTrue(TailscaleManager.isTailscaleTarget("[FD7A:115C:A1E0:ab12::99]", emptyList()))
        assertFalse(TailscaleManager.isTailscaleTarget("fe80::1", emptyList()))
        assertFalse(TailscaleManager.isTailscaleTarget("2001:4860:4860::8888", emptyList()))
    }

    @Test
    fun `routes MagicDNS domains and known peer hostnames via Tailscale`() {
        assertTrue(TailscaleManager.isTailscaleTarget("mac-studio.tail1234.ts.net", emptyList()))
        assertTrue(TailscaleManager.isTailscaleTarget("mac-studio.tail1234.ts.net.", emptyList()))
        assertTrue(TailscaleManager.isTailscaleTarget("node.custom-tailnet.example.org", emptyList(), "custom-tailnet.example.org"))

        // Short hostname matching known tailnet peer
        assertTrue(TailscaleManager.isTailscaleTarget("mac-studio", samplePeers))
        assertTrue(TailscaleManager.isTailscaleTarget("MAC-STUDIO", samplePeers))
        assertTrue(TailscaleManager.isTailscaleTarget("homelab-nas", samplePeers))

        // Public SSH hosts are not routed via Tailscale
        assertFalse(TailscaleManager.isTailscaleTarget("github.com", samplePeers))
        assertFalse(TailscaleManager.isTailscaleTarget("prod.mycompany.com", samplePeers))
    }

    @Test
    fun `parses native status JSON accurately`() {
        val raw = """
            {
              "running": true,
              "backendState": "Running",
              "authUrl": "",
              "selfHostname": "attache-pixel",
              "selfDnsName": "attache-pixel.tail1234.ts.net",
              "selfIpv4": "100.77.88.99",
              "selfIpv6": "fd7a:115c:a1e0::99",
              "tailnetName": "user@example.com",
              "magicDnsSuffix": "tail1234.ts.net",
              "peers": [
                {
                  "hostname": "mac-studio",
                  "dnsName": "mac-studio.tail1234.ts.net",
                  "ips": ["100.101.102.103"],
                  "os": "macOS",
                  "online": true,
                  "active": true,
                  "relay": "hkg",
                  "peerRelay": "203.0.113.10:40000:vni:123",
                  "direct": ""
                }
              ],
              "health": [],
              "error": ""
            }
        """.trimIndent()

        val st = TailscaleManager.parseStatusJson(raw)
        assertTrue(st.running)
        assertEquals("Running", st.backendState)
        assertEquals("100.77.88.99", st.selfIpv4)
        assertEquals("tail1234.ts.net", st.magicDnsSuffix)
        assertEquals(1, st.peers.size)
        assertEquals("mac-studio", st.peers[0].hostname)
        assertEquals(listOf("100.101.102.103"), st.peers[0].ips)
        assertTrue(st.peers[0].online)
        assertEquals("203.0.113.10:40000:vni:123", st.peers[0].peerRelay)
        assertEquals("PeerRelay (203.0.113.10:40000)", TailscaleManager.formatPeerRoute(st.peers[0]))
    }

    @Test
    fun `formats peer route according to Tailscale status priority`() {
        val offlineWithHomeRelay = samplePeers[1]
        assertEquals("离线", TailscaleManager.formatPeerRoute(offlineWithHomeRelay))

        val idleWithHomeRelay = offlineWithHomeRelay.copy(online = true, active = false)
        assertEquals("在线 · 空闲", TailscaleManager.formatPeerRoute(idleWithHomeRelay))

        val activeDerp = offlineWithHomeRelay.copy(online = true, active = true)
        assertEquals("DERP (tok)", TailscaleManager.formatPeerRoute(activeDerp))

        val activePeerRelay = activeDerp.copy(peerRelay = "198.51.100.7:40000:vni:9")
        assertEquals("PeerRelay (198.51.100.7:40000)", TailscaleManager.formatPeerRoute(activePeerRelay))

        val activeDirect = samplePeers[0]
        assertEquals("直连 (192.168.1.10:41641)", TailscaleManager.formatPeerRoute(activeDirect))
    }
}
