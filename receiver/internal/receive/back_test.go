package receive

// §10 Bring back to phone against the fake phone.

import (
	"bytes"
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"galleryslim/receiver/internal/client"
	"galleryslim/receiver/internal/store"
)

type backRec struct {
	mu       sync.Mutex
	listed   []string
	started  []string
	progress int64
	results  map[string]BackResult
}

func (b *backRec) BackListed(keys, _ []string, _ []int64) {
	b.mu.Lock()
	b.listed = keys
	b.results = map[string]BackResult{}
	b.mu.Unlock()
}
func (b *backRec) BackStarted(k string) { b.mu.Lock(); b.started = append(b.started, k); b.mu.Unlock() }
func (b *backRec) BackProgress(d int64) { b.mu.Lock(); b.progress += d; b.mu.Unlock() }
func (b *backRec) BackDone(r BackResult) {
	b.mu.Lock()
	b.results[r.Key] = r
	b.mu.Unlock()
}

// approvedSession pairs with the fake phone by QR.
func approvedSession(t *testing.T, p *phone) *client.Session {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	s, err := Pair(ctx, PairOptions{
		HTTP: client.NewHTTPClient(), Addr: p.addr(), Config: client.Config{FrameSize: p.frameSize, BusyWait: time.Millisecond},
		Info: client.SessionInfo{OS: "linux"}, PollEvery: 5 * time.Millisecond,
		ShowQR: func(text, _ string) { go p.approve(text) },
	})
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func writeFile(t *testing.T, path string, data []byte) {
	t.Helper()
	os.MkdirAll(filepath.Dir(path), 0o755)
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestBackWantedHappyPathAndMissing(t *testing.T) {
	p := newPhone(t, testFrame)
	p.receiving = true
	root := t.TempDir()
	inMonth := payload(10, 5000)        // at <month>/<name>
	renamed := payload(11, testFrame*3) // moved elsewhere in the folder, exact frame multiple
	viaManifest := payload(12, 777)     // found through the manifest
	empty := []byte{}                   // an empty file
	changed := payload(13, 900)         // same place and size, other bytes
	writeFile(t, filepath.Join(root, "2023-11", "IMG_1.jpg"), inMonth)
	writeFile(t, filepath.Join(root, "old", "sorted", "holiday.jpg"), renamed)
	writeFile(t, filepath.Join(root, "2023-11", "x (2).jpg"), viaManifest)
	writeFile(t, filepath.Join(root, "undated", "e.jpg"), empty)
	bad := append([]byte(nil), changed...)
	bad[0] ^= 1
	writeFile(t, filepath.Join(root, "2023-11", "c.jpg"), bad)
	folder, err := store.Open(root)
	if err != nil {
		t.Fatal(err)
	}
	folder.Record("2023-11/x (2).jpg", store.ManifestEntry{Size: 777, SHA256: shaHex(viaManifest), FileID: "9"})
	p.want("1", "IMG_1.jpg", "2023-11", inMonth)
	p.want("2", "IMG_2.jpg", "2023-11", renamed)
	p.want("3", "x.jpg", "2024-01", viaManifest)
	p.want("4", "e.jpg", "undated", empty)
	p.want("5", "c.jpg", "2023-11", changed)
	p.want("6", "gone.jpg", "2023-11", payload(14, 10))
	p.dropUpload["2"] = 1 // one network error: retried once

	s := approvedSession(t, p)
	wants, err := s.Wants(context.Background())
	if err != nil || len(wants) != 6 {
		t.Fatalf("wants %d %v", len(wants), err)
	}
	rec := &backRec{}
	sum, err := SendWanted(context.Background(), s, folder, wants, rec)
	if err != nil {
		t.Fatal(err)
	}
	if sum.Sent != 4 || sum.NotFound != 2 {
		t.Fatalf("summary %+v results %+v", sum, rec.results)
	}
	for id, data := range map[string][]byte{"1": inMonth, "2": renamed, "3": viaManifest, "4": empty} {
		if !bytes.Equal(p.uploaded[id], data) {
			t.Errorf("upload %s differs", id)
		}
	}
	for _, id := range []string{"5", "6"} {
		if r := rec.results[id]; r.Outcome != NotFound || r.Detail != NotFoundText {
			t.Errorf("%s: %+v", id, r)
		}
		if p.uploadTries[id] != 0 {
			t.Errorf("%s uploaded although not found", id)
		}
	}
	if p.uploadTries["2"] != 2 {
		t.Errorf("tries for the dropped upload: %d", p.uploadTries["2"])
	}
	if rec.progress != int64(len(inMonth)+len(renamed)+len(viaManifest)) {
		t.Errorf("progress %d", rec.progress)
	}
	if r := rec.results["2"]; r.Path != filepath.Join(root, "old", "sorted", "holiday.jpg") {
		t.Errorf("path %q", r.Path)
	}
}

func TestBackNotReceiving(t *testing.T) {
	p := newPhone(t, testFrame)
	s := approvedSession(t, p)
	if _, err := s.Wants(context.Background()); !errors.Is(err, client.ErrNotReceiving) {
		t.Fatalf("wants: %v", err)
	}
	// It stops on 403 in the middle of a list.
	p.receiving, p.stopAfter = true, 1
	root := t.TempDir()
	a, b := payload(20, 100), payload(21, 200)
	writeFile(t, filepath.Join(root, "2023-11", "a.jpg"), a)
	writeFile(t, filepath.Join(root, "2023-11", "b.jpg"), b)
	p.want("a", "a.jpg", "2023-11", a)
	p.want("b", "b.jpg", "2023-11", b)
	p.want("c", "c.jpg", "2023-11", b)
	folder, _ := store.Open(root)
	wants, err := s.Wants(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	rec := &backRec{}
	sum, err := SendWanted(context.Background(), s, folder, wants, rec)
	if !errors.Is(err, client.ErrNotReceiving) || sum.Sent != 1 || sum.Failed != 1 {
		t.Fatalf("sum %+v err %v", sum, err)
	}
	if _, ok := rec.results["c"]; ok {
		t.Error("continued after not receiving")
	}
	if p.uploadTries["b"] != 0 { // refused before the upload was read; not retried
		t.Errorf("tries %d", p.uploadTries["b"])
	}
	if !strings.Contains(rec.results["b"].Detail, "not taking files") {
		t.Errorf("detail %q", rec.results["b"].Detail)
	}
}

func TestBackTamperedAnswer(t *testing.T) {
	p := newPhone(t, testFrame)
	p.receiving = true
	root := t.TempDir()
	a := payload(30, 300)
	writeFile(t, filepath.Join(root, "2023-11", "a.jpg"), a)
	p.want("a", "a.jpg", "2023-11", a)
	p.forgeUpload = 2 // both attempts are forged; never believed
	folder, _ := store.Open(root)
	s := approvedSession(t, p)
	wants, _ := s.Wants(context.Background())
	rec := &backRec{}
	sum, err := SendWanted(context.Background(), s, folder, wants, rec)
	if err != nil || sum.Failed != 1 || sum.Sent != 0 {
		t.Fatalf("sum %+v err %v", sum, err)
	}
	if p.uploadTries["a"] != 2 {
		t.Errorf("tries %d", p.uploadTries["a"])
	}
	// Once the answer is genuine, the file counts as sent.
	sum, _ = SendWanted(context.Background(), s, folder, wants, rec)
	if sum.Sent != 1 {
		t.Errorf("genuine answer: %+v", sum)
	}
}

func TestBackChosenOfferRefusals(t *testing.T) {
	p := newPhone(t, testFrame)
	p.receiving = true
	p.refuse = func(name, _ string) string {
		if name == "big.mp4" {
			return "Not enough space on the phone"
		}
		return ""
	}
	dir := t.TempDir()
	photo1, video, doc := payload(40, 2500), payload(41, 3000), payload(42, 10)
	paths := []string{filepath.Join(dir, "p1.JPG"), filepath.Join(dir, "big.mp4"), filepath.Join(dir, "notes.txt"),
		filepath.Join(dir, "missing.jpg"), dir, "relative.jpg"}
	writeFile(t, paths[0], photo1)
	writeFile(t, paths[1], video)
	writeFile(t, paths[2], doc)
	s := approvedSession(t, p)
	rec := &backRec{}
	sum, err := SendChosen(context.Background(), s, paths, rec)
	if err != nil {
		t.Fatal(err)
	}
	if sum.Sent != 1 || sum.Refused != 2 || sum.NotFound != 3 {
		t.Fatalf("sum %+v results %+v", sum, rec.results)
	}
	if !bytes.Equal(p.uploaded["o1"], photo1) {
		t.Error("photo not uploaded as o1")
	}
	if r := rec.results["pick-1"]; r.Outcome != Refused || r.Detail != "Not enough space on the phone" {
		t.Errorf("video %+v", r)
	}
	if r := rec.results["pick-2"]; r.Outcome != Refused || r.Detail != "Only photos and videos" {
		t.Errorf("text %+v", r)
	}
	if p.offers != 1 {
		t.Errorf("offers %d", p.offers)
	}
	// A file that changes between the offer and its upload is not sent.
	p.refuse = func(name, _ string) string {
		os.WriteFile(paths[0], append(photo1, 1), 0o644) // grows while the phone decides
		return ""
	}
	sum, _ = SendChosen(context.Background(), s, paths[:1], rec)
	if sum.Failed != 1 || !strings.Contains(rec.results["pick-0"].Detail, "changed") {
		t.Errorf("changed file: %+v %+v", sum, rec.results["pick-0"])
	}
}

func TestBackOfferSplitsLongLists(t *testing.T) {
	p := newPhone(t, testFrame)
	p.receiving = true
	s := approvedSession(t, p)
	var files []client.OfferFile
	for i := 0; i < 700; i++ {
		files = append(files, client.OfferFile{Name: strings.Repeat("n", 30) + ".jpg", Size: 1, SHA256: shaHex([]byte{byte(i)}), Mime: "image/jpeg"})
	}
	res, err := s.Offer(context.Background(), files)
	if err != nil || len(res) != 700 {
		t.Fatalf("%d %v", len(res), err)
	}
	if p.offers < 2 {
		t.Errorf("one offer for 700 files")
	}
	seen := map[string]bool{}
	for _, r := range res {
		if r.ID == "" || seen[r.ID] {
			t.Fatalf("bad id %q", r.ID)
		}
		seen[r.ID] = true
	}
}

func TestMimeOf(t *testing.T) {
	for name, want := range map[string]string{"a.JPG": "image/jpeg", "b.heic": "image/heic", "c.MOV": "video/quicktime", "d": "application/octet-stream"} {
		if got := MimeOf(name); got != want {
			t.Errorf("%s: %s", name, got)
		}
	}
}
