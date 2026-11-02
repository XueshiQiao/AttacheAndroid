//go:build !android

package main

func registerPlatformHooks(bindFunc func(fd int) error) {
	_ = bindFunc
}

func updatePlatformDefaultGateway(gw string) {
	_ = gw
}
