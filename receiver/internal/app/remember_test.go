package app

// The app with remembered pairing (gs2 §9) against a small fake phone: QR first, then
// fingerprint-only; background mode claims by itself, opens the page when files arrive and
// goes back to waiting; a forgotten receiver never shows a QR in the background.

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"image"
	"image/jpeg"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"galleryslim/receiver/internal/client"
	"galleryslim/receiver/internal/discover"
	"galleryslim/receiver/internal/gs2"
)

const frame = 1024

type miniSession struct {
	sid, rid, peer string
	n              []byte
	keys           *gs2.Keys
	claimed, ended bool
}

// miniPhone is the phone side of gs2 with §9, without fault injection.
type miniPhone struct {
	t    *testing.T
	srv  *httptest.Server
	pid  string
	name string
	code string
	file []byte // one photo, "IMG_1.jpg"

	mu         sync.Mutex
	sessions   map[string]*miniSession
	paired     map[string][]byte // rid → PK
	autoKnown  bool              // approve every accepted claim (the owner taps + fingerprint)
	claims     []string
	receivers  []string // "receiver" names seen in session requests
	sessionReq int

	// §10
	receiving bool
	wanted    []byte            // when set, the wanted list holds this file as "w1"
	uploads   map[string][]byte // id → bytes saved
	offered   map[string]string // id → sha256
}

func newMiniPhone(t *testing.T) *miniPhone {
	img := image.NewRGBA(image.Rect(0, 0, 64, 48))
	var b bytes.Buffer
	jpeg.Encode(&b, img, nil)
	p := &miniPhone{t: t, pid: "ICEiIyQlJicoKSorLC0uLw", name: "Galaxy S24", code: "K7-42", file: b.Bytes(),
		sessions: map[string]*miniSession{}, paired: map[string][]byte{}, uploads: map[string][]byte{}, offered: map[string]string{}}
	p.srv = httptest.NewServer(http.HandlerFunc(p.serve))
	t.Cleanup(p.srv.Close)
	return p
}

func (p *miniPhone) addr() string { return strings.TrimPrefix(p.srv.URL, "http://") }

func (p *miniPhone) cand() discover.Candidate {
	return discover.Candidate{Addr: p.addr(), Name: p.name, Code: p.code, Pid: p.pid}
}

func (p *miniPhone) claimLog() []string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]string(nil), p.claims...)
}

// scan is the phone scanning the QR text and the owner confirming.
func (p *miniPhone) scan(text string) {
	sid, k, err := gs2.ParseQRText(text)
	if err != nil {
		p.t.Error(err)
		return
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	s := p.sessions[sid]
	keys, _ := gs2.DeriveKeys(sid, s.n, k)
	s.keys = &keys
	if s.rid != "" {
		p.paired[s.rid], _ = gs2.DerivePairKey(sid, s.n, k)
	}
}

func peer(r *http.Request) string { h, _, _ := net.SplitHostPort(r.RemoteAddr); return h }

func reply(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func (p *miniPhone) serve(w http.ResponseWriter, r *http.Request) {
	var body []byte
	if r.URL.Path != "/api/r/upload" { // a streamed upload is MACed with the empty-body hash
		body, _ = io.ReadAll(io.LimitReader(r.Body, 16<<10))
	}
	switch r.URL.Path {
	case "/api/hello":
		w.Header().Set("X-GallerySlim", "2")
		reply(w, 200, map[string]any{"app": "GallerySlim", "proto": 2, "name": p.name, "code": p.code, "port": 8765, "pid": p.pid})
		return
	case "/api/r/session":
		var req map[string]string
		json.Unmarshal(body, &req)
		n, sid := make([]byte, 32), make([]byte, 12)
		rand.Read(n)
		rand.Read(sid)
		s := &miniSession{sid: gs2.B64.EncodeToString(sid), n: n, peer: peer(r), rid: req["rid"]}
		p.mu.Lock()
		p.sessions[s.sid] = s
		p.sessionReq++
		p.receivers = append(p.receivers, req["receiver"])
		known := p.paired[s.rid] != nil
		p.mu.Unlock()
		reply(w, 200, map[string]any{"sid": s.sid, "n": gs2.B64.EncodeToString(n), "code": p.code, "known": known})
		return
	case "/api/r/claim":
		var c struct{ Sid, Rid, Proof string }
		json.Unmarshal(body, &c)
		p.mu.Lock()
		s, pk := p.sessions[c.Sid], p.paired[c.Rid]
		ok := s != nil && pk != nil && s.rid == c.Rid && s.peer == peer(r) &&
			hmac.Equal([]byte(c.Proof), []byte(gs2.ClaimProof(pk, c.Sid, s.n)))
		if ok {
			s.claimed = true
			p.claims = append(p.claims, "ok")
			if p.autoKnown {
				keys, _ := gs2.DeriveKeys(s.sid, s.n, pk)
				s.keys = &keys
			}
		} else {
			p.claims = append(p.claims, "401")
		}
		p.mu.Unlock()
		if !ok {
			reply(w, 401, map[string]string{"error": "auth"})
			return
		}
		reply(w, 200, map[string]bool{"ok": true})
		return
	case "/api/r/state":
		p.mu.Lock()
		s := p.sessions[r.URL.Query().Get("sid")]
		state := "expired"
		if s != nil && !s.ended {
			state = "waiting"
			if s.keys != nil {
				state = "approved"
			}
		}
		p.mu.Unlock()
		reply(w, 200, map[string]string{"state": state})
		return
	}
	p.mu.Lock()
	s := p.sessions[r.Header.Get("X-GS-Sid")]
	p.mu.Unlock()
	if s == nil || s.keys == nil || s.ended {
		reply(w, 410, map[string]string{"error": "expired"})
		return
	}
	ctr, _ := strconv.ParseUint(r.Header.Get("X-GS-Ctr"), 10, 64)
	if !gs2.VerifyMAC(s.keys.Mac[:], gs2.Canonical(r.Method, r.RequestURI, r.Header.Get("Range"), s.sid, ctr, body), r.Header.Get("X-GS-Mac")) {
		reply(w, 401, map[string]string{"error": "auth"})
		return
	}
	signedStatus := func(status int, v any) {
		b, _ := json.Marshal(v)
		w.Header().Set("X-GS-RMac", gs2.RespMAC(s.keys.Mac[:], ctr, status, b))
		w.WriteHeader(status)
		w.Write(b)
	}
	signed := func(v any) { signedStatus(200, v) }
	stream := func(resource string, data []byte) {
		w.Header().Set("X-GS-Enc", "1")
		w.WriteHeader(200)
		prefix := make([]byte, gs2.PrefixSize)
		rand.Read(prefix)
		enc, _ := gs2.NewEncoder(w, s.keys.Enc[:], prefix, s.sid, resource, 0, frame)
		enc.Write(data)
		enc.Close()
	}
	sum := sha256.Sum256(p.file)
	p.mu.Lock()
	receiving, wanted := p.receiving, p.wanted
	p.mu.Unlock()
	switch r.URL.Path {
	case "/api/r/wants":
		if !receiving {
			signedStatus(403, map[string]string{"error": "not receiving"})
			return
		}
		files := []map[string]any{}
		if wanted != nil {
			h := sha256.Sum256(wanted)
			files = append(files, map[string]any{"id": "w1", "name": "IMG_1.jpg", "size": len(wanted), "sha256": hex.EncodeToString(h[:]), "month": "2024-05"})
			p.mu.Lock()
			p.offered["w1"] = hex.EncodeToString(h[:])
			p.mu.Unlock()
		}
		b, _ := json.Marshal(map[string]any{"files": files})
		stream(gs2.WantsResource, b)
	case "/api/r/offer":
		var o struct {
			Files []struct{ Name, SHA256 string }
		}
		json.Unmarshal(body, &o)
		var acc []string
		p.mu.Lock()
		for _, f := range o.Files {
			id := "o" + strconv.Itoa(len(p.offered)+1)
			p.offered[id] = f.SHA256
			acc = append(acc, id)
		}
		p.mu.Unlock()
		signed(map[string]any{"ok": true, "accepted": acc, "refused": []any{}})
	case "/api/r/upload":
		id := r.URL.Query().Get("f")
		dec, _ := gs2.NewDecoder(r.Body, s.keys.Enc[:], s.sid, gs2.UploadResource(id), 0, frame)
		var got []byte
		for {
			b, _, err := dec.Next()
			if err != nil {
				break
			}
			got = append(got, b...)
		}
		h := sha256.Sum256(got)
		p.mu.Lock()
		ok := p.offered[id] == hex.EncodeToString(h[:])
		if ok {
			p.uploads[id] = got
		}
		p.mu.Unlock()
		signed(map[string]any{"ok": ok, "saved": ok})
	case "/api/r/list":
		list, _ := json.Marshal(map[string]any{"files": []map[string]any{{"id": "1", "name": "IMG_1.jpg", "size": len(p.file),
			"mime": "image/jpeg", "taken": time.Date(2024, 5, 1, 0, 0, 0, 0, time.UTC).UnixMilli(), "month": "2024-05", "sha256": nil}}})
		stream(gs2.ListResource, list)
	case "/api/r/file":
		stream(gs2.FileResource("1"), p.file)
	case "/api/r/ack":
		var a struct {
			SHA256 string `json:"sha256"`
		}
		json.Unmarshal(body, &a)
		signed(map[string]bool{"ok": true, "verified": a.SHA256 == hex.EncodeToString(sum[:])})
	case "/api/r/done":
		p.mu.Lock()
		s.ended = true
		p.mu.Unlock()
		signed(map[string]bool{"ok": true})
	}
}

func rememberDeps(t *testing.T, cfgDir string) Deps {
	d := baseDeps()
	d.Client = client.Config{FrameSize: frame}
	d.Pairs = LoadPairs(cfgDir)
	d.RID = "EBESExQVFhcYGRobHB0eHw"
	return d
}

func TestPageModeQRThenRemembered(t *testing.T) {
	cfg := t.TempDir()
	p2 := newMiniPhone(t)
	d := rememberDeps(t, cfg)
	d.Find = func(context.Context) ([]discover.Candidate, error) { return []discover.Candidate{p2.cand()}, nil }
	a := New(d, t.TempDir())
	a.setQRHook(p2.scan) // the phone "scans" the QR text
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go a.Run(ctx)
	s := waitFor(t, a, "done", func(s Status) bool { return s.Phase == PhaseDone })
	if s.FilesDone != 1 || len(s.Paired) != 1 || s.Paired[0].Name != "Galaxy S24" || s.Paired[0].ID != p2.pid {
		t.Fatalf("after QR: %+v", s)
	}
	st, err := os.Stat(filepath.Join(cfg, PairsFile))
	if err != nil {
		t.Fatal(err)
	}
	if st.Mode().Perm() != 0o600 {
		t.Errorf("pairs.json mode %v", st.Mode().Perm())
	}
	raw, _ := os.ReadFile(filepath.Join(cfg, PairsFile))
	pk := LoadPairs(cfg).Key(p2.pid)
	if !bytes.Equal(pk, p2.paired[d.RID]) {
		t.Fatal("stored PK differs from the phone's")
	}
	if strings.Contains(string(raw), hex.EncodeToString(pk)) {
		t.Error("PK stored as hex?")
	}
	// A preview of the received photo.
	if len(s.Rows) != 1 || !s.Rows[0].Preview {
		t.Fatalf("rows %+v", s.Rows)
	}
	if p, ok := a.PreviewPath(s.Rows[0].ID); !ok || filepath.Base(p) != "IMG_1.jpg" {
		t.Fatalf("preview path %q %v", p, ok)
	}
	if _, ok := a.PreviewPath("nope"); ok {
		t.Error("preview for an unknown row")
	}
	cancel()

	// Next run: a fresh app, same settings folder. No QR; the owner confirms on the phone.
	p2.mu.Lock()
	p2.autoKnown = false
	p2.mu.Unlock()
	d = rememberDeps(t, cfg)
	d.Find = func(context.Context) ([]discover.Candidate, error) { return []discover.Candidate{p2.cand()}, nil }
	a = New(d, t.TempDir())
	a.setQRHook(func(string) { t.Error("QR shown for a remembered phone") })
	if err := a.SetComputerName("  Kartik's PC‮  "); err != nil {
		t.Fatal(err)
	}
	ctx, cancel = context.WithCancel(context.Background())
	defer cancel()
	go a.Run(ctx)
	s = waitFor(t, a, "confirm on phone", func(s Status) bool { return s.Remembered && s.Phase == PhasePairing })
	if s.QRSVG != "" || !strings.Contains(s.Current, "Kartik's PC") {
		t.Errorf("remembered pairing status %+v", s)
	}
	p2.mu.Lock()
	for _, x := range p2.sessions {
		if x.claimed && x.keys == nil {
			keys, _ := gs2.DeriveKeys(x.sid, x.n, p2.paired[x.rid])
			x.keys = &keys
		}
	}
	last := p2.receivers[len(p2.receivers)-1]
	p2.mu.Unlock()
	if last != "Kartik's PC" {
		t.Errorf("receiver name sent %q", last)
	}
	waitFor(t, a, "done", func(s Status) bool { return s.Phase == PhaseDone && s.FilesDone == 1 })
	if got := p2.claimLog(); len(got) != 1 || got[0] != "ok" {
		t.Errorf("claims %v", got)
	}

	// Forget: the phone list empties and the file no longer holds the key.
	if err := a.ForgetPhone(p2.pid); err != nil {
		t.Fatal(err)
	}
	if err := a.ForgetPhone(p2.pid); err == nil {
		t.Error("forgot twice")
	}
	if LoadPairs(cfg).Len() != 0 || len(a.Status().Paired) != 0 {
		t.Error("phone still remembered")
	}
}

func TestBackgroundClaimsRemembersAndWaitsAgain(t *testing.T) {
	p := newMiniPhone(t)
	p.autoKnown = true
	cfg := t.TempDir()
	d := rememberDeps(t, cfg)
	// Paired earlier: the phone and the receiver share PK.
	pk := bytes.Repeat([]byte{5}, 32)
	p.paired[d.RID] = pk
	if err := d.Pairs.Remember(p.pid, "Galaxy S24", p.addr(), pk); err != nil {
		t.Fatal(err)
	}
	d.Background = true
	d.WatchEvery = 20 * time.Millisecond
	var quick atomic.Int32
	var lastAddrs atomic.Value
	d.Quick = func(_ context.Context, last []string) []discover.Candidate {
		quick.Add(1)
		lastAddrs.Store(strings.Join(last, ","))
		return []discover.Candidate{p.cand()}
	}
	d.Find = func(context.Context) ([]discover.Candidate, error) {
		t.Error("full search in background")
		return nil, nil
	}
	var opened atomic.Int32
	d.OnReceiving = func() { opened.Add(1) }
	a := New(d, t.TempDir())
	a.setQRHook(func(string) { t.Error("QR in background") })
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go a.Run(ctx)
	s := waitFor(t, a, "done", func(s Status) bool { return s.Phase == PhaseDone && s.FilesDone == 1 })
	if !s.Background {
		t.Error("status not background")
	}
	waitFor(t, a, "waiting again", func(s Status) bool { return s.Waiting && s.Phase == PhaseDone })
	if opened.Load() != 1 {
		t.Errorf("page opened %d times", opened.Load())
	}
	if lastAddrs.Load() != p.addr() {
		t.Errorf("probed last addresses %v", lastAddrs.Load())
	}
	// The phone stays visible with the same code: no second claim while held off.
	n := quick.Load()
	waitFor(t, a, "more looks", func(Status) bool { return quick.Load() >= n+3 })
	if got := p.claimLog(); len(got) != 1 {
		t.Errorf("claims %v", got)
	}
	// A new sharing run (new code) is claimed again.
	p.mu.Lock()
	p.code = "M3-17"
	p.mu.Unlock()
	waitFor(t, a, "second claim", func(Status) bool { return len(p.claimLog()) == 2 })
}

func TestBackgroundForgottenNeverShowsQR(t *testing.T) {
	p := newMiniPhone(t)
	cfg := t.TempDir()
	d := rememberDeps(t, cfg)
	d.Pairs.Remember(p.pid, "Galaxy S24", p.addr(), bytes.Repeat([]byte{5}, 32)) // the phone forgot us
	d.Background = true
	d.WatchEvery = 10 * time.Millisecond
	d.Quick = func(context.Context, []string) []discover.Candidate { return []discover.Candidate{p.cand()} }
	a := New(d, t.TempDir())
	a.setQRHook(func(string) { t.Error("QR in background") })
	var searched atomic.Bool
	a.d.Find = func(context.Context) ([]discover.Candidate, error) {
		searched.Store(true)
		return []discover.Candidate{p.cand()}, nil
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go a.Run(ctx)
	time.Sleep(200 * time.Millisecond)
	s := a.Status()
	if s.QRSVG != "" || !s.Waiting || s.Phase != PhaseFinding {
		t.Errorf("status %+v", s)
	}
	p.mu.Lock()
	reqs := p.sessionReq
	p.mu.Unlock()
	if reqs != 1 {
		t.Errorf("%d sessions created; the phone should be left alone after one refusal", reqs)
	}
	if searched.Load() {
		t.Error("full search without being asked")
	}
	// "Search for a phone" on the page: a normal search with QR.
	a.setQRHook(nil)
	a.Retry()
	waitFor(t, a, "QR after search", func(s Status) bool { return s.QRSVG != "" })
	if !searched.Load() {
		t.Error("no search")
	}
}

func TestPairsStore(t *testing.T) {
	dir := t.TempDir()
	ps := LoadPairs(dir)
	now := time.Unix(1000, 0)
	ps.now = func() time.Time { now = now.Add(time.Second); return now }
	pk := bytes.Repeat([]byte{1}, 32)
	if err := ps.Remember("bad", "x", "", pk); err == nil {
		t.Error("bad pid accepted")
	}
	if err := ps.Remember("ICEiIyQlJicoKSorLC0uLw", "Pixel‮ 8", "10.0.0.5:8765", pk); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < MaxPairs+2; i++ {
		id := make([]byte, 16)
		id[0] = byte(i + 1)
		ps.Remember(gs2.B64.EncodeToString(id), "p", "nonsense", pk)
	}
	if ps.Len() != MaxPairs || ps.Known("ICEiIyQlJicoKSorLC0uLw") {
		t.Errorf("oldest not dropped: %d", ps.Len())
	}
	re := LoadPairs(dir)
	if re.Len() != MaxPairs {
		t.Fatalf("reloaded %d", re.Len())
	}
	for _, p := range re.List() {
		if p.Addr != "" {
			t.Errorf("bad address kept: %q", p.Addr)
		}
	}
	// Hand-edited garbage is dropped entry by entry.
	os.WriteFile(filepath.Join(dir, PairsFile), []byte(`{"phones":[{"pid":"ICEiIyQlJicoKSorLC0uLw","pk":"short"},
		{"pid":"ICEiIyQlJicoKSorLC0uLw","pk":"`+gs2.B64.EncodeToString(pk)+`","name":"A\u0007B","addr":"10.0.0.9:8766"}]}`), 0o600)
	re = LoadPairs(dir)
	if l := re.List(); len(l) != 1 || l[0].Name != "AB" || l[0].Addr != "10.0.0.9:8766" {
		t.Errorf("%+v", l)
	}
	var nilStore *Pairs
	if nilStore.Known("x") || nilStore.Key("x") != nil || nilStore.List() != nil || nilStore.Len() != 0 {
		t.Error("nil store")
	}
}

func TestEnsureRID(t *testing.T) {
	dir := t.TempDir()
	r1 := EnsureRID(dir)
	if !gs2.ValidID(r1) || EnsureRID(dir) != r1 {
		t.Fatal("rid not kept")
	}
	if err := UpdateConfig(dir, func(c *Config) { c.Name = "Desk" }); err != nil {
		t.Fatal(err)
	}
	if c := LoadConfig(dir); c.RID != r1 || c.Name != "Desk" {
		t.Errorf("%+v", c)
	}
	if EnsureRID("") == "" {
		t.Error("no rid without settings folder")
	}
}

func TestBringBackWantedAndFreeChoice(t *testing.T) {
	p := newMiniPhone(t)
	p.autoKnown = true
	cfg := t.TempDir()
	d := rememberDeps(t, cfg)
	pk := bytes.Repeat([]byte{5}, 32)
	p.paired[d.RID] = pk
	d.Pairs.Remember(p.pid, "Galaxy S24", p.addr(), pk)
	d.Find = func(context.Context) ([]discover.Candidate, error) { return []discover.Candidate{p.cand()}, nil }
	var picked atomic.Value
	d.PickFiles = func(string) ([]string, error) { return picked.Load().([]string), nil }
	root := t.TempDir()
	// The phone wants back a photo this computer received earlier.
	os.MkdirAll(filepath.Join(root, "2024-05"), 0o755)
	os.WriteFile(filepath.Join(root, "2024-05", "IMG_1.jpg"), p.file, 0o644)
	p.receiving, p.wanted = true, p.file
	a := New(d, root)
	if err := a.SendToPhone([]string{"/x.jpg"}); err == nil {
		t.Error("send accepted before the phone takes files")
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go a.Run(ctx)
	s := waitFor(t, a, "sent back", func(s Status) bool { return s.Direction == DirToPhone && s.Phase == PhaseDone })
	if len(s.Rows) != 1 || s.Rows[0].State != RowSent || !s.Rows[0].Preview || !bytes.Equal(p.uploads["w1"], p.file) {
		t.Fatalf("wanted: %+v", s)
	}
	if p, ok := a.PreviewPath("w1"); !ok || p != filepath.Join(root, "2024-05", "IMG_1.jpg") {
		t.Errorf("preview of a sent photo: %q", p)
	}
	cancel()

	// Free choice: the phone has no wanted list; the person picks a file on the page.
	p.mu.Lock()
	p.wanted = nil
	p.mu.Unlock()
	a = New(d, root)
	ctx, cancel = context.WithCancel(context.Background())
	defer cancel()
	go a.Run(ctx)
	waitFor(t, a, "phone takes files", func(s Status) bool { return s.PhoneReceiving && s.Direction == DirToPhone })
	other := filepath.Join(t.TempDir(), "pick.jpg")
	os.WriteFile(other, append([]byte{0xFF, 0xD8}, bytes.Repeat([]byte{1}, 3000)...), 0o644)
	picked.Store([]string{other})
	if err := a.PickAndSend(); err != nil {
		t.Fatal(err)
	}
	if st := a.Status(); st.Phase != PhaseReceiving || st.Direction != DirToPhone {
		t.Errorf("not sending right after the request: %s %s", st.Phase, st.Direction)
	}
	if err := a.SendToPhone([]string{other}); err == nil {
		t.Error("second send accepted while one is running")
	}
	s = waitFor(t, a, "picked sent", func(s Status) bool { return len(s.Rows) == 1 && s.Rows[0].State == RowSent })
	if !s.PhoneReceiving || s.BytesDone != 3002 || s.BytesTotal != 3002 {
		t.Errorf("after free choice: %+v", s)
	}
	if len(p.uploads) != 2 {
		t.Errorf("uploads %d", len(p.uploads))
	}
}
