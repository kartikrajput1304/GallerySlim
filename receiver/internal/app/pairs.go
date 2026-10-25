package app

// Remembered phones (protocol gs2 §9): for each phone id (pid) the phone's name, the pair key
// PK and the last address it answered on. Stored in pairs.json in the config folder with file
// mode 0600 (on Windows the per-user profile folder already limits access).
//
// Security-sensitive: PK lets this computer start a session that the phone owner then only
// confirms with a fingerprint. It is never logged, never sent, never shown on the page.

import (
	"crypto/rand"
	"encoding/json"
	"errors"
	"io"
	"net/netip"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"sync"
	"time"

	"galleryslim/receiver/internal/gs2"
	"galleryslim/receiver/internal/textsafe"
)

// PairsFile holds the remembered phones inside the config folder.
const PairsFile = "pairs.json"

// MaxPairs is how many phones are remembered (the least recently used is dropped).
const MaxPairs = 16

// PairedPhone is a remembered phone as the page shows it (no key).
type PairedPhone struct {
	ID       string // pid
	Name     string // cleaned, still to be HTML-escaped
	Addr     string // last ip:port it answered on
	Paired   int64  // unix ms of the QR pairing
	LastUsed int64  // unix ms of the last approved session
}

type pairRecord struct {
	PID      string `json:"pid"`
	Name     string `json:"name"`
	Key      string `json:"pk"` // base64url, 32 bytes
	Addr     string `json:"addr,omitempty"`
	Paired   int64  `json:"paired"`
	LastUsed int64  `json:"lastUsed"`
}

// Pairs is the remembered-phone store. It is safe for concurrent use.
type Pairs struct {
	dir string // "" = memory only (no settings folder)
	mu  sync.Mutex
	m   map[string]pairRecord
	now func() time.Time
}

// LoadPairs reads dir/pairs.json; a missing or unreadable file gives an empty store. Invalid
// entries are dropped.
func LoadPairs(dir string) *Pairs {
	p := &Pairs{dir: dir, m: map[string]pairRecord{}, now: time.Now}
	if dir == "" {
		return p
	}
	f, err := os.Open(filepath.Join(dir, PairsFile))
	if err != nil {
		return p
	}
	defer f.Close()
	var doc struct {
		Phones []pairRecord `json:"phones"`
	}
	if err := json.NewDecoder(io.LimitReader(f, 256<<10)).Decode(&doc); err != nil {
		return p
	}
	for _, r := range doc.Phones {
		if !gs2.ValidID(r.PID) {
			continue
		}
		if k, err := gs2.B64.DecodeString(r.Key); err != nil || len(k) != gs2.KeySize {
			continue
		}
		if !validPhoneAddr(r.Addr) {
			r.Addr = ""
		}
		r.Name = textsafe.Clean(r.Name, 40)
		p.m[r.PID] = r
	}
	return p
}

func validPhoneAddr(a string) bool {
	ap, err := netip.ParseAddrPort(a)
	return err == nil && ap.Addr().Is4() && ap.Port() != 0
}

// Key returns a copy of the PK remembered for pid, or nil.
func (p *Pairs) Key(pid string) []byte {
	if p == nil {
		return nil
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	r, ok := p.m[pid]
	if !ok {
		return nil
	}
	k, err := gs2.B64.DecodeString(r.Key)
	if err != nil || len(k) != gs2.KeySize {
		return nil
	}
	return k
}

// Known reports whether pid is remembered.
func (p *Pairs) Known(pid string) bool {
	if p == nil || pid == "" {
		return false
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	_, ok := p.m[pid]
	return ok
}

// Remember stores (or replaces) the PK of a phone after an approved QR pairing.
func (p *Pairs) Remember(pid, name, addr string, pk []byte) error {
	if p == nil {
		return errors.New("no store")
	}
	if !gs2.ValidID(pid) || len(pk) != gs2.KeySize {
		return errors.New("bad pairing")
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	now := p.now().UnixMilli()
	if !validPhoneAddr(addr) {
		addr = ""
	}
	p.m[pid] = pairRecord{PID: pid, Name: textsafe.Clean(name, 40), Key: gs2.B64.EncodeToString(pk), Addr: addr, Paired: now, LastUsed: now}
	for len(p.m) > MaxPairs {
		oldest := ""
		for id, r := range p.m {
			if oldest == "" || r.LastUsed < p.m[oldest].LastUsed {
				oldest = id
			}
		}
		delete(p.m, oldest)
	}
	return p.saveLocked()
}

// Used notes an approved remembered session: the phone's current name and address.
func (p *Pairs) Used(pid, name, addr string) error {
	if p == nil {
		return nil
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	r, ok := p.m[pid]
	if !ok {
		return nil
	}
	if n := textsafe.Clean(name, 40); n != "" {
		r.Name = n
	}
	if validPhoneAddr(addr) {
		r.Addr = addr
	}
	r.LastUsed = p.now().UnixMilli()
	p.m[pid] = r
	return p.saveLocked()
}

// Forget deletes a phone's PK. Forgetting an unknown phone is not an error.
func (p *Pairs) Forget(pid string) error {
	if p == nil {
		return nil
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if _, ok := p.m[pid]; !ok {
		return nil
	}
	delete(p.m, pid)
	return p.saveLocked()
}

// List returns the remembered phones, most recently used first.
func (p *Pairs) List() []PairedPhone {
	if p == nil {
		return nil
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	out := make([]PairedPhone, 0, len(p.m))
	for _, r := range p.m {
		out = append(out, PairedPhone{ID: r.PID, Name: r.Name, Addr: r.Addr, Paired: r.Paired, LastUsed: r.LastUsed})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].LastUsed != out[j].LastUsed {
			return out[i].LastUsed > out[j].LastUsed
		}
		return out[i].ID < out[j].ID
	})
	return out
}

// Addrs returns the last known addresses of the remembered phones.
func (p *Pairs) Addrs() []string {
	var out []string
	for _, ph := range p.List() {
		if ph.Addr != "" {
			out = append(out, ph.Addr)
		}
	}
	return out
}

// Len is the number of remembered phones.
func (p *Pairs) Len() int {
	if p == nil {
		return 0
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.m)
}

func (p *Pairs) saveLocked() error {
	if p.dir == "" {
		return nil
	}
	doc := struct {
		Phones []pairRecord `json:"phones"`
	}{Phones: make([]pairRecord, 0, len(p.m))}
	for _, r := range p.m {
		doc.Phones = append(doc.Phones, r)
	}
	sort.Slice(doc.Phones, func(i, j int) bool { return doc.Phones[i].PID < doc.Phones[j].PID })
	b, err := json.MarshalIndent(doc, "", "  ")
	if err != nil {
		return err
	}
	return writePrivate(p.dir, PairsFile, b)
}

// writePrivate replaces dir/name atomically with a file readable only by this user.
func writePrivate(dir, name string, b []byte) error {
	tmp, err := os.CreateTemp(dir, name+"-*.tmp") // created with mode 0600
	if err != nil {
		return err
	}
	_, werr := tmp.Write(b)
	if werr == nil && runtime.GOOS != "windows" {
		werr = tmp.Chmod(0o600)
	}
	if werr == nil {
		werr = tmp.Sync()
	}
	cerr := tmp.Close()
	if werr != nil || cerr != nil {
		os.Remove(tmp.Name())
		if werr != nil {
			return werr
		}
		return cerr
	}
	if err := os.Rename(tmp.Name(), filepath.Join(dir, name)); err != nil {
		os.Remove(tmp.Name())
		return err
	}
	return nil
}

// NewID returns a fresh random receiver id (16 bytes, base64url).
func NewID() string {
	b := make([]byte, gs2.IDSize)
	rand.Read(b)
	return gs2.B64.EncodeToString(b)
}
