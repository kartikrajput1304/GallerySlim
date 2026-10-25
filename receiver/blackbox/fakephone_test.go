package blackbox

// A fake GallerySlim phone speaking protocol 2, used to drive the receiver's real engine
// (receive.Pair / receive.Run) and to inject phone-side faults the real phone cannot be made
// to produce on demand: busy answers, unsigned answers, stalls, wrong list checksums, odd list
// entries. It approves a pairing only when given the exact QR text the receiver showed.

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"galleryslim/receiver/internal/gs2"
)

type phoneFile struct {
	ID      string
	Name    string
	Mime    string
	Taken   int64
	Month   string
	Data    []byte
	ListSHA *string // sent in the list as sha256 (nil = null)
	// ListSize overrides the listed size (to make invalid entries); 0 = len(Data).
	ListSize int64
}

func sha(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }

type phoneSession struct {
	sid      string
	n        []byte
	receiver string
	os       string
	folder   string
	approved bool
	keys     gs2.Keys
	expired  bool
}

type request struct {
	Method, URI string
	Header      http.Header
	Body        []byte
	At          time.Time
}

type fakePhone struct {
	t    *testing.T
	srv  *http.Server
	ln   net.Listener
	Addr string

	Name, Code string
	Proto      int // hello proto (default 2)

	mu       sync.Mutex
	files    []*phoneFile
	sessions map[string]*phoneSession
	last     *phoneSession
	requests []request

	// Fault knobs (read under mu by handlers).
	stateStatus  int                                                 // answer /api/r/state with this status (0 = normal)
	listFailures int                                                 // first N list requests answer 500 (signed)
	busyFor      func(path string) bool                              // answer 503 Busy
	fileHook     func(f *phoneFile, start int64, attempt int) string // "", "fail500", "stall", "gone", "changed", "cut"
	stall        time.Duration
	throttle     time.Duration // sleep per 64 KiB chunk while streaming
	ackHook      func(f *phoneFile) string                           // "", "mismatch", "unsigned", "fail500", "badsig"
	ackDelay     time.Duration

	fileAttempts map[string]int
	ranges       []string // "id@start" of every file request
	acks         []string
	streams      atomic.Int32
	maxStreams   atomic.Int32
	verified     []string
}

func newFakePhone(t *testing.T, files ...*phoneFile) *fakePhone {
	t.Helper()
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	return startFakePhoneOn(t, ln, files...)
}

func startFakePhoneOn(t *testing.T, ln net.Listener, files ...*phoneFile) *fakePhone {
	p := &fakePhone{t: t, ln: ln, Addr: ln.Addr().String(), Name: "Fake Phone", Code: "FK-42", Proto: 2,
		files: files, sessions: map[string]*phoneSession{}, fileAttempts: map[string]int{}}
	p.srv = &http.Server{Handler: http.HandlerFunc(p.serve)}
	go p.srv.Serve(ln)
	t.Cleanup(func() { p.srv.Close() })
	return p
}

func (p *fakePhone) setFiles(files ...*phoneFile) { p.mu.Lock(); p.files = files; p.mu.Unlock() }

func (p *fakePhone) port() int { return p.ln.Addr().(*net.TCPAddr).Port }

// approve does what the phone does after the owner confirms: it accepts the scanned text.
func (p *fakePhone) approve(qrText string) error {
	sid, k, err := gs2.ParseQRText(qrText)
	if err != nil {
		return err
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	s := p.sessions[sid]
	if s == nil || s.approved {
		return fmt.Errorf("no waiting session %q", sid)
	}
	keys, err := gs2.DeriveKeys(sid, s.n, k)
	if err != nil {
		return err
	}
	s.keys, s.approved = keys, true
	return nil
}

// endAll ends every session (the phone stopped sharing).
func (p *fakePhone) endAll() {
	p.mu.Lock()
	defer p.mu.Unlock()
	for _, s := range p.sessions {
		s.expired = true
	}
}

func (p *fakePhone) allRequests() []request {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]request(nil), p.requests...)
}

func (p *fakePhone) fileRanges() []string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]string(nil), p.ranges...)
}

func (p *fakePhone) verifiedIDs() []string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]string(nil), p.verified...)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	b, _ := json.Marshal(v)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	w.Write(b)
}

func (p *fakePhone) serve(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	p.mu.Lock()
	p.requests = append(p.requests, request{r.Method, r.URL.RequestURI(), r.Header.Clone(), body, time.Now()})
	busy := p.busyFor != nil && p.busyFor(r.URL.Path)
	p.mu.Unlock()
	if busy {
		w.Header().Set("Connection", "close")
		w.WriteHeader(http.StatusServiceUnavailable)
		return
	}
	switch {
	case r.URL.Path == "/api/hello":
		w.Header().Set("X-GallerySlim", "2")
		writeJSON(w, 200, map[string]any{"app": "GallerySlim", "proto": p.Proto, "name": p.Name, "code": p.Code, "port": p.port()})
	case r.URL.Path == "/api/r/session" && r.Method == http.MethodPost:
		var in map[string]string
		_ = json.Unmarshal(body, &in)
		sidb := make([]byte, 12)
		n := make([]byte, 32)
		rand.Read(sidb)
		rand.Read(n)
		s := &phoneSession{sid: gs2.B64.EncodeToString(sidb), n: n, receiver: in["receiver"], os: in["os"], folder: in["folder"]}
		p.mu.Lock()
		p.sessions[s.sid] = s
		p.last = s
		p.mu.Unlock()
		writeJSON(w, 200, map[string]string{"sid": s.sid, "n": gs2.B64.EncodeToString(n), "code": p.Code})
	case r.URL.Path == "/api/r/state":
		p.mu.Lock()
		st := p.stateStatus
		s := p.sessions[r.URL.Query().Get("sid")]
		state := "expired"
		if s != nil && !s.expired {
			state = "waiting"
			if s.approved {
				state = "approved"
			}
		}
		p.mu.Unlock()
		if st != 0 {
			w.WriteHeader(st)
			return
		}
		writeJSON(w, 200, map[string]string{"state": state})
	default:
		p.signedRoute(w, r, body)
	}
}

func (p *fakePhone) signedRoute(w http.ResponseWriter, r *http.Request, body []byte) {
	p.mu.Lock()
	s := p.sessions[r.Header.Get("X-GS-Sid")]
	p.mu.Unlock()
	if s == nil || s.expired {
		writeJSON(w, http.StatusGone, map[string]string{"error": "expired"})
		return
	}
	if !s.approved {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "auth"})
		return
	}
	ctr, err := strconv.ParseUint(r.Header.Get("X-GS-Ctr"), 10, 64)
	canon := gs2.Canonical(r.Method, r.URL.RequestURI(), r.Header.Get("Range"), s.sid, ctr, body)
	if err != nil || !gs2.VerifyMAC(s.keys.Mac[:], canon, r.Header.Get("X-GS-Mac")) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "auth"})
		return
	}
	signed := func(status int, v any) {
		b, _ := json.Marshal(v)
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("X-GS-RMac", gs2.RespMAC(s.keys.Mac[:], ctr, status, b))
		w.WriteHeader(status)
		w.Write(b)
	}
	switch r.URL.Path {
	case "/api/r/list":
		p.mu.Lock()
		fail := p.listFailures > 0
		if fail {
			p.listFailures--
		}
		files := append([]*phoneFile(nil), p.files...)
		p.mu.Unlock()
		if fail {
			signed(500, map[string]string{"error": "try later"})
			return
		}
		type entry struct {
			ID     string  `json:"id"`
			Name   string  `json:"name"`
			Size   int64   `json:"size"`
			Mime   string  `json:"mime"`
			Taken  int64   `json:"taken"`
			Month  string  `json:"month"`
			SHA256 *string `json:"sha256"`
		}
		var list []entry
		for _, f := range files {
			size := int64(len(f.Data))
			if f.ListSize != 0 {
				size = f.ListSize
			}
			month := f.Month
			if month == "" {
				month = "undated"
				if f.Taken > 0 {
					month = time.UnixMilli(f.Taken).Format("2006-01")
				}
			}
			list = append(list, entry{f.ID, f.Name, size, f.Mime, f.Taken, month, f.ListSHA})
		}
		b, _ := json.Marshal(map[string]any{"files": list})
		p.stream(w, s, gs2.ListResource, 0, b, 200, "")
	case "/api/r/file":
		id := r.URL.Query().Get("f")
		var f *phoneFile
		p.mu.Lock()
		for _, x := range p.files {
			if x.ID == id {
				f = x
			}
		}
		p.mu.Unlock()
		if f == nil {
			signed(404, map[string]string{"error": "gone"})
			return
		}
		var start int64
		if rg := r.Header.Get("Range"); rg != "" {
			v := strings.TrimSuffix(strings.TrimPrefix(rg, "bytes="), "-")
			start, _ = strconv.ParseInt(v, 10, 64)
		}
		p.mu.Lock()
		p.fileAttempts[id]++
		attempt := p.fileAttempts[id]
		p.ranges = append(p.ranges, fmt.Sprintf("%s@%d", id, start))
		hook := p.fileHook
		p.mu.Unlock()
		action := ""
		if hook != nil {
			action = hook(f, start, attempt)
		}
		switch action {
		case "fail500":
			signed(500, map[string]string{"error": "io"})
			return
		case "gone":
			signed(404, map[string]string{"error": "gone"})
			return
		case "changed":
			signed(409, map[string]string{"error": "changed"})
			return
		}
		if start > int64(len(f.Data)) {
			signed(416, map[string]string{"error": "range"})
			return
		}
		status := 200
		if start > 0 {
			status = 206
		}
		p.stream(w, s, gs2.FileResource(id), start, f.Data[start:], status, action)
	case "/api/r/ack":
		var in struct {
			F      string `json:"f"`
			SHA256 string `json:"sha256"`
			Size   int64  `json:"size"`
		}
		_ = json.Unmarshal(body, &in)
		var f *phoneFile
		p.mu.Lock()
		for _, x := range p.files {
			if x.ID == in.F {
				f = x
			}
		}
		p.acks = append(p.acks, in.F)
		hook := p.ackHook
		delay := p.ackDelay
		p.mu.Unlock()
		if delay > 0 {
			time.Sleep(delay)
		}
		if f == nil {
			signed(400, map[string]string{"error": "bad ack"})
			return
		}
		action := ""
		if hook != nil {
			action = hook(f)
		}
		ok := in.SHA256 == sha(f.Data) && in.Size == int64(len(f.Data))
		switch action {
		case "mismatch":
			ok = false
		case "unsigned": // a forged "verified" answer without a valid signature
			writeJSON(w, 200, map[string]bool{"ok": true, "verified": true})
			return
		case "badsig":
			b, _ := json.Marshal(map[string]bool{"ok": true, "verified": true})
			w.Header().Set("X-GS-RMac", gs2.RespMAC(s.keys.Mac[:], ctr+1, 200, b))
			w.WriteHeader(200)
			w.Write(b)
			return
		case "fail500":
			signed(500, map[string]string{"error": "io"})
			return
		}
		if ok {
			p.mu.Lock()
			p.verified = append(p.verified, f.ID)
			p.mu.Unlock()
		}
		signed(200, map[string]bool{"ok": true, "verified": ok})
	case "/api/r/done":
		p.mu.Lock()
		s.expired = true
		p.mu.Unlock()
		signed(200, map[string]bool{"ok": true})
	default:
		signed(404, map[string]string{"error": "not found"})
	}
}

// stream writes an encrypted answer; action "stall" waits p.stall before sending any data,
// "cut" ends the stream half-way without the LAST frame.
func (p *fakePhone) stream(w http.ResponseWriter, s *phoneSession, resource string, start int64, data []byte, status int, action string) {
	n := p.streams.Add(1)
	defer p.streams.Add(-1)
	for {
		m := p.maxStreams.Load()
		if n <= m || p.maxStreams.CompareAndSwap(m, n) {
			break
		}
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("X-GS-Enc", "1")
	w.WriteHeader(status)
	fl, _ := w.(http.Flusher)
	if action == "stall" {
		if fl != nil {
			fl.Flush()
		}
		select {
		case <-time.After(p.stall):
		case <-w.(interface{ CloseNotify() <-chan bool }).CloseNotify():
			return
		}
	}
	prefix := make([]byte, gs2.PrefixSize)
	rand.Read(prefix)
	enc, err := gs2.NewEncoder(w, s.keys.Enc[:], prefix, s.sid, resource, uint64(start), gs2.FrameSize)
	if err != nil {
		return
	}
	limit := len(data)
	if action == "cut" {
		limit = len(data) / 2
	}
	const chunk = 64 << 10
	for off := 0; off < limit; off += chunk {
		end := min(off+chunk, limit)
		if _, err := enc.Write(data[off:end]); err != nil {
			return
		}
		if p.throttle > 0 {
			if fl != nil {
				fl.Flush()
			}
			time.Sleep(p.throttle)
		}
	}
	if action == "cut" {
		enc.FlushNotLast()
		return
	}
	enc.Close()
}

// pseudo returns n deterministic bytes.
func pseudo(n int, seed byte) []byte {
	b := make([]byte, n)
	x := uint32(seed) + 1
	for i := range b {
		x = x*1664525 + 1013904223
		b[i] = byte(x >> 24)
	}
	return b
}
