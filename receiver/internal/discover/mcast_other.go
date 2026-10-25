//go:build !linux && !darwin && !windows

package discover

import (
	"net"
	"net/netip"
)

func setMulticastOptions(*net.UDPConn, netip.Addr) {}
