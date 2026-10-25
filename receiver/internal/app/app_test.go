package app

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"galleryslim/receiver/internal/client"
	"galleryslim/receiver/internal/discover"
	"galleryslim/receiver/internal/gs2"
	"galleryslim/receiver/internal/receive"
)

func waitFor(t *testing.T, a *App, what string, ok func(Status) bool) Status {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if s := a.Status(); ok(s) {
			return s
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s; status %+v", what, a.Status())
	return Status{}
}

func baseDeps() Deps {
	return Deps{
		HTTP: client.NewHTTPClient(), Hostname: "laptop", OSName: "linux", Version: "test",
		PollEvery: 5 * time.Millisecond, RescanEvery: 20 * time.Millisecond,
		CloudSynced: func(p string) (bool, string) {
			if strings.Contains(p, "OneDrive") {
				return true, "OneDrive"
			}
			return false, ""
		},
	}
}

func TestSetFolder(t *testing.T) {
	d := baseDeps()
	var saved string
	d.SaveFolder = func(p string) error { saved = p; return nil }
	a := New(d, "")
	if err := a.SetFolder("relative/dir"); err == nil {
		t.Error("relative path accepted")
	}
	file := filepath.Join(t.TempDir(), "file")
	os.WriteFile(file, []byte("x"), 0o644)
	if err := a.SetFolder(file); err == nil {
		t.Error("a file accepted as folder")
	}
	good := filepath.Join(t.TempDir(), "OneDrive", "Photos")
	if err := a.SetFolder(good); err != nil {
		t.Fatal(err)
	}
	s := a.Status()
	if s.Folder != good || saved != good || !strings.Contains(s.FolderNote, "OneDrive") {
		t.Errorf("status %+v saved %q", s, saved)
	}
}

func TestChooseFolderPassesDialogErrors(t *testing.T) {
	d := baseDeps()
	unsupported := errors.New("not supported on this system")
	d.PickFolder = func(string, string) (string, error) { return "", fmt.Errorf("no dialog: %w", unsupported) }
	a := New(d, "")
	if _, err := a.ChooseFolder(); err == nil || !strings.Contains(err.Error(), "not supported") {
		t.Errorf("err = %v", err)
	}
	d.PickFolder = func(string, string) (string, error) { return "", nil } // cancelled
	a = New(d, "")
	if p, err := a.ChooseFolder(); p != "" || err != nil {
		t.Errorf("cancel: %q %v", p, err)
	}
	dir := t.TempDir()
	d.PickFolder = func(_, start string) (string, error) { return dir, nil }
	a = New(d, "")
	if p, err := a.ChooseFolder(); p != dir || err != nil || a.Status().Folder != dir {
		t.Errorf("pick: %q %v", p, err)
	}
}

// pairingPhone answers hello, session and state (always waiting).
func pairingPhone(t *testing.T) *httptest.Server {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/api/hello":
			w.Header().Set("X-GallerySlim", "2")
			json.NewEncoder(w).Encode(map[string]any{"app": "GallerySlim", "proto": 2, "name": "Galaxy <S24>", "code": "M3-17", "port": 8765})
		case "/api/r/session":
			n := make([]byte, 32)
			sid := make([]byte, 12)
			rand.Read(n)
			rand.Read(sid)
			json.NewEncoder(w).Encode(map[string]string{"sid": gs2.B64.EncodeToString(sid), "n": gs2.B64.EncodeToString(n), "code": "M3-17"})
		case "/api/r/state":
			json.NewEncoder(w).Encode(map[string]string{"state": "waiting"})
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(srv.Close)
	return srv
}

func TestFindingSeveralPhonesThenPairing(t *testing.T) {
	srv := pairingPhone(t)
	addr := strings.TrimPrefix(srv.URL, "http://")
	d := baseDeps()
	d.Find = func(context.Context) ([]discover.Candidate, error) {
		return []discover.Candidate{{Addr: "192.0.2.1:8765", Name: "Pixel", Code: "K7-42"}, {Addr: addr, Name: "Galaxy <S24>", Code: "M3-17"}}, nil
	}
	d.Probe = discover.NewProber()
	a := New(d, t.TempDir())
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go a.Run(ctx)
	s := waitFor(t, a, "two phones", func(s Status) bool { return s.Phase == PhaseFinding && len(s.Phones) == 2 })
	if s.Error != "" {
		t.Errorf("error shown with phone list: %q", s.Error)
	}
	if err := a.EnterAddress("not an address"); err == nil {
		t.Error("bad address accepted")
	}
	if err := a.EnterAddress(addr); err != nil {
		t.Fatal(err)
	}
	s = waitFor(t, a, "QR", func(s Status) bool { return s.Phase == PhasePairing && s.QRSVG != "" })
	if s.PhoneName != "Galaxy <S24>" || s.Code != "M3-17" || !strings.HasPrefix(s.QRSVG, "<svg") {
		t.Errorf("pairing status %+v", s)
	}
	// "Try again" while the code is shown makes a fresh code.
	old := s.QRSVG
	a.Retry()
	waitFor(t, a, "new QR", func(s Status) bool { return s.QRSVG != "" && s.QRSVG != old })
}

func TestNoPhoneFoundKeepsSearching(t *testing.T) {
	d := baseDeps()
	var calls atomic.Int32
	d.Find = func(context.Context) ([]discover.Candidate, error) { calls.Add(1); return nil, nil }
	a := New(d, t.TempDir())
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go a.Run(ctx)
	s := waitFor(t, a, "no phone", func(s Status) bool { return s.Phase == PhaseFinding && s.Error != "" })
	if !strings.Contains(s.Error, "Send to computer") {
		t.Errorf("message %q", s.Error)
	}
	waitFor(t, a, "rescans", func(Status) bool { return calls.Load() >= 3 })
}

func TestChooseFolderPhaseFirst(t *testing.T) {
	d := baseDeps()
	d.DefaultFolder = func() (string, string) { return "/home/x/GallerySlim Photos", "Pictures syncs to OneDrive" }
	found := make(chan bool, 1)
	d.Find = func(context.Context) ([]discover.Candidate, error) {
		select {
		case found <- true:
		default:
		}
		return nil, nil
	}
	a := New(d, "")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go a.Run(ctx)
	s := waitFor(t, a, "choose folder", func(s Status) bool { return s.Phase == PhaseChooseFolder })
	if s.Folder != "/home/x/GallerySlim Photos" || s.FolderNote == "" {
		t.Errorf("suggestion %+v", s)
	}
	select {
	case <-found:
		t.Fatal("searched before a folder was chosen")
	case <-time.After(30 * time.Millisecond):
	}
	if err := a.SetFolder(t.TempDir()); err != nil {
		t.Fatal(err)
	}
	waitFor(t, a, "finding", func(s Status) bool { return s.Phase == PhaseFinding })
}

func TestEventsRowsAreCapped(t *testing.T) {
	a := New(baseDeps(), t.TempDir())
	ev := (*events)(a)
	var files []client.File
	for i := 0; i < 600; i++ {
		files = append(files, client.File{ID: fmt.Sprint(i), Name: fmt.Sprintf("f%d.jpg", i), Size: 10})
	}
	ev.Listed(files, 6000)
	for _, f := range files {
		ev.FileStarted(f, f.Name)
		ev.Progress(10)
		ev.FileDone(receive.Result{File: f, Name: f.Name, Rel: "2023-11/" + f.Name, Outcome: receive.Verified})
	}
	s := a.Status()
	if len(s.Rows) != MaxRows || s.FilesDone != 600 || s.BytesDone != 6000 || s.FilesTotal != 600 {
		t.Fatalf("rows %d done %d bytes %d", len(s.Rows), s.FilesDone, s.BytesDone)
	}
	if last := s.Rows[len(s.Rows)-1]; last.Name != "2023-11/f599.jpg" || last.State != RowVerified {
		t.Errorf("last row %+v", last)
	}
}

func TestHeadlessExitCodes(t *testing.T) {
	var out bytes.Buffer
	d := baseDeps()
	if code := RunHeadless(context.Background(), HeadlessOptions{Deps: d, Out: &out}); code != ExitUsage {
		t.Errorf("no folder: %d", code)
	}
	d.Find = func(context.Context) ([]discover.Candidate, error) { return nil, nil }
	if code := RunHeadless(context.Background(), HeadlessOptions{Deps: d, Folder: t.TempDir(), Out: &out}); code != ExitFailed {
		t.Errorf("no phone: %d", code)
	}
	d.Find = func(context.Context) ([]discover.Candidate, error) {
		return []discover.Candidate{{Addr: "192.0.2.1:8765", Name: "A", Code: "K7-42"}, {Addr: "192.0.2.2:8765", Name: "B", Code: "M3-17"}}, nil
	}
	out.Reset()
	if code := RunHeadless(context.Background(), HeadlessOptions{Deps: d, Folder: t.TempDir(), Out: &out}); code != ExitUsage {
		t.Errorf("two phones: %d", code)
	}
	if !strings.Contains(out.String(), "192.0.2.2:8765") {
		t.Errorf("phones not listed: %s", out.String())
	}
}

func TestConfigRoundTrip(t *testing.T) {
	dir := t.TempDir()
	no := false
	if err := SaveConfig(dir, Config{Folder: "/photos", OpenBrowser: &no}); err != nil {
		t.Fatal(err)
	}
	c := LoadConfig(dir)
	if c.Folder != "/photos" || c.WantsBrowser() {
		t.Errorf("%+v", c)
	}
	b, _ := os.ReadFile(filepath.Join(dir, ConfigFile))
	if strings.Contains(strings.ToLower(string(b)), "key") {
		t.Error("config mentions a key")
	}
	os.WriteFile(filepath.Join(dir, ConfigFile), []byte(`{"folder":"relative"}`), 0o600)
	if LoadConfig(dir).Folder != "" {
		t.Error("relative folder accepted from config")
	}
}
