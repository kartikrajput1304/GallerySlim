package blackbox

// The receiver's local page: PDF 10.2 (folder choice), 10.3 (phases), 10.4 (typed address)
// and 10.9 "Limits, settings, log and page security" (page 84).

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"
)

type page struct {
	p     *proc
	h     home
	port  string
	token string
	base  string // http://127.0.0.1:port/token/
}

// startPage runs the receiver in page mode (a fake DISPLAY, --no-browser) and returns its
// address. Without a saved folder the receiver stays in the choose-folder phase and never
// searches the network.
func startPage(t *testing.T, h home, env []string, args ...string) *page {
	t.Helper()
	if env == nil {
		env = h.env(true)
	}
	pr := startReceiver(t, env, append([]string{"--no-browser"}, args...)...)
	pr.waitFor(t, "Close it or press Ctrl+C to stop.", 15*time.Second)
	m := pageURL.FindStringSubmatch(pr.output())
	if m == nil {
		t.Fatalf("no page address in:\n%s", pr.output())
	}
	pg := &page{p: pr, h: h, port: m[1], token: m[2], base: m[0]}
	t.Cleanup(func() {
		pr.cmd.Process.Signal(syscall.SIGINT)
		pr.wait(t, 10*time.Second)
	})
	return pg
}

type resp struct {
	Status int
	Header http.Header
	Body   string
}

var plainClient = &http.Client{Transport: &http.Transport{Proxy: nil}, Timeout: 20 * time.Second}

func (pg *page) do(t *testing.T, method, path, host string, hdr map[string]string, body []byte) resp {
	t.Helper()
	var rd io.Reader
	if body != nil {
		rd = bytes.NewReader(body)
	}
	req, err := http.NewRequest(method, "http://127.0.0.1:"+pg.port+path, rd)
	if err != nil {
		t.Fatal(err)
	}
	if host != "" {
		req.Host = host
	}
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	r, err := plainClient.Do(req)
	if err != nil {
		t.Fatalf("%s %s: %v", method, path, err)
	}
	defer r.Body.Close()
	b, _ := io.ReadAll(r.Body)
	return resp{r.StatusCode, r.Header, string(b)}
}

func (pg *page) path(rest string) string { return "/" + pg.token + "/" + rest }

func (pg *page) action(t *testing.T, name string, body any) (int, map[string]any) {
	t.Helper()
	b, _ := json.Marshal(body)
	r := pg.do(t, "POST", pg.path(name), "", map[string]string{"Content-Type": "application/json", "Origin": "http://127.0.0.1:" + pg.port}, b)
	var m map[string]any
	_ = json.Unmarshal([]byte(r.Body), &m)
	return r.Status, m
}

func (pg *page) status(t *testing.T) map[string]any {
	t.Helper()
	r := pg.do(t, "GET", pg.path("status.json"), "", nil, nil)
	var m map[string]any
	if err := json.Unmarshal([]byte(r.Body), &m); err != nil {
		t.Fatalf("status.json %d %q: %v", r.Status, r.Body, err)
	}
	return m
}

func (pg *page) waitStatus(t *testing.T, d time.Duration, cond func(map[string]any) bool) map[string]any {
	t.Helper()
	deadline := time.Now().Add(d)
	var st map[string]any
	for time.Now().Before(deadline) {
		st = pg.status(t)
		if cond(st) {
			return st
		}
		time.Sleep(200 * time.Millisecond)
	}
	t.Fatalf("status never reached the wanted state; last: %v", st)
	return nil
}

// RS-05: Host other than 127.0.0.1:port or localhost:port → 403 "Forbidden".
func TestRS05_ForeignHostForbidden(t *testing.T) {
	pg := startPage(t, newHome(t), nil)
	for _, host := range []string{"evil.example:" + pg.port, "evil.example", "127.0.0.1", "192.168.1.5:" + pg.port, "127.0.0.1:1"} {
		r := pg.do(t, "GET", pg.path(""), host, nil, nil)
		if r.Status != 403 || strings.TrimSpace(r.Body) != "Forbidden" {
			t.Errorf("Host %q → %d %q, want 403 Forbidden", host, r.Status, r.Body)
		}
	}
	for _, host := range []string{"127.0.0.1:" + pg.port, "localhost:" + pg.port} {
		if r := pg.do(t, "GET", pg.path(""), host, nil, nil); r.Status != 200 {
			t.Errorf("Host %q → %d, want 200", host, r.Status)
		}
	}
}

// RS-06: wrong or missing token, unknown path → 404.
func TestRS06_TokenRequired(t *testing.T) {
	pg := startPage(t, newHome(t), nil)
	wrong := strings.Repeat("0", len(pg.token))
	for _, p := range []string{"/", "/status.json", "/" + wrong + "/", "/" + wrong + "/status.json", pg.path("nope"), "/" + pg.token, "/" + pg.token[:31] + "/"} {
		if r := pg.do(t, "GET", p, "", nil, nil); r.Status != 404 {
			t.Errorf("GET %s → %d, want 404", p, r.Status)
		}
	}
}

// RS-07: wrong method → 405.
func TestRS07_WrongMethod(t *testing.T) {
	pg := startPage(t, newHome(t), nil)
	cases := []struct{ method, path string }{
		{"POST", pg.path("")}, {"DELETE", pg.path("status.json")}, {"GET", pg.path("retry")}, {"PUT", pg.path("quit")},
	}
	for _, c := range cases {
		if r := pg.do(t, c.method, c.path, "", map[string]string{"Content-Type": "application/json"}, []byte("{}")); r.Status != 405 {
			t.Errorf("%s %s → %d, want 405", c.method, c.path, r.Status)
		}
	}
}

// RS-08: action from another origin → 403. RS-09: not JSON → 415. RS-10: body over 16 KB →
// 413. RS-11: unknown JSON fields → 400.
func TestRS08to11_ActionChecks(t *testing.T) {
	pg := startPage(t, newHome(t), nil)
	own := "http://127.0.0.1:" + pg.port
	j := map[string]string{"Content-Type": "application/json"}
	with := func(extra map[string]string) map[string]string {
		m := map[string]string{"Content-Type": "application/json"}
		for k, v := range extra {
			m[k] = v
		}
		return m
	}
	t.Run("RS-08 other origin", func(t *testing.T) {
		for _, h := range []map[string]string{
			with(map[string]string{"Origin": "http://evil.example"}),
			with(map[string]string{"Origin": "null"}),
			with(map[string]string{"Origin": "http://127.0.0.1:1"}),
			with(map[string]string{"Sec-Fetch-Site": "cross-site"}),
			with(map[string]string{"Origin": own, "Sec-Fetch-Site": "same-site"}),
		} {
			if r := pg.do(t, "POST", pg.path("retry"), "", h, []byte("{}")); r.Status != 403 {
				t.Errorf("headers %v → %d, want 403", h, r.Status)
			}
		}
		if r := pg.do(t, "POST", pg.path("retry"), "", with(map[string]string{"Origin": own}), []byte("{}")); r.Status != 200 {
			t.Errorf("own origin → %d, want 200", r.Status)
		}
	})
	t.Run("RS-09 not JSON", func(t *testing.T) {
		for _, ct := range []string{"text/plain", "application/x-www-form-urlencoded", "multipart/form-data; boundary=x", ""} {
			h := map[string]string{"Origin": own}
			if ct != "" {
				h["Content-Type"] = ct
			}
			if r := pg.do(t, "POST", pg.path("retry"), "", h, []byte("{}")); r.Status != 415 {
				t.Errorf("Content-Type %q → %d, want 415", ct, r.Status)
			}
		}
	})
	t.Run("RS-10 body over 16 KB", func(t *testing.T) {
		big := []byte(`{"path":"` + strings.Repeat("a", 17*1024) + `"}`)
		if r := pg.do(t, "POST", pg.path("set-folder"), "", j, big); r.Status != 413 {
			t.Errorf("17 KB body → %d, want 413", r.Status)
		}
	})
	t.Run("RS-11 unknown JSON fields", func(t *testing.T) {
		if r := pg.do(t, "POST", pg.path("set-folder"), "", j, []byte(`{"path":"/x","admin":true}`)); r.Status != 400 {
			t.Errorf("unknown field → %d, want 400", r.Status)
		}
	})
}

// RS-12: listens on 127.0.0.1 only, random port, random 32-character token.
func TestRS12_LoopbackRandomPortAndToken(t *testing.T) {
	a := startPage(t, newHome(t), nil)
	b := startPage(t, newHome(t), nil)
	for _, pg := range []*page{a, b} {
		if len(pg.token) != 32 {
			t.Errorf("token %q has %d characters, want 32", pg.token, len(pg.token))
		}
	}
	if a.token == b.token {
		t.Error("two runs got the same token")
	}
	if a.port == b.port {
		t.Error("two runs got the same port")
	}
	if c, err := net.DialTimeout("tcp", "127.0.0.2:"+a.port, time.Second); err == nil {
		c.Close()
		t.Error("page answers on 127.0.0.2 (not bound to 127.0.0.1 only)")
	}
	ifaces, _ := net.InterfaceAddrs()
	for _, ia := range ifaces {
		ipn, ok := ia.(*net.IPNet)
		if !ok || ipn.IP.IsLoopback() || ipn.IP.To4() == nil {
			continue
		}
		if c, err := net.DialTimeout("tcp", net.JoinHostPort(ipn.IP.String(), a.port), time.Second); err == nil {
			c.Close()
			t.Errorf("page answers on %s", ipn.IP)
		}
	}
}

// RS-13: strict content-security policy, no cross-origin access, cannot be framed.
func TestRS13_SecurityHeaders(t *testing.T) {
	pg := startPage(t, newHome(t), nil)
	r := pg.do(t, "GET", pg.path(""), "", map[string]string{"Origin": "http://evil.example"}, nil)
	csp := r.Header.Get("Content-Security-Policy")
	if csp == "" {
		t.Fatal("no Content-Security-Policy")
	}
	if strings.Contains(csp, "unsafe-inline") && strings.Contains(csp, "script-src") && strings.Contains(csp, "'unsafe-inline'") {
		t.Errorf("CSP allows inline scripts: %s", csp)
	}
	if strings.Contains(csp, "unsafe-eval") || strings.Contains(csp, "*") {
		t.Errorf("CSP is not strict: %s", csp)
	}
	framing := r.Header.Get("X-Frame-Options") == "DENY" || strings.Contains(csp, "frame-ancestors 'none'")
	if !framing {
		t.Errorf("page can be framed (XFO %q, CSP %q)", r.Header.Get("X-Frame-Options"), csp)
	}
	if v := r.Header.Get("Access-Control-Allow-Origin"); v != "" {
		t.Errorf("cross-origin access allowed: %q", v)
	}
	pre := pg.do(t, "OPTIONS", pg.path("retry"), "", map[string]string{"Origin": "http://evil.example", "Access-Control-Request-Method": "POST"}, nil)
	if pre.Header.Get("Access-Control-Allow-Origin") != "" || pre.Status < 400 {
		t.Errorf("CORS preflight answered %d with ACAO %q", pre.Status, pre.Header.Get("Access-Control-Allow-Origin"))
	}
	st := pg.do(t, "GET", pg.path("status.json"), "", map[string]string{"Origin": "http://evil.example"}, nil)
	if st.Header.Get("Access-Control-Allow-Origin") != "" {
		t.Error("status.json readable cross-origin")
	}
}

// RS-01 / RS-03 / RS-04: settings folder private; receiver.log private; the log never holds
// the page token.
func TestRS01_RS04_SettingsAndLog(t *testing.T) {
	h := newHome(t)
	pg := startPage(t, h, nil)
	pg.status(t)
	pg.action(t, "set-folder", map[string]string{"path": "relative"})
	pg.p.cmd.Process.Signal(syscall.SIGINT)
	pg.p.wait(t, 10*time.Second)
	fi, err := os.Stat(h.settingsDir())
	if err != nil {
		t.Fatalf("settings folder %s: %v", h.settingsDir(), err)
	}
	if fi.Mode().Perm()&0o077 != 0 {
		t.Errorf("settings folder mode %v, want private to the user", fi.Mode().Perm())
	}
	logf := filepath.Join(h.settingsDir(), "receiver.log")
	li, err := os.Stat(logf)
	if err != nil {
		t.Fatalf("no receiver.log: %v", err)
	}
	if li.Mode().Perm()&0o077 != 0 {
		t.Errorf("receiver.log mode %v, want private", li.Mode().Perm())
	}
	b, _ := os.ReadFile(logf)
	if strings.Contains(string(b), pg.token) {
		t.Error("receiver.log contains the page token")
	}
}

// RD-07: path checks on the page.
func TestRD07_FolderPathChecks(t *testing.T) {
	pg := startPage(t, newHome(t), nil)
	cases := map[string]string{
		"":                                  "Please choose a folder, or type its full path.",
		"   ":                               "Please choose a folder, or type its full path.",
		"/" + strings.Repeat("a", 4100):     "That folder path is too long.",
		"/tmp/evil‮gnp.exe":            "That folder path has characters that can't be used.",
		"/tmp/line\nbreak":                  "That folder path has characters that can't be used.",
		"Pictures/Phone":                    "Please type the full folder path, for example ",
	}
	for path, want := range cases {
		_, m := pg.action(t, "set-folder", map[string]string{"path": path})
		got, _ := m["error"].(string)
		if m["ok"] == true || !strings.HasPrefix(got, want) {
			t.Errorf("set-folder %q → %v, want error starting %q", trunc(path), m, want)
		}
	}
	if st := pg.status(t); st["phase"] != "choose-folder" {
		t.Errorf("phase %v after rejected paths", st["phase"])
	}
}

func trunc(s string) string {
	if len(s) > 40 {
		return s[:40] + "…"
	}
	return s
}

// RD-10: not writable → "Photos can't be saved in that folder: <cause>".
func TestRD10_FolderNotWritable(t *testing.T) {
	pg := startPage(t, newHome(t), nil)
	file := filepath.Join(t.TempDir(), "file")
	os.WriteFile(file, []byte("x"), 0o644)
	_, m := pg.action(t, "set-folder", map[string]string{"path": filepath.Join(file, "Photos")})
	got, _ := m["error"].(string)
	if m["ok"] == true || !strings.HasPrefix(got, "Photos can't be saved in that folder: ") || len(got) <= len("Photos can't be saved in that folder: ") {
		t.Errorf("→ %v", m)
	}
}

// RD-13: without a folder dialog program: "This computer can't show a folder picker; type the
// folder path." and the text box opens.
func TestRD13_NoFolderDialog(t *testing.T) {
	h := newHome(t)
	env := h.env(true)
	for i, e := range env {
		if strings.HasPrefix(e, "PATH=") {
			env[i] = "PATH=" + t.TempDir()
		}
	}
	pg := startPage(t, h, env)
	_, m := pg.action(t, "choose-folder", map[string]string{})
	if m["error"] != "This computer can't show a folder picker; type the folder path." || m["needPath"] != true {
		t.Errorf("choose-folder → %v", m)
	}
}

// RP-01 / RP-10 / RP-11 / RP-18 / RD-01: the page itself (served text and script).
func TestRP01_PageContent(t *testing.T) {
	pg := startPage(t, newHome(t), nil)
	r := pg.do(t, "GET", pg.path(""), "", nil, nil)
	if r.Status != 200 || !strings.Contains(r.Header.Get("Content-Type"), "text/html") {
		t.Fatalf("page → %d %s", r.Status, r.Header.Get("Content-Type"))
	}
	st := pg.status(t)
	if st["version"] != receiverVersion {
		t.Errorf("version %v, want %s", st["version"], receiverVersion)
	}
	if st["phase"] != "choose-folder" {
		t.Errorf("first-run phase %v, want choose-folder", st["phase"])
	}
	for id, texts := range map[string][]string{
		"RP-01 header/footer": {"GallerySlim Receiver", "Close the receiver"},
		"RD-01 choose folder": {"Where should your photos go?", "Your photos will be saved in this folder, sorted into one folder for each month.", "Suggested folder", "Choose folder", "Use this folder", "Type a folder path", "Use this path"},
		"RP-03 finding":       {"Looking for your phone…", "Search again", "Enter the phone's address", "Connect"},
		"RP-07 pairing":       {"Scan this code with your phone"},
		"RP-08 receiving":     {"Receiving your photos", "Keep this window open until it says Done.", "Recent files"},
		"RP-09 done":          {"All done!", "Finished, but some photos were not saved", "Open folder", "Receive more"},
		"RP-10 row states":    {"Waiting", "Receiving…", "Saved, checking…", "Checked and safe", "Skipped", "Not saved"},
		"RP-11 lost contact":  {"Lost contact with the receiver."},
		"RP-xx error":         {"Something went wrong", "The transfer stopped.", "Photos already saved on this computer are kept.", "Try again"},
		"RP-16 closed":        {"The receiver is closed", "You can close this browser tab now."},
		"RP-18 tab titles":    {"% · ", "Done · ", "GallerySlim Receiver"},
	} {
		for _, s := range texts {
			if !strings.Contains(r.Body, s) && !strings.Contains(r.Body, jsEscape(s)) {
				t.Errorf("%s: page lacks %q", id, s)
			}
		}
	}
}

func jsEscape(s string) string {
	b, _ := json.Marshal(s)
	return strings.Trim(string(b), `"`)
}

// RP-06 / RN-05: typed phone address on the page.
func TestRP06_TypedAddressChecks(t *testing.T) {
	pg := startPage(t, newHome(t), nil)
	cases := map[string]string{
		"":                       "Please type the address shown on the phone.",
		strings.Repeat("1", 101): "That address is too long.",
		"phone!":                 "That doesn't look like the phone's address. It looks like 192.168.1.23:8765.",
		"192.168.1.23 8765":      "That doesn't look like the phone's address. It looks like 192.168.1.23:8765.",
	}
	for addr, want := range cases {
		_, m := pg.action(t, "address", map[string]string{"address": addr})
		if m["error"] != want {
			t.Errorf("address %q → %v, want %q", trunc(addr), m, want)
		}
	}
	// Host names are rejected (10.4).
	_, m := pg.action(t, "address", map[string]string{"address": "galleryslim.local:8765"})
	if m["ok"] == true {
		t.Errorf("host name accepted: %v", m)
	} else if e, _ := m["error"].(string); e != "That doesn't look like the phone's address. It looks like 192.168.1.23:8765." {
		t.Errorf("host name rejected with %q; the page's messages for a wrong address (10.4) are “That doesn't look like the phone's address. It looks like 192.168.1.23:8765.”", e)
	}
	// http:// and paths are stripped.
	if _, m := pg.action(t, "address", map[string]string{"address": "http://192.168.1.23:8765/x"}); m["ok"] != true {
		t.Errorf("pasted link not accepted: %v", m)
	}
}

// RP-16: “Close the receiver” → the program exits about 150 ms later (exit 0).
func TestRP16_CloseTheReceiver(t *testing.T) {
	h := newHome(t)
	pr := startReceiver(t, h.env(true), "--no-browser")
	pr.waitFor(t, "Close it or press Ctrl+C to stop.", 10*time.Second)
	m := pageURL.FindStringSubmatch(pr.output())
	pg := &page{p: pr, h: h, port: m[1], token: m[2]}
	start := time.Now()
	_, res := pg.action(t, "quit", map[string]string{})
	if res["ok"] != true {
		t.Errorf("quit → %v", res)
	}
	code := pr.wait(t, 5*time.Second)
	if el := time.Since(start); el > 2*time.Second {
		t.Errorf("exited %v after Close, PDF says about 150 ms", el)
	}
	if code != 0 {
		t.Errorf("exit %d, want 0", code)
	}
}

// RP-19: “Open folder” with no folder chosen → “No folder chosen yet”.
func TestRP19_OpenFolderWithoutFolder(t *testing.T) {
	pg := startPage(t, newHome(t), nil)
	if _, m := pg.action(t, "open-folder", map[string]string{}); m["error"] != "No folder chosen yet" {
		t.Errorf("open-folder → %v", m)
	}
}

// RD-02 / RD-03 / RD-04: the suggested folder.
func TestRD02to04_SuggestedFolder(t *testing.T) {
	type tc struct {
		name       string
		homeSub    string // home inside the temp dir
		picturesIn string // Pictures, relative to home
		wantRel    string // suggestion relative to home
		wantNote   string // substring of the note
	}
	for _, c := range []tc{
		{"RD-02 Pictures does not sync", "u", "Pictures", "Pictures/GallerySlim Photos", ""},
		{"RD-03 Pictures syncs", "u", "Dropbox/Pictures", "GallerySlim Photos", "Your Pictures folder syncs with Dropbox, which can move photos to the cloud and leave only placeholders on this computer."},
		{"RD-04 both sync", "Dropbox/u", "Pictures", "Pictures", "Dropbox"},
	} {
		t.Run(c.name, func(t *testing.T) {
			root := t.TempDir()
			hd := filepath.Join(root, c.homeSub)
			h := home{Dir: hd, Config: filepath.Join(hd, ".config")}
			os.MkdirAll(h.Config, 0o700)
			pics := filepath.Join(hd, c.picturesIn)
			os.MkdirAll(pics, 0o755)
			os.WriteFile(filepath.Join(h.Config, "user-dirs.dirs"), []byte(fmt.Sprintf("XDG_PICTURES_DIR=\"%s\"\n", pics)), 0o644)
			pg := startPage(t, h, nil)
			st := pg.status(t)
			want := filepath.Join(hd, c.wantRel)
			if st["folder"] != want {
				t.Errorf("suggested %v, want %s", st["folder"], want)
			}
			note, _ := st["folderNote"].(string)
			if c.wantNote == "" && note != "" {
				t.Errorf("unexpected note %q", note)
			}
			if c.wantNote != "" && !strings.Contains(note, c.wantNote) {
				t.Errorf("note %q lacks %q", note, c.wantNote)
			}
		})
	}
}

// RD-06 / RD-08 / RD-09 / RD-12 / RS-02 / RP-03: choosing a folder on the page; needs the
// loopback-only namespace because the receiver then searches for phones.
func TestRD06_RD08_ChoosingAFolder(t *testing.T) {
	needNetns(t)
	t.Run("RD-06 cloud warning is not blocking", func(t *testing.T) {
		pg := startPage(t, newHome(t), nil)
		folder := filepath.Join(t.TempDir(), "Dropbox", "Phone")
		os.MkdirAll(folder, 0o755)
		_, m := pg.action(t, "set-folder", map[string]string{"path": folder})
		if m["ok"] != true {
			t.Fatalf("synced folder refused: %v", m)
		}
		st := pg.status(t)
		want := "This folder syncs to Dropbox. Photos there can be moved to the cloud and removed from this computer, so they can't be checked here. A folder that stays on this computer is safer."
		if st["folderNote"] != want {
			t.Errorf("note %q, want %q", st["folderNote"], want)
		}
		if st["phase"] == "choose-folder" {
			t.Error("still asking for a folder")
		}
	})
	t.Run("RD-08 quotes stripped", func(t *testing.T) {
		h := newHome(t)
		pg := startPage(t, h, nil)
		folder := filepath.Join(t.TempDir(), "My Photos")
		os.MkdirAll(folder, 0o755)
		_, m := pg.action(t, "set-folder", map[string]string{"path": `"` + folder + `"`})
		if m["ok"] != true {
			t.Fatalf("quoted path refused: %v", m)
		}
		if st := pg.status(t); st["folder"] != folder {
			t.Errorf("folder %v, want %s", st["folder"], folder)
		}
		if !fileExists(filepath.Join(folder, ".gallery-slim")) {
			t.Error("RD-09: .gallery-slim/ was not created in the chosen folder")
		}
		b, _ := os.ReadFile(filepath.Join(h.settingsDir(), "config.json"))
		var cfg map[string]any
		json.Unmarshal(b, &cfg)
		if cfg["folder"] != folder {
			t.Errorf("RS-02: config.json folder %v, want %s", cfg["folder"], folder)
		}
	})
	t.Run("RD-08 tilde expanded", func(t *testing.T) {
		h := newHome(t)
		os.MkdirAll(filepath.Join(h.Dir, "PhonePics"), 0o755)
		pg := startPage(t, h, nil)
		_, m := pg.action(t, "set-folder", map[string]string{"path": "~/PhonePics"})
		if m["ok"] != true {
			t.Errorf("~/PhonePics refused: %v (PDF 10.2: ~ is expanded)", m)
		} else if st := pg.status(t); st["folder"] != filepath.Join(h.Dir, "PhonePics") {
			t.Errorf("folder %v", st["folder"])
		}
	})
	t.Run("RD-12 --folder remembered in page mode", func(t *testing.T) {
		h := newHome(t)
		folder := t.TempDir()
		pg := startPage(t, h, nil, "--folder", folder)
		pg.status(t)
		b, err := os.ReadFile(filepath.Join(h.settingsDir(), "config.json"))
		var cfg map[string]any
		json.Unmarshal(b, &cfg)
		if err != nil || cfg["folder"] != folder {
			t.Errorf("config.json %s (%v), want folder %s", b, err, folder)
		}
	})
	t.Run("RP-03 finding without a phone", func(t *testing.T) {
		pg := startPage(t, newHome(t), nil, "--folder", t.TempDir())
		want := "No phone found yet. On your phone, open GallerySlim and tap \"Send to computer\". Both need to be on the same Wi-Fi. You can also type the address shown on the phone."
		st := pg.waitStatus(t, 30*time.Second, func(m map[string]any) bool { return m["error"] == want })
		if st["phase"] != "finding" {
			t.Errorf("phase %v, want finding", st["phase"])
		}
	})
	t.Run("RS-02 relative folder in config.json is ignored", func(t *testing.T) {
		h := newHome(t)
		writeConfig(t, h, map[string]any{"folder": "relative/Photos"})
		pg := startPage(t, h, nil)
		if st := pg.status(t); st["phase"] != "choose-folder" {
			t.Errorf("phase %v; a relative folder in config.json must not be used", st["phase"])
		}
	})
}
