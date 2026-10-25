package blackbox

// Receiver engine (pairing, transfer, resume, verification) driven in-process against the fake
// phone. PDF 10.5 "Pairing, transfer, resume and verification" (pages 79–81).

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"galleryslim/receiver/internal/client"
	"galleryslim/receiver/internal/gs2"
	"galleryslim/receiver/internal/receive"
	"galleryslim/receiver/internal/store"
)

type collector struct {
	mu       sync.Mutex
	results  []receive.Result
	listed   []client.File
	progress int64
	onProg   func(total int64)
}

func (c *collector) Listed(files []client.File, total int64) {
	c.mu.Lock()
	c.listed = files
	c.mu.Unlock()
}
func (c *collector) FileStarted(client.File, string) {}
func (c *collector) Progress(d int64) {
	c.mu.Lock()
	c.progress += d
	p, f := c.progress, c.onProg
	c.mu.Unlock()
	if f != nil {
		f(p)
	}
}
func (c *collector) FileDone(r receive.Result) {
	c.mu.Lock()
	c.results = append(c.results, r)
	c.mu.Unlock()
}
func (c *collector) byID(id string) (receive.Result, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, r := range c.results {
		if r.File.ID == id {
			return r, true
		}
	}
	return receive.Result{}, false
}

type pairing struct {
	sess   *client.Session
	qrText string
	code   string
}

// pairWith runs the receiver's pairing against the phone; the "owner" approves the scanned
// text as soon as it is shown.
func pairWith(t *testing.T, ctx context.Context, p *fakePhone) (pairing, error) {
	t.Helper()
	var pr pairing
	sess, err := receive.Pair(ctx, receive.PairOptions{
		HTTP: client.NewHTTPClient(), Addr: p.Addr,
		Info: client.SessionInfo{Receiver: "bb-host", OS: "linux", Folder: "Photos"},
		ShowQR: func(text, code string) {
			pr.qrText, pr.code = text, code
			go func() { _ = p.approve(text) }()
		},
	})
	pr.sess = sess
	return pr, err
}

type runOut struct {
	sum  receive.Summary
	err  error
	col  *collector
	pair pairing
}

func runEngine(t *testing.T, ctx context.Context, p *fakePhone, folder string, mod func(*receive.Options, *collector)) runOut {
	t.Helper()
	pr, err := pairWith(t, ctx, p)
	if err != nil {
		t.Fatalf("pairing failed: %v", err)
	}
	f, err := store.Open(folder)
	if err != nil {
		t.Fatal(err)
	}
	col := &collector{}
	o := receive.Options{Session: pr.sess, Folder: f, Phone: p.Name, Events: col}
	if mod != nil {
		mod(&o, col)
	}
	sum, err := receive.Run(ctx, o)
	return runOut{sum, err, col, pr}
}

func photo(id, name string, n int, seed byte, taken int64) *phoneFile {
	return &phoneFile{ID: id, Name: name, Mime: "image/jpeg", Taken: taken, Data: pseudo(n, seed)}
}

const jan2024 = int64(1705312800000) // 2024-01-15 10:00 UTC

// savedFiles lists files under folder, excluding .gallery-slim, as "rel" → content.
func savedFiles(t *testing.T, folder string) map[string][]byte {
	t.Helper()
	out := map[string][]byte{}
	filepath.WalkDir(folder, func(p string, d os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if d.IsDir() && d.Name() == store.MetaDir {
			return filepath.SkipDir
		}
		if !d.IsDir() {
			b, _ := os.ReadFile(p)
			rel, _ := filepath.Rel(folder, p)
			out[filepath.ToSlash(rel)] = b
		}
		return nil
	})
	return out
}

// RT-01: 32-byte secret only inside the QR (gs2:…), shown with the phone's short code; the
// phone is polled about every 1 s.
func TestRT01_PairingQRAndPolling(t *testing.T) {
	p := newFakePhone(t, photo("1", "a.jpg", 1000, 1, jan2024))
	p.mu.Lock()
	p.files = nil
	p.mu.Unlock()
	ctx := ctxFor(t, 30*time.Second)
	// Approve only after ~4 polls so the interval can be measured.
	var pr pairing
	sess, err := receive.Pair(ctx, receive.PairOptions{
		HTTP: client.NewHTTPClient(), Addr: p.Addr,
		Info: client.SessionInfo{Receiver: "bb-host", OS: "linux", Folder: "Photos"},
		ShowQR: func(text, code string) {
			pr.qrText, pr.code = text, code
			go func() { time.Sleep(4500 * time.Millisecond); _ = p.approve(text) }()
		},
	})
	if err != nil || sess == nil {
		t.Fatalf("pairing failed: %v", err)
	}
	if !strings.HasPrefix(pr.qrText, "gs2:") {
		t.Errorf("QR text %q does not start with gs2:", pr.qrText)
	}
	parts := strings.Split(strings.TrimPrefix(pr.qrText, "gs2:"), ":")
	if len(parts) != 2 {
		t.Fatalf("QR text %q is not gs2:<sid>:<secret>", pr.qrText)
	}
	k, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil || len(k) != 32 {
		t.Errorf("secret in QR is %d bytes (err %v), want 32", len(k), err)
	}
	if pr.code != p.Code {
		t.Errorf("code shown with the QR = %q, want the phone's short code %q", pr.code, p.Code)
	}
	var polls []time.Time
	for _, r := range p.allRequests() {
		if strings.HasPrefix(r.URI, "/api/r/state") {
			polls = append(polls, r.At)
		}
	}
	if len(polls) < 3 {
		t.Fatalf("only %d state polls in 4.5 s", len(polls))
	}
	for i := 1; i < len(polls); i++ {
		gap := polls[i].Sub(polls[i-1])
		if gap < 800*time.Millisecond || gap > 1300*time.Millisecond {
			t.Errorf("poll gap %v, want about 1 s", gap)
		}
	}
}

// RT-02 / GS2-24: the pairing secret never travels over the network; GS2-02: the session
// request carries computer name, system and folder name.
func TestRT02_SecretNeverOnTheNetwork(t *testing.T) {
	p := newFakePhone(t, photo("1", "IMG_1.jpg", 300_000, 1, jan2024), photo("2", "IMG_2.jpg", 2_500_000, 2, jan2024))
	folder := t.TempDir()
	out := runEngine(t, ctxFor(t, 60*time.Second), p, folder, nil)
	if out.err != nil || out.sum.Verified != 2 {
		t.Fatalf("run: err=%v sum=%+v", out.err, out.sum)
	}
	_, k, err := gs2.ParseQRText(out.pair.qrText)
	if err != nil {
		t.Fatal(err)
	}
	needles := map[string][]byte{
		"base64url": []byte(base64.RawURLEncoding.EncodeToString(k)),
		"base64":    []byte(base64.StdEncoding.EncodeToString(k)),
		"hex":       []byte(hex.EncodeToString(k)),
		"raw":       k,
		"qr text":   []byte(out.pair.qrText),
	}
	reqs := p.allRequests()
	for _, r := range reqs {
		var all bytes.Buffer
		all.WriteString(r.Method + " " + r.URI + "\n")
		for h, vs := range r.Header {
			all.WriteString(h + ": " + strings.Join(vs, ",") + "\n")
		}
		all.Write(r.Body)
		for what, n := range needles {
			if bytes.Contains(all.Bytes(), n) {
				t.Errorf("pairing secret (%s) seen in request %s %s", what, r.Method, r.URI)
			}
		}
	}
	p.mu.Lock()
	s := p.last
	p.mu.Unlock()
	if s.receiver != "bb-host" || s.os != "linux" || s.folder != "Photos" {
		t.Errorf("session request = receiver %q os %q folder %q; want computer name, system and folder name", s.receiver, s.os, s.folder)
	}
}

// RT-03: the file list is fetched up to 3 times (waits 1 s and 2 s).
func TestRT03_ListRetries(t *testing.T) {
	t.Run("two failures then success", func(t *testing.T) {
		p := newFakePhone(t, photo("1", "a.jpg", 1000, 1, jan2024))
		p.listFailures = 2
		start := time.Now()
		out := runEngine(t, ctxFor(t, 60*time.Second), p, t.TempDir(), nil)
		if out.err != nil || out.sum.Verified != 1 {
			t.Fatalf("err=%v sum=%+v", out.err, out.sum)
		}
		var lists []time.Time
		for _, r := range p.allRequests() {
			if r.URI == "/api/r/list" {
				lists = append(lists, r.At)
			}
		}
		if len(lists) != 3 {
			t.Fatalf("%d list requests, want 3", len(lists))
		}
		g1, g2 := lists[1].Sub(lists[0]), lists[2].Sub(lists[1])
		if g1 < 900*time.Millisecond || g1 > 1600*time.Millisecond || g2 < 1900*time.Millisecond || g2 > 2600*time.Millisecond {
			t.Errorf("waits between list tries %v, %v; want about 1 s and 2 s", g1, g2)
		}
		_ = start
	})
	t.Run("three failures give up", func(t *testing.T) {
		p := newFakePhone(t, photo("1", "a.jpg", 1000, 1, jan2024))
		p.listFailures = 3
		out := runEngine(t, ctxFor(t, 60*time.Second), p, t.TempDir(), nil)
		if out.err == nil {
			t.Fatalf("expected an error after 3 failed list fetches, got sum=%+v", out.sum)
		}
		n := 0
		for _, r := range p.allRequests() {
			if r.URI == "/api/r/list" {
				n++
			}
		}
		if n != 3 {
			t.Errorf("%d list requests, want 3", n)
		}
	})
}

// RT-04: free-space check = sizes not yet present + 50 MB margin; the error says
// "not enough free space: need <needed>, free <free>" and "needed" excludes the margin.
func TestRT04_FreeSpaceCheck(t *testing.T) {
	folder := t.TempDir()
	var st syscall.Statfs_t
	if err := syscall.Statfs(folder, &st); err != nil {
		t.Skip("statfs:", err)
	}
	free := int64(st.Bavail) * int64(st.Bsize)
	size := free - 25_000_000 // fits on disk, but not with the 50 MB margin
	if size <= 0 {
		t.Skip("disk nearly full")
	}
	f := photo("1", "huge.mp4", 10, 1, jan2024)
	f.ListSize = size
	p := newFakePhone(t, f)
	out := runEngine(t, ctxFor(t, 60*time.Second), p, folder, nil)
	if out.err == nil {
		t.Fatalf("expected the free-space check to stop the run (size %d, free %d); sum=%+v", size, free, out.sum)
	}
	msg := receive.Friendly(out.err)
	if !strings.HasPrefix(msg, "not enough free space: need ") || !strings.Contains(msg, ", free ") {
		t.Errorf("message %q; PDF 10.5 step 3 wants \"not enough free space: need <needed>, free <free>\"", msg)
	}
	if want := receive.HumanBytes(size); !strings.Contains(msg, want) {
		t.Errorf("message %q should name the needed size without the margin (%s)", msg, want)
	}
	if len(p.fileRanges()) != 0 {
		t.Errorf("files were requested although the space check failed: %v", p.fileRanges())
	}
}

// RT-05 / GS2-23: up to 3 files download in parallel.
func TestRT05_AtMostThreeParallelDownloads(t *testing.T) {
	var files []*phoneFile
	for i := 0; i < 8; i++ {
		files = append(files, photo(string(rune('a'+i)), "p"+string(rune('a'+i))+".jpg", 600_000, byte(i), jan2024))
	}
	p := newFakePhone(t, files...)
	p.throttle = 30 * time.Millisecond
	out := runEngine(t, ctxFor(t, 120*time.Second), p, t.TempDir(), nil)
	if out.err != nil || out.sum.Verified != 8 {
		t.Fatalf("err=%v sum=%+v", out.err, out.sum)
	}
	if m := p.maxStreams.Load(); m > 3 {
		t.Errorf("%d downloads at once, PDF allows at most 3", m)
	} else if m < 2 {
		t.Errorf("downloads never ran in parallel (max %d)", m)
	}
}

// RT-07: pairing gives up after 15 failed polls in a row.
func TestRT07_PairingFailsAfter15FailedPolls(t *testing.T) {
	p := newFakePhone(t)
	p.stateStatus = 500
	ctx := ctxFor(t, 60*time.Second)
	_, err := receive.Pair(ctx, receive.PairOptions{
		HTTP: client.NewHTTPClient(), Addr: p.Addr,
		Info:   client.SessionInfo{Receiver: "bb", OS: "linux", Folder: "x"},
		ShowQR: func(string, string) {},
	})
	if err == nil {
		t.Fatal("pairing succeeded although every poll failed")
	}
	n := 0
	for _, r := range p.allRequests() {
		if strings.HasPrefix(r.URI, "/api/r/state") {
			n++
		}
	}
	if n != 15 {
		t.Errorf("pairing ended after %d failed polls, PDF says 15", n)
	}
}

// RT-08: phone busy (503): wait 2 s, retry up to 5 times, then "The phone stayed busy".
func TestRT08_PhoneBusy(t *testing.T) {
	t.Run("stays busy", func(t *testing.T) {
		p := newFakePhone(t, photo("1", "a.jpg", 1000, 1, jan2024))
		p.busyFor = func(path string) bool { return path == "/api/r/list" }
		start := time.Now()
		out := runEngine(t, ctxFor(t, 120*time.Second), p, t.TempDir(), func(o *receive.Options, _ *collector) {})
		if out.err == nil {
			t.Fatalf("no error although the phone stayed busy; sum=%+v", out.sum)
		}
		if got := receive.Friendly(out.err); got != "The phone stayed busy" {
			t.Errorf("error %q, want \"The phone stayed busy\"", got)
		}
		var at []time.Time
		for _, r := range p.allRequests() {
			if r.URI == "/api/r/list" {
				at = append(at, r.At)
			}
		}
		// One list fetch = 1 try + 5 busy retries; the list itself is tried up to 3 times (RT-03).
		if len(at) < 6 {
			t.Fatalf("only %d list requests", len(at))
		}
		for i := 1; i < 6; i++ {
			if g := at[i].Sub(at[i-1]); g < 1900*time.Millisecond || g > 2600*time.Millisecond {
				t.Errorf("wait after 503 #%d = %v, want about 2 s", i, g)
			}
		}
		_ = start
	})
	t.Run("busy twice then fine", func(t *testing.T) {
		p := newFakePhone(t, photo("1", "a.jpg", 1000, 1, jan2024))
		n := 0
		var mu sync.Mutex
		p.busyFor = func(path string) bool {
			if path != "/api/r/file" {
				return false
			}
			mu.Lock()
			defer mu.Unlock()
			n++
			return n <= 2
		}
		out := runEngine(t, ctxFor(t, 60*time.Second), p, t.TempDir(), nil)
		if out.err != nil || out.sum.Verified != 1 {
			t.Errorf("err=%v sum=%+v", out.err, out.sum)
		}
	})
}

// RT-09: no data for 60 s cancels the stream ("The connection stopped; try again") and it is
// retried. Slow (about 65 s).
func TestRT09_StalledStreamIsRetried(t *testing.T) {
	if testing.Short() {
		t.Skip("slow")
	}
	f := photo("1", "a.jpg", 200_000, 1, jan2024)
	p := newFakePhone(t, f)
	p.stall = 75 * time.Second
	p.fileHook = func(_ *phoneFile, _ int64, attempt int) string {
		if attempt == 1 {
			return "stall"
		}
		return ""
	}
	start := time.Now()
	out := runEngine(t, ctxFor(t, 150*time.Second), p, t.TempDir(), nil)
	el := time.Since(start)
	if out.err != nil || out.sum.Verified != 1 {
		t.Fatalf("err=%v sum=%+v", out.err, out.sum)
	}
	if el < 59*time.Second || el > 74*time.Second {
		t.Errorf("stalled stream was given up after about %v, want about 60 s", el)
	}
	if n := len(p.fileRanges()); n != 2 {
		t.Errorf("%d file requests, want 2 (stalled one + retry)", n)
	}
	if got := receive.Friendly(client.ErrStalled); got != "The connection stopped; try again" {
		t.Errorf("stall message %q", got)
	}
}

// RT-10: per-file retries: 5 attempts, waits 1, 2, 4, 8 s.
func TestRT10_PerFileRetries(t *testing.T) {
	p := newFakePhone(t, photo("1", "a.jpg", 1000, 1, jan2024))
	p.fileHook = func(*phoneFile, int64, int) string { return "fail500" }
	out := runEngine(t, ctxFor(t, 90*time.Second), p, t.TempDir(), nil)
	r, ok := out.col.byID("1")
	if !ok || r.Outcome != receive.Failed {
		t.Fatalf("result %+v, want failed", r)
	}
	var at []time.Time
	for _, q := range p.allRequests() {
		if strings.HasPrefix(q.URI, "/api/r/file") {
			at = append(at, q.At)
		}
	}
	if len(at) != 5 {
		t.Fatalf("%d attempts, want 5", len(at))
	}
	want := []time.Duration{1, 2, 4, 8}
	for i := 1; i < len(at); i++ {
		g := at[i].Sub(at[i-1])
		w := want[i-1] * time.Second
		if g < w-200*time.Millisecond || g > w+700*time.Millisecond {
			t.Errorf("wait before attempt %d = %v, want about %v", i+1, g, w)
		}
	}
}

// RT-11: resume — a partial file in <folder>/.gallery-slim/parts/ is continued by a new QR
// session with the same phone. RT-12: a partial file larger than the listed size is discarded.
func TestRT11_ResumeWithNewSession(t *testing.T) {
	data := pseudo(8<<20, 7)
	f := &phoneFile{ID: "v1", Name: "VID_1.mp4", Mime: "video/mp4", Taken: jan2024, Data: data}
	p := newFakePhone(t, f)
	p.throttle = 15 * time.Millisecond
	folder := t.TempDir()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	out := runEngine(t, ctx, p, folder, func(o *receive.Options, c *collector) {
		c.onProg = func(total int64) {
			if total >= 3<<20 {
				cancel()
			}
		}
	})
	if out.err == nil && out.sum.Verified == 1 {
		t.Fatal("the transfer finished before it could be interrupted")
	}
	parts, _ := filepath.Glob(filepath.Join(folder, ".gallery-slim", "parts", "*"))
	if len(parts) != 1 {
		t.Fatalf("partial files after interruption: %v", parts)
	}
	fi, _ := os.Stat(parts[0])
	if fi.Size() == 0 {
		t.Fatal("partial file is empty")
	}
	p.throttle = 0
	before := len(p.fileRanges())
	out2 := runEngine(t, ctxFor(t, 60*time.Second), p, folder, nil)
	if out2.err != nil || out2.sum.Verified != 1 {
		t.Fatalf("second run: err=%v sum=%+v", out2.err, out2.sum)
	}
	rs := p.fileRanges()[before:]
	if len(rs) == 0 || rs[0] == "v1@0" {
		t.Errorf("second session requests %v; want a resume from the partial file's offset", rs)
	}
	got := savedFiles(t, folder)
	if b, ok := got["2024-01/VID_1.mp4"]; !ok || !bytes.Equal(b, data) {
		t.Errorf("resumed file missing or different; saved: %v", keys(got))
	}

	t.Run("RT-12 partial larger than listed size is discarded", func(t *testing.T) {
		data := pseudo(4<<20, 9)
		g := &phoneFile{ID: "v2", Name: "VID_2.mp4", Mime: "video/mp4", Taken: jan2024, Data: data}
		p2 := newFakePhone(t, g)
		p2.throttle = 15 * time.Millisecond
		folder := t.TempDir()
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		runEngine(t, ctx, p2, folder, func(o *receive.Options, c *collector) {
			c.onProg = func(total int64) {
				if total >= 2<<20 {
					cancel()
				}
			}
		})
		parts, _ := filepath.Glob(filepath.Join(folder, ".gallery-slim", "parts", "*"))
		if len(parts) != 1 {
			t.Fatalf("partial files: %v", parts)
		}
		fh, _ := os.OpenFile(parts[0], os.O_WRONLY|os.O_APPEND, 0)
		fh.Write(make([]byte, 5<<20)) // now larger than the listed 4 MiB
		fh.Close()
		p2.throttle = 0
		before := len(p2.fileRanges())
		out := runEngine(t, ctxFor(t, 60*time.Second), p2, folder, nil)
		if out.err != nil || out.sum.Verified != 1 {
			t.Fatalf("err=%v sum=%+v", out.err, out.sum)
		}
		if rs := p2.fileRanges()[before:]; len(rs) == 0 || rs[0] != "v2@0" {
			t.Errorf("requests %v; an oversized partial must be discarded (start at 0)", rs)
		}
		if b := savedFiles(t, folder)["2024-01/VID_2.mp4"]; !bytes.Equal(b, data) {
			t.Error("saved file differs from the phone's")
		}
	})
}

func keys[V any](m map[string]V) []string {
	var out []string
	for k := range m {
		out = append(out, k)
	}
	return out
}

// RT-14: file changed on the phone: partial discarded, list fetched again (at most 2 times).
func TestRT14_FileChangedOnPhone(t *testing.T) {
	t.Run("changed once", func(t *testing.T) {
		f := photo("1", "a.jpg", 100_000, 1, jan2024)
		newData := pseudo(150_000, 2)
		p := newFakePhone(t, f)
		p.fileHook = func(x *phoneFile, _ int64, attempt int) string {
			if attempt == 1 {
				p.mu.Lock()
				x.Data = newData
				p.mu.Unlock()
				return "changed"
			}
			return ""
		}
		folder := t.TempDir()
		out := runEngine(t, ctxFor(t, 60*time.Second), p, folder, nil)
		if out.err != nil || out.sum.Verified != 1 {
			t.Fatalf("err=%v sum=%+v", out.err, out.sum)
		}
		if b := savedFiles(t, folder)["2024-01/a.jpg"]; !bytes.Equal(b, newData) {
			t.Error("saved file is not the phone's new version")
		}
		n := 0
		for _, r := range p.allRequests() {
			if r.URI == "/api/r/list" {
				n++
			}
		}
		if n != 2 {
			t.Errorf("%d list fetches, want 2 (initial + one after the change)", n)
		}
	})
	t.Run("keeps changing", func(t *testing.T) {
		p := newFakePhone(t, photo("1", "a.jpg", 100_000, 1, jan2024))
		p.fileHook = func(*phoneFile, int64, int) string { return "changed" }
		out := runEngine(t, ctxFor(t, 60*time.Second), p, t.TempDir(), nil)
		n := 0
		for _, r := range p.allRequests() {
			if r.URI == "/api/r/list" {
				n++
			}
		}
		if n > 3 {
			t.Errorf("list fetched %d times; PDF: again at most 2 times", n)
		}
		if r, _ := out.col.byID("1"); r.Outcome == receive.Verified {
			t.Error("a file that keeps changing was reported verified")
		}
	})
}

// RT-15: gone from the phone → "Skipped" "No longer on the phone".
func TestRT15_GoneFromPhone(t *testing.T) {
	t.Run("phone answers gone", func(t *testing.T) {
		p := newFakePhone(t, photo("1", "a.jpg", 1000, 1, jan2024), photo("2", "b.jpg", 1000, 2, jan2024))
		p.fileHook = func(x *phoneFile, _ int64, _ int) string {
			if x.ID == "2" {
				return "gone"
			}
			return ""
		}
		out := runEngine(t, ctxFor(t, 60*time.Second), p, t.TempDir(), nil)
		r, _ := out.col.byID("2")
		if r.Outcome != receive.Skipped || r.Detail != "No longer on the phone" {
			t.Errorf("result %q %q, want skipped \"No longer on the phone\"", r.Outcome, r.Detail)
		}
		if out.sum.Failed != 0 || out.sum.Skipped != 1 || out.sum.Verified != 1 {
			t.Errorf("summary %+v", out.sum)
		}
	})
	t.Run("removed from the list after a change", func(t *testing.T) {
		keep := photo("1", "a.jpg", 1000, 1, jan2024)
		p := newFakePhone(t, keep, photo("2", "b.jpg", 1000, 2, jan2024))
		p.fileHook = func(x *phoneFile, _ int64, _ int) string {
			if x.ID == "2" {
				p.mu.Lock()
				p.files = []*phoneFile{keep}
				p.mu.Unlock()
				return "changed"
			}
			return ""
		}
		out := runEngine(t, ctxFor(t, 60*time.Second), p, t.TempDir(), nil)
		r, _ := out.col.byID("2")
		if r.Outcome != receive.Skipped || r.Detail != "No longer on the phone" {
			t.Errorf("result %q %q, want skipped \"No longer on the phone\"", r.Outcome, r.Detail)
		}
	})
}

// RT-16: checksum differs from the phone's list → downloaded once more from scratch; then
// "Not saved" "The received copy doesn't match the phone's checksum".
func TestRT16_ListChecksumMismatch(t *testing.T) {
	f := photo("1", "a.jpg", 50_000, 1, jan2024)
	wrong := strings.Repeat("ab", 32)
	f.ListSHA = &wrong
	p := newFakePhone(t, f)
	folder := t.TempDir()
	out := runEngine(t, ctxFor(t, 60*time.Second), p, folder, nil)
	r, _ := out.col.byID("1")
	if r.Outcome != receive.Failed || r.Detail != "The received copy doesn't match the phone's checksum" {
		t.Errorf("result %q %q", r.Outcome, r.Detail)
	}
	if rs := p.fileRanges(); len(rs) != 2 || rs[0] != "1@0" || rs[1] != "1@0" {
		t.Errorf("file requests %v, want exactly two full downloads", rs)
	}
	if got := savedFiles(t, folder); len(got) != 0 {
		t.Errorf("a copy with the wrong checksum was saved: %v", keys(got))
	}
	t.Run("right checksum in the list", func(t *testing.T) {
		g := photo("1", "a.jpg", 50_000, 1, jan2024)
		h := sha(g.Data)
		g.ListSHA = &h
		p := newFakePhone(t, g)
		out := runEngine(t, ctxFor(t, 60*time.Second), p, t.TempDir(), nil)
		if out.sum.Verified != 1 {
			t.Errorf("sum %+v", out.sum)
		}
	})
}

// RT-18: confirmation could not be sent → saved but not confirmed.
func TestRT18_ConfirmationCouldNotBeSent(t *testing.T) {
	p := newFakePhone(t, photo("1", "a.jpg", 1000, 1, jan2024))
	p.ackHook = func(*phoneFile) string { return "fail500" }
	folder := t.TempDir()
	out := runEngine(t, ctxFor(t, 60*time.Second), p, folder, nil)
	r, _ := out.col.byID("1")
	if r.Outcome != receive.Saved || r.Detail != "Saved, but the phone could not be asked to confirm it" {
		t.Errorf("result %q %q", r.Outcome, r.Detail)
	}
	if len(savedFiles(t, folder)) != 1 {
		t.Error("the file should stay saved")
	}
}

// RT-19 / RT-25: the phone says the copy does not match → kept and flagged; one fresh download
// saved under a new name; then "Couldn't confirm — kept. …". A finished file is never deleted
// or replaced because of the phone's answer.
func TestRT19_PhoneSaysMismatch(t *testing.T) {
	f := photo("1", "IMG_9.jpg", 80_000, 1, jan2024)
	p := newFakePhone(t, f)
	p.ackHook = func(*phoneFile) string { return "mismatch" }
	folder := t.TempDir()
	out := runEngine(t, ctxFor(t, 60*time.Second), p, folder, nil)
	r, _ := out.col.byID("1")
	want := "Couldn't confirm — kept. The phone says this copy doesn't match, so it won't be offered for freeing space."
	if r.Outcome != receive.Saved || r.Detail != want {
		t.Errorf("result %q %q", r.Outcome, r.Detail)
	}
	got := savedFiles(t, folder)
	if len(got) != 2 {
		t.Fatalf("saved files %v, want the first copy kept plus one fresh download under a new name", keys(got))
	}
	for name, b := range got {
		if !bytes.Equal(b, f.Data) {
			t.Errorf("%s was changed", name)
		}
	}
	if _, ok := got["2024-01/IMG_9.jpg"]; !ok {
		t.Error("first copy IMG_9.jpg was not kept")
	}
	if _, ok := got["2024-01/IMG_9 (2).jpg"]; !ok {
		t.Errorf("fresh copy not saved as \"IMG_9 (2).jpg\": %v", keys(got))
	}
	if out.sum.Saved != 1 || out.sum.Verified != 0 {
		t.Errorf("summary %+v", out.sum)
	}
}

// RT-20 / GS2-27: a file already on the computer is re-hashed and confirmed without
// downloading: "Already on this computer (checked)".
func TestRT20_AlreadyOnComputer(t *testing.T) {
	p := newFakePhone(t, photo("1", "a.jpg", 300_000, 1, jan2024), photo("2", "b.jpg", 300_000, 2, 0))
	folder := t.TempDir()
	if out := runEngine(t, ctxFor(t, 60*time.Second), p, folder, nil); out.sum.Verified != 2 {
		t.Fatalf("first run %+v %v", out.sum, out.err)
	}
	before := len(p.fileRanges())
	out := runEngine(t, ctxFor(t, 60*time.Second), p, folder, nil)
	if out.err != nil || out.sum.Verified != 2 || out.sum.Already != 2 {
		t.Fatalf("second run err=%v sum=%+v", out.err, out.sum)
	}
	if rs := p.fileRanges()[before:]; len(rs) != 0 {
		t.Errorf("files downloaded again: %v", rs)
	}
	for _, id := range []string{"1", "2"} {
		if r, _ := out.col.byID(id); r.Detail != "Already on this computer (checked)" {
			t.Errorf("file %s detail %q", id, r.Detail)
		}
	}
	t.Run("copy on disk changed", func(t *testing.T) {
		// The manifest is only a hint: a changed copy must not be confirmed from it.
		os.WriteFile(filepath.Join(folder, "2024-01", "a.jpg"), []byte("tampered"), 0o644)
		out := runEngine(t, ctxFor(t, 60*time.Second), p, folder, nil)
		r, _ := out.col.byID("1")
		if r.Outcome != receive.Verified || r.Already {
			t.Errorf("result %+v; a changed copy must be received again, not confirmed", r)
		}
		if b, _ := os.ReadFile(filepath.Join(folder, "2024-01", "a.jpg")); string(b) != "tampered" {
			t.Error("the existing (changed) file was overwritten")
		}
	})
}

// RT-22: list limits — invalid or duplicate entries are silently dropped; id ≤ 256 bytes.
func TestRT22_ListLimits(t *testing.T) {
	ok1 := photo("ok1", "ok1.jpg", 1000, 1, jan2024)
	dup := photo("ok1", "dup.jpg", 1000, 2, jan2024)
	id256 := strings.Repeat("x", 256)
	ok256 := photo(id256, "id256.jpg", 1000, 3, jan2024)
	id257 := photo(strings.Repeat("y", 257), "id257.jpg", 1000, 4, jan2024)
	neg := photo("neg", "neg.jpg", 1000, 5, jan2024)
	neg.ListSize = -5
	tooBig := photo("big", "big.mp4", 1000, 6, jan2024)
	tooBig.ListSize = 2 << 40 // 2 TiB
	ctrl := photo("bad\x01id", "ctrl.jpg", 1000, 7, jan2024)
	p := newFakePhone(t, ok1, dup, ok256, id257, neg, tooBig, ctrl)
	folder := t.TempDir()
	out := runEngine(t, ctxFor(t, 60*time.Second), p, folder, nil)
	if out.err != nil {
		t.Fatalf("run failed: %v", out.err)
	}
	if out.sum.Verified != 2 {
		t.Errorf("verified %d, want 2 (ok1 and the 256-byte id)", out.sum.Verified)
	}
	if out.sum.Failed != 0 {
		t.Errorf("dropped entries were reported as %d failures; PDF: silently dropped", out.sum.Failed)
	}
	for _, rg := range p.fileRanges() {
		if strings.HasPrefix(rg, "y") || strings.HasPrefix(rg, "neg@") || strings.HasPrefix(rg, "big@") {
			t.Errorf("invalid entry was requested: %s", rg)
		}
	}
	got := savedFiles(t, folder)
	if _, ok := got["2024-01/dup.jpg"]; ok {
		t.Error("duplicate id entry was received")
	}
}

// RT-23: short error texts.
func TestRT23_ShortErrors(t *testing.T) {
	cases := map[error]string{
		client.ErrExpired:   "The phone ended the session",
		client.ErrAuth:      "The phone did not accept this computer's requests",
		client.ErrForbidden: "The phone refused this computer's address",
		client.ErrBusy:      "The phone stayed busy",
		client.ErrStalled:   "The connection stopped; try again",
		context.Canceled:    "Stopped",
	}
	for err, want := range cases {
		if got := receive.Friendly(err); got != want {
			t.Errorf("Friendly(%v) = %q, want %q", err, got, want)
		}
	}
}

// RT-24: an answer without a valid signature is a network error, never a decision.
func TestRT24_UnsignedAnswersAreNotDecisions(t *testing.T) {
	for _, mode := range []string{"unsigned", "badsig"} {
		t.Run(mode, func(t *testing.T) {
			p := newFakePhone(t, photo("1", "a.jpg", 1000, 1, jan2024))
			p.ackHook = func(*phoneFile) string { return mode }
			out := runEngine(t, ctxFor(t, 60*time.Second), p, t.TempDir(), nil)
			r, _ := out.col.byID("1")
			if r.Outcome == receive.Verified {
				t.Errorf("a forged %s \"verified\" answer was believed", mode)
			}
		})
	}
	t.Run("phone ended the session is a session error", func(t *testing.T) {
		p := newFakePhone(t, photo("1", "a.jpg", 1000, 1, jan2024))
		out := runEngine(t, ctxFor(t, 60*time.Second), p, t.TempDir(), func(o *receive.Options, _ *collector) {
			p.endAll()
		})
		if !errors.Is(out.err, client.ErrExpired) {
			t.Errorf("err %v, want the session-ended error", out.err)
		}
	})
}

// Units 2.2 / 10.5 step 4: decimal units, one decimal under 10, whole from 10 up; the terminal writes "kB".
func TestUnits_ReceiverHumanBytes(t *testing.T) {
	cases := map[int64]string{
		1_200_000_000:   "1.2 GB",
		12_000_000_000:  "12 GB",
		900_000_000_000: "900 GB",
		5_500_000:       "5.5 MB",
		55_000_000:      "55 MB",
		2_900_000_000:   "2.9 GB",
		4_200_000_000:   "4.2 GB",
		245_000_000:     "245 MB",
		245_000:         "245 kB",
		1_500:           "1.5 kB",
	}
	for n, want := range cases {
		if got := receive.HumanBytes(n); got != want {
			t.Errorf("HumanBytes(%d) = %q, want %q", n, got, want)
		}
	}
}
