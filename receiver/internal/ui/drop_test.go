package ui

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
)

type sendRecorder struct {
	mu    sync.Mutex
	paths [][]string
	err   error
}

func (r *sendRecorder) send(p []string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.paths = append(r.paths, append([]string(nil), p...))
	return r.err
}

func (r *sendRecorder) last() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	if len(r.paths) == 0 {
		return nil
	}
	return r.paths[len(r.paths)-1]
}

func (c *uiClient) drop(name, body string, mod func(*http.Request)) (int, actionResult) {
	c.t.Helper()
	resp, data := c.do(http.MethodPost, "drop", body, func(r *http.Request) {
		r.Header.Set("Content-Type", "application/octet-stream")
		r.Header.Set("X-Drop-Name", url.PathEscape(name))
		if mod != nil {
			mod(r)
		}
	})
	var res actionResult
	_ = json.Unmarshal([]byte(data), &res)
	return resp.StatusCode, res
}

func dropSetup(t *testing.T) (*uiClient, *snapBox, *sendRecorder, string) {
	t.Helper()
	tmp := t.TempDir()
	t.Setenv("TMPDIR", tmp)
	box := &snapBox{s: Snapshot{Phase: "finding", PhoneReceiving: true}}
	rec := &sendRecorder{}
	c := startUI(t, Handlers{Snapshot: box.get, SendFiles: rec.send})
	return c, box, rec, tmp
}

func TestDropAndSend(t *testing.T) {
	c, box, rec, tmp := dropSetup(t)
	code, a := c.drop("IMG_0001.jpg", "photo-one", nil)
	if code != 200 || !a.OK || len(a.ID) != 2*dropIDBytes {
		t.Fatalf("drop 1: %d %+v", code, a)
	}
	_, b := c.drop("Holi 2024 – दादी.mp4", "video-two", nil)
	if !b.OK {
		t.Fatalf("drop 2: %+v", b)
	}
	body, _ := json.Marshal(map[string][]string{"ids": {a.ID, b.ID}})
	if _, res := c.post("drop-send", string(body)); !res.OK {
		t.Fatalf("drop-send: %+v", res)
	}
	paths := rec.last()
	if len(paths) != 2 || filepath.Base(paths[0]) != "IMG_0001.jpg" || filepath.Base(paths[1]) != "Holi 2024 – दादी.mp4" {
		t.Fatalf("SendFiles got %q", paths)
	}
	for i, want := range []string{"photo-one", "video-two"} {
		got, err := os.ReadFile(paths[i])
		if err != nil || string(got) != want {
			t.Errorf("file %d = %q, %v", i, got, err)
		}
		if rel, err := filepath.Rel(tmp, paths[i]); err != nil || strings.HasPrefix(rel, "..") {
			t.Errorf("%s is outside the temp folder %s", paths[i], tmp)
		}
		if fi, err := os.Stat(paths[i]); err == nil && fi.Mode().Perm()&0o077 != 0 {
			t.Errorf("%s mode %v, want private", paths[i], fi.Mode())
		}
	}
	// An id can be sent only once.
	if _, res := c.post("drop-send", string(body)); res.OK {
		t.Error("ids sent twice")
	}
	// Files stay while the transfer runs and go once it has ended.
	box.set(Snapshot{Phase: "receiving", Direction: "to-phone", PhoneReceiving: true})
	c.status()
	if _, err := os.Stat(paths[0]); err != nil {
		t.Fatalf("file deleted during the transfer: %v", err)
	}
	box.set(Snapshot{Phase: "done", Direction: "to-phone"})
	c.status()
	for _, p := range paths {
		if _, err := os.Stat(p); !errors.Is(err, os.ErrNotExist) {
			t.Errorf("%s still there after the transfer: %v", p, err)
		}
	}
}

func TestDropNamesAreCleaned(t *testing.T) {
	c, _, rec, _ := dropSetup(t)
	cases := map[string]string{
		"../../../etc/passwd":              "passwd",
		`C:\Users\x\..\evil.jpg`:           "evil.jpg",
		"CON.jpg":                          "_CON.jpg",
		"a<b>c:d|e?f*.jpg":                 "a_b_c_d_e_f_.jpg",
		"photo\u202egpj.exe":               "photogpj.exe",
		"..hidden.":                        "hidden",
		"tab\there.png":                    "tab here.png",
		strings.Repeat("x", 300) + ".heic": strings.Repeat("x", maxDropNameLen-5) + ".heic",
	}
	for in, want := range cases {
		_, res := c.drop(in, "data", nil)
		if !res.OK {
			t.Errorf("drop %.40q: %+v", in, res)
			continue
		}
		body, _ := json.Marshal(map[string][]string{"ids": {res.ID}})
		if _, r := c.post("drop-send", string(body)); !r.OK {
			t.Fatalf("drop-send: %+v", r)
		}
		p := rec.last()[0]
		if filepath.Base(p) != want || filepath.Base(filepath.Dir(filepath.Dir(p))) == "" {
			t.Errorf("name %.40q stored as %q, want %q", in, filepath.Base(p), want)
		}
	}
	for _, bad := range []string{"", "%zz", "..", "/", "%FF.jpg"} {
		resp, _ := c.do(http.MethodPost, "drop", "data", func(r *http.Request) {
			r.Header.Set("Content-Type", "application/octet-stream")
			r.Header.Set("X-Drop-Name", bad)
		})
		if resp.StatusCode != http.StatusBadRequest {
			t.Errorf("name %q = %d, want 400", bad, resp.StatusCode)
		}
	}
}

func TestDropRefusals(t *testing.T) {
	c, box, rec, tmp := dropSetup(t)
	p := strconv.Itoa(c.port)
	for name, mod := range map[string]func(*http.Request){
		"evil origin":  func(r *http.Request) { r.Header.Set("Origin", "http://evil.com") },
		"cross-site":   func(r *http.Request) { r.Header.Set("Sec-Fetch-Site", "cross-site") },
		"rebind host":  func(r *http.Request) { r.Host = "evil.com:" + p },
		"form":         func(r *http.Request) { r.Header.Set("Content-Type", "application/x-www-form-urlencoded") },
		"multipart":    func(r *http.Request) { r.Header.Set("Content-Type", "multipart/form-data; boundary=x") },
		"text/plain":   func(r *http.Request) { r.Header.Set("Content-Type", "text/plain") },
		"json":         func(r *http.Request) { r.Header.Set("Content-Type", "application/json") },
		"null origin":  func(r *http.Request) { r.Header.Set("Origin", "null") },
		"other port":   func(r *http.Request) { r.Header.Set("Origin", "http://127.0.0.1:"+strconv.Itoa(c.port+1)) },
		"wrong scheme": func(r *http.Request) { r.Header.Set("Origin", "https://127.0.0.1:"+p) },
	} {
		code, _ := c.drop("a.jpg", "data", mod)
		if code != http.StatusForbidden && code != http.StatusUnsupportedMediaType {
			t.Errorf("%s: %d, want 403/415", name, code)
		}
	}
	for _, path := range []string{"/drop", "/" + strings.Repeat("0", 32) + "/drop"} {
		if resp, _ := c.do(http.MethodPost, path, "data", nil); resp.StatusCode != http.StatusNotFound {
			t.Errorf("POST %s = %d, want 404", path, resp.StatusCode)
		}
	}
	for _, m := range []string{http.MethodGet, http.MethodHead, http.MethodPut} {
		resp, _ := c.do(m, "drop", "", nil)
		if resp.StatusCode != http.StatusMethodNotAllowed || resp.Header.Get("Allow") != "POST" {
			t.Errorf("%s drop = %d", m, resp.StatusCode)
		}
	}
	if _, res := c.drop("empty.jpg", "", nil); res.OK {
		t.Error("empty file accepted")
	}
	// Only while the phone accepts uploads.
	box.set(Snapshot{Phase: "finding"})
	if _, res := c.drop("a.jpg", "data", nil); res.OK || res.Error != msgPhoneNotReady {
		t.Errorf("phone not receiving: %+v", res)
	}
	box.set(Snapshot{Phase: "finding", PhoneReceiving: true})

	// Nothing was written for any refused request.
	written := 0
	_ = filepath.Walk(tmp, func(_ string, fi os.FileInfo, _ error) error {
		if fi != nil && fi.Mode().IsRegular() {
			written++
		}
		return nil
	})
	if written != 0 {
		t.Errorf("%d files written for refused drops", written)
	}

	// Ids must be known, unique and few enough.
	_, a := c.drop("a.jpg", "data", nil)
	for _, ids := range [][]string{{}, {"0123456789abcdef"}, {a.ID, a.ID}, {"../../x"}} {
		body, _ := json.Marshal(map[string][]string{"ids": ids})
		if _, res := c.post("drop-send", string(body)); res.OK {
			t.Errorf("drop-send %q accepted", ids)
		}
	}
	many := make([]string, maxDropFiles+1)
	for i := range many {
		many[i] = fmt.Sprintf("%016x", i)
	}
	body, _ := json.Marshal(map[string][]string{"ids": many})
	if _, res := c.post("drop-send", string(body)); res.OK || res.Error != msgDropTooMany {
		t.Errorf("too many ids: %+v", res)
	}
	if n := len(rec.paths); n != 0 {
		t.Errorf("SendFiles ran %d times", n)
	}

	// A SendFiles error removes the files at once.
	rec.mu.Lock()
	rec.err = errors.New("The phone has no room for these.")
	rec.mu.Unlock()
	body, _ = json.Marshal(map[string][]string{"ids": {a.ID}})
	if _, res := c.post("drop-send", string(body)); res.OK || res.Error != "The phone has no room for these." {
		t.Errorf("send error: %+v", res)
	}
	if _, err := os.Stat(rec.last()[0]); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("file kept after SendFiles failed: %v", err)
	}
}

// A body announced as bigger than 4 GiB is refused before anything is read.
func TestDropTooBig(t *testing.T) {
	c, _, _, _ := dropSetup(t)
	conn, err := net.Dial("tcp4", fmt.Sprintf("127.0.0.1:%d", c.port))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	fmt.Fprintf(conn, "POST /%s/drop HTTP/1.1\r\nHost: 127.0.0.1:%d\r\nContent-Type: application/octet-stream\r\n"+
		"X-Drop-Name: big.mp4\r\nContent-Length: %d\r\n\r\n", c.token, c.port, int64(maxDropBytes)+1)
	resp, err := http.ReadResponse(bufio.NewReader(conn), nil)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusRequestEntityTooLarge {
		t.Errorf("status %d, want 413", resp.StatusCode)
	}
}

func TestDropFilesRemovedOnStop(t *testing.T) {
	c, _, _, tmp := dropSetup(t)
	if _, res := c.drop("a.jpg", "data", nil); !res.OK {
		t.Fatalf("drop: %+v", res)
	}
	dirs, _ := filepath.Glob(filepath.Join(tmp, "galleryslim-drop-*"))
	if len(dirs) != 1 {
		t.Fatalf("temp folders: %q", dirs)
	}
	c.stop()
	if _, err := os.Stat(dirs[0]); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("temp folder kept after stop: %v", err)
	}
}

func TestPickAndSend(t *testing.T) {
	var calls atomic.Int32
	box := &snapBox{s: Snapshot{Phase: "finding"}}
	c := startUI(t, Handlers{Snapshot: box.get, PickAndSend: func() error { calls.Add(1); return nil }})
	if _, res := c.post("pick-send", "{}"); res.OK || res.Error != msgPhoneNotReady || calls.Load() != 0 {
		t.Errorf("not receiving: %+v", res)
	}
	box.set(Snapshot{Phase: "finding", PhoneReceiving: true})
	if _, res := c.post("pick-send", "{}"); !res.OK || calls.Load() != 1 {
		t.Errorf("pick-send: %+v", res)
	}
	m, _ := c.status()
	f := m["features"].(map[string]any)
	if f["pickAndSend"] != true || f["sendFiles"] != false || m["phoneReceiving"] != true {
		t.Errorf("status: %v", m)
	}
}

func TestToPhoneStatus(t *testing.T) {
	c := startUI(t, Handlers{Snapshot: func() Snapshot {
		return Snapshot{Phase: "receiving", Direction: "to-phone", Rows: []FileRow{
			{Name: "a.jpg", State: "sent"}, {Name: "b.jpg", State: "uploading"}, {Name: "c.jpg", State: "not-found", Detail: "Not found on this computer"},
		}}
	}})
	var st statusJSON
	_, data := c.status()
	if err := json.Unmarshal([]byte(data), &st); err != nil {
		t.Fatal(err)
	}
	if st.Direction != "to-phone" || st.Counts != (countsJSON{Sent: 1, Uploading: 1, NotFound: 1}) || st.Rows[2].State != "not-found" {
		t.Errorf("status = %+v", st)
	}
	c2 := startUI(t, Handlers{Snapshot: func() Snapshot { return Snapshot{Direction: "<sideways>"} }})
	if m, _ := c2.status(); m["direction"] != "to-computer" {
		t.Errorf("unknown direction = %v", m["direction"])
	}
}
