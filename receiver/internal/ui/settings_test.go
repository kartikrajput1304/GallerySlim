package ui

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestFeaturesFollowHandlers(t *testing.T) {
	c := startUI(t, Handlers{})
	var st statusJSON
	_, data := c.status()
	if err := json.Unmarshal([]byte(data), &st); err != nil {
		t.Fatal(err)
	}
	if st.Features != (featuresJSON{}) {
		t.Errorf("features with no handlers = %+v", st.Features)
	}
	nop := func() error { return nil }
	c = startUI(t, Handlers{
		ChooseFolder: func() (string, error) { return "", nil }, SetFolder: func(string) error { return nil },
		EnterAddress: func(string) error { return nil }, OpenFolder: nop, Retry: nop, Quit: func() {},
		Rename: func(string) error { return nil }, Forget: func(string) error { return nil },
		SetStartAtLogin: func(bool) error { return nil }, Install: nop,
		ChangeFolder: func() (string, error) { return "", nil },
		Thumb:        func(int) ([]byte, string, bool) { return nil, "", false },
	})
	st = statusJSON{}
	_, data = c.status()
	if err := json.Unmarshal([]byte(data), &st); err != nil {
		t.Fatal(err)
	}
	all := featuresJSON{true, true, true, true, true, true, true, true, true, true, true, true, false, false}
	if st.Features != all {
		t.Errorf("features with all handlers = %+v", st.Features)
	}
}

func TestSnapshotOptionalFields(t *testing.T) {
	snap := Snapshot{
		Phase: "finding", ComputerName: "  Kartik's   PC ", WaitingFor: "Galaxy S24",
		PairedPhones: []PairedPhone{{Name: "Galaxy S24", LastSeen: "2026-10-24T18:30:00Z"}, {Name: "", LastSeen: "x"}},
		StartAtLogin: true, Installed: true, EtaSeconds: 99 * 24 * 3600,
		Rows: []FileRow{{Name: "a.jpg", State: "verified", Thumb: true}},
	}
	c := startUI(t, Handlers{Snapshot: func() Snapshot { return snap }})
	var st statusJSON
	_, data := c.status()
	if err := json.Unmarshal([]byte(data), &st); err != nil {
		t.Fatal(err)
	}
	if st.ComputerName != "Kartik's PC" || st.WaitingFor != "Galaxy S24" || !st.StartAtLogin || !st.Installed {
		t.Errorf("status = %+v", st)
	}
	if len(st.PairedPhones) != 1 || st.PairedPhones[0].LastSeen != "2026-10-24T18:30:00Z" {
		t.Errorf("paired = %+v (nameless phones are dropped)", st.PairedPhones)
	}
	if st.EtaSeconds != maxEtaSeconds {
		t.Errorf("eta = %d, want clamped to %d", st.EtaSeconds, maxEtaSeconds)
	}
	if st.Rows[0].Thumb {
		t.Error("row offers a thumbnail but there is no Thumb handler")
	}
	snap.EtaSeconds = -4
	_, data = c.status()
	st = statusJSON{}
	_ = json.Unmarshal([]byte(data), &st)
	if st.EtaSeconds != 0 {
		t.Errorf("negative eta = %d", st.EtaSeconds)
	}
}

func TestRename(t *testing.T) {
	var got atomic.Value
	got.Store("")
	c := startUI(t, Handlers{Rename: func(n string) error {
		got.Store(n)
		if n == "Taken" {
			return errors.New("That name is already used.")
		}
		return nil
	}})
	for in, want := range map[string]string{
		"Kartik's PC":         "Kartik's PC",
		"  Mummy ka laptop  ": "Mummy ka laptop",
		"दादी का कंप्यूटर": "दादी का कंप्यूटर",
		"<b>PC</b>": "<b>PC</b>", // shown with textContent, so harmless
	} {
		body, _ := json.Marshal(map[string]string{"name": in})
		if _, res := c.post("rename", string(body)); !res.OK || got.Load() != want {
			t.Errorf("rename %q: %+v, Rename got %q want %q", in, res, got.Load(), want)
		}
	}
	got.Store("")
	for _, in := range []string{"", "   ", "PC‮", "PC\x00", "a\nb", "a\tb", "zero​width", "", strings.Repeat("x", 41)} {
		body, _ := json.Marshal(map[string]string{"name": in})
		if _, res := c.post("rename", string(body)); res.OK || res.Error == "" {
			t.Errorf("name %q accepted: %+v", in, res)
		}
	}
	if got.Load() != "" {
		t.Errorf("Rename ran with invalid name %q", got.Load())
	}
	if _, res := c.post("rename", `{"name":"Taken"}`); res.OK || res.Error != "That name is already used." {
		t.Errorf("Rename error not shown: %+v", res)
	}
	if resp, _ := c.do(http.MethodPost, "rename", `{"name":"x","path":1}`, nil); resp.StatusCode != http.StatusBadRequest {
		t.Errorf("bad body = %d", resp.StatusCode)
	}
}

func TestForgetOnlyPairedPhones(t *testing.T) {
	var got atomic.Value
	got.Store("")
	c := startUI(t, Handlers{
		Snapshot: func() Snapshot {
			return Snapshot{Phase: "finding", PairedPhones: []PairedPhone{
				{Name: "Galaxy‮ S24"}, // shown without the bidi control
				{Name: "Pixel 8"},
			}}
		},
		Forget: func(n string) error { got.Store(n); return nil },
	})
	m, _ := c.status()
	shown := m["pairedPhones"].([]any)[0].(map[string]any)["name"].(string)
	body, _ := json.Marshal(map[string]string{"phone": shown})
	if _, res := c.post("forget", string(body)); !res.OK || got.Load() != "Galaxy‮ S24" {
		t.Errorf("forget shown name: %+v, Forget got %q", res, got.Load())
	}
	if _, res := c.post("forget", `{"phone":"Pixel 8"}`); !res.OK || got.Load() != "Pixel 8" {
		t.Errorf("forget Pixel: %+v", res)
	}
	got.Store("")
	for _, in := range []string{"", "Pixel", "pixel 8", "Pixel 8 ", "iPhone"} {
		body, _ := json.Marshal(map[string]string{"phone": in})
		if _, res := c.post("forget", string(body)); res.OK || res.Error == "" {
			t.Errorf("forget %q accepted: %+v", in, res)
		}
	}
	if got.Load() != "" {
		t.Errorf("Forget ran with %q", got.Load())
	}
}

func TestStartAtLoginAndInstall(t *testing.T) {
	var on atomic.Int32 // 0 unset, 1 off, 2 on
	var installs atomic.Int32
	c := startUI(t, Handlers{
		SetStartAtLogin: func(b bool) error {
			if b {
				on.Store(2)
			} else {
				on.Store(1)
			}
			return nil
		},
		Install: func() error {
			if installs.Add(1) > 1 {
				return errors.New("Couldn't copy the receiver. Is the disk full?")
			}
			return nil
		},
	})
	if _, res := c.post("start-at-login", `{}`); res.OK || on.Load() != 0 {
		t.Errorf("start-at-login without on: %+v", res)
	}
	for _, body := range []string{`{"on":"yes"}`, `{"on":1}`} {
		if resp, _ := c.do(http.MethodPost, "start-at-login", body, nil); resp.StatusCode != http.StatusBadRequest {
			t.Errorf("body %s = %d, want 400", body, resp.StatusCode)
		}
	}
	if _, res := c.post("start-at-login", `{"on":true}`); !res.OK || on.Load() != 2 {
		t.Errorf("on: %+v", res)
	}
	if _, res := c.post("start-at-login", `{"on":false}`); !res.OK || on.Load() != 1 {
		t.Errorf("off: %+v", res)
	}
	if _, res := c.post("install", `{}`); !res.OK {
		t.Errorf("install: %+v", res)
	}
	if _, res := c.post("install", ``); res.OK || res.Error != "Couldn't copy the receiver. Is the disk full?" {
		t.Errorf("install error: %+v", res)
	}
}

func TestChangeFolder(t *testing.T) {
	release := make(chan struct{})
	entered := make(chan struct{}, 1)
	var block atomic.Bool
	c := startUI(t, Handlers{ChangeFolder: func() (string, error) {
		if block.Load() {
			entered <- struct{}{}
			<-release
		}
		return "/new/Photos", nil
	}, Retry: func() error { return nil }})
	if _, res := c.post("change-folder", "{}"); !res.OK || res.Path == nil || *res.Path != "/new/Photos" {
		t.Errorf("change-folder: %+v", res)
	}
	block.Store(true)
	first := make(chan actionResult, 1)
	go func() { _, res := c.post("change-folder", "{}"); first <- res }()
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("ChangeFolder not called")
	}
	if _, res := c.post("retry", "{}"); res.OK || res.Error != msgDialogOpen {
		t.Errorf("retry while the folder window is open: %+v", res)
	}
	close(release)
	if res := <-first; !res.OK {
		t.Errorf("first: %+v", res)
	}
}

// The page hides what it cannot do and keeps every new control on the action list.
func TestPageHasNewControls(t *testing.T) {
	for _, id := range []string{
		`id="btn-settings"`, `id="settings"`, `id="btn-rename"`, `id="st-login"`, `id="btn-install"`,
		`id="st-phones"`, `id="btn-change-folder"`, `id="rc-ring"`, `id="rc-thumbs"`, `id="rc-live"`,
		`aria-live="polite"`, `id="cn-waiting"`,
	} {
		if !strings.Contains(pageHTML, id) {
			t.Errorf("page.html lacks %s", id)
		}
	}
	for _, a := range allActions {
		if !strings.Contains(pageJS, "'"+a+"'") {
			t.Errorf("page.js never posts %q", a)
		}
	}
	if !strings.Contains(pageJS, "'thumb/' + idx") || !strings.Contains(pageJS, "idx = int(r.index)") {
		t.Error("page.js must build thumbnail URLs from an integer index only")
	}
}
