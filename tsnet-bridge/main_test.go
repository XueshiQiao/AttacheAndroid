package main

import (
	"net"
	"testing"
)

func TestParseInterfacesSpec(t *testing.T) {
	spec := "wlan0|30|1500|1|0|1|0|1|192.168.1.50/24,fe80::1234/64\n" +
		"lo|1|65536|1|1|0|0|0|127.0.0.1/8\n"

	ifaces := parseInterfacesSpec(spec)
	if len(ifaces) != 2 {
		t.Fatalf("expected 2 interfaces, got %d", len(ifaces))
	}

	wlan := ifaces[0]
	if wlan.Name != "wlan0" || wlan.Index != 30 || wlan.MTU != 1500 {
		t.Fatalf("unexpected wlan0 metadata: %+v", wlan.Interface)
	}
	if wlan.Flags&net.FlagUp == 0 || wlan.Flags&net.FlagLoopback != 0 {
		t.Fatalf("unexpected wlan0 flags: %v", wlan.Flags)
	}
	addrs, err := wlan.Addrs()
	if err != nil || len(addrs) != 2 {
		t.Fatalf("expected 2 addrs on wlan0, got %v (err=%v)", addrs, err)
	}

	lo := ifaces[1]
	if lo.Name != "lo" || lo.Flags&net.FlagLoopback == 0 {
		t.Fatalf("unexpected lo interface: %+v", lo.Interface)
	}
}
