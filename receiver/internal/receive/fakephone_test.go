package receive

// A fake GallerySlim phone: the phone side of protocol gs2 (docs/protocol-gs2.md) on an
// httptest server bound to 127.0.0.1, with fault injection for the end-to-end tests.

import (
	"bytes"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"galleryslim/receiver/internal/gs2"
)

type fakeFile struct {
	id, name, mime, month string
	taken                 int64
	data                  []byte
	listSHA               bool // put the SHA-256 in the list
}

type fakeSession struct {
	sid     string
	n       []byte
	peer    string
	keys    *gs2.Keys
	highest uint64
	seen    map[uint64]bool
	ended   bool
	rid     string // §9 receiver id sent with the session request
	claimed bool   // §9 claim accepted
}

type reqRec struct {
	method, target, rng string
	ctr                 uint64
}

type ackRec struct {
	id, sha  string
	size     int64
	verified bool
}

type phone struct {
	t         *testing.T
	srv       *httptest.Server
	frameSize int
	name      string
	code      string

	mu       sync.Mutex
	files    []*fakeFile
	sessions map[string]*fakeSession

	// Fault injection (guarded by mu).
	dropAfter       map[string]int    // id → drop the connection after this many plaintext bytes (once each)
	tamper          map[string]int    // id → number of responses with a flipped ciphertext bit
	noLast          map[string]bool   // id → never send the LAST frame
	busy            map[string]int    // path → number of 503 answers to give
	changeOnce      map[string][]byte // id → answer 409 once and replace the data
	spurious401     int               // answer this many valid requests with 401
	wrongKey        bool              // derive keys from a different K (every MAC fails)
	expireAfterDrop bool              // once a drop happened, answer 410 to everything
	forgeAck        int               // flip "verified" in this many ack answers, keeping the old RMac
	noRMac          int               // send this many signed-route answers without X-GS-RMac
	ackSaysFalse    bool              // the phone's own hash differs: answer verified:false (signed)
	range416        map[string]bool   // id → refuse the next Range request with 416
	gone            map[string]bool   // id → listed, but the file request answers 404 (signed)
	frameDelay      time.Duration     // sleep between frames (to overlap streams)

	// §9 remembered pairing.
	pid       string            // sent in /api/hello ("" = an older phone)
	paired    map[string][]byte // rid → PK
	onClaimed func(sid string)  // called after an accepted claim (e.g. the owner approves)
	claims    []string          // "ok" or "401" per claim
	noClaim   bool              // a phone without the claim route (404)

	// §10 bring back.
	receiving   bool                           // the "Bring back"/"Receive from computer" screen is open
	wants       []map[string]any               // the wanted list
	wantSHA     map[string]string              // upload id → expected sha256 (wanted or accepted)
	wantSize    map[string]int64               // upload id → expected size
	refuse      func(name, mime string) string // offer: reason to refuse, "" accepts
	offers      int                            // offers received
	uploaded    map[string][]byte              // id → saved plaintext
	uploadTries map[string]int                 // id → upload requests
	dropUpload  map[string]int                 // id → number of uploads to cut off (network error)
	stopAfter   int                            // after this many saved uploads, answer 403 not receiving (0 = never)
	forgeUpload int                            // flip "saved" in this many upload answers, keeping the old RMac

	// Records.
	reqs              []reqRec
	acks              []ackRec
	onAck             func(ackRec)
	fileReqs          map[string][]string // id → Range headers seen
	active, maxActive int
	dropped           bool
	approvals         int
}

func newPhone(t *testing.T, frameSize int, files ...*fakeFile) *phone {
	p := &phone{
		t: t, frameSize: frameSize, name: "Pixel 8", code: "K7-42", files: files,
		sessions: map[string]*fakeSession{}, dropAfter: map[string]int{}, tamper: map[string]int{},
		noLast: map[string]bool{}, busy: map[string]int{}, changeOnce: map[string][]byte{},
		fileReqs: map[string][]string{}, range416: map[string]bool{}, gone: map[string]bool{},
		pid: "ICEiIyQlJicoKSorLC0uLw", paired: map[string][]byte{},
		wantSHA: map[string]string{}, wantSize: map[string]int64{}, uploaded: map[string][]byte{},
		uploadTries: map[string]int{}, dropUpload: map[string]int{},
	}
	p.srv = httptest.NewServer(http.HandlerFunc(p.serve)) // listens on 127.0.0.1 only
	t.Cleanup(p.srv.Close)
	return p
}

func (p *phone) addr() string { return strings.TrimPrefix(p.srv.URL, "http://") }

func b64(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return gs2.B64.EncodeToString(b)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

// approve is what the phone does after scanning the QR and the owner's confirmation.
func (p *phone) approve(qrText string) {
	sid, k, err := gs2.ParseQRText(qrText)
	if err != nil {
		p.t.Errorf("phone could not parse QR %q: %v", qrText, err)
		return
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	s := p.sessions[sid]
	if s == nil {
		p.t.Errorf("QR for unknown session")
		return
	}
	if p.wrongKey {
		k[0] ^= 0xFF
	}
	keys, err := gs2.DeriveKeys(sid, s.n, k)
	if err != nil {
		p.t.Error(err)
		return
	}
	s.keys = &keys
	p.approvals++
	if s.rid != "" { // §9: remember the receiver
		pk, err := gs2.DerivePairKey(sid, s.n, k)
		if err != nil {
			p.t.Error(err)
			return
		}
		p.paired[s.rid] = pk
	}
}

// approveKnown is the owner tapping a claimed computer and confirming (§9).
func (p *phone) approveKnown(sid string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	s := p.sessions[sid]
	if s == nil || !s.claimed {
		p.t.Errorf("approveKnown: session not claimed")
		return
	}
	keys, err := gs2.DeriveKeys(sid, s.n, p.paired[s.rid])
	if err != nil {
		p.t.Error(err)
		return
	}
	s.keys = &keys
	p.approvals++
}

// forget deletes the phone's PK for every receiver (the owner tapped Forget).
func (p *phone) forget() {
	p.mu.Lock()
	defer p.mu.Unlock()
	clear(p.paired)
}

func (p *phone) claimLog() []string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]string(nil), p.claims...)
}

// serveClaim checks a §9 claim: session pinned to the peer, rid remembered, proof correct.
func (p *phone) serveClaim(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(io.LimitReader(r.Body, 16<<10))
	var c struct{ Sid, Rid, Proof string }
	if json.Unmarshal(body, &c) != nil {
		writeJSON(w, 400, map[string]string{"error": "bad"})
		return
	}
	p.mu.Lock()
	s := p.sessions[c.Sid]
	pk := p.paired[c.Rid]
	ok := s != nil && !s.ended && s.peer == peerIP(r) && s.rid == c.Rid && pk != nil &&
		hmac.Equal([]byte(c.Proof), []byte(gs2.ClaimProof(pk, c.Sid, s.n)))
	if ok {
		s.claimed = true
		p.claims = append(p.claims, "ok")
	} else {
		p.claims = append(p.claims, "401")
	}
	cb := p.onClaimed
	p.mu.Unlock()
	if !ok {
		writeJSON(w, 401, map[string]string{"error": "auth"})
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
	if cb != nil {
		go cb(c.Sid)
	}
}

func peerIP(r *http.Request) string {
	h, _, _ := net.SplitHostPort(r.RemoteAddr)
	return h
}

func (p *phone) serve(w http.ResponseWriter, r *http.Request) {
	switch {
	case r.URL.Path == "/api/hello" && r.Method == http.MethodGet:
		w.Header().Set("X-GallerySlim", "2")
		hello := map[string]any{"app": "GallerySlim", "proto": 2, "name": p.name, "code": p.code, "port": 8765}
		if p.pid != "" {
			hello["pid"] = p.pid
		}
		writeJSON(w, 200, hello)
		return
	case r.URL.Path == "/api/r/claim" && r.Method == http.MethodPost && !p.noClaim:
		p.serveClaim(w, r)
		return
	case r.URL.Path == "/api/r/session" && r.Method == http.MethodPost:
		body, _ := io.ReadAll(io.LimitReader(r.Body, 16<<10))
		var req map[string]string
		if json.Unmarshal(body, &req) != nil || req["os"] == "" {
			writeJSON(w, 400, map[string]string{"error": "bad"})
			return
		}
		n := make([]byte, 32)
		rand.Read(n)
		s := &fakeSession{sid: b64(12), n: n, peer: peerIP(r), seen: map[uint64]bool{}}
		if gs2.ValidID(req["rid"]) {
			s.rid = req["rid"]
		}
		p.mu.Lock()
		p.sessions[s.sid] = s
		known := s.rid != "" && p.paired[s.rid] != nil
		p.mu.Unlock()
		ans := map[string]any{"sid": s.sid, "n": gs2.B64.EncodeToString(n), "code": p.code}
		if known {
			ans["known"] = true
		}
		writeJSON(w, 200, ans)
		return
	case r.URL.Path == "/api/r/state" && r.Method == http.MethodGet:
		p.mu.Lock()
		s := p.sessions[r.URL.Query().Get("sid")]
		state := "expired"
		if s != nil && !s.ended && s.peer == peerIP(r) {
			state = "waiting"
			if s.keys != nil {
				state = "approved"
			}
		}
		p.mu.Unlock()
		writeJSON(w, 200, map[string]string{"state": state})
		return
	}

	var body []byte
	if r.URL.Path != "/api/r/upload" { // an upload body is a stream: its MAC uses the empty hash
		body, _ = io.ReadAll(io.LimitReader(r.Body, 16<<10))
	}
	s, ctr, status := p.authorize(r, body)
	if status != 0 {
		writeJSON(w, status, map[string]string{"error": http.StatusText(status)}) // unsigned by design
		return
	}
	p.mu.Lock()
	if p.busy[r.URL.Path] > 0 {
		p.busy[r.URL.Path]--
		p.mu.Unlock()
		writeJSON(w, 503, map[string]string{"error": "busy"})
		return
	}
	p.mu.Unlock()

	switch {
	case r.URL.Path == "/api/r/list" && r.Method == http.MethodGet:
		p.serveList(w, s)
	case r.URL.Path == "/api/r/file" && r.Method == http.MethodGet:
		p.serveFile(w, r, s, ctr)
	case r.URL.Path == "/api/r/ack" && r.Method == http.MethodPost:
		p.serveAck(w, s, ctr, body)
	case r.URL.Path == "/api/r/wants" && r.Method == http.MethodGet:
		if p.notReceiving() {
			p.signed(w, s, ctr, 403, map[string]string{"error": "not receiving"})
			return
		}
		p.mu.Lock()
		list := p.wants
		if list == nil {
			list = []map[string]any{}
		}
		p.mu.Unlock()
		b, _ := json.Marshal(map[string]any{"files": list})
		p.stream(w, s, gs2.WantsResource, 0, b, 200, streamOpts{})
	case r.URL.Path == "/api/r/offer" && r.Method == http.MethodPost:
		p.serveOffer(w, s, ctr, body)
	case r.URL.Path == "/api/r/upload" && r.Method == http.MethodPost:
		p.serveUpload(w, r, s, ctr)
	case r.URL.Path == "/api/r/done" && r.Method == http.MethodPost:
		p.mu.Lock()
		s.ended = true
		p.mu.Unlock()
		p.signed(w, s, ctr, 200, map[string]bool{"ok": true})
	default:
		p.signed(w, s, ctr, 404, map[string]string{"error": "not found"})
	}
}

// authorize checks a signed request like the real phone: session, pinned peer, approval,
// MAC over the canonical string, counter window.
func (p *phone) authorize(r *http.Request, body []byte) (*fakeSession, uint64, int) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.expireAfterDrop && p.dropped {
		return nil, 0, 410
	}
	s := p.sessions[r.Header.Get("X-GS-Sid")]
	if s == nil || s.ended {
		return nil, 0, 410
	}
	if s.peer != peerIP(r) {
		return nil, 0, 403
	}
	if s.keys == nil {
		return nil, 0, 401
	}
	ctrText := r.Header.Get("X-GS-Ctr")
	ctr, err := strconv.ParseUint(ctrText, 10, 64)
	if err != nil || ctr == 0 || strconv.FormatUint(ctr, 10) != ctrText {
		return nil, 0, 401
	}
	canonical := gs2.Canonical(r.Method, r.RequestURI, r.Header.Get("Range"), s.sid, ctr, body)
	if !gs2.VerifyMAC(s.keys.Mac[:], canonical, r.Header.Get("X-GS-Mac")) {
		return nil, 0, 401
	}
	if (s.highest >= 1024 && ctr <= s.highest-1024) || s.seen[ctr] {
		return nil, 0, 401
	}
	s.seen[ctr] = true
	s.highest = max(s.highest, ctr)
	p.reqs = append(p.reqs, reqRec{r.Method, r.RequestURI, r.Header.Get("Range"), ctr})
	if p.spurious401 > 0 {
		p.spurious401--
		return nil, 0, 401
	}
	return s, ctr, 0
}

// signed writes a JSON answer to an authenticated request with its X-GS-RMac (§4a).
func (p *phone) signed(w http.ResponseWriter, s *fakeSession, ctr uint64, status int, v any) {
	body, _ := json.Marshal(v)
	p.mu.Lock()
	omit := p.noRMac > 0
	if omit {
		p.noRMac--
	}
	p.mu.Unlock()
	if !omit {
		w.Header().Set("X-GS-RMac", gs2.RespMAC(s.keys.Mac[:], ctr, status, body))
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	w.Write(body)
}

func (p *phone) serveList(w http.ResponseWriter, s *fakeSession) {
	type entry struct {
		ID     string  `json:"id"`
		Name   string  `json:"name"`
		Size   int     `json:"size"`
		Mime   string  `json:"mime"`
		Taken  int64   `json:"taken"`
		Month  string  `json:"month"`
		SHA256 *string `json:"sha256"`
	}
	p.mu.Lock()
	var list []entry
	for _, f := range p.files {
		e := entry{f.id, f.name, len(f.data), f.mime, f.taken, f.month, nil}
		if f.listSHA {
			h := sha256.Sum256(f.data)
			x := hex.EncodeToString(h[:])
			e.SHA256 = &x
		}
		list = append(list, e)
	}
	p.mu.Unlock()
	body, _ := json.Marshal(map[string]any{"files": list})
	p.stream(w, s, gs2.ListResource, 0, body, 200, streamOpts{})
}

type streamOpts struct {
	dropAfter int
	tamper    bool
	noLast    bool
}

type flushWriter struct {
	w http.ResponseWriter
}

func (f flushWriter) Write(b []byte) (int, error) {
	n, err := f.w.Write(b)
	f.w.(http.Flusher).Flush()
	return n, err
}

// tamperWriter flips one bit inside the first frame's ciphertext.
type tamperWriter struct {
	w    io.Writer
	pos  int
	done bool
}

func (t *tamperWriter) Write(b []byte) (int, error) {
	const at = gs2.PrefixSize + 4 + 2
	if !t.done && t.pos+len(b) > at {
		c := bytes.Clone(b)
		c[at-t.pos] ^= 0x10
		t.done = true
		t.pos += len(b)
		return t.w.Write(c)
	}
	t.pos += len(b)
	return t.w.Write(b)
}

func (p *phone) stream(w http.ResponseWriter, s *fakeSession, resource string, start int, data []byte, status int, o streamOpts) {
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("X-GS-Enc", "1")
	w.WriteHeader(status)
	var out io.Writer = flushWriter{w}
	if o.tamper {
		out = &tamperWriter{w: out}
	}
	prefix := make([]byte, gs2.PrefixSize)
	rand.Read(prefix)
	enc, err := gs2.NewEncoder(out, s.keys.Enc[:], prefix, s.sid, resource, uint64(start), p.frameSize)
	if err != nil {
		p.t.Error(err)
		return
	}
	sent := 0
	for sent < len(data) {
		n := min(p.frameSize, len(data)-sent)
		if o.dropAfter > 0 && sent+n > o.dropAfter {
			p.mu.Lock()
			p.dropped = true
			p.mu.Unlock()
			panic(http.ErrAbortHandler) // the Wi-Fi drops: connection closed mid-stream
		}
		enc.Write(data[sent : sent+n])
		sent += n
		if p.frameDelay > 0 {
			time.Sleep(p.frameDelay)
		}
	}
	if o.noLast {
		enc.FlushNotLast()
		return
	}
	enc.Close()
}

var rangeRe = regexp.MustCompile(`^bytes=([0-9]+)-$`)

func (p *phone) serveFile(w http.ResponseWriter, r *http.Request, s *fakeSession, ctr uint64) {
	id := r.URL.Query().Get("f")
	rng := r.Header.Get("Range")
	p.mu.Lock()
	p.fileReqs[id] = append(p.fileReqs[id], rng)
	var f *fakeFile
	for _, x := range p.files {
		if x.id == id {
			f = x
		}
	}
	if f == nil || p.gone[id] {
		p.mu.Unlock()
		p.signed(w, s, ctr, 404, map[string]string{"error": "no such file"})
		return
	}
	if nd, ok := p.changeOnce[id]; ok {
		f.data = nd
		delete(p.changeOnce, id)
		p.mu.Unlock()
		p.signed(w, s, ctr, 409, map[string]string{"error": "changed"})
		return
	}
	o := streamOpts{noLast: p.noLast[id]}
	if n, ok := p.dropAfter[id]; ok {
		o.dropAfter = n
		delete(p.dropAfter, id)
	}
	if p.tamper[id] > 0 {
		p.tamper[id]--
		o.tamper = true
	}
	data := f.data
	p.active++
	p.maxActive = max(p.maxActive, p.active)
	p.mu.Unlock()
	defer func() {
		p.mu.Lock()
		p.active--
		p.mu.Unlock()
	}()

	start, status := 0, 200
	if rng != "" {
		m := rangeRe.FindStringSubmatch(rng)
		if m == nil {
			p.signed(w, s, ctr, 416, map[string]string{"error": "range"})
			return
		}
		start, _ = strconv.Atoi(m[1])
		if start > len(data) || p.reject416(id) {
			p.signed(w, s, ctr, 416, map[string]string{"error": "range"})
			return
		}
		status = 206
	}
	p.stream(w, s, gs2.FileResource(id), start, data[start:], status, o)
}

func (p *phone) serveAck(w http.ResponseWriter, s *fakeSession, ctr uint64, body []byte) {
	var a struct {
		F      string `json:"f"`
		SHA256 string `json:"sha256"`
		Size   int64  `json:"size"`
	}
	if json.Unmarshal(body, &a) != nil {
		p.signed(w, s, ctr, 400, map[string]string{"error": "bad ack"})
		return
	}
	p.mu.Lock()
	var f *fakeFile
	for _, x := range p.files {
		if x.id == a.F {
			f = x
		}
	}
	rec := ackRec{id: a.F, sha: a.SHA256, size: a.Size}
	if f != nil {
		h := sha256.Sum256(f.data) // the phone hashes its whole current file
		rec.verified = hex.EncodeToString(h[:]) == a.SHA256 && int64(len(f.data)) == a.Size && !p.ackSaysFalse
	}
	p.acks = append(p.acks, rec)
	cb := p.onAck
	forge := p.forgeAck > 0
	if forge {
		p.forgeAck--
	}
	p.mu.Unlock()
	if cb != nil {
		cb(rec)
	}
	if forge {
		// An attacker in the middle flips the answer but cannot fix the signature.
		real, _ := json.Marshal(map[string]bool{"ok": true, "verified": rec.verified})
		fake, _ := json.Marshal(map[string]bool{"ok": true, "verified": !rec.verified})
		w.Header().Set("X-GS-RMac", gs2.RespMAC(s.keys.Mac[:], ctr, 200, real))
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(200)
		w.Write(fake)
		return
	}
	p.signed(w, s, ctr, 200, map[string]bool{"ok": true, "verified": rec.verified})
}

func (p *phone) notReceiving() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return !p.receiving || (p.stopAfter > 0 && len(p.uploaded) >= p.stopAfter)
}

func (p *phone) serveOffer(w http.ResponseWriter, s *fakeSession, ctr uint64, body []byte) {
	if p.notReceiving() {
		p.signed(w, s, ctr, 403, map[string]string{"error": "not receiving"})
		return
	}
	var o struct {
		Files []struct {
			Name   string `json:"name"`
			Size   int64  `json:"size"`
			SHA256 string `json:"sha256"`
			Mime   string `json:"mime"`
			Taken  int64  `json:"taken"`
		} `json:"files"`
	}
	if json.Unmarshal(body, &o) != nil || len(o.Files) > 500 {
		p.signed(w, s, ctr, 400, map[string]string{"error": "bad offer"})
		return
	}
	p.mu.Lock()
	p.offers++
	accepted := []string{}
	refused := []map[string]string{}
	for _, f := range o.Files {
		why := ""
		if !strings.HasPrefix(f.Mime, "image/") && !strings.HasPrefix(f.Mime, "video/") {
			why = "Only photos and videos"
		} else if p.refuse != nil {
			why = p.refuse(f.Name, f.Mime)
		}
		if why != "" {
			refused = append(refused, map[string]string{"name": f.Name, "why": why})
			continue
		}
		id := "o" + strconv.Itoa(len(p.wantSHA)+1)
		p.wantSHA[id], p.wantSize[id] = f.SHA256, f.Size
		accepted = append(accepted, id)
	}
	p.mu.Unlock()
	p.signed(w, s, ctr, 200, map[string]any{"ok": true, "accepted": accepted, "refused": refused})
}

// serveUpload decrypts the frame stream like the phone: frame by frame, then checks the
// LAST frame, size and SHA-256 against the wanted or accepted entry.
func (p *phone) serveUpload(w http.ResponseWriter, r *http.Request, s *fakeSession, ctr uint64) {
	id := r.URL.Query().Get("f")
	if p.notReceiving() {
		p.signed(w, s, ctr, 403, map[string]string{"error": "not receiving"})
		return
	}
	p.mu.Lock()
	p.uploadTries[id]++
	want, known := p.wantSHA[id]
	size := p.wantSize[id]
	drop := p.dropUpload[id] > 0
	if drop {
		p.dropUpload[id]--
	}
	p.mu.Unlock()
	if !known {
		p.signed(w, s, ctr, 404, map[string]string{"error": "unknown"})
		return
	}
	if drop {
		io.CopyN(io.Discard, r.Body, 100)
		panic(http.ErrAbortHandler)
	}
	if r.ContentLength != gs2.UploadLength(size, p.frameSize) {
		p.t.Errorf("upload %s: Content-Length %d, want %d", id, r.ContentLength, gs2.UploadLength(size, p.frameSize))
	}
	dec, err := gs2.NewDecoder(r.Body, s.keys.Enc[:], s.sid, gs2.UploadResource(id), 0, p.frameSize)
	if err != nil {
		p.t.Error(err)
		return
	}
	var got []byte
	for {
		b, _, err := dec.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			p.signed(w, s, ctr, 200, map[string]any{"ok": false, "why": "bad stream"})
			return
		}
		got = append(got, b...)
	}
	h := sha256.Sum256(got)
	saved := hex.EncodeToString(h[:]) == want && int64(len(got)) == size
	ans := map[string]any{"ok": saved, "saved": saved}
	if saved {
		ans["uri"] = "content://media/external/images/media/" + id
		p.mu.Lock()
		p.uploaded[id] = got
		p.mu.Unlock()
	} else {
		ans["why"] = "The file didn't match"
	}
	p.mu.Lock()
	forge := p.forgeUpload > 0
	if forge {
		p.forgeUpload--
	}
	p.mu.Unlock()
	if forge {
		real, _ := json.Marshal(ans)
		fake, _ := json.Marshal(map[string]any{"ok": !saved, "saved": !saved})
		w.Header().Set("X-GS-RMac", gs2.RespMAC(s.keys.Mac[:], ctr, 200, real))
		w.WriteHeader(200)
		w.Write(fake)
		return
	}
	p.signed(w, s, ctr, 200, ans)
}

// want adds a file to the wanted list.
func (p *phone) want(id, name, month string, data []byte) {
	h := sha256.Sum256(data)
	p.mu.Lock()
	defer p.mu.Unlock()
	p.wants = append(p.wants, map[string]any{"id": id, "name": name, "size": len(data), "sha256": hex.EncodeToString(h[:]), "month": month, "taken": nov2023})
	p.wantSHA[id], p.wantSize[id] = hex.EncodeToString(h[:]), int64(len(data))
}

// reject416 answers 416 once for files listed in range416.
func (p *phone) reject416(id string) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.range416[id] {
		delete(p.range416, id)
		return true
	}
	return false
}

// Accessors for assertions.
func (p *phone) ackList() []ackRec {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]ackRec(nil), p.acks...)
}

func (p *phone) ranges(id string) []string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]string(nil), p.fileReqs[id]...)
}

func (p *phone) requests() []reqRec {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]reqRec(nil), p.reqs...)
}
