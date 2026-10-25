//go:build windows

package discover

import (
	"net"
	"net/netip"
	"syscall"
)

// setMulticastOptions sends the query out of the interface that owns local, with TTL 255 as
// mDNS expects. Best effort.
func setMulticastOptions(conn *net.UDPConn, local netip.Addr) {
	rc, err := conn.SyscallConn()
	if err != nil {
		return
	}
	rc.Control(func(fd uintptr) {
		syscall.SetsockoptInet4Addr(syscall.Handle(fd), syscall.IPPROTO_IP, syscall.IP_MULTICAST_IF, local.As4())
		syscall.SetsockoptInt(syscall.Handle(fd), syscall.IPPROTO_IP, syscall.IP_MULTICAST_TTL, 255)
	})
}
