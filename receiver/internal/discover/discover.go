// Package discover finds GallerySlim phones on the local network: a one-shot mDNS query for
// galleryslim.local, then a probe of the local /24-or-smaller subnets, or an address the user
// typed.
package discover

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"galleryslim/receiver/internal/client"
)

// Ports are the ports the phone tries, in order.
var Ports = []int{8765, 8766, 8767, 8768, 8769}

const (
	// MDNSWait is how long answers to the mDNS query are collected.
	MDNSWait = 1500 * time.Millisecond
	// ProbeTimeout bounds one /api/hello probe.
	ProbeTimeout = 300 * time.Millisecond
	// ProbeParallel bounds concurrent probes.
	ProbeParallel = 64
)

// Candidate is a phone that answered /api/hello.
type Candidate struct {
	Addr string // ip:port
	Name string // cleaned
	Code string // cleaned
	Pid  string // phone id (gs2 §9), "" if the phone sent none
}

// Prober asks one address for /api/hello.
type Prober func(ctx context.Context, addr string) (client.Hello, error)

// NewProber returns a Prober using client.GetHello with ProbeTimeout per address.
func NewProber() Prober {
	hc := client.NewHTTPClient()
	return func(ctx context.Context, addr string) (client.Hello, error) {
		ctx, cancel := context.WithTimeout(ctx, ProbeTimeout)
		defer cancel()
		return client.GetHello(ctx, hc, addr)
	}
}

// probeAll probes every address with bounded concurrency and returns the phones found,
// at most one per IP (the first port that answers).
func probeAll(ctx context.Context, addrs []string, probe Prober) []Candidate {
	var (
		mu    sync.Mutex
		found = map[string]Candidate{}
		wg    sync.WaitGroup
		sem   = make(chan struct{}, ProbeParallel)
	)
	for _, a := range addrs {
		if ctx.Err() != nil {
			break
		}
		sem <- struct{}{}
		wg.Add(1)
		go func(addr string) {
			defer wg.Done()
			defer func() { <-sem }()
			h, err := probe(ctx, addr)
			if err != nil {
				return
			}
			host, _, _ := net.SplitHostPort(addr)
			mu.Lock()
			defer mu.Unlock()
			if prev, ok := found[host]; ok && portOf(prev.Addr) <= portOf(addr) {
				return
			}
			found[host] = Candidate{Addr: addr, Name: h.Name, Code: h.Code, Pid: h.Pid}
		}(a)
	}
	wg.Wait()
	out := make([]Candidate, 0, len(found))
	for _, c := range found {
		out = append(out, c)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Addr < out[j].Addr })
	return out
}

func portOf(addr string) int {
	_, p, _ := net.SplitHostPort(addr)
	n, _ := strconv.Atoi(p)
	return n
}

func withPorts(ips []netip.Addr) []string {
	var out []string
	for _, ip := range ips {
		for _, p := range Ports {
			out = append(out, netip.AddrPortFrom(ip, uint16(p)).String())
		}
	}
	return out
}

// Find looks for phones: mDNS first; if that finds none, the local subnets.
func Find(ctx context.Context, probe Prober) ([]Candidate, error) {
	ips, _ := QueryMDNS(ctx, MDNSWait)
	if len(ips) > 0 {
		if c := probeAll(ctx, withPorts(ips), probe); len(c) > 0 {
			return c, nil
		}
	}
	hosts := SubnetHosts(LocalPrefixes())
	c := probeAll(ctx, withPorts(hosts), probe)
	return c, ctx.Err()
}

// queryMDNS is replaced in tests.
var queryMDNS = QueryMDNS

// FindQuick is the cheap look used while waiting in the background (§9 "discovery while
// idle"): the given last-known addresses (every phone port of each address's IP) and one
// mDNS query, without the subnet sweep.
func FindQuick(ctx context.Context, probe Prober, last []string) []Candidate {
	seen := map[netip.Addr]bool{}
	var ips []netip.Addr
	add := func(ip netip.Addr) {
		if ip.Is4() && !seen[ip] {
			seen[ip] = true
			ips = append(ips, ip)
		}
	}
	for _, a := range last {
		if ap, err := netip.ParseAddrPort(a); err == nil {
			add(ap.Addr().Unmap())
		}
	}
	if found, _ := queryMDNS(ctx, MDNSWait); len(found) > 0 {
		for _, ip := range found {
			add(ip.Unmap())
		}
	}
	if len(ips) == 0 || ctx.Err() != nil {
		return nil
	}
	return probeAll(ctx, withPorts(ips), probe)
}

// ParseAddress validates an address the user typed: an IP literal with an optional port.
// Without a port, every phone port is a candidate.
func ParseAddress(s string) ([]string, error) {
	s = strings.TrimSpace(s)
	s = strings.TrimPrefix(strings.TrimPrefix(s, "http://"), "https://")
	s = strings.TrimSuffix(s, "/")
	if s == "" || len(s) > 64 {
		return nil, errors.New("please type the address shown on your phone, like 192.168.1.23:8765")
	}
	if ap, err := netip.ParseAddrPort(s); err == nil {
		if ap.Port() == 0 || !ap.Addr().Is4() {
			return nil, errors.New("That doesn't look like the phone's address. It looks like 192.168.1.23:8765.")
		}
		return []string{ap.String()}, nil
	}
	ip, err := netip.ParseAddr(s)
	if err != nil || !ip.Is4() {
		return nil, errors.New("That doesn't look like the phone's address. It looks like 192.168.1.23:8765.")
	}
	return withPorts([]netip.Addr{ip}), nil
}

// ProbeAddress checks a typed address.
func ProbeAddress(ctx context.Context, typed string, probe Prober) (Candidate, error) {
	addrs, err := ParseAddress(typed)
	if err != nil {
		return Candidate{}, err
	}
	c := probeAll(ctx, addrs, probe)
	if len(c) == 0 {
		return Candidate{}, fmt.Errorf("no GallerySlim phone answered at %s; check that the phone shows \"Send to computer\" and is on the same Wi-Fi", strings.TrimSpace(typed))
	}
	return c[0], nil
}

// LocalPrefixes lists the IPv4 networks of this computer's active, non-loopback interfaces.
func LocalPrefixes() []netip.Prefix {
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil
	}
	var out []netip.Prefix
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := ifc.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			ipn, ok := a.(*net.IPNet)
			if !ok {
				continue
			}
			ip, ok := netip.AddrFromSlice(ipn.IP)
			if !ok {
				continue
			}
			ip = ip.Unmap()
			if !ip.Is4() || ip.IsLoopback() {
				continue
			}
			ones, _ := ipn.Mask.Size()
			out = append(out, netip.PrefixFrom(ip, ones))
		}
	}
	return out
}

// SubnetHosts lists every host address of the given networks with prefix length ≥ 24,
// excluding the network and broadcast addresses and the computer's own address.
func SubnetHosts(prefixes []netip.Prefix) []netip.Addr {
	seen := map[netip.Addr]bool{}
	self := map[netip.Addr]bool{}
	for _, p := range prefixes {
		self[p.Addr()] = true
	}
	var out []netip.Addr
	for _, p := range prefixes {
		if !p.Addr().Is4() || p.Bits() < 24 || p.Bits() > 30 {
			continue
		}
		network := p.Masked()
		first := network.Addr()
		size := 1 << (32 - p.Bits())
		a := first.Next() // skip the network address
		for i := 1; i < size-1; i++ {
			if !self[a] && !seen[a] {
				seen[a] = true
				out = append(out, a)
			}
			a = a.Next()
		}
	}
	return out
}
