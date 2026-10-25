package ui

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"galleryslim/receiver/internal/qr"
)

// ---- helpers ----

type uiClient struct {
	t     *testing.T
	base  string // http://127.0.0.1:<port>/<token>/
	root  string // http://127.0.0.1:<port>
	port  int
	token string
	stop  func()
	http  *http.Client
}

func startUI(t *testing.T, h Handlers) *uiClient {
	t.Helper()
	if h.Snapshot == nil {
		h.Snapshot = func() Snapshot { return Snapshot{Phase: "finding"} }
	}
	raw, stop, err := Start(h)
	if err != nil {
		t.Fatalf("Start: %v", err)
	}
	t.Cleanup(stop)
	u, err := url.Parse(raw)
	if err != nil {
		t.Fatalf("parse %q: %v", raw, err)
	}
	port, err := strconv.Atoi(u.Port())
	if err != nil {
		t.Fatalf("port in %q: %v", raw, err)
	}
	tr := &http.Transport{DisableKeepAlives: true}
	t.Cleanup(tr.CloseIdleConnections)
	return &uiClient{
		t: t, base: raw, root: "http://" + u.Host, port: port,
		token: strings.Trim(u.Path, "/"), stop: stop,
		http: &http.Client{Transport: tr, Timeout: 10 * time.Second},
	}
}

// do sends a request. path is relative to the token base unless it starts with "/".
// POSTs get a same-origin Origin and a JSON content type unless mod changes them.
func (c *uiClient) do(method, path, body string, mod func(*http.Request)) (*http.Response, string) {
	c.t.Helper()
	target := c.base + path
	if strings.HasPrefix(path, "/") {
		target = c.root + path
	}
	var rd io.Reader
	if body != "" {
		rd = strings.NewReader(body)
	}
	req, err := http.NewRequest(method, target, rd)
	if err != nil {
		c.t.Fatalf("NewRequest: %v", err)
	}
	if method == http.MethodPost {
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Origin", c.root)
	}
	if mod != nil {
		mod(req)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		c.t.Fatalf("%s %s: %v", method, target, err)
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		c.t.Fatalf("read body: %v", err)
	}
	return resp, string(data)
}

func (c *uiClient) post(action, body string) (int, actionResult) {
	c.t.Helper()
	resp, data := c.do(http.MethodPost, action, body, nil)
	var res actionResult
	if err := json.Unmarshal([]byte(data), &res); err != nil {
		c.t.Fatalf("POST %s: status %d, body %q is not JSON: %v", action, resp.StatusCode, data, err)
	}
	return resp.StatusCode, res
}

func (c *uiClient) status() (map[string]any, string) {
	c.t.Helper()
	resp, data := c.do(http.MethodGet, "status.json", "", nil)
	if resp.StatusCode != http.StatusOK {
		c.t.Fatalf("status.json: %d %s", resp.StatusCode, data)
	}
	var m map[string]any
	if err := json.Unmarshal([]byte(data), &m); err != nil {
		c.t.Fatalf("status.json is not JSON: %v", err)
	}
	return m, data
}

// snapBox lets a test change the Snapshot while the server reads it from other goroutines.
type snapBox struct {
	mu sync.Mutex
	s  Snapshot
}

func (b *snapBox) get() Snapshot  { b.mu.Lock(); defer b.mu.Unlock(); return b.s }
func (b *snapBox) set(s Snapshot) { b.mu.Lock(); b.s = s; b.mu.Unlock() }

func absPath(t *testing.T, elem ...string) string {
	t.Helper()
	return filepath.Join(append([]string{t.TempDir()}, elem...)...)
}

var allActions = []string{
	"choose-folder", "set-folder", "address", "open-folder", "retry", "quit",
	"rename", "forget", "start-at-login", "install", "change-folder", "pick-send", "drop-send",
}

// ---- Start, token, port ----

func TestStartRequiresSnapshot(t *testing.T) {
	if _, _, err := Start(Handlers{}); err == nil {
		t.Fatal("Start without Snapshot handler succeeded")
	}
}

func TestStartURLShape(t *testing.T) {
	a := startUI(t, Handlers{})
	b := startUI(t, Handlers{})
	re := regexp.MustCompile(`^http://127\.0\.0\.1:[0-9]+/[0-9a-f]{32}/$`)
	for _, c := range []*uiClient{a, b} {
		if !re.MatchString(c.base) {
			t.Errorf("URL %q does not match %s", c.base, re)
		}
	}
	if a.token == b.token || a.port == b.port {
		t.Errorf("two servers share a token or port: %q %q", a.base, b.base)
	}
}

func TestTokenRequired(t *testing.T) {
	c := startUI(t, Handlers{})
	wrong := []byte(c.token)
	wrong[0] ^= 1
	for _, p := range []string{
		"/", "/status.json", "/favicon.ico", "/" + c.token, "/" + string(wrong) + "/",
		"/" + string(wrong) + "/status.json", "/" + c.token[:31] + "/", "/" + c.token + "x/",
		"/x/" + c.token + "/", "/" + c.token + "/nope", "/" + c.token + "/status.json/",
	} {
		resp, _ := c.do(http.MethodGet, p, "", nil)
		if resp.StatusCode != http.StatusNotFound {
			t.Errorf("GET %s = %d, want 404", p, resp.StatusCode)
		}
	}
	resp, _ := c.do(http.MethodPost, "/retry", "{}", nil)
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("POST /retry without token = %d, want 404", resp.StatusCode)
	}
	resp, body := c.do(http.MethodGet, "", "", nil)
	if resp.StatusCode != http.StatusOK || !strings.HasPrefix(resp.Header.Get("Content-Type"), "text/html") {
		t.Fatalf("GET page = %d %q", resp.StatusCode, resp.Header.Get("Content-Type"))
	}
	if !strings.Contains(body, `<html lang="en">`) {
		t.Error("page lacks lang=\"en\"")
	}
}

func TestStartStopReleasesPort(t *testing.T) {
	raw, stop, err := Start(Handlers{Snapshot: func() Snapshot { return Snapshot{} }})
	if err != nil {
		t.Fatal(err)
	}
	u, _ := url.Parse(raw)
	if u.Hostname() != "127.0.0.1" {
		t.Fatalf("listening on %q, want 127.0.0.1", u.Hostname())
	}
	resp, err := http.Get(raw + "status.json")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	stop()
	stop() // idempotent
	ln, err := net.Listen("tcp4", u.Host)
	if err != nil {
		t.Fatalf("port %s still busy after stop: %v", u.Host, err)
	}
	ln.Close()
	client := &http.Client{Timeout: 2 * time.Second}
	if resp, err := client.Get(raw + "status.json"); err == nil {
		resp.Body.Close()
		t.Fatal("server still answers after stop")
	}
}

// ---- DNS rebinding, cross-origin, methods ----

func TestHostCheckBlocksRebinding(t *testing.T) {
	var retries atomic.Int32
	c := startUI(t, Handlers{Retry: func() error { retries.Add(1); return nil }})
	p := strconv.Itoa(c.port)
	for _, host := range []string{
		"evil.com", "evil.com:" + p, "127.0.0.1", "127.0.0.1:" + strconv.Itoa(c.port+1),
		"localhost.evil.com:" + p, "127.0.0.1.evil.com:" + p, "[::1]:" + p, "0.0.0.0:" + p,
	} {
		for _, path := range []string{"", "status.json"} {
			resp, _ := c.do(http.MethodGet, path, "", func(r *http.Request) { r.Host = host })
			if resp.StatusCode != http.StatusForbidden && resp.StatusCode != http.StatusMisdirectedRequest {
				t.Errorf("GET %q with Host %q = %d, want 403/421", path, host, resp.StatusCode)
			}
		}
		resp, _ := c.do(http.MethodPost, "retry", "{}", func(r *http.Request) { r.Host = host })
		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("POST retry with Host %q = %d, want 403", host, resp.StatusCode)
		}
	}
	if n := retries.Load(); n != 0 {
		t.Fatalf("Retry ran %d times through a rebinding Host", n)
	}
	for _, host := range []string{"127.0.0.1:" + p, "localhost:" + p} {
		resp, _ := c.do(http.MethodGet, "status.json", "", func(r *http.Request) { r.Host = host })
		if resp.StatusCode != http.StatusOK {
			t.Errorf("Host %q = %d, want 200", host, resp.StatusCode)
		}
	}
}

func TestCrossOriginPostRejected(t *testing.T) {
	var retries atomic.Int32
	c := startUI(t, Handlers{Retry: func() error { retries.Add(1); return nil }})
	p := strconv.Itoa(c.port)
	bad := []struct {
		name string
		mod  func(*http.Request)
		want int
	}{
		{"evil origin", func(r *http.Request) { r.Header.Set("Origin", "http://evil.com") }, 403},
		{"null origin", func(r *http.Request) { r.Header.Set("Origin", "null") }, 403},
		{"https origin", func(r *http.Request) { r.Header.Set("Origin", "https://127.0.0.1:"+p) }, 403},
		{"other port", func(r *http.Request) { r.Header.Set("Origin", "http://127.0.0.1:"+strconv.Itoa(c.port+1)) }, 403},
		{"origin with path", func(r *http.Request) { r.Header.Set("Origin", "http://127.0.0.1:"+p+"/") }, 403},
		{"two origins", func(r *http.Request) {
			r.Header.Set("Origin", "http://127.0.0.1:"+p)
			r.Header.Add("Origin", "http://evil.com")
		}, 403},
		{"cross-site fetch metadata", func(r *http.Request) { r.Header.Set("Sec-Fetch-Site", "cross-site") }, 403},
		{"same-site fetch metadata", func(r *http.Request) { r.Header.Set("Sec-Fetch-Site", "same-site") }, 403},
		{"form content type", func(r *http.Request) { r.Header.Set("Content-Type", "application/x-www-form-urlencoded") }, 415},
		{"text/plain", func(r *http.Request) { r.Header.Set("Content-Type", "text/plain") }, 415},
		{"no content type", func(r *http.Request) { r.Header.Del("Content-Type") }, 415},
	}
	for _, b := range bad {
		resp, _ := c.do(http.MethodPost, "retry", "{}", b.mod)
		if resp.StatusCode != b.want {
			t.Errorf("%s: status %d, want %d", b.name, resp.StatusCode, b.want)
		}
		if v := resp.Header.Get("Access-Control-Allow-Origin"); v != "" {
			t.Errorf("%s: CORS header %q", b.name, v)
		}
	}
	if n := retries.Load(); n != 0 {
		t.Fatalf("Retry ran %d times for rejected requests", n)
	}
	good := []func(*http.Request){
		nil, // same origin 127.0.0.1
		func(r *http.Request) { r.Header.Set("Origin", "http://localhost:"+p) },
		func(r *http.Request) { r.Header.Del("Origin") }, // non-browser client
		func(r *http.Request) { r.Header.Set("Sec-Fetch-Site", "same-origin") },
		func(r *http.Request) { r.Header.Set("Content-Type", "application/json; charset=utf-8") },
	}
	for i, mod := range good {
		resp, body := c.do(http.MethodPost, "retry", "{}", mod)
		if resp.StatusCode != http.StatusOK || !strings.Contains(body, `"ok":true`) {
			t.Errorf("good request %d: %d %s", i, resp.StatusCode, body)
		}
	}
	if n := retries.Load(); n != int32(len(good)) {
		t.Fatalf("Retry ran %d times, want %d", n, len(good))
	}
}

func TestPreflightNotApproved(t *testing.T) {
	c := startUI(t, Handlers{})
	resp, _ := c.do(http.MethodOptions, "retry", "", func(r *http.Request) {
		r.Header.Set("Origin", "http://evil.com")
		r.Header.Set("Access-Control-Request-Method", "POST")
	})
	if resp.StatusCode < 400 {
		t.Errorf("OPTIONS = %d, want an error status", resp.StatusCode)
	}
	for k := range resp.Header {
		if strings.HasPrefix(k, "Access-Control-") {
			t.Errorf("CORS header %s present", k)
		}
	}
}

func TestActionsArePostOnly(t *testing.T) {
	var calls atomic.Int32
	count := func() error { calls.Add(1); return nil }
	c := startUI(t, Handlers{
		ChooseFolder: func() (string, error) { calls.Add(1); return "", nil },
		SetFolder:    func(string) error { calls.Add(1); return nil },
		EnterAddress: func(string) error { calls.Add(1); return nil },
		OpenFolder:   count,
		Retry:        count,
		Quit:         func() { calls.Add(1) },

		Rename:          func(string) error { calls.Add(1); return nil },
		Forget:          func(string) error { calls.Add(1); return nil },
		SetStartAtLogin: func(bool) error { calls.Add(1); return nil },
		Install:         count,
		ChangeFolder:    func() (string, error) { calls.Add(1); return "", nil },
		PickAndSend:     count,
		SendFiles:       func([]string) error { calls.Add(1); return nil },
	})
	for _, a := range allActions {
		for _, m := range []string{http.MethodGet, http.MethodHead, http.MethodPut, http.MethodDelete} {
			resp, _ := c.do(m, a, "", nil)
			if resp.StatusCode != http.StatusMethodNotAllowed {
				t.Errorf("%s %s = %d, want 405", m, a, resp.StatusCode)
			}
			if resp.Header.Get("Allow") != "POST" {
				t.Errorf("%s %s Allow = %q", m, a, resp.Header.Get("Allow"))
			}
		}
	}
	for _, p := range []string{"", "status.json"} {
		resp, _ := c.do(http.MethodPost, p, "{}", nil)
		if resp.StatusCode != http.StatusMethodNotAllowed {
			t.Errorf("POST %q = %d, want 405", p, resp.StatusCode)
		}
	}
	time.Sleep(300 * time.Millisecond) // a wrongly accepted quit runs after a short delay
	if n := calls.Load(); n != 0 {
		t.Fatalf("handlers ran %d times for non-POST requests", n)
	}
}

func TestBodyValidation(t *testing.T) {
	var got atomic.Value
	c := startUI(t, Handlers{EnterAddress: func(a string) error { got.Store(a); return nil }})
	big := `{"address":"` + strings.Repeat("1", maxBodyBytes) + `"}`
	if resp, _ := c.do(http.MethodPost, "address", big, nil); resp.StatusCode != http.StatusRequestEntityTooLarge {
		t.Errorf("oversized body = %d, want 413", resp.StatusCode)
	}
	for _, body := range []string{`{"address":"1.2.3.4","x":1}`, `{"address":"1.2.3.4"}{}`, `[`, `"str"`} {
		if resp, _ := c.do(http.MethodPost, "address", body, nil); resp.StatusCode != http.StatusBadRequest {
			t.Errorf("body %q = %d, want 400", body, resp.StatusCode)
		}
	}
	if got.Load() != nil {
		t.Fatalf("EnterAddress ran with %v for a bad body", got.Load())
	}
}

// ---- security headers and CSP ----

func TestSecurityHeadersEverywhere(t *testing.T) {
	c := startUI(t, Handlers{})
	checks := []struct {
		method, path string
		mod          func(*http.Request)
	}{
		{http.MethodGet, "", nil},
		{http.MethodGet, "status.json", nil},
		{http.MethodGet, "/", nil},
		{http.MethodGet, "", func(r *http.Request) { r.Host = "evil.com" }},
		{http.MethodPost, "retry", nil},
		{http.MethodGet, "retry", nil},
	}
	for _, ch := range checks {
		resp, _ := c.do(ch.method, ch.path, "{}", ch.mod)
		h := resp.Header
		want := map[string]string{
			"X-Content-Type-Options": "nosniff",
			"Referrer-Policy":        "no-referrer",
			"Cache-Control":          "no-store",
		}
		for k, v := range want {
			if h.Get(k) != v {
				t.Errorf("%s %q: %s = %q, want %q", ch.method, ch.path, k, h.Get(k), v)
			}
		}
		csp := h.Get("Content-Security-Policy")
		if !strings.Contains(csp, "default-src 'none'") {
			t.Errorf("%s %q: CSP %q lacks default-src 'none'", ch.method, ch.path, csp)
		}
		for k := range h {
			if strings.HasPrefix(k, "Access-Control-") {
				t.Errorf("%s %q: CORS header %s", ch.method, ch.path, k)
			}
		}
	}
}

func TestCSPMatchesInlineBlocks(t *testing.T) {
	c := startUI(t, Handlers{})
	resp, page := c.do(http.MethodGet, "", "", nil)
	csp := resp.Header.Get("Content-Security-Policy")
	for _, bad := range []string{"unsafe-inline", "unsafe-eval", "http:", "https:", "*", "data:"} {
		if strings.Contains(csp, bad) {
			t.Errorf("CSP %q allows %q", csp, bad)
		}
	}
	if !strings.Contains(csp, "img-src 'self';") {
		t.Errorf("CSP %q: images must come from this origin only", csp)
	}
	for _, tag := range []string{"script", "style"} {
		re := regexp.MustCompile(`(?s)<` + tag + `>(.*?)</` + tag + `>`)
		blocks := re.FindAllStringSubmatch(page, -1)
		if len(blocks) != 1 {
			t.Fatalf("page has %d <%s> blocks, want 1", len(blocks), tag)
		}
		sum := sha256.Sum256([]byte(blocks[0][1]))
		src := "'sha256-" + base64.StdEncoding.EncodeToString(sum[:]) + "'"
		if !strings.Contains(csp, tag+"-src "+src) {
			t.Errorf("CSP %q does not allow the <%s> block (%s)", csp, tag, src)
		}
	}
	// Nothing external, nothing the CSP would block.
	for _, re := range []*regexp.Regexp{
		regexp.MustCompile(`(?i)<(script|link|img|iframe|object|embed)[^>]*\s(src|href)\s*=`),
		regexp.MustCompile(`(?i)\sstyle\s*=`),
		regexp.MustCompile(`(?i)<[a-z][^>]*\son[a-z]+\s*=`),
		regexp.MustCompile(`(?i)https?://(?:[^w]|w[^w]|ww[^w])`), // only the SVG namespace URI is allowed
	} {
		if m := re.FindString(page); m != "" {
			t.Errorf("page contains %q", m)
		}
	}
}

func TestAssemblePageRejectsBrokenAssets(t *testing.T) {
	cases := []struct{ html, css, js string }{
		{"<style></style><script>{{JS}}</script>", "", ""},
		{"<style>{{CSS}}</style><script></script>", "", ""},
		{"<style>{{CSS}}</style><script>{{JS}}</script>", "a{}</style>", ""},
		{"<style>{{CSS}}</style><script>{{JS}}</script>", "", "x='</SCRIPT>'"},
		{"<style>{{CSS}}</style><script>{{JS}}</script>", "", "x='<!--'"},
	}
	for i, c := range cases {
		if _, err := assemblePage(c.html, c.css, c.js); err == nil {
			t.Errorf("case %d: assemblePage accepted broken assets", i)
		}
	}
	p, err := assemblePage("<style>{{CSS}}</style><script>{{JS}}</script>", "a{}\r\n", "x=1\r\n")
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(p.body), "\r") || !strings.Contains(p.csp, hashSource("x=1\n")) {
		t.Errorf("CRLF not normalized before hashing: %q %q", p.body, p.csp)
	}
}

// ---- status.json ----

func TestStatusJSONShape(t *testing.T) {
	snap := Snapshot{
		Phase: "receiving", Folder: `/home/a/GallerySlim Photos`, FolderNote: "note",
		PhoneName: "Galaxy S24", Code: "K7-42", QRSVG: "",
		Phones:    []PhoneChoice{{Name: "Pixel", Code: "AB-12", Addr: "192.168.1.5:8765"}},
		FilesDone: 142, FilesTotal: 300, BytesDone: 1_200_000_000, BytesTotal: 2_900_000_000,
		Current: "IMG_0001.jpg",
		Rows: []FileRow{
			{Name: "a.jpg", Size: 10, State: "verified"},
			{Name: "b.jpg", Size: 20, State: "failed", Detail: "Disk full"},
			{Name: "c.jpg", Size: 30, State: "receiving"},
		},
		Error: "", Version: "0.1.0",
	}
	box := &snapBox{s: snap}
	c := startUI(t, Handlers{Snapshot: box.get})
	resp, _ := c.do(http.MethodGet, "status.json", "", nil)
	if ct := resp.Header.Get("Content-Type"); ct != "application/json; charset=utf-8" {
		t.Errorf("Content-Type = %q", ct)
	}
	m, _ := c.status()
	strs := []string{"phase", "folder", "folderNote", "phoneName", "code", "qrSvg", "current", "error", "version"}
	nums := []string{"filesDone", "filesTotal", "bytesDone", "bytesTotal", "rowsHidden"}
	for _, k := range strs {
		if _, ok := m[k].(string); !ok {
			t.Errorf("%s = %#v, want string", k, m[k])
		}
	}
	for _, k := range nums {
		if _, ok := m[k].(float64); !ok {
			t.Errorf("%s = %#v, want number", k, m[k])
		}
	}
	strs = append(strs, "computerName", "waitingFor", "direction")
	nums = append(nums, "etaSeconds")
	for _, k := range []string{"computerName", "waitingFor"} {
		if _, ok := m[k].(string); !ok {
			t.Errorf("%s = %#v, want string", k, m[k])
		}
	}
	for _, k := range []string{"startAtLogin", "installed", "phoneReceiving"} {
		if _, ok := m[k].(bool); !ok {
			t.Errorf("%s = %#v, want bool", k, m[k])
		}
	}
	// + phones, rows, counts, pairedPhones, features, startAtLogin, installed, phoneReceiving
	if len(m) != len(strs)+len(nums)+8 {
		t.Errorf("status has %d keys: %v", len(m), m)
	}
	var st statusJSON
	_, data := c.status()
	if err := json.Unmarshal([]byte(data), &st); err != nil {
		t.Fatal(err)
	}
	if st.Phase != "receiving" || st.FilesDone != 142 || st.BytesTotal != 2_900_000_000 || st.Code != "K7-42" ||
		st.Folder != snap.Folder || st.Version != "0.1.0" || st.Current != "IMG_0001.jpg" {
		t.Errorf("status = %+v", st)
	}
	if len(st.Rows) != 3 || st.Rows[1] != (rowJSON{Index: 1, Name: "b.jpg", Size: 20, State: "failed", Detail: "Disk full"}) {
		t.Errorf("rows = %+v", st.Rows)
	}
	if st.Counts != (countsJSON{Verified: 1, Failed: 1, Receiving: 1}) {
		t.Errorf("counts = %+v", st.Counts)
	}
	if len(st.Phones) != 1 || st.Phones[0] != (phoneJSON{Name: "Pixel", Code: "AB-12", Addr: "192.168.1.5:8765"}) {
		t.Errorf("phones = %+v", st.Phones)
	}

	// Empty lists are [] not null; unknown phase and states are normalised; negatives clamp.
	box.set(Snapshot{Phase: "<b>hack</b>", FilesDone: -5, BytesDone: -1,
		Rows: []FileRow{{Name: "x", Size: -3, State: "pwned"}}})
	_, data = c.status()
	for _, k := range []string{`"phones":[]`, `"pairedPhones":[]`} {
		if !strings.Contains(data, k) {
			t.Errorf("%s not an empty list: %s", k, data)
		}
	}
	st = statusJSON{}
	if err := json.Unmarshal([]byte(data), &st); err != nil {
		t.Fatal(err)
	}
	if st.Phase != "" || st.FilesDone != 0 || st.BytesDone != 0 || st.Rows[0].State != "waiting" || st.Rows[0].Size != 0 {
		t.Errorf("not normalised: %+v", st)
	}
}

func TestStatusRowsCappedNewestKept(t *testing.T) {
	rows := make([]FileRow, 620)
	for i := range rows {
		rows[i] = FileRow{Name: fmt.Sprintf("IMG_%04d.jpg", i), Size: 1, State: "verified"}
	}
	rows[0].State, rows[619].State = "failed", "receiving"
	c := startUI(t, Handlers{Snapshot: func() Snapshot { return Snapshot{Phase: "receiving", Rows: rows} }})
	var st statusJSON
	_, data := c.status()
	if err := json.Unmarshal([]byte(data), &st); err != nil {
		t.Fatal(err)
	}
	if len(st.Rows) != maxRowsSent || st.RowsHidden != 120 {
		t.Fatalf("rows %d hidden %d", len(st.Rows), st.RowsHidden)
	}
	if st.Rows[0].Name != "IMG_0120.jpg" || st.Rows[len(st.Rows)-1].Name != "IMG_0619.jpg" {
		t.Errorf("kept %s..%s, want the newest 500", st.Rows[0].Name, st.Rows[len(st.Rows)-1].Name)
	}
	if st.Counts != (countsJSON{Verified: 618, Failed: 1, Receiving: 1}) {
		t.Errorf("counts over all rows = %+v", st.Counts)
	}
}

func TestStatusSnapshotPanic(t *testing.T) {
	c := startUI(t, Handlers{Snapshot: func() Snapshot { panic("boom") }})
	resp, body := c.do(http.MethodGet, "status.json", "", nil)
	if resp.StatusCode != http.StatusInternalServerError || strings.Contains(body, "boom") {
		t.Errorf("panic: %d %q", resp.StatusCode, body)
	}
}

// ---- untrusted text ----

func TestMaliciousTextNeverUnescaped(t *testing.T) {
	const evil = `<img src=x onerror=alert(1)>`
	const evil2 = `"><script>alert(2)</script>`
	snap := Snapshot{
		Phase: "pairing", Folder: "/tmp/" + evil, FolderNote: evil, PhoneName: evil, Code: evil2,
		Phones:  []PhoneChoice{{Name: evil, Code: evil2, Addr: evil}},
		Current: evil, Rows: []FileRow{{Name: evil, State: "failed", Detail: evil2}},
		Error: evil2, Version: evil,
		ComputerName: evil, WaitingFor: evil2, PairedPhones: []PairedPhone{{Name: evil, LastSeen: evil2}},
	}
	box := &snapBox{s: snap}
	c := startUI(t, Handlers{
		Snapshot: box.get,
		Retry:    func() error { return errors.New(evil) },
	})
	_, page := c.do(http.MethodGet, "", "", nil)
	_, status := c.do(http.MethodGet, "status.json", "", nil)
	_, action := c.do(http.MethodPost, "retry", "{}", nil)
	for name, body := range map[string]string{"page": page, "status.json": status, "action reply": action} {
		for _, s := range []string{evil, evil2, "<img", "<script>alert"} {
			if strings.Contains(body, s) {
				t.Errorf("%s contains unescaped %q", name, s)
			}
		}
	}
	// The text itself survives (it is shown with textContent, so it renders as harmless text).
	var st statusJSON
	if err := json.Unmarshal([]byte(status), &st); err != nil {
		t.Fatal(err)
	}
	if st.PhoneName != evil { // 28 runes, under the 40-rune cap, so it must be intact
		t.Errorf("phoneName = %q", st.PhoneName)
	}
	if st.Rows[0].Name != evil || st.Error != evil2 || st.Phones[0].Name != evil || st.ComputerName != evil ||
		st.WaitingFor != evil2 || st.PairedPhones[0] != (pairedJSON{Name: evil, LastSeen: evil2}) {
		t.Errorf("text altered: %+v", st)
	}
	// Control and bidi characters are stripped and long names are capped.
	box.set(Snapshot{Phase: "pairing", PhoneName: "Pixel\u202egpj.exe\x1b[31m" + strings.Repeat("x", 100),
		ComputerName: "PC\u202e\x1b" + strings.Repeat("y", 100), WaitingFor: "S24\u2066" + strings.Repeat("z", 100)})
	st = statusJSON{}
	_, status = c.do(http.MethodGet, "status.json", "", nil)
	if err := json.Unmarshal([]byte(status), &st); err != nil {
		t.Fatal(err)
	}
	for name, v := range map[string]string{"phoneName": st.PhoneName, "computerName": st.ComputerName, "waitingFor": st.WaitingFor} {
		if strings.ContainsAny(v, "\u202e\u2066\x1b") || len([]rune(v)) > 40 {
			t.Errorf("%s not cleaned: %q", name, v)
		}
	}
}

// The page must never turn data into markup: no innerHTML and friends, no eval.
func TestPageScriptUsesTextOnly(t *testing.T) {
	for _, bad := range []string{
		"innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function",
		"setAttribute('on", `setAttribute("on`, "setAttribute('style", "setAttribute('href", "srcdoc",
		"createContextualFragment", "javascript:", "setTimeout('", `setTimeout("`,
	} {
		if bad == "javascript:" {
			// Only allowed inside the vetting function's list of banned strings.
			if strings.Count(pageJS, bad) != 1 {
				t.Errorf("page.js mentions %q outside the SVG ban list", bad)
			}
			continue
		}
		if strings.Contains(pageJS, bad) {
			t.Errorf("page.js uses %q", bad)
		}
	}
	if !strings.Contains(pageJS, "textContent") || !strings.Contains(pageJS, "vetSvg(") {
		t.Error("page.js does not render with textContent / vetSvg")
	}
}

// ---- QR SVG vetting ----

func TestVetSVGAcceptsOurQR(t *testing.T) {
	code, err := qr.Encode([]byte("gs2:AAAAAAAAAAAAAAAA:" + strings.Repeat("A", 43)))
	if err != nil {
		t.Fatal(err)
	}
	svg := code.SVG()
	if !vetSVG(svg) {
		t.Fatalf("vetSVG rejected our own QR SVG: %.200s", svg)
	}
	c := startUI(t, Handlers{Snapshot: func() Snapshot { return Snapshot{Phase: "pairing", QRSVG: svg} }})
	var st statusJSON
	_, data := c.status()
	if err := json.Unmarshal([]byte(data), &st); err != nil {
		t.Fatal(err)
	}
	if st.QRSVG != svg {
		t.Error("status.json dropped a valid QR SVG")
	}
}

func TestVetSVGRejectsActiveContent(t *testing.T) {
	const ns = `xmlns="http://www.w3.org/2000/svg"`
	bad := []string{
		``,
		`<svg ` + ns + `><script>alert(1)</script></svg>`,
		`<svg ` + ns + `><SCRIPT>alert(1)</SCRIPT></svg>`,
		`<svg ` + ns + ` onload="alert(1)"></svg>`,
		`<svg ` + ns + ` ONLOAD = "alert(1)"></svg>`,
		`<svg ` + ns + `><rect onclick="x"/></svg>`,
		`<svg ` + ns + `><a href="javascript:alert(1)"><rect/></a></svg>`,
		`<svg ` + ns + ` xmlns:xlink="http://www.w3.org/1999/xlink"><use xlink:href="#x"/></svg>`,
		`<svg ` + ns + `><foreignObject><div/></foreignObject></svg>`,
		`<svg ` + ns + `><style>*{}</style></svg>`,
		`<svg ` + ns + ` style="x"></svg>`,
		`<svg ` + ns + `><set attributeName="fill" to="red"/></svg>`,
		`<svg ` + ns + `><animate attributeName="x"/></svg>`,
		`<svg ` + ns + `><rect fill="url(#x)"/></svg>`,
		`<svg ` + ns + `><!-- hi --></svg>`,
		`<svg ` + ns + `><![CDATA[x]]></svg>`,
		`<svg ` + ns + `>text</svg>`,
		`<svg ` + ns + `><rect fill="&#106;"/></svg>`,
		`<svg ` + ns + `></svg><img src=x onerror=alert(1)>`,
		`<svg ` + ns + `></svg><svg ` + ns + `></svg>`,
		`<svg ` + ns + `><rect></svg>`,
		`<svg xmlns="http://www.w3.org/1999/xhtml"></svg>`,
		`<svgx ` + ns + `></svgx>`,
		` <svg ` + ns + `></svg>`,
		`<div><svg ` + ns + `></svg></div>`,
		`<?xml version="1.0"?><svg ` + ns + `></svg>`,
		`<!DOCTYPE svg><svg ` + ns + `></svg>`,
		"<svg " + ns + "><rect fill=\"\xff\"/></svg>",
		`<svg ` + ns + `><path d="M0 0" fill="#000"` + strings.Repeat(" ", maxSVGBytes) + `/></svg>`,
	}
	for _, s := range bad {
		if vetSVG(s) {
			t.Errorf("vetSVG accepted %.120q", s)
		}
	}
	good := `<svg ` + ns + ` viewBox="0 0 4 4" role="img" aria-label="QR code"><g><rect width="4" height="4" fill="#ffffff"/><path fill="#000000" d="M1 1h1v1h-1z"/></g></svg>`
	if !vetSVG(good) {
		t.Errorf("vetSVG rejected %q", good)
	}

	// A script-bearing SVG never reaches the page.
	evil := `<svg ` + ns + ` onload="alert(1)"><script>alert(1)</script></svg>`
	c := startUI(t, Handlers{Snapshot: func() Snapshot { return Snapshot{Phase: "pairing", QRSVG: evil} }})
	m, data := c.status()
	if m["qrSvg"] != "" || strings.Contains(data, "alert") {
		t.Errorf("status.json passed a bad SVG: %s", data)
	}
}

// ---- actions ----

func TestChooseFolder(t *testing.T) {
	var reply struct {
		path string
		err  error
	}
	var mu sync.Mutex
	c := startUI(t, Handlers{ChooseFolder: func() (string, error) {
		mu.Lock()
		defer mu.Unlock()
		return reply.path, reply.err
	}})
	set := func(p string, err error) { mu.Lock(); reply.path, reply.err = p, err; mu.Unlock() }

	set("/home/a/Photos", nil)
	code, res := c.post("choose-folder", "")
	if code != 200 || !res.OK || res.Path == nil || *res.Path != "/home/a/Photos" {
		t.Errorf("chosen: %d %+v", code, res)
	}
	set("", nil)
	_, data := c.do(http.MethodPost, "choose-folder", "{}", nil)
	if data != `{"ok":true,"path":""}` {
		t.Errorf("cancelled: %s", data)
	}
	set("", fmt.Errorf("folder dialog: %w", errors.New("not supported on this system")))
	_, res = c.post("choose-folder", "{}")
	if res.OK || !res.NeedPath || res.Error != msgNoPicker {
		t.Errorf("unsupported: %+v", res)
	}
	set("", errors.New("The folder can't be written to."))
	_, res = c.post("choose-folder", "{}")
	if res.OK || res.NeedPath || res.Error != "The folder can't be written to." {
		t.Errorf("error: %+v", res)
	}
}

func TestSetFolder(t *testing.T) {
	var got atomic.Value
	var fail atomic.Bool
	suggested := absPath(t, "GallerySlim\u202e Photos") // a bidi control the page can't show
	c := startUI(t, Handlers{
		Snapshot: func() Snapshot { return Snapshot{Phase: "choose-folder", Folder: suggested} },
		SetFolder: func(p string) error {
			got.Store(p)
			if fail.Load() {
				return errors.New("That folder is on a disk that is full.")
			}
			return nil
		},
	})
	m, _ := c.status()
	shown := m["folder"].(string)
	if strings.ContainsRune(shown, '\u202e') {
		t.Fatalf("folder shown with a bidi control: %q", shown)
	}
	body, _ := json.Marshal(map[string]string{"path": shown})
	if _, res := c.post("set-folder", string(body)); !res.OK || got.Load() != suggested {
		t.Errorf("suggested folder: %+v, SetFolder got %q", res, got.Load())
	}

	typed := absPath(t, "My Photos")
	body, _ = json.Marshal(map[string]string{"path": `  "` + typed + string(filepath.Separator) + `"  `})
	if _, res := c.post("set-folder", string(body)); !res.OK || got.Load() != typed {
		t.Errorf("typed path: %+v, SetFolder got %q want %q", res, got.Load(), typed)
	}

	got.Store("")
	for _, p := range []string{"", "   ", "Photos", "relative/dir", typed + "\x00x", typed + "\u202e", strings.Repeat("/a", 3000)} {
		body, _ := json.Marshal(map[string]string{"path": p})
		if _, res := c.post("set-folder", string(body)); res.OK || res.Error == "" {
			t.Errorf("path %.40q accepted: %+v", p, res)
		}
	}
	if got.Load() != "" {
		t.Errorf("SetFolder ran with invalid path %q", got.Load())
	}

	fail.Store(true)
	body, _ = json.Marshal(map[string]string{"path": typed})
	if _, res := c.post("set-folder", string(body)); res.OK || res.Error != "That folder is on a disk that is full." {
		t.Errorf("SetFolder error not shown: %+v", res)
	}
}

func TestEnterAddress(t *testing.T) {
	var got atomic.Value
	c := startUI(t, Handlers{
		Snapshot: func() Snapshot {
			return Snapshot{Phase: "finding", Phones: []PhoneChoice{{Name: "Galaxy S24", Code: "K7-42", Addr: "192.168.1.40:8766"}}}
		},
		EnterAddress: func(a string) error { got.Store(a); return nil },
	})
	ok := map[string]string{
		"192.168.1.23:8765":           "192.168.1.23:8765",
		" http://192.168.1.23:8765/ ": "192.168.1.23:8765",
		"HTTPS://10.0.0.2:8767/x?y":   "10.0.0.2:8767",
		"192.168.1.23":                "192.168.1.23",
		"[fe80::1%wlan0]:8765":        "[fe80::1%wlan0]:8765",
		"192.168.1.40:8766":           "192.168.1.40:8766", // a phone button
	}
	for in, want := range ok {
		body, _ := json.Marshal(map[string]string{"address": in})
		if _, res := c.post("address", string(body)); !res.OK || got.Load() != want {
			t.Errorf("address %q: %+v, EnterAddress got %q want %q", in, res, got.Load(), want)
		}
	}
	got.Store("")
	for _, in := range []string{"", "http://", "<script>", "192.168.1.1 8765", "a\u0000b", "१९२.१६८.१.१", strings.Repeat("1", 101)} {
		body, _ := json.Marshal(map[string]string{"address": in})
		if _, res := c.post("address", string(body)); res.OK || res.Error == "" {
			t.Errorf("address %q accepted: %+v", in, res)
		}
	}
	if got.Load() != "" {
		t.Errorf("EnterAddress ran with %q", got.Load())
	}
}

func TestHandlerErrorsPanicsAndMissing(t *testing.T) {
	c := startUI(t, Handlers{
		Retry:      func() error { return errors.New("No phone answered.\x1b[2J\u202e") },
		OpenFolder: func() error { panic("secret internal detail") },
	})
	if _, res := c.post("retry", "{}"); res.OK || res.Error != "No phone answered.[2J" {
		t.Errorf("retry error: %+v", res)
	}
	_, data := c.do(http.MethodPost, "open-folder", "{}", nil)
	if strings.Contains(data, "secret") || !strings.Contains(data, `"ok":false`) {
		t.Errorf("panic leaked: %s", data)
	}
	for _, a := range []string{"choose-folder", "set-folder", "address", "quit",
		"rename", "forget", "start-at-login", "install", "change-folder", "pick-send", "drop-send"} {
		if _, res := c.post(a, `{}`); res.OK || res.Error == "" {
			t.Errorf("%s with nil handler: %+v", a, res)
		}
	}
	// After a panic the action lock is free again.
	if _, res := c.post("retry", "{}"); res.Error == msgBusy || res.Error == msgDialogOpen {
		t.Errorf("lock not released after panic: %+v", res)
	}
}

func TestSlowHandlerDoesNotBlock(t *testing.T) {
	release := make(chan struct{})
	entered := make(chan struct{})
	var retries atomic.Int32
	c := startUI(t, Handlers{
		ChooseFolder: func() (string, error) {
			close(entered)
			<-release
			return "/picked", nil
		},
		Retry: func() error { retries.Add(1); return nil },
	})
	first := make(chan actionResult, 1)
	go func() {
		_, res := c.post("choose-folder", "{}")
		first <- res
	}()
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("ChooseFolder not called")
	}
	start := time.Now()
	c.status()
	if d := time.Since(start); d > 2*time.Second {
		t.Errorf("status.json took %v while the dialog was open", d)
	}
	if _, res := c.post("choose-folder", "{}"); res.OK || res.Error != msgDialogOpen {
		t.Errorf("second dialog: %+v", res)
	}
	if _, res := c.post("retry", "{}"); res.OK || res.Error != msgDialogOpen || retries.Load() != 0 {
		t.Errorf("retry during dialog: %+v", res)
	}
	close(release)
	select {
	case res := <-first:
		if !res.OK || res.Path == nil || *res.Path != "/picked" {
			t.Errorf("first: %+v", res)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("choose-folder never answered")
	}
	if _, res := c.post("retry", "{}"); !res.OK || retries.Load() != 1 {
		t.Errorf("retry after dialog: %+v", res)
	}
}

func TestStopWhileDialogOpen(t *testing.T) {
	release := make(chan struct{})
	entered := make(chan struct{})
	c := startUI(t, Handlers{ChooseFolder: func() (string, error) {
		close(entered)
		<-release
		return "", nil
	}})
	defer close(release)
	go func() {
		req, _ := http.NewRequest(http.MethodPost, c.base+"choose-folder", strings.NewReader("{}"))
		req.Header.Set("Content-Type", "application/json")
		if resp, err := c.http.Do(req); err == nil {
			resp.Body.Close()
		}
	}()
	<-entered
	done := make(chan struct{})
	go func() { c.stop(); close(done) }()
	select {
	case <-done:
	case <-time.After(shutdownGrace + 3*time.Second):
		t.Fatal("stop hung while a handler was blocked")
	}
	ln, err := net.Listen("tcp4", fmt.Sprintf("127.0.0.1:%d", c.port))
	if err != nil {
		t.Fatalf("port still busy: %v", err)
	}
	ln.Close()
}

func TestQuitCallsStopWithoutDeadlock(t *testing.T) {
	var quits atomic.Int32
	var stopFn atomic.Value // func(), set once Start has returned
	stopped := make(chan struct{})
	cl := startUI(t, Handlers{Quit: func() {
		quits.Add(1)
		stopFn.Load().(func())()
		close(stopped)
	}})
	stopFn.Store(cl.stop)
	if _, res := cl.post("quit", "{}"); !res.OK {
		t.Fatalf("quit: %+v", res)
	}
	// A second click before shutdown must not call Quit again.
	req, _ := http.NewRequest(http.MethodPost, cl.base+"quit", strings.NewReader("{}"))
	req.Header.Set("Content-Type", "application/json")
	if resp, err := cl.http.Do(req); err == nil {
		resp.Body.Close()
	}
	select {
	case <-stopped:
	case <-time.After(shutdownGrace + 3*time.Second):
		t.Fatal("Quit -> stop deadlocked")
	}
	if n := quits.Load(); n != 1 {
		t.Errorf("Quit ran %d times", n)
	}
	ln, err := net.Listen("tcp4", fmt.Sprintf("127.0.0.1:%d", cl.port))
	if err != nil {
		t.Fatalf("port still busy after Quit: %v", err)
	}
	ln.Close()
}

func TestDisplayPath(t *testing.T) {
	cases := map[string]string{
		`D:\Photos`:                         `D:\Photos`,
		"/home/a/फ़ोटो  two spaces":         "/home/a/फ़ोटो  two spaces",
		"/x/\u202egpj.exe":                  "/x/gpj.exe",
		"/x/\x07bell\n":                     "/x/bell",
		"/bad/\xff":                         "/bad/\uFFFD",
		"C:\\Users\\Kartik\\OneDrive\\Pics": "C:\\Users\\Kartik\\OneDrive\\Pics",
	}
	for in, want := range cases {
		if got := displayPath(in); got != want {
			t.Errorf("displayPath(%q) = %q, want %q", in, got, want)
		}
	}
}
