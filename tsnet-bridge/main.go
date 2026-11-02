package main

/*
#cgo android LDFLAGS: -landroid -llog

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#ifdef __ANDROID__
#include <jni.h>
#include <android/log.h>
#include <android/multinetwork.h>

static const char* jstring_to_utf8(JNIEnv* env, jstring jstr) {
    if (jstr == NULL) return NULL;
    return (*env)->GetStringUTFChars(env, jstr, NULL);
}

static void release_utf8(JNIEnv* env, jstring jstr, const char* utf8) {
    if (jstr != NULL && utf8 != NULL) {
        (*env)->ReleaseStringUTFChars(env, jstr, utf8);
    }
}

static jstring utf8_to_jstring(JNIEnv* env, const char* utf8) {
    if (utf8 == NULL) return (*env)->NewStringUTF(env, "");
    return (*env)->NewStringUTF(env, utf8);
}

static int bind_socket_to_network(uint64_t net_handle, int fd) {
    if (net_handle == 0) return 0;
    return android_setsocknetwork((net_handle_t)net_handle, fd);
}

static void log_android(const char* msg) {
    __android_log_write(ANDROID_LOG_INFO, "AttacheTsnet", msg);
}
#else
typedef void* JNIEnv;
typedef void* jclass;
typedef uintptr_t jstring;
typedef int32_t jint;
typedef int64_t jlong;

static const char* jstring_to_utf8(JNIEnv* env, jstring jstr) {
    (void)env;
    return (const char*)jstr;
}

static void release_utf8(JNIEnv* env, jstring jstr, const char* utf8) {
    (void)env;
    (void)jstr;
    (void)utf8;
}

static jstring utf8_to_jstring(JNIEnv* env, const char* utf8) {
    (void)env;
    return (jstring)(uintptr_t)strdup(utf8 ? utf8 : "");
}

static int bind_socket_to_network(uint64_t net_handle, int fd) {
    (void)net_handle;
    (void)fd;
    return 0;
}

static void log_android(const char* msg) {
    (void)msg;
}
#endif
*/
import "C"

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	"tailscale.com/client/local"
	"tailscale.com/ipn"
	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

var (
	srvMu         sync.Mutex
	srv           *tsnet.Server
	srvCancel     context.CancelFunc
	lastInitErr   string
	latestAuthURL atomic.Value // string

	activeNetworkHandle atomic.Uint64

	ifacesMu     sync.RWMutex
	cachedIfaces []netmon.Interface

	forwardersMu sync.Mutex
	forwarders   = make(map[int]net.Listener)
)

func init() {
	latestAuthURL.Store("")
	netmon.RegisterInterfaceGetter(getAndroidInterfaces)
	registerPlatformHooks(func(fd int) error {
		handle := activeNetworkHandle.Load()
		if handle == 0 {
			return nil
		}
		// Best-effort bind to the physical non-VPN network handle.
		// If a strict lockdown VPN denies binding (EPERM), do not fail the socket
		// so tsnet can still fall back to dialing through the default network/DERP.
		_ = C.bind_socket_to_network(C.uint64_t(handle), C.int(fd))
		return nil
	})
}

func logf(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	cmsg := C.CString(msg)
	C.log_android(cmsg)
	C.free(unsafe.Pointer(cmsg))
}

func goString(env *C.JNIEnv, jstr C.jstring) string {
	if jstr == 0 {
		return ""
	}
	utf8 := C.jstring_to_utf8(env, jstr)
	if utf8 == nil {
		return ""
	}
	defer C.release_utf8(env, jstr, utf8)
	return C.GoString(utf8)
}

func jString(env *C.JNIEnv, s string) C.jstring {
	cs := C.CString(s)
	defer C.free(unsafe.Pointer(cs))
	return C.utf8_to_jstring(env, cs)
}

func getAndroidInterfaces() ([]netmon.Interface, error) {
	ifacesMu.RLock()
	cloned := make([]netmon.Interface, len(cachedIfaces))
	copy(cloned, cachedIfaces)
	ifacesMu.RUnlock()

	if len(cloned) > 0 {
		return cloned, nil
	}
	if f, ok := netmon.HookInterfacesFallback.GetOk(); ok {
		if ret, err := f(); err == nil && len(ret) > 0 {
			return ret, nil
		}
	}
	ifs, err := net.Interfaces()
	if err != nil {
		return nil, err
	}
	ret := make([]netmon.Interface, len(ifs))
	for i := range ifs {
		ret[i].Interface = &ifs[i]
	}
	return ret, nil
}

// parseInterfacesSpec parses lines of:
// name|index|mtu|isUp|isLoopback|isBroadcast|isPointToPoint|isMulticast|cidr1,cidr2,...
func parseInterfacesSpec(spec string) []netmon.Interface {
	lines := strings.Split(strings.TrimSpace(spec), "\n")
	var out []netmon.Interface
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		parts := strings.Split(line, "|")
		if len(parts) < 9 {
			continue
		}
		name := parts[0]
		idx, _ := strconv.Atoi(parts[1])
		mtu, _ := strconv.Atoi(parts[2])
		if mtu <= 0 {
			mtu = 1500
		}
		var flags net.Flags
		if parts[3] == "1" {
			flags |= net.FlagUp | net.FlagRunning
		}
		if parts[4] == "1" {
			flags |= net.FlagLoopback
		}
		if parts[5] == "1" {
			flags |= net.FlagBroadcast
		}
		if parts[6] == "1" {
			flags |= net.FlagPointToPoint
		}
		if parts[7] == "1" {
			flags |= net.FlagMulticast
		}
		var addrs []net.Addr
		for _, cidr := range strings.Split(parts[8], ",") {
			cidr = strings.TrimSpace(cidr)
			if cidr == "" {
				continue
			}
			if pfx, err := netip.ParsePrefix(cidr); err == nil {
				addr := pfx.Addr().Unmap()
				bits := 32
				if addr.Is6() {
					bits = 128
				}
				addrs = append(addrs, &net.IPNet{
					IP:   addr.AsSlice(),
					Mask: net.CIDRMask(pfx.Bits(), bits),
				})
			} else if ip, err := netip.ParseAddr(cidr); err == nil {
				ip = ip.Unmap()
				bits := 32
				if ip.Is6() {
					bits = 128
				}
				addrs = append(addrs, &net.IPNet{
					IP:   ip.AsSlice(),
					Mask: net.CIDRMask(bits, bits),
				})
			}
		}
		out = append(out, netmon.Interface{
			Interface: &net.Interface{
				Index: idx,
				MTU:   mtu,
				Name:  name,
				Flags: flags,
			},
			AltAddrs: addrs,
		})
	}
	return out
}

func injectNetMonEvent() {
	srvMu.Lock()
	s := srv
	srvMu.Unlock()
	if s == nil {
		return
	}
	sys := s.Sys()
	if sys == nil {
		return
	}
	if nm, ok := sys.NetMon.GetOK(); ok && nm != nil {
		nm.InjectEvent()
	}
}

func watchIPNBus(ctx context.Context, lc *local.Client) {
	for {
		if ctx.Err() != nil {
			return
		}
		watcher, err := lc.WatchIPNBus(ctx, ipn.NotifyInitialState)
		if err != nil {
			select {
			case <-ctx.Done():
				return
			case <-time.After(2 * time.Second):
				continue
			}
		}
		for {
			n, err := watcher.Next()
			if err != nil {
				_ = watcher.Close()
				break
			}
			if n.BrowseToURL != nil && *n.BrowseToURL != "" {
				latestAuthURL.Store(*n.BrowseToURL)
				logf("Received BrowseToURL: %s", *n.BrowseToURL)
			}
			if n.State != nil && *n.State == ipn.Running {
				latestAuthURL.Store("")
			}
		}
	}
}

type peerInfoJSON struct {
	HostName  string   `json:"hostname"`
	DNSName   string   `json:"dnsName"`
	IPs       []string `json:"ips"`
	OS        string   `json:"os"`
	Online    bool     `json:"online"`
	Active    bool     `json:"active"`
	Relay     string   `json:"relay"`
	PeerRelay string   `json:"peerRelay"`
	Direct    string   `json:"direct"`
}

type statusJSON struct {
	Running        bool           `json:"running"`
	BackendState   string         `json:"backendState"`
	AuthURL        string         `json:"authUrl"`
	SelfHostname   string         `json:"selfHostname"`
	SelfDNSName    string         `json:"selfDnsName"`
	SelfIPv4       string         `json:"selfIpv4"`
	SelfIPv6       string         `json:"selfIpv6"`
	TailnetName    string         `json:"tailnetName"`
	MagicDNSSuffix string         `json:"magicDnsSuffix"`
	Peers          []peerInfoJSON `json:"peers"`
	Health         []string       `json:"health"`
	Error          string         `json:"error"`
}

//export Java_org_attache_tailscale_TsnetNative_nativeStart
func Java_org_attache_tailscale_TsnetNative_nativeStart(
	env *C.JNIEnv,
	clazz C.jclass,
	jStateDir C.jstring,
	jHostname C.jstring,
	jAuthKey C.jstring,
	jControlURL C.jstring,
) C.jstring {
	stateDir := strings.TrimSpace(goString(env, jStateDir))
	hostname := strings.TrimSpace(goString(env, jHostname))
	authKey := strings.TrimSpace(goString(env, jAuthKey))
	controlURL := strings.TrimSpace(goString(env, jControlURL))

	if hostname == "" {
		hostname = "attache-android"
	}

	srvMu.Lock()
	defer srvMu.Unlock()

	if stateDir == "" {
		lastInitErr = "stateDir is required"
		return jString(env, lastInitErr)
	}
	if err := os.MkdirAll(stateDir, 0700); err != nil {
		lastInitErr = fmt.Sprintf("mkdir %s: %v", stateDir, err)
		return jString(env, lastInitErr)
	}
	_ = os.Setenv("TS_LOGS_DIR", stateDir)
	_ = os.Setenv("HOME", stateDir)
	_ = os.Setenv("TMPDIR", stateDir)
	_ = os.Setenv("XDG_CACHE_HOME", stateDir)
	_ = os.Setenv("XDG_CONFIG_HOME", stateDir)

	// If already running, update prefs/authkey if provided.
	if srv != nil {
		lc, err := srv.LocalClient()
		if err == nil {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			mp := &ipn.MaskedPrefs{
				WantRunningSet: true,
				Prefs: ipn.Prefs{
					WantRunning: true,
				},
			}
			if hostname != "" {
				mp.HostnameSet = true
				mp.Prefs.Hostname = hostname
			}
			if controlURL != "" {
				mp.ControlURLSet = true
				mp.Prefs.ControlURL = controlURL
			}
			_, _ = lc.EditPrefs(ctx, mp)
			if authKey != "" {
				prefs, err := lc.GetPrefs(ctx)
				if err == nil {
					prefs.WantRunning = true
					_ = lc.Start(ctx, ipn.Options{
						AuthKey:     authKey,
						UpdatePrefs: prefs,
					})
				}
			}
		}
		lastInitErr = ""
		return jString(env, "")
	}

	s := &tsnet.Server{
		Dir:        stateDir,
		Hostname:   hostname,
		AuthKey:    authKey,
		ControlURL: controlURL,
		UserLogf:   logf,
		Logf:       logf,
	}

	if err := s.Start(); err != nil {
		lastInitErr = err.Error()
		logf("tsnet.Start failed: %v", err)
		_ = s.Close()
		return jString(env, lastInitErr)
	}

	srv = s
	lastInitErr = ""
	ctx, cancel := context.WithCancel(context.Background())
	srvCancel = cancel

	if lc, err := s.LocalClient(); err == nil {
		go watchIPNBus(ctx, lc)
	}

	logf("tsnet started in %s as %s", stateDir, hostname)
	return jString(env, "")
}

//export Java_org_attache_tailscale_TsnetNative_nativeStop
func Java_org_attache_tailscale_TsnetNative_nativeStop(env *C.JNIEnv, clazz C.jclass) {
	forwardersMu.Lock()
	for port, ln := range forwarders {
		_ = ln.Close()
		delete(forwarders, port)
	}
	forwardersMu.Unlock()

	srvMu.Lock()
	s := srv
	cancel := srvCancel
	srv = nil
	srvCancel = nil
	latestAuthURL.Store("")
	srvMu.Unlock()

	if cancel != nil {
		cancel()
	}
	if s != nil {
		_ = s.Close()
		logf("tsnet stopped")
	}
}

//export Java_org_attache_tailscale_TsnetNative_nativeStartLoginInteractive
func Java_org_attache_tailscale_TsnetNative_nativeStartLoginInteractive(
	env *C.JNIEnv,
	clazz C.jclass,
) C.jstring {
	srvMu.Lock()
	s := srv
	srvMu.Unlock()
	if s == nil {
		return jString(env, "")
	}
	lc, err := s.LocalClient()
	if err != nil {
		return jString(env, "")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	// Ensure WantRunning is true and trigger interactive login.
	_, _ = lc.EditPrefs(ctx, &ipn.MaskedPrefs{
		WantRunningSet: true,
		Prefs:          ipn.Prefs{WantRunning: true},
	})
	_ = lc.StartLoginInteractive(ctx)

	deadline := time.Now().Add(8 * time.Second)
	for time.Now().Before(deadline) {
		if u, _ := latestAuthURL.Load().(string); u != "" {
			return jString(env, u)
		}
		if st, err := lc.StatusWithoutPeers(ctx); err == nil && st.AuthURL != "" {
			latestAuthURL.Store(st.AuthURL)
			return jString(env, st.AuthURL)
		}
		time.Sleep(200 * time.Millisecond)
	}
	if u, _ := latestAuthURL.Load().(string); u != "" {
		return jString(env, u)
	}
	return jString(env, "")
}

//export Java_org_attache_tailscale_TsnetNative_nativeLogout
func Java_org_attache_tailscale_TsnetNative_nativeLogout(
	env *C.JNIEnv,
	clazz C.jclass,
) C.jstring {
	srvMu.Lock()
	s := srv
	srvMu.Unlock()
	if s == nil {
		return jString(env, "tsnet not started")
	}
	lc, err := s.LocalClient()
	if err != nil {
		return jString(env, err.Error())
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	latestAuthURL.Store("")
	if err := lc.Logout(ctx); err != nil {
		return jString(env, err.Error())
	}
	return jString(env, "")
}

//export Java_org_attache_tailscale_TsnetNative_nativeStatusJson
func Java_org_attache_tailscale_TsnetNative_nativeStatusJson(
	env *C.JNIEnv,
	clazz C.jclass,
) C.jstring {
	srvMu.Lock()
	s := srv
	initErr := lastInitErr
	srvMu.Unlock()

	out := statusJSON{
		BackendState: "Stopped",
		Peers:        []peerInfoJSON{},
		Health:       []string{},
		Error:        initErr,
	}

	if s == nil {
		b, _ := json.Marshal(out)
		return jString(env, string(b))
	}

	lc, err := s.LocalClient()
	if err != nil {
		out.Error = err.Error()
		b, _ := json.Marshal(out)
		return jString(env, string(b))
	}

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	st, err := lc.Status(ctx)
	if err != nil {
		out.Error = err.Error()
		b, _ := json.Marshal(out)
		return jString(env, string(b))
	}

	out.BackendState = st.BackendState
	out.Running = st.BackendState == "Running"
	out.AuthURL = st.AuthURL
	if out.AuthURL == "" {
		if u, _ := latestAuthURL.Load().(string); u != "" && st.BackendState != "Running" {
			out.AuthURL = u
		}
	}
	if len(st.Health) > 0 {
		out.Health = st.Health
	}
	if st.CurrentTailnet != nil {
		out.TailnetName = st.CurrentTailnet.Name
		out.MagicDNSSuffix = strings.Trim(st.CurrentTailnet.MagicDNSSuffix, ".")
	}
	if st.Self != nil {
		out.SelfHostname = st.Self.HostName
		out.SelfDNSName = strings.TrimSuffix(st.Self.DNSName, ".")
	}
	for _, ip := range st.TailscaleIPs {
		if ip.Is4() && out.SelfIPv4 == "" {
			out.SelfIPv4 = ip.String()
		} else if ip.Is6() && out.SelfIPv6 == "" {
			out.SelfIPv6 = ip.String()
		}
	}

	for _, peer := range st.Peer {
		if peer == nil || peer.ShareeNode {
			continue
		}
		var ips []string
		for _, ip := range peer.TailscaleIPs {
			ips = append(ips, ip.String())
		}
		pInfo := peerInfoJSON{
			HostName:  peer.HostName,
			DNSName:   strings.TrimSuffix(peer.DNSName, "."),
			IPs:       ips,
			OS:        peer.OS,
			Online:    peer.Online,
			Active:    peer.Active,
			Relay:     peer.Relay,
			PeerRelay: peer.PeerRelay,
			Direct:    peer.CurAddr,
		}
		out.Peers = append(out.Peers, pInfo)
	}

	sort.Slice(out.Peers, func(i, j int) bool {
		if out.Peers[i].Online != out.Peers[j].Online {
			return out.Peers[i].Online
		}
		return strings.ToLower(out.Peers[i].HostName) < strings.ToLower(out.Peers[j].HostName)
	})

	b, _ := json.Marshal(out)
	return jString(env, string(b))
}

func resolvePeerTarget(s *tsnet.Server, host string) string {
	trimmed := strings.TrimSuffix(strings.TrimSpace(host), ".")
	if trimmed == "" {
		return host
	}
	if _, err := netip.ParseAddr(trimmed); err == nil {
		return trimmed
	}
	lc, err := s.LocalClient()
	if err != nil {
		return trimmed
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	st, err := lc.Status(ctx)
	if err != nil {
		return trimmed
	}
	lower := strings.ToLower(trimmed)
	for _, peer := range st.Peer {
		if peer == nil || len(peer.TailscaleIPs) == 0 {
			continue
		}
		dnsName := strings.ToLower(strings.TrimSuffix(peer.DNSName, "."))
		hostName := strings.ToLower(peer.HostName)
		shortDNS := dnsName
		if idx := strings.IndexByte(dnsName, '.'); idx > 0 {
			shortDNS = dnsName[:idx]
		}
		if lower == dnsName || lower == hostName || lower == shortDNS {
			// Prefer IPv4 Tailscale address when available.
			for _, ip := range peer.TailscaleIPs {
				if ip.Is4() {
					return ip.String()
				}
			}
			return peer.TailscaleIPs[0].String()
		}
	}
	return trimmed
}

//export Java_org_attache_tailscale_TsnetNative_nativeOpenTcpForwarder
func Java_org_attache_tailscale_TsnetNative_nativeOpenTcpForwarder(
	env *C.JNIEnv,
	clazz C.jclass,
	jTargetHost C.jstring,
	jTargetPort C.jint,
) C.jint {
	targetHost := strings.TrimSpace(goString(env, jTargetHost))
	targetPort := int(jTargetPort)
	if targetHost == "" || targetPort <= 0 || targetPort > 65535 {
		return -1
	}

	srvMu.Lock()
	s := srv
	srvMu.Unlock()
	if s == nil {
		return -2
	}

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		logf("Failed to listen on loopback: %v", err)
		return -3
	}

	tcpAddr, ok := ln.Addr().(*net.TCPAddr)
	if !ok {
		_ = ln.Close()
		return -4
	}
	localPort := tcpAddr.Port

	forwardersMu.Lock()
	forwarders[localPort] = ln
	forwardersMu.Unlock()

	go func() {
		defer func() {
			forwardersMu.Lock()
			delete(forwarders, localPort)
			forwardersMu.Unlock()
			_ = ln.Close()
		}()

		for {
			clientConn, err := ln.Accept()
			if err != nil {
				if !errors.Is(err, net.ErrClosed) {
					logf("Forwarder :%d accept ended: %v", localPort, err)
				}
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				dialHost := resolvePeerTarget(s, targetHost)
				dest := net.JoinHostPort(dialHost, strconv.Itoa(targetPort))
				ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
				remoteConn, err := s.Dial(ctx, "tcp", dest)
				cancel()
				if err != nil {
					logf("tsnet.Dial(%s [orig=%s]) failed: %v", dest, targetHost, err)
					return
				}
				defer remoteConn.Close()

				done := make(chan struct{}, 2)
				go func() {
					_, _ = io.Copy(remoteConn, c)
					if tc, ok := remoteConn.(interface{ CloseWrite() error }); ok {
						_ = tc.CloseWrite()
					}
					done <- struct{}{}
				}()
				go func() {
					_, _ = io.Copy(c, remoteConn)
					if tc, ok := c.(*net.TCPConn); ok {
						_ = tc.CloseWrite()
					}
					done <- struct{}{}
				}()
				<-done
				<-done
			}(clientConn)
		}
	}()

	return C.jint(localPort)
}

//export Java_org_attache_tailscale_TsnetNative_nativeCloseTcpForwarder
func Java_org_attache_tailscale_TsnetNative_nativeCloseTcpForwarder(
	env *C.JNIEnv,
	clazz C.jclass,
	jLocalPort C.jint,
) {
	localPort := int(jLocalPort)
	forwardersMu.Lock()
	ln, ok := forwarders[localPort]
	if ok {
		delete(forwarders, localPort)
	}
	forwardersMu.Unlock()
	if ok && ln != nil {
		_ = ln.Close()
	}
}

//export Java_org_attache_tailscale_TsnetNative_nativeSetNetworkHandle
func Java_org_attache_tailscale_TsnetNative_nativeSetNetworkHandle(
	env *C.JNIEnv,
	clazz C.jclass,
	jNetworkHandle C.jlong,
	jDefaultIfName C.jstring,
	jDefaultGateway C.jstring,
) {
	handle := uint64(jNetworkHandle)
	old := activeNetworkHandle.Swap(handle)
	ifName := strings.TrimSpace(goString(env, jDefaultIfName))
	gw := strings.TrimSpace(goString(env, jDefaultGateway))

	netmon.UpdateLastKnownDefaultRouteInterface(ifName)
	updatePlatformDefaultGateway(gw)

	if old != handle {
		logf("Updated active physical network handle=%d if=%s gw=%s", handle, ifName, gw)
		injectNetMonEvent()
	}
}

//export Java_org_attache_tailscale_TsnetNative_nativeUpdateInterfaces
func Java_org_attache_tailscale_TsnetNative_nativeUpdateInterfaces(
	env *C.JNIEnv,
	clazz C.jclass,
	jSpec C.jstring,
) {
	spec := goString(env, jSpec)
	parsed := parseInterfacesSpec(spec)
	ifacesMu.Lock()
	cachedIfaces = parsed
	ifacesMu.Unlock()
	injectNetMonEvent()
}

func main() {}
