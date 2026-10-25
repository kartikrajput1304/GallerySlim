package receive

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io/fs"
	"math/rand"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"

	"galleryslim/receiver/internal/client"
	"galleryslim/receiver/internal/store"
)

const testFrame = 1024

func payload(seed int64, n int) []byte {
	b := make([]byte, n)
	rand.New(rand.NewSource(seed)).Read(b)
	return b
}

func shaHex(b []byte) string {
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}

var nov2023 = time.Date(2023, 11, 14, 9, 30, 0, 0, time.UTC).UnixMilli()

func photo(id, name string, data []byte) *fakeFile {
	return &fakeFile{id: id, name: name, mime: "image/jpeg", month: "2023-11", taken: nov2023, data: data}
}

type recorder struct {
	mu      sync.Mutex
	results []Result
	listed  int
	bytes   int64
}

func (r *recorder) Listed(f []client.File, _ int64) { r.mu.Lock(); r.listed = len(f); r.mu.Unlock() }
func (r *recorder) FileStarted(client.File, string) {}
func (r *recorder) Progress(d int64)                { r.mu.Lock(); r.bytes += d; r.mu.Unlock() }
func (r *recorder) FileDone(res Result) {
	r.mu.Lock()
	r.results = append(r.results, res)
	r.mu.Unlock()
}

func (r *recorder) byID() map[string]Result {
	r.mu.Lock()
	defer r.mu.Unlock()
	m := map[string]Result{}
	for _, x := range r.results {
		m[x.File.ID] = x
	}
	return m
}

type session struct {
	sum  Summary
	err  error
	rec  *recorder
	qr   string
	code string
}

// receiveFrom pairs with the phone (the phone "scans" the QR), then runs a receive into root.
func receiveFrom(t *testing.T, p *phone, root string, tweak func(*store.Folder, *Options)) session {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	cfg := client.Config{FrameSize: p.frameSize, BusyWait: 10 * time.Millisecond, IdleTimeout: 5 * time.Second}
	var out session
	s, err := Pair(ctx, PairOptions{
		HTTP: client.NewHTTPClient(), Addr: p.addr(), Config: cfg,
		Info:      client.SessionInfo{Receiver: "test-laptop", OS: "linux", Folder: "Photos"},
		PollEvery: 5 * time.Millisecond,
		ShowQR: func(text, code string) {
			out.qr, out.code = text, code
			go func() { time.Sleep(20 * time.Millisecond); p.approve(text) }()
		},
	})
	if err != nil {
		out.err = err
		return out
	}
	folder, err := store.Open(root)
	if err != nil {
		t.Fatal(err)
	}
	out.rec = &recorder{}
	o := Options{Session: s, Folder: folder, Phone: p.name, Events: out.rec, RetryWait: 5 * time.Millisecond}
	if tweak != nil {
		tweak(folder, &o)
	}
	out.sum, out.err = Run(ctx, o)
	return out
}

func mustRead(t *testing.T, path string) []byte {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestE2EPairingWithApproval(t *testing.T) {
	p := newPhone(t, testFrame, photo("1", "a.jpg", payload(1, 10)))
	got := receiveFrom(t, p, t.TempDir(), nil)
	if got.err != nil {
		t.Fatal(got.err)
	}
	if !regexp.MustCompile(`^gs2:[A-Za-z0-9_-]{16}:[A-Za-z0-9_-]{43}$`).MatchString(got.qr) {
		t.Errorf("QR text has the wrong shape: %d chars", len(got.qr))
	}
	if got.code != "K7-42" || p.approvals != 1 || got.sum.Verified != 1 {
		t.Errorf("code %q approvals %d summary %+v", got.code, p.approvals, got.sum)
	}
	// Counters start at 1 and never repeat.
	seen := map[uint64]bool{}
	for _, r := range p.requests() {
		if r.ctr == 0 || seen[r.ctr] {
			t.Errorf("bad counter %d", r.ctr)
		}
		seen[r.ctr] = true
	}
	if !seen[1] {
		t.Error("first counter was not 1")
	}
}

func TestE2EPairingExpired(t *testing.T) {
	p := newPhone(t, testFrame)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, err := Pair(ctx, PairOptions{
		HTTP: client.NewHTTPClient(), Addr: p.addr(), Info: client.SessionInfo{OS: "linux"},
		PollEvery: 5 * time.Millisecond,
		ShowQR: func(text, _ string) {
			p.mu.Lock()
			for _, s := range p.sessions {
				s.ended = true // the phone dropped the request
			}
			p.mu.Unlock()
		},
	})
	if !errors.Is(err, ErrPairingExpired) {
		t.Fatalf("err = %v", err)
	}
}

func TestE2EDownloadThreeFiles(t *testing.T) {
	small := payload(2, 100)
	big := payload(3, 3*testFrame+17) // more than two frames
	exact := payload(4, 2*testFrame)  // ends on a full frame flagged LAST
	p := newPhone(t, testFrame, photo("10", "small.jpg", small), photo("11", "big.jpg", big), photo("12", "exact.jpg", exact))
	root := t.TempDir()
	got := receiveFrom(t, p, root, nil)
	if got.err != nil || got.sum.Verified != 3 || got.sum.Failed != 0 {
		t.Fatalf("err %v summary %+v", got.err, got.sum)
	}
	for name, want := range map[string][]byte{"small.jpg": small, "big.jpg": big, "exact.jpg": exact} {
		path := filepath.Join(root, "2023-11", name)
		if !bytes.Equal(mustRead(t, path), want) {
			t.Errorf("%s content differs", name)
		}
		fi, _ := os.Stat(path)
		if fi.ModTime().UnixMilli() != nov2023 {
			t.Errorf("%s mtime %v", name, fi.ModTime())
		}
	}
	acks := p.ackList()
	if len(acks) != 3 {
		t.Fatalf("%d acks", len(acks))
	}
	for _, a := range acks {
		if !a.verified {
			t.Errorf("ack %s not verified", a.id)
		}
	}
	if got.rec.bytes != int64(len(small)+len(big)+len(exact)) {
		t.Errorf("progress bytes %d", got.rec.bytes)
	}
	parts, _ := os.ReadDir(filepath.Join(root, store.MetaDir, "parts"))
	if len(parts) != 0 {
		t.Errorf("%d part files left", len(parts))
	}
	if _, err := os.Stat(filepath.Join(root, store.MetaDir, "manifest.json")); err != nil {
		t.Error("manifest not written")
	}
	p.mu.Lock()
	ended := false
	for _, s := range p.sessions {
		ended = ended || s.ended
	}
	p.mu.Unlock()
	if !ended {
		t.Error("session not ended with /api/r/done")
	}
}

func TestE2EAtMostThreeStreams(t *testing.T) {
	var files []*fakeFile
	for i := 0; i < 8; i++ {
		files = append(files, photo(string(rune('a'+i)), "f"+string(rune('a'+i))+".jpg", payload(int64(i), 4*testFrame)))
	}
	p := newPhone(t, testFrame, files...)
	p.frameDelay = 3 * time.Millisecond
	got := receiveFrom(t, p, t.TempDir(), nil)
	if got.err != nil || got.sum.Verified != 8 {
		t.Fatalf("%v %+v", got.err, got.sum)
	}
	if p.maxActive > 3 || p.maxActive < 2 {
		t.Errorf("max concurrent streams %d", p.maxActive)
	}
}

func TestE2EResumeAfterDroppedConnection(t *testing.T) {
	data := payload(5, 10*testFrame+5)
	p := newPhone(t, testFrame, photo("20", "video.mp4", data))
	p.dropAfter["20"] = 4 * testFrame
	root := t.TempDir()
	got := receiveFrom(t, p, root, nil)
	if got.err != nil || got.sum.Verified != 1 {
		t.Fatalf("%v %+v", got.err, got.sum)
	}
	rs := p.ranges("20")
	if len(rs) != 2 || rs[0] != "" || rs[1] != "bytes=3072-" {
		t.Fatalf("ranges %q", rs)
	}
	if !bytes.Equal(mustRead(t, filepath.Join(root, "2023-11", "video.mp4")), data) {
		t.Error("content differs after resume")
	}
}

func TestE2EResumeInNewSession(t *testing.T) {
	data := payload(6, 6*testFrame)
	p := newPhone(t, testFrame, photo("30", "clip.mp4", data))
	p.dropAfter["30"] = 3 * testFrame
	p.expireAfterDrop = true
	root := t.TempDir()
	first := receiveFrom(t, p, root, nil)
	if !errors.Is(first.err, client.ErrExpired) {
		t.Fatalf("first session: %v", first.err)
	}
	if _, err := os.Stat(filepath.Join(root, "2023-11", "clip.mp4")); err == nil {
		t.Fatal("incomplete file was saved")
	}
	p.mu.Lock()
	p.expireAfterDrop = false
	p.mu.Unlock()
	second := receiveFrom(t, p, root, nil)
	if second.err != nil || second.sum.Verified != 1 {
		t.Fatalf("second session: %v %+v", second.err, second.sum)
	}
	rs := p.ranges("30")
	if last := rs[len(rs)-1]; last != "bytes=2048-" {
		t.Errorf("second session did not resume with Range: %q", rs)
	}
	if !bytes.Equal(mustRead(t, filepath.Join(root, "2023-11", "clip.mp4")), data) {
		t.Error("content differs")
	}
}

func TestE2ETamperedFrameRejected(t *testing.T) {
	good := payload(7, 50)
	bad := payload(8, 3*testFrame)
	p := newPhone(t, testFrame, photo("40", "good.jpg", good), photo("41", "bad.jpg", bad))
	p.tamper["41"] = 1000 // every response for this file is tampered with
	root := t.TempDir()
	got := receiveFrom(t, p, root, nil)
	if got.err != nil {
		t.Fatal(got.err)
	}
	res := got.rec.byID()
	if res["41"].Outcome != Failed || res["40"].Outcome != Verified {
		t.Fatalf("results %+v", res)
	}
	if _, err := os.Stat(filepath.Join(root, "2023-11", "bad.jpg")); err == nil {
		t.Error("tampered file was saved")
	}
	for _, a := range p.ackList() {
		if a.id == "41" {
			t.Error("tampered file was acknowledged")
		}
	}
	// Only authenticated bytes may be in the part file (here: none, the first frame is bad).
	part := filepath.Join(root, store.MetaDir, "parts", store.PartName(p.name, "41", int64(len(bad)), nov2023, ""))
	if b, err := os.ReadFile(part); err == nil && !bytes.HasPrefix(bad, b) {
		t.Error("part file holds unauthenticated bytes")
	}

	// A single tampered response is retried and the file arrives intact.
	p2 := newPhone(t, testFrame, photo("42", "once.jpg", bad))
	p2.tamper["42"] = 1
	root2 := t.TempDir()
	got2 := receiveFrom(t, p2, root2, nil)
	if got2.err != nil || got2.sum.Verified != 1 || !bytes.Equal(mustRead(t, filepath.Join(root2, "2023-11", "once.jpg")), bad) {
		t.Fatalf("%v %+v", got2.err, got2.sum)
	}
}

func TestE2EMissingLastFrameRejected(t *testing.T) {
	data := payload(9, 4*testFrame) // ends on a full frame, so only the LAST flag is missing
	p := newPhone(t, testFrame, photo("50", "nolast.jpg", data))
	p.noLast["50"] = true
	root := t.TempDir()
	got := receiveFrom(t, p, root, nil)
	if got.err != nil {
		t.Fatal(got.err)
	}
	if r := got.rec.byID()["50"]; r.Outcome != Failed {
		t.Fatalf("outcome %v", r.Outcome)
	}
	if _, err := os.Stat(filepath.Join(root, "2023-11", "nolast.jpg")); err == nil {
		t.Error("file without LAST frame was saved")
	}
	if len(p.ackList()) != 0 {
		t.Error("file without LAST frame was acknowledged")
	}
	if len(p.ranges("50")) != 5 {
		t.Errorf("attempts: %q", p.ranges("50"))
	}
}

func TestE2EWrongMAC(t *testing.T) {
	p := newPhone(t, testFrame, photo("60", "a.jpg", payload(10, 10)))
	p.wrongKey = true // the phone derived different keys: every MAC fails
	root := t.TempDir()
	got := receiveFrom(t, p, root, nil)
	if !errors.Is(got.err, client.ErrAuth) {
		t.Fatalf("err = %v", got.err)
	}
	entries, _ := os.ReadDir(root)
	for _, e := range entries {
		if e.Name() != store.MetaDir {
			t.Errorf("unexpected %s written", e.Name())
		}
	}

	// One spurious 401 is retried with a fresh counter.
	p2 := newPhone(t, testFrame, photo("61", "b.jpg", payload(11, 10)))
	p2.spurious401 = 1
	got2 := receiveFrom(t, p2, t.TempDir(), nil)
	if got2.err != nil || got2.sum.Verified != 1 {
		t.Fatalf("%v %+v", got2.err, got2.sum)
	}
	reqs := p2.requests()
	if len(reqs) < 2 || reqs[0].target != "/api/r/list" || reqs[1].target != "/api/r/list" || reqs[1].ctr <= reqs[0].ctr {
		t.Errorf("401 was not retried with a new counter: %+v", reqs)
	}
}

func TestE2ENameClash(t *testing.T) {
	a, b := payload(12, 30), payload(13, 40)
	p := newPhone(t, testFrame, photo("70", "IMG_1.jpg", a), photo("71", "IMG_1.jpg", b))
	root := t.TempDir()
	got := receiveFrom(t, p, root, nil)
	if got.err != nil || got.sum.Verified != 2 {
		t.Fatalf("%v %+v", got.err, got.sum)
	}
	res := got.rec.byID()
	rels := []string{res["70"].Rel, res["71"].Rel}
	sort.Strings(rels)
	if rels[0] != "2023-11/IMG_1 (2).jpg" || rels[1] != "2023-11/IMG_1.jpg" {
		t.Fatalf("rels %q", rels)
	}
	if !bytes.Equal(mustRead(t, filepath.Join(root, res["70"].Rel)), a) || !bytes.Equal(mustRead(t, filepath.Join(root, res["71"].Rel)), b) {
		t.Error("contents swapped")
	}
}

func TestE2ECaseOnlyClash(t *testing.T) {
	a, b := payload(14, 30), payload(15, 40)
	p := newPhone(t, testFrame, photo("80", "IMG_2.jpg", a), photo("81", "img_2.JPG", b))
	root := t.TempDir()
	got := receiveFrom(t, p, root, func(f *store.Folder, o *Options) {
		f.CaseInsensitive = true // as on Windows and macOS
		o.Parallel = 1
	})
	if got.err != nil || got.sum.Verified != 2 {
		t.Fatalf("%v %+v", got.err, got.sum)
	}
	res := got.rec.byID()
	if res["80"].Rel != "2023-11/IMG_2.jpg" || res["81"].Rel != "2023-11/img_2 (2).JPG" {
		t.Fatalf("rels %q %q", res["80"].Rel, res["81"].Rel)
	}
}

func TestE2EStaleManifest(t *testing.T) {
	a, b, c := payload(16, 300), payload(17, 300), payload(18, 300)
	p := newPhone(t, testFrame, photo("90", "A.jpg", a), photo("91", "B.jpg", b), photo("92", "C.jpg", c))
	root := t.TempDir()
	if got := receiveFrom(t, p, root, nil); got.err != nil || got.sum.Verified != 3 {
		t.Fatalf("first: %v %+v", got.err, got.sum)
	}
	dir := filepath.Join(root, "2023-11")
	os.Remove(filepath.Join(dir, "A.jpg"))       // deleted on the laptop
	changed := bytes.Repeat([]byte{'x'}, len(b)) // edited on the laptop, same size
	os.WriteFile(filepath.Join(dir, "B.jpg"), changed, 0o644)

	p.mu.Lock()
	p.fileReqs = map[string][]string{}
	p.acks = nil
	p.mu.Unlock()
	got := receiveFrom(t, p, root, nil)
	if got.err != nil || got.sum.Verified != 3 || got.sum.Already != 1 {
		t.Fatalf("second: %v %+v", got.err, got.sum)
	}
	res := got.rec.byID()
	if res["90"].Rel != "2023-11/A.jpg" || res["90"].Already {
		t.Errorf("A: %+v", res["90"])
	}
	if res["91"].Rel != "2023-11/B (2).jpg" || res["91"].Already {
		t.Errorf("B: %+v", res["91"])
	}
	if !bytes.Equal(mustRead(t, filepath.Join(dir, "B.jpg")), changed) {
		t.Error("the user's edited B.jpg was overwritten")
	}
	if !res["92"].Already || len(p.ranges("92")) != 0 {
		t.Errorf("C was downloaded again: %+v %q", res["92"], p.ranges("92"))
	}
	acked := map[string]bool{}
	for _, x := range p.ackList() {
		acked[x.id] = x.verified
	}
	if !acked["90"] || !acked["91"] || !acked["92"] {
		t.Errorf("acks %v", acked)
	}
}

func TestE2EWindowsReservedNamesCleaned(t *testing.T) {
	names := map[string]string{
		"100": "CON.jpg", "101": "aux", "102": "nul.txt", "103": "LPT1.png",
		"104": `a<b>:c?.jpg`, "105": "evil‮gpj.exe", "106": "../../escape.jpg", "107": "trailing. . ",
	}
	want := map[string]string{
		"100": "_CON.jpg", "101": "_aux.jpg", "102": "_nul.txt", "103": "_LPT1.png",
		"104": "a_b__c_.jpg", "105": "evilgpj.exe", "106": "_.._.._escape.jpg", "107": "trailing.jpg",
	}
	var files []*fakeFile
	for id, n := range names {
		files = append(files, photo(id, n, payload(int64(len(n)), 20)))
	}
	p := newPhone(t, testFrame, files...)
	root := t.TempDir()
	got := receiveFrom(t, p, root, nil)
	if got.err != nil || got.sum.Verified != len(names) {
		t.Fatalf("%v %+v", got.err, got.sum)
	}
	res := got.rec.byID()
	for id, w := range want {
		if res[id].Rel != "2023-11/"+w {
			t.Errorf("%q saved as %q, want %q", names[id], res[id].Rel, w)
		}
	}
	// Nothing outside the chosen folder.
	parent := filepath.Dir(root)
	filepath.WalkDir(parent, func(path string, d fs.DirEntry, err error) error {
		if err == nil && !d.IsDir() && !strings.HasPrefix(path, root+string(filepath.Separator)) {
			t.Errorf("file outside the folder: %s", path)
		}
		return nil
	})
}

// hashesIn returns the SHA-256 of every file in dir.
func hashesIn(dir string) map[string]string {
	out := map[string]string{}
	entries, _ := os.ReadDir(dir)
	for _, e := range entries {
		if b, err := os.ReadFile(filepath.Join(dir, e.Name())); err == nil {
			out[shaHex(b)] = e.Name()
		}
	}
	return out
}

func TestE2EAckOnlyAfterOnDiskRehash(t *testing.T) {
	data := payload(19, 2*testFrame+3)
	p := newPhone(t, testFrame, photo("110", "check.jpg", data))
	root := t.TempDir()
	dir := filepath.Join(root, "2023-11")
	// At every ack, the acknowledged bytes must already be a file in its final place.
	var ackedOnDisk []bool
	p.onAck = func(a ackRec) {
		_, ok := hashesIn(dir)[a.sha]
		ackedOnDisk = append(ackedOnDisk, ok)
	}
	// Simulate the disk returning different bytes than were written (once).
	tampered := false
	testAfterSave = func(p string) {
		if !tampered {
			tampered = true
			b, _ := os.ReadFile(p)
			b[0] ^= 0xFF
			os.WriteFile(p, b, 0o644)
		}
	}
	defer func() { testAfterSave = nil }()

	got := receiveFrom(t, p, root, nil)
	if got.err != nil || got.sum.Verified != 1 {
		t.Fatalf("%v %+v", got.err, got.sum)
	}
	acks := p.ackList()
	if len(acks) != 2 {
		t.Fatalf("%d acks", len(acks))
	}
	modified := bytes.Clone(data)
	modified[0] ^= 0xFF
	if acks[0].sha != shaHex(modified) || acks[0].verified {
		t.Error("first ack did not carry the hash of the bytes on disk")
	}
	if acks[1].sha != shaHex(data) || !acks[1].verified {
		t.Error("second ack wrong")
	}
	if len(ackedOnDisk) != 2 || !ackedOnDisk[0] || !ackedOnDisk[1] {
		t.Errorf("an ack was sent for bytes not on disk: %v", ackedOnDisk)
	}
	// The unconfirmed copy is kept (never deleted because of a phone answer); the new attempt
	// went to a new name.
	if !bytes.Equal(mustRead(t, filepath.Join(dir, "check.jpg")), modified) {
		t.Error("the unconfirmed copy was not kept as it was")
	}
	if !bytes.Equal(mustRead(t, filepath.Join(dir, "check (2).jpg")), data) {
		t.Error("the second attempt is wrong")
	}
	if r := got.rec.byID()["110"]; r.Rel != "2023-11/check (2).jpg" || !strings.Contains(r.Detail, "kept") {
		t.Errorf("result %+v", r)
	}
}

func TestE2ENotVerifiedNeverDeletes(t *testing.T) {
	data := payload(24, 1500)
	p := newPhone(t, testFrame, photo("140", "keep.jpg", data))
	p.ackSaysFalse = true // the phone's own hash differs (signed answer)
	root := t.TempDir()
	got := receiveFrom(t, p, root, nil)
	if got.err != nil || got.sum.Saved != 1 || got.sum.Verified != 0 {
		t.Fatalf("%v %+v", got.err, got.sum)
	}
	dir := filepath.Join(root, "2023-11")
	for _, n := range []string{"keep.jpg", "keep (2).jpg"} {
		if !bytes.Equal(mustRead(t, filepath.Join(dir, n)), data) {
			t.Errorf("%s missing or changed", n)
		}
	}
	if r := got.rec.byID()["140"]; !strings.HasPrefix(r.Detail, "Couldn't confirm — kept") {
		t.Errorf("detail %q", r.Detail)
	}
	// A later session must not treat the unconfirmed copies as already received.
	f, _ := store.Open(root)
	if _, ok := f.FindExisting(p.name, "140", int64(len(data)), "", "2023-11", "keep.jpg"); ok {
		t.Error("unconfirmed copy offered as already received")
	}
}

func TestE2EForgedAckNotTrusted(t *testing.T) {
	data := payload(25, 700)
	// One forged answer (verified flipped, old signature kept): retried, then believed.
	p := newPhone(t, testFrame, photo("150", "a.jpg", data))
	p.forgeAck = 1
	got := receiveFrom(t, p, t.TempDir(), nil)
	if got.err != nil || got.sum.Verified != 1 || len(p.ackList()) != 2 {
		t.Fatalf("%v %+v acks=%d", got.err, got.sum, len(p.ackList()))
	}

	// Every answer forged to say "not verified": nothing is deleted or re-downloaded.
	p2 := newPhone(t, testFrame, photo("151", "b.jpg", data))
	p2.forgeAck = 1000
	root := t.TempDir()
	got2 := receiveFrom(t, p2, root, nil)
	if got2.err != nil || got2.sum.Saved != 1 {
		t.Fatalf("%v %+v", got2.err, got2.sum)
	}
	if entries, _ := os.ReadDir(filepath.Join(root, "2023-11")); len(entries) != 1 || !bytes.Equal(mustRead(t, filepath.Join(root, "2023-11", "b.jpg")), data) {
		t.Errorf("forged answers changed the folder: %d files", len(entries))
	}
	if len(p2.ranges("151")) != 1 {
		t.Error("forged answer caused a re-download")
	}

	// Forged to say "verified" when the phone says no: not believed.
	p3 := newPhone(t, testFrame, photo("152", "c.jpg", data))
	p3.ackSaysFalse = true
	p3.forgeAck = 1000
	got3 := receiveFrom(t, p3, t.TempDir(), nil)
	if got3.err != nil || got3.sum.Verified != 0 {
		t.Fatalf("forged 'verified' was believed: %v %+v", got3.err, got3.sum)
	}
}

func TestE2EMissingRMacNotTrusted(t *testing.T) {
	data := payload(26, 300)
	// An unsigned ack answer is retried.
	p := newPhone(t, testFrame, photo("160", "a.jpg", data))
	p.noRMac = 1
	got := receiveFrom(t, p, t.TempDir(), nil)
	if got.err != nil || got.sum.Verified != 1 || len(p.ackList()) != 2 {
		t.Fatalf("%v %+v acks=%d", got.err, got.sum, len(p.ackList()))
	}

	// An unsigned 404 does not make the receiver give up on the file; a signed one does.
	p2 := newPhone(t, testFrame, photo("161", "gone.jpg", data))
	p2.gone["161"] = true
	p2.noRMac = 1
	got2 := receiveFrom(t, p2, t.TempDir(), nil)
	if r := got2.rec.byID()["161"]; got2.err != nil || r.Outcome != Skipped || len(p2.ranges("161")) != 2 {
		t.Fatalf("%v %+v requests=%d", got2.err, r, len(p2.ranges("161")))
	}

	// Never signed: the file fails (it is not reported as gone from the phone).
	p3 := newPhone(t, testFrame, photo("162", "gone.jpg", data))
	p3.gone["162"] = true
	p3.noRMac = 1000
	got3 := receiveFrom(t, p3, t.TempDir(), nil)
	if r := got3.rec.byID()["162"]; got3.err != nil || r.Outcome != Failed {
		t.Fatalf("%v %+v", got3.err, r)
	}
}

func TestE2ERangeRefusedRestartsFile(t *testing.T) {
	data := payload(27, 5*testFrame+9)
	p := newPhone(t, testFrame, photo("170", "r.mp4", data))
	p.dropAfter["170"] = 3 * testFrame
	p.range416["170"] = true
	root := t.TempDir()
	got := receiveFrom(t, p, root, nil)
	if got.err != nil || got.sum.Verified != 1 {
		t.Fatalf("%v %+v", got.err, got.sum)
	}
	if rs := p.ranges("170"); len(rs) != 3 || rs[0] != "" || rs[1] != "bytes=2048-" || rs[2] != "" {
		t.Errorf("ranges %q", rs)
	}
	if !bytes.Equal(mustRead(t, filepath.Join(root, "2023-11", "r.mp4")), data) {
		t.Error("content differs")
	}
}

func TestE2EBusyAndChangedFile(t *testing.T) {
	old := payload(20, 1500)
	p := newPhone(t, testFrame, photo("120", "edit.jpg", old), photo("121", "x.jpg", payload(21, 10)))
	p.busy["/api/r/list"] = 2
	p.busy["/api/r/file"] = 1
	updated := payload(22, 2100)
	p.changeOnce["120"] = updated // the photo was edited on the phone: 409, then a new list
	root := t.TempDir()
	got := receiveFrom(t, p, root, nil)
	if got.err != nil || got.sum.Verified != 2 {
		t.Fatalf("%v %+v", got.err, got.sum)
	}
	if !bytes.Equal(mustRead(t, filepath.Join(root, "2023-11", "edit.jpg")), updated) {
		t.Error("did not receive the edited version")
	}
	lists := 0
	for _, r := range p.requests() {
		if r.target == "/api/r/list" {
			lists++
		}
	}
	if lists < 4 { // 2 busy + 1 ok + 1 re-list after 409
		t.Errorf("list requests %d", lists)
	}
}

func TestE2ENoSpace(t *testing.T) {
	p := newPhone(t, testFrame, photo("130", "a.jpg", payload(23, 10)))
	got := receiveFrom(t, p, t.TempDir(), func(_ *store.Folder, o *Options) { o.SpaceMargin = 1 << 62 })
	var ns *ErrNoSpace
	if !errors.As(got.err, &ns) {
		t.Fatalf("err = %v", got.err)
	}
	if len(p.ranges("130")) != 0 {
		t.Error("download started despite no space")
	}
}
