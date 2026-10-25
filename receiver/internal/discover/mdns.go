package discover

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"net"
	"net/netip"
	"strings"
	"sync"
	"time"
)

// MDNSName is the name the phone answers for.
const MDNSName = "galleryslim.local"

var mdnsGroup = &net.UDPAddr{IP: net.IPv4(224, 0, 0, 251), Port: 5353}

const (
	typeA    = 1
	classIN  = 1
	qubit    = 0x8000 // "unicast response requested" in the question class
	maxPkt   = 9000
	maxNames = 64
)

// BuildQuery builds a one-shot mDNS question for name, type A, class IN with the QU bit.
func BuildQuery(id uint16, name string) ([]byte, error) {
	b := make([]byte, 12, 64)
	binary.BigEndian.PutUint16(b[0:], id)
	binary.BigEndian.PutUint16(b[4:], 1) // QDCOUNT
	for _, label := range strings.Split(strings.TrimSuffix(name, "."), ".") {
		if label == "" || len(label) > 63 {
			return nil, errors.New("bad name")
		}
		b = append(b, byte(len(label)))
		b = append(b, label...)
	}
	b = append(b, 0)
	b = binary.BigEndian.AppendUint16(b, typeA)
	b = binary.BigEndian.AppendUint16(b, classIN|qubit)
	return b, nil
}

// readName decodes a (possibly compressed) DNS name starting at off. It returns the name and
// the offset just past it in the original position.
func readName(pkt []byte, off int) (string, int, error) {
	var labels []string
	end := -1
	total := 0
	for jumps := 0; jumps < maxNames; {
		if off >= len(pkt) {
			return "", 0, errors.New("name out of range")
		}
		l := int(pkt[off])
		switch {
		case l == 0:
			if end < 0 {
				end = off + 1
			}
			return strings.Join(labels, "."), end, nil
		case l&0xC0 == 0xC0:
			if off+1 >= len(pkt) {
				return "", 0, errors.New("bad pointer")
			}
			if end < 0 {
				end = off + 2
			}
			off = int(binary.BigEndian.Uint16(pkt[off:]) & 0x3FFF)
			jumps++
		case l&0xC0 != 0:
			return "", 0, errors.New("bad label type")
		default:
			if off+1+l > len(pkt) {
				return "", 0, errors.New("label out of range")
			}
			total += l + 1
			if total > 255 {
				return "", 0, errors.New("name too long")
			}
			labels = append(labels, string(pkt[off+1:off+1+l]))
			off += 1 + l
		}
	}
	return "", 0, errors.New("too many pointers")
}

// ParseAnswers returns the IPv4 addresses that an mDNS response gives for name.
func ParseAnswers(pkt []byte, name string) []netip.Addr {
	if len(pkt) < 12 || binary.BigEndian.Uint16(pkt[2:])&0x8000 == 0 {
		return nil // not a response
	}
	qd := int(binary.BigEndian.Uint16(pkt[4:]))
	rr := int(binary.BigEndian.Uint16(pkt[6:])) + int(binary.BigEndian.Uint16(pkt[8:])) + int(binary.BigEndian.Uint16(pkt[10:]))
	off := 12
	for i := 0; i < qd; i++ {
		_, n, err := readName(pkt, off)
		if err != nil || n+4 > len(pkt) {
			return nil
		}
		off = n + 4
	}
	want := strings.TrimSuffix(name, ".")
	var out []netip.Addr
	for i := 0; i < rr; i++ {
		rrName, n, err := readName(pkt, off)
		if err != nil || n+10 > len(pkt) {
			return out
		}
		typ := binary.BigEndian.Uint16(pkt[n:])
		class := binary.BigEndian.Uint16(pkt[n+2:]) &^ 0x8000 // cache-flush bit
		rdlen := int(binary.BigEndian.Uint16(pkt[n+8:]))
		rd := n + 10
		if rd+rdlen > len(pkt) {
			return out
		}
		if typ == typeA && class == classIN && rdlen == 4 && strings.EqualFold(rrName, want) {
			ip := netip.AddrFrom4([4]byte(pkt[rd : rd+4]))
			if !ip.IsUnspecified() && !ip.IsMulticast() && !ip.IsLoopback() {
				out = append(out, ip)
			}
		}
		off = rd + rdlen
	}
	return out
}

// QueryMDNS sends one mDNS question for galleryslim.local from an ephemeral port on each local
// IPv4 interface address and collects every A answer (sent back by unicast) for wait.
// The sockets are bound to the interface address, not to all addresses, and are closed after.
func QueryMDNS(ctx context.Context, wait time.Duration) ([]netip.Addr, error) {
	var idb [2]byte
	rand.Read(idb[:])
	query, err := BuildQuery(binary.BigEndian.Uint16(idb[:]), MDNSName)
	if err != nil {
		return nil, err
	}
	deadline := time.Now().Add(wait)
	var (
		mu    sync.Mutex
		found = map[netip.Addr]bool{}
		wg    sync.WaitGroup
	)
	for _, p := range LocalPrefixes() {
		conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: p.Addr().AsSlice(), Port: 0})
		if err != nil {
			continue
		}
		setMulticastOptions(conn, p.Addr())
		if _, err := conn.WriteToUDP(query, mdnsGroup); err != nil {
			conn.Close()
			continue
		}
		conn.SetReadDeadline(deadline)
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer conn.Close()
			buf := make([]byte, maxPkt)
			for ctx.Err() == nil {
				n, _, err := conn.ReadFromUDP(buf)
				if err != nil {
					return
				}
				for _, ip := range ParseAnswers(buf[:n], MDNSName) {
					mu.Lock()
					found[ip] = true
					mu.Unlock()
				}
			}
		}()
	}
	wg.Wait()
	out := make([]netip.Addr, 0, len(found))
	for ip := range found {
		out = append(out, ip)
	}
	return out, nil
}
