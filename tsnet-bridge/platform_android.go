//go:build android

package main

import (
	_ "tailscale.com/feature/androidbin"
	"tailscale.com/net/netmon"
	"tailscale.com/net/netns"
)

func registerPlatformHooks(bindFunc func(fd int) error) {
	netns.SetAndroidBindToNetworkFunc(bindFunc)
}

func updatePlatformDefaultGateway(gw string) {
	netmon.UpdateLastKnownDefaultGateway(gw)
}
