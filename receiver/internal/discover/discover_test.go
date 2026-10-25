package discover

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"sync"
	"testing"
	"time"

	"galleryslim/receiver/internal/client"
)

// These tests never send anything on the real network.

func TestBuildQuery(t *testing.T) {
	q, err := BuildQuery(0x1234, MDNSName)
	if err != nil {
		t.Fatal(err)
	}
	want := []byte{0x12, 0x34, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0,
		11, 'g', 'a', 'l', 'l', 'e', 'r', 'y', 's', 'l', 'i', 'm', 5, 'l', 'o', 'c', 'a', 'l', 0,
		0, 1, 0x80, 1}
	if string(q) != string(want) {
		t.Fatalf("query = % x", q)
	}
}

// response builds an mDNS answer packet: the question echoed, then A records.
func response(t *testing.T, records ...[]byte) []byte {
	t.Helper()
	q, _ := BuildQuery(0x1234, MDNSName)
	pkt := append([]byte{}, q...)
	binary.BigEndian.PutUint16(pkt[2:], 0x8400) // response, authoritative
	binary.BigEndian.PutUint16(pkt[6:], uint16(len(records)))
	for _, r := range records {
		pkt = append(pkt, r...)
	}
	return pkt
}

// aRecord uses a compression pointer to the question name at offset 12.
func aRecord(typ, class uint16, ip ...byte) []byte {
	r := []byte{0xC0, 12}
	r = binary.BigEndian.AppendUint16(r, typ)
	r = binary.BigEndian.AppendUint16(r, class)
	r = append(r, 0, 0, 0, 120)
	r = binary.BigEndian.AppendUint16(r, uint16(len(ip)))
	return append(r, ip...)
}

func TestParseAnswers(t *testing.T) {
	pkt := response(t,
		aRecord(1, 0x8001, 192, 168, 1, 23),                                   // cache-flush bit set
		aRecord(28, 1, make([]byte, 16)...),                                   // AAAA: ignored
		aRecord(1, 1, 10, 0, 0, 7),                                            // second phone
		aRecord(1, 1, 127, 0, 0, 1),                                           // loopback: ignored
		append([]byte{3, 'f', 'o', 'o', 0}, aRecord(1, 1, 1, 2, 3, 4)[2:]...), // other name
	)
	got := ParseAnswers(pkt, MDNSName)
	want := []netip.Addr{netip.MustParseAddr("192.168.1.23"), netip.MustParseAddr("10.0.0.7")}
	if len(got) != len(want) || got[0] != want[0] || got[1] != want[1] {
		t.Fatalf("got %v", got)
	}
	// Queries (QR bit clear) are not answers.
	q, _ := BuildQuery(1, MDNSName)
	if ParseAnswers(q, MDNSName) != nil {
		t.Error("query parsed as answer")
	}
	// Truncated or looping packets must not panic.
	for i := 0; i < len(pkt); i++ {
		ParseAnswers(pkt[:i], MDNSName)
	}
	loop := response(t, []byte{0xC0, byte(len(pkt))})
	loop = append(loop, 0xC0, byte(len(pkt)))
	ParseAnswers(loop, MDNSName)
}

func TestSubnetHosts(t *testing.T) {
	hosts := SubnetHosts([]netip.Prefix{
		netip.MustParsePrefix("192.168.1.10/24"),
		netip.MustParsePrefix("10.0.0.5/16"),   // too large: not probed
		netip.MustParsePrefix("172.16.0.1/30"), // two hosts, one is us
		netip.MustParsePrefix("172.16.9.1/31"),
	})
	if len(hosts) != 253+1 {
		t.Fatalf("%d hosts", len(hosts))
	}
	for _, h := range hosts {
		s := h.String()
		if s == "192.168.1.10" || s == "192.168.1.0" || s == "192.168.1.255" || strings.HasPrefix(s, "10.") {
			t.Errorf("unexpected host %s", s)
		}
	}
	if hosts[len(hosts)-1].String() != "172.16.0.2" {
		t.Errorf("last host %s", hosts[len(hosts)-1])
	}
}

func TestParseAddress(t *testing.T) {
	if a, err := ParseAddress(" 192.168.1.5:8766 "); err != nil || len(a) != 1 || a[0] != "192.168.1.5:8766" {
		t.Errorf("%v %v", a, err)
	}
	if a, err := ParseAddress("http://192.168.1.5/"); err != nil || len(a) != len(Ports) {
		t.Errorf("%v %v", a, err)
	}
	for _, bad := range []string{"", "phone.local", "[::1]:8765", "192.168.1.5:0", "1.2.3.4:99999", "1.2.3.4; rm -rf /"} {
		if _, err := ParseAddress(bad); err == nil {
			t.Errorf("%q accepted", bad)
		}
	}
}

func TestProbeAllPrefersLowestPort(t *testing.T) {
	probe := func(_ context.Context, addr string) (client.Hello, error) {
		if strings.HasSuffix(addr, ":8765") || strings.HasPrefix(addr, "10.0.0.9:") {
			return client.Hello{}, errors.New("closed")
		}
		return client.Hello{Name: "P " + addr, Code: "K7-42"}, nil
	}
	got := probeAll(context.Background(), withPorts([]netip.Addr{netip.MustParseAddr("10.0.0.8"), netip.MustParseAddr("10.0.0.9")}), probe)
	if len(got) != 1 || got[0].Addr != "10.0.0.8:8766" {
		t.Fatalf("%+v", got)
	}
}

func TestProbeAddressRealHello(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/hello" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("X-GallerySlim", "2")
		json.NewEncoder(w).Encode(map[string]any{"app": "GallerySlim", "proto": 2,
			"name": "Pixel‮ 8 <b>", "code": "K7-42", "port": 8765})
	}))
	defer srv.Close()
	addr := strings.TrimPrefix(srv.URL, "http://")
	c, err := ProbeAddress(context.Background(), addr, NewProber())
	if err != nil {
		t.Fatal(err)
	}
	if c.Name != "Pixel 8 <b>" || c.Code != "K7-42" || c.Addr != addr {
		t.Fatalf("%+v", c)
	}
	// A server that is not a GallerySlim phone.
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("{}")) }))
	defer other.Close()
	if _, err := ProbeAddress(context.Background(), strings.TrimPrefix(other.URL, "http://"), NewProber()); err == nil {
		t.Error("non-phone accepted")
	}
}

func TestFindQuick(t *testing.T) {
	orig := queryMDNS
	defer func() { queryMDNS = orig }()
	queryMDNS = func(context.Context, time.Duration) ([]netip.Addr, error) {
		return []netip.Addr{netip.MustParseAddr("10.0.0.7")}, nil
	}
	var mu sync.Mutex
	probed := map[string]bool{}
	probe := func(_ context.Context, addr string) (client.Hello, error) {
		mu.Lock()
		probed[addr] = true
		mu.Unlock()
		switch addr {
		case "10.0.0.5:8767": // the phone moved to another port since last time
			return client.Hello{Name: "S24", Code: "K7-42", Pid: "ICEiIyQlJicoKSorLC0uLw"}, nil
		case "10.0.0.7:8765":
			return client.Hello{Name: "Pixel", Code: "M3-17"}, nil
		}
		return client.Hello{}, errors.New("closed")
	}
	got := FindQuick(context.Background(), probe, []string{"10.0.0.5:8765", "bad", "10.0.0.5:8766"})
	if len(got) != 2 || got[0].Addr != "10.0.0.5:8767" || got[0].Pid == "" || got[1].Addr != "10.0.0.7:8765" {
		t.Fatalf("%+v", got)
	}
	if len(probed) != 10 {
		t.Errorf("probed %d addresses, want 2 IPs × 5 ports", len(probed))
	}
	queryMDNS = func(context.Context, time.Duration) ([]netip.Addr, error) { return nil, nil }
	if got := FindQuick(context.Background(), probe, nil); got != nil {
		t.Errorf("nothing to probe: %+v", got)
	}
}
