// Package ui serves the receiver's local web page on 127.0.0.1.
//
// The page lives under a random token path on a random port, checks the Host header (DNS
// rebinding) and the Origin of every POST, sends no CORS headers and a strict Content
// Security Policy. All text from a Snapshot is untrusted: the page renders it with
// textContent only. The one exception is the QR code SVG, which is vetted on both the
// server (vetSVG) and in the page before it is inserted.
package ui

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unicode"
	"unicode/utf8"

	"galleryslim/receiver/internal/textsafe"
)

// FileRow is one file in the transfer list.
type FileRow struct {
	Name string
	Size int64
	// waiting | receiving | saved | verified | skipped | failed, and for Direction "to-phone"
	// also uploading | sent | not-found
	State  string
	Detail string
	// Thumb reports that Handlers.Thumb can give a small picture of this row's saved file.
	Thumb bool
}

// PhoneChoice is one phone found on the network, offered as a button while finding.
type PhoneChoice struct {
	Name, Code, Addr string
}

// PairedPhone is a phone this computer remembers. LastSeen is either text to show as is
// ("Yesterday") or an RFC 3339 time, which the page formats in the viewer's locale.
type PairedPhone struct {
	Name, LastSeen string
}

// Snapshot is what the page shows. Every string in it is treated as untrusted text.
type Snapshot struct {
	Phase              string // choose-folder | finding | pairing | receiving | done | error
	Folder, FolderNote string
	PhoneName, Code    string
	QRSVG              string // a complete <svg> element produced by our own QR encoder
	Phones             []PhoneChoice
	FilesDone          int
	FilesTotal         int
	BytesDone          int64
	BytesTotal         int64
	Current            string
	Rows               []FileRow // oldest first, newest last
	Error, Version     string

	// Optional fields; the zero value hides the related part of the page.
	ComputerName string        // this computer's friendly name, shown in the header
	PairedPhones []PairedPhone // remembered phones, listed in Settings with Forget
	WaitingFor   string        // name of a remembered phone the receiver is waiting for
	StartAtLogin bool          // the Start with this computer switch
	Installed    bool          // hides "Install on this computer"
	EtaSeconds   int           // seconds left while receiving; 0 lets the page estimate

	// Bring back to phone (gs2 §10).
	Direction      string // "to-computer" (also "") or "to-phone"; words the receiving phase
	PhoneReceiving bool   // an approved phone session accepts uploads: shows Send and the drop zone
}

// Handlers are the main program's callbacks. Snapshot is required; a nil action handler makes
// that button answer "not available", and the page hides the controls of nil handlers.
// Snapshot and Thumb may be called from several goroutines at once. The other handlers
// (except Quit) are never called concurrently with each other.
type Handlers struct {
	Snapshot     func() Snapshot
	ChooseFolder func() (string, error) // opens the OS folder dialog and saves the choice; "" if cancelled
	SetFolder    func(path string) error
	EnterAddress func(addr string) error
	OpenFolder   func() error
	Retry        func() error
	Quit         func()

	// Optional (nil hides the related control).
	Rename          func(name string) error      // new computer name, already trimmed and checked
	Forget          func(phoneName string) error // a PairedPhone.Name exactly as in the Snapshot
	SetStartAtLogin func(on bool) error
	Install         func() error
	ChangeFolder    func() (string, error) // like ChooseFolder, from Settings; "" if cancelled
	// PickAndSend opens the native file picker and sends the chosen photos and videos to
	// the phone. SendFiles sends files dropped on the page; the paths are temporary copies
	// owned by this package (see drop.go for when they are deleted), named after the
	// dropped file's cleaned name. Both are called only while Snapshot.PhoneReceiving.
	PickAndSend func() error
	SendFiles   func(paths []string) error
	// Thumb returns a small JPEG or PNG of Rows[index]'s saved file (index into the full
	// Snapshot.Rows), with its MIME type. ThumbFromFile makes one. ok=false shows a plain tile.
	Thumb func(index int) (data []byte, mime string, ok bool)
}

const (
	maxBodyBytes   = 16 << 10
	maxRowsSent    = 500
	maxPhonesSent  = 10
	maxPathBytes   = 4096
	maxAddressLen  = 100
	maxPairedSent  = 20
	maxNameRunes   = 40
	maxEtaSeconds  = 7 * 24 * 3600
	shutdownGrace  = 2 * time.Second
	tokenByteCount = 16 // 32 hex characters
)

var phases = map[string]bool{
	"choose-folder": true, "finding": true, "pairing": true,
	"receiving": true, "done": true, "error": true,
}

var rowStates = map[string]bool{
	"waiting": true, "receiving": true, "saved": true,
	"verified": true, "skipped": true, "failed": true,
	"uploading": true, "sent": true, "not-found": true,
}

// Start serves the page on 127.0.0.1 with a random port and returns its URL
// (http://127.0.0.1:<port>/<token>/) and a stop function that closes the listener. stop is
// safe to call more than once and from any goroutine, including from Handlers.Quit.
func Start(h Handlers) (url string, stop func(), err error) {
	if h.Snapshot == nil {
		return "", nil, errors.New("ui: Handlers.Snapshot is required")
	}
	page, err := buildPage()
	if err != nil {
		return "", nil, err
	}
	token, err := newToken()
	if err != nil {
		return "", nil, fmt.Errorf("ui: make token: %w", err)
	}
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return "", nil, fmt.Errorf("ui: listen: %w", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	s := newServer(h, token, port, page)

	srv := &http.Server{
		Handler:           s,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    32 << 10,
		// No WriteTimeout: the folder dialog can keep a request open for minutes.
	}
	served := make(chan struct{})
	go func() {
		defer close(served)
		_ = srv.Serve(ln)
	}()
	var once sync.Once
	stop = func() {
		once.Do(func() {
			ctx, cancel := context.WithTimeout(context.Background(), shutdownGrace)
			defer cancel()
			if srv.Shutdown(ctx) != nil {
				_ = srv.Close()
			}
			<-served
			s.removeAllDrops()
		})
	}
	return fmt.Sprintf("http://127.0.0.1:%d/%s/", port, token), stop, nil
}

func newToken() (string, error) {
	b := make([]byte, tokenByteCount)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

type server struct {
	h       Handlers
	token   []byte
	hosts   [2]string
	origins [2]string
	page    pageAsset

	actionMu sync.Mutex
	busyWith atomic.Value // string: the action holding actionMu
	quitOnce sync.Once

	svgMu     sync.Mutex
	svgLast   string
	svgLastOK bool

	thumbSlots chan struct{} // bounds concurrent Thumb calls

	drops dropStore
}

func newServer(h Handlers, token string, port int, page pageAsset) *server {
	hp := fmt.Sprintf("%d", port)
	s := &server{
		h:       h,
		token:   []byte(token),
		hosts:   [2]string{"127.0.0.1:" + hp, "localhost:" + hp},
		origins: [2]string{"http://127.0.0.1:" + hp, "http://localhost:" + hp},
		page:    page,

		thumbSlots: make(chan struct{}, maxThumbCalls),
	}
	s.busyWith.Store("")
	return s
}

func (s *server) securityHeaders(w http.ResponseWriter) {
	hd := w.Header()
	hd.Set("X-Content-Type-Options", "nosniff")
	hd.Set("Content-Security-Policy", s.page.csp)
	hd.Set("Referrer-Policy", "no-referrer")
	hd.Set("Cache-Control", "no-store")
	hd.Set("X-Frame-Options", "DENY")
	hd.Set("Cross-Origin-Opener-Policy", "same-origin")
	hd.Set("Cross-Origin-Resource-Policy", "same-origin")
}

func (s *server) hostOK(host string) bool {
	host = strings.ToLower(host)
	return host == s.hosts[0] || host == s.hosts[1]
}

// route returns the part of the path after "/<token>/", or ok=false.
func (s *server) route(path string) (rest string, ok bool) {
	if !strings.HasPrefix(path, "/") {
		return "", false
	}
	seg, rest, found := strings.Cut(path[1:], "/")
	if !found || subtle.ConstantTimeCompare([]byte(seg), s.token) != 1 {
		return "", false
	}
	return rest, true
}

func (s *server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	s.securityHeaders(w)
	if !s.hostOK(r.Host) {
		http.Error(w, "Forbidden", http.StatusForbidden)
		return
	}
	rest, ok := s.route(r.URL.Path)
	if !ok {
		http.NotFound(w, r)
		return
	}
	if idx, isThumb := strings.CutPrefix(rest, "thumb/"); isThumb {
		s.serveThumb(w, r, idx)
		return
	}
	if rest == "drop" {
		s.serveDrop(w, r)
		return
	}
	switch rest {
	case "":
		if !allowMethods(w, r, http.MethodGet, http.MethodHead) {
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(s.page.body)
	case "status.json":
		if !allowMethods(w, r, http.MethodGet, http.MethodHead) {
			return
		}
		s.serveStatus(w)
	case "choose-folder", "set-folder", "address", "open-folder", "retry", "quit",
		"rename", "forget", "start-at-login", "install", "change-folder", "pick-send", "drop-send":
		if !allowMethods(w, r, http.MethodPost) {
			return
		}
		if !s.postOK(w, r, "application/json") {
			return
		}
		var body actionBody
		if status, err := readBody(w, r, &body); err != nil {
			writeJSON(w, status, actionResult{Error: err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, s.action(rest, body))
	default:
		http.NotFound(w, r)
	}
}

func allowMethods(w http.ResponseWriter, r *http.Request, methods ...string) bool {
	for _, m := range methods {
		if r.Method == m {
			return true
		}
	}
	w.Header().Set("Allow", strings.Join(methods, ", "))
	http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
	return false
}

// postOK applies the cross-site checks for state-changing requests. mediaType is the one
// Content-Type accepted (application/json, or application/octet-stream for drop).
func (s *server) postOK(w http.ResponseWriter, r *http.Request, mediaType string) bool {
	if origins, present := r.Header["Origin"]; present {
		if len(origins) != 1 || (origins[0] != s.origins[0] && origins[0] != s.origins[1]) {
			http.Error(w, "Forbidden", http.StatusForbidden)
			return false
		}
	}
	if site := r.Header.Get("Sec-Fetch-Site"); site != "" && site != "same-origin" {
		http.Error(w, "Forbidden", http.StatusForbidden)
		return false
	}
	// A cross-site HTML form cannot send application/json (or octet-stream) without a CORS
	// preflight, which this server never approves.
	mt, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || mt != mediaType {
		http.Error(w, "Unsupported Media Type", http.StatusUnsupportedMediaType)
		return false
	}
	return true
}

type actionBody struct {
	Path    string   `json:"path"`
	Address string   `json:"address"`
	Name    string   `json:"name"`  // rename
	Phone   string   `json:"phone"` // forget
	On      *bool    `json:"on"`    // start-at-login
	IDs     []string `json:"ids"`   // drop-send
}

type actionResult struct {
	OK       bool    `json:"ok"`
	Error    string  `json:"error,omitempty"`
	Path     *string `json:"path,omitempty"`
	NeedPath bool    `json:"needPath,omitempty"`
	ID       string  `json:"id,omitempty"` // drop: the uploaded file's id for drop-send
}

var errBadRequest = errors.New("bad request")

// readBody decodes an optional JSON object of at most maxBodyBytes into v.
func readBody(w http.ResponseWriter, r *http.Request, v any) (int, error) {
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxBodyBytes))
	if err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			return http.StatusRequestEntityTooLarge, errors.New("request too large")
		}
		return http.StatusBadRequest, errBadRequest
	}
	if len(strings.TrimSpace(string(data))) == 0 {
		return 0, nil
	}
	dec := json.NewDecoder(strings.NewReader(string(data)))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		return http.StatusBadRequest, errBadRequest
	}
	if _, err := dec.Token(); err != io.EOF {
		return http.StatusBadRequest, errBadRequest
	}
	return 0, nil
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	data, err := json.Marshal(v)
	if err != nil {
		status, data = http.StatusInternalServerError, []byte(`{"ok":false,"error":"internal error"}`)
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_, _ = w.Write(data)
}

const (
	msgNotAvailable = "This isn't available right now."
	msgFailed       = "That didn't work. Please try again."
	msgPanic        = "Something went wrong inside the receiver. Please try again."
	msgBusy         = "Please wait a moment, the receiver is still working on your last request."
	msgDialogOpen   = "The folder window is already open. It may be hidden behind this browser window."
	msgNoPicker     = "This computer can't show a folder picker; type the folder path."
)

func (s *server) action(name string, body actionBody) actionResult {
	if name == "quit" {
		return s.quit()
	}
	if !s.actionMu.TryLock() {
		switch s.busyWith.Load() {
		case "choose-folder", "change-folder":
			return actionResult{Error: msgDialogOpen}
		case "pick-send":
			return actionResult{Error: msgPickerOpen}
		}
		return actionResult{Error: msgBusy}
	}
	s.busyWith.Store(name)
	defer func() {
		s.busyWith.Store("")
		s.actionMu.Unlock()
	}()

	switch name {
	case "choose-folder", "change-folder":
		choose := s.h.ChooseFolder
		if name == "change-folder" {
			choose = s.h.ChangeFolder
		}
		if choose == nil {
			return actionResult{Error: msgNotAvailable}
		}
		var path string
		err := safeCall(func() error {
			var err error
			path, err = choose()
			return err
		})
		if err != nil {
			if !errors.Is(err, errPanicked) && strings.Contains(strings.ToLower(err.Error()), "not supported") {
				return actionResult{Error: msgNoPicker, NeedPath: true}
			}
			return actionResult{Error: friendlyError(err)}
		}
		shown := displayPath(path)
		return actionResult{OK: true, Path: &shown}
	case "set-folder":
		if s.h.SetFolder == nil {
			return actionResult{Error: msgNotAvailable}
		}
		path, err := s.folderFromRequest(body.Path)
		if err != nil {
			return actionResult{Error: err.Error()}
		}
		return result(safeCall(func() error { return s.h.SetFolder(path) }))
	case "address":
		if s.h.EnterAddress == nil {
			return actionResult{Error: msgNotAvailable}
		}
		addr, err := s.addressFromRequest(body.Address)
		if err != nil {
			return actionResult{Error: err.Error()}
		}
		return result(safeCall(func() error { return s.h.EnterAddress(addr) }))
	case "open-folder":
		if s.h.OpenFolder == nil {
			return actionResult{Error: msgNotAvailable}
		}
		return result(safeCall(s.h.OpenFolder))
	case "retry":
		if s.h.Retry == nil {
			return actionResult{Error: msgNotAvailable}
		}
		return result(safeCall(s.h.Retry))
	case "rename":
		if s.h.Rename == nil {
			return actionResult{Error: msgNotAvailable}
		}
		n, err := computerNameFromRequest(body.Name)
		if err != nil {
			return actionResult{Error: err.Error()}
		}
		return result(safeCall(func() error { return s.h.Rename(n) }))
	case "forget":
		if s.h.Forget == nil {
			return actionResult{Error: msgNotAvailable}
		}
		phone, ok := s.pairedFromRequest(body.Phone)
		if !ok {
			return actionResult{Error: "That phone isn't in the list any more."}
		}
		return result(safeCall(func() error { return s.h.Forget(phone) }))
	case "start-at-login":
		if s.h.SetStartAtLogin == nil {
			return actionResult{Error: msgNotAvailable}
		}
		if body.On == nil {
			return actionResult{Error: msgFailed}
		}
		on := *body.On
		return result(safeCall(func() error { return s.h.SetStartAtLogin(on) }))
	case "install":
		if s.h.Install == nil {
			return actionResult{Error: msgNotAvailable}
		}
		return result(safeCall(s.h.Install))
	case "pick-send":
		if s.h.PickAndSend == nil {
			return actionResult{Error: msgNotAvailable}
		}
		if snap, ok := s.snapshot(); !ok || !snap.PhoneReceiving {
			return actionResult{Error: msgPhoneNotReady}
		}
		return result(safeCall(s.h.PickAndSend))
	case "drop-send":
		return s.dropSend(body.IDs)
	}
	return actionResult{Error: msgNotAvailable}
}

// quit answers first and then calls Handlers.Quit on its own goroutine, so a Quit that calls
// stop() (which waits for requests to finish) cannot deadlock on this request.
func (s *server) quit() actionResult {
	if s.h.Quit == nil {
		return actionResult{Error: msgNotAvailable}
	}
	s.quitOnce.Do(func() {
		go func() {
			// Give the response a moment to reach the browser before the program exits.
			time.Sleep(150 * time.Millisecond)
			_ = safeCall(func() error { s.h.Quit(); return nil })
		}()
	})
	return actionResult{OK: true}
}

func result(err error) actionResult {
	if err != nil {
		return actionResult{Error: friendlyError(err)}
	}
	return actionResult{OK: true}
}

var errPanicked = errors.New(msgPanic)

func safeCall(fn func() error) (err error) {
	defer func() {
		if recover() != nil {
			err = errPanicked
		}
	}()
	return fn()
}

func friendlyError(err error) string {
	if errors.Is(err, errPanicked) {
		return msgPanic
	}
	if msg := textsafe.Clean(err.Error(), 300); msg != "" {
		return msg
	}
	return msgFailed
}

// folderFromRequest validates a folder path typed or shown on the page. If it is the
// suggested folder as displayed, the exact original string from the Snapshot is used, so a
// path that displayPath had to alter still round-trips.
func (s *server) folderFromRequest(p string) (string, error) {
	if snap, ok := s.snapshot(); ok && snap.Folder != "" && p == displayPath(snap.Folder) {
		return snap.Folder, nil
	}
	p = strings.TrimSpace(p)
	if len(p) >= 2 && p[0] == '"' && p[len(p)-1] == '"' { // Windows "Copy as path" adds quotes
		p = strings.TrimSpace(p[1 : len(p)-1])
	}
	// "~" and "~/…" mean the home folder (people type it; the page used to refuse it).
	if p == "~" || strings.HasPrefix(p, "~/") || strings.HasPrefix(p, `~\`) {
		if home, err := os.UserHomeDir(); err == nil && home != "" {
			p = filepath.Join(home, p[1:])
		}
	}
	switch {
	case p == "":
		return "", errors.New("Please choose a folder, or type its full path.")
	case len(p) > maxPathBytes:
		return "", errors.New("That folder path is too long.")
	case !utf8.ValidString(p) || strings.ContainsFunc(p, badPathRune):
		return "", errors.New("That folder path has characters that can't be used.")
	case !filepath.IsAbs(p):
		return "", fmt.Errorf("Please type the full folder path, for example %s", examplePath())
	}
	return filepath.Clean(p), nil
}

func badPathRune(r rune) bool {
	return unicode.IsControl(r) || textsafe.IsBidiControl(r) || r == 0x2028 || r == 0x2029 || r == 0xFEFF
}

func examplePath() string {
	switch runtime.GOOS {
	case "windows":
		return `D:\Photos`
	case "darwin":
		return "/Users/you/Pictures/Photos"
	default:
		return "/home/you/Pictures/Photos"
	}
}

// addressFromRequest accepts a phone address such as 192.168.1.23:8765, also when pasted
// as http://192.168.1.23:8765/. A phone offered in Snapshot.Phones is passed through as is.
func (s *server) addressFromRequest(a string) (string, error) {
	if snap, ok := s.snapshot(); ok {
		for i, p := range snap.Phones {
			if i >= maxPhonesSent {
				break
			}
			if p.Addr != "" && a == textsafe.Clean(p.Addr, maxAddressLen) {
				return p.Addr, nil
			}
		}
	}
	a = strings.TrimSpace(a)
	lower := strings.ToLower(a)
	for _, scheme := range []string{"http://", "https://"} {
		if strings.HasPrefix(lower, scheme) {
			a = a[len(scheme):]
			break
		}
	}
	if i := strings.IndexByte(a, '/'); i >= 0 {
		a = a[:i]
	}
	switch {
	case a == "":
		return "", errors.New("Please type the address shown on the phone.")
	case len(a) > maxAddressLen:
		return "", errors.New("That address is too long.")
	case strings.ContainsFunc(a, func(r rune) bool { return !addressRune(r) }):
		return "", errors.New("That doesn't look like the phone's address. It looks like 192.168.1.23:8765.")
	}
	return a, nil
}

// computerNameFromRequest checks a new computer name: 1 to maxNameRunes characters after
// trimming, no control, bidi, format or private-use characters.
func computerNameFromRequest(n string) (string, error) {
	n = strings.TrimSpace(n)
	switch {
	case n == "":
		return "", errors.New("Please type a name for this computer.")
	case !utf8.ValidString(n) || strings.ContainsFunc(n, badNameRune):
		return "", errors.New("That name has characters that can't be used.")
	case utf8.RuneCountInString(n) > maxNameRunes:
		return "", fmt.Errorf("Please use a shorter name (at most %d letters).", maxNameRunes)
	}
	return n, nil
}

func badNameRune(r rune) bool {
	return badPathRune(r) || r == utf8.RuneError || unicode.In(r, unicode.Cf, unicode.Co, unicode.Cs) ||
		(unicode.IsSpace(r) && r != ' ')
}

// pairedFromRequest maps a phone name shown on the page back to the exact PairedPhone.Name.
func (s *server) pairedFromRequest(shown string) (string, bool) {
	snap, ok := s.snapshot()
	if !ok || shown == "" {
		return "", false
	}
	for i, p := range snap.PairedPhones {
		if i >= maxPairedSent {
			break
		}
		if p.Name != "" && shown == textsafe.Clean(p.Name, maxNameRunes) {
			return p.Name, true
		}
	}
	return "", false
}

func addressRune(r rune) bool {
	return r < utf8.RuneSelf && (r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' ||
		strings.ContainsRune(".:-[]%_", r))
}

// snapshot calls Handlers.Snapshot, turning a panic into ok=false.
func (s *server) snapshot() (snap Snapshot, ok bool) {
	defer func() {
		if recover() != nil {
			snap, ok = Snapshot{}, false
		}
	}()
	return s.h.Snapshot(), true
}

func (s *server) serveStatus(w http.ResponseWriter) {
	snap, ok := s.snapshot()
	if !ok {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "unavailable"})
		return
	}
	s.noteDrops(snap)
	writeJSON(w, http.StatusOK, s.status(snap))
}

type rowJSON struct {
	Index  int    `json:"index"` // position in the full Snapshot.Rows, for thumb/<index>
	Name   string `json:"name"`
	Size   int64  `json:"size"`
	State  string `json:"state"`
	Detail string `json:"detail"`
	Thumb  bool   `json:"thumb"`
}

type pairedJSON struct {
	Name     string `json:"name"`
	LastSeen string `json:"lastSeen"`
}

// featuresJSON tells the page which optional controls have a handler behind them.
type featuresJSON struct {
	ChooseFolder bool `json:"chooseFolder"`
	SetFolder    bool `json:"setFolder"`
	Address      bool `json:"address"`
	OpenFolder   bool `json:"openFolder"`
	Retry        bool `json:"retry"`
	Quit         bool `json:"quit"`
	Rename       bool `json:"rename"`
	Forget       bool `json:"forget"`
	StartAtLogin bool `json:"startAtLogin"`
	Install      bool `json:"install"`
	ChangeFolder bool `json:"changeFolder"`
	Thumbs       bool `json:"thumbs"`
	PickAndSend  bool `json:"pickAndSend"`
	SendFiles    bool `json:"sendFiles"`
}

type phoneJSON struct {
	Name string `json:"name"`
	Code string `json:"code"`
	Addr string `json:"addr"`
}

type countsJSON struct {
	Waiting   int `json:"waiting"`
	Receiving int `json:"receiving"`
	Saved     int `json:"saved"`
	Verified  int `json:"verified"`
	Skipped   int `json:"skipped"`
	Failed    int `json:"failed"`
	Uploading int `json:"uploading"`
	Sent      int `json:"sent"`
	NotFound  int `json:"notFound"`
}

type statusJSON struct {
	Phase      string      `json:"phase"`
	Folder     string      `json:"folder"`
	FolderNote string      `json:"folderNote"`
	PhoneName  string      `json:"phoneName"`
	Code       string      `json:"code"`
	QRSVG      string      `json:"qrSvg"`
	Phones     []phoneJSON `json:"phones"`
	FilesDone  int         `json:"filesDone"`
	FilesTotal int         `json:"filesTotal"`
	BytesDone  int64       `json:"bytesDone"`
	BytesTotal int64       `json:"bytesTotal"`
	Current    string      `json:"current"`
	Rows       []rowJSON   `json:"rows"`
	RowsHidden int         `json:"rowsHidden"`
	Counts     countsJSON  `json:"counts"`
	Error      string      `json:"error"`
	Version    string      `json:"version"`

	ComputerName string       `json:"computerName"`
	PairedPhones []pairedJSON `json:"pairedPhones"`
	WaitingFor   string       `json:"waitingFor"`
	StartAtLogin bool         `json:"startAtLogin"`
	Installed    bool         `json:"installed"`
	EtaSeconds   int          `json:"etaSeconds"`
	Features     featuresJSON `json:"features"`

	Direction      string `json:"direction"`
	PhoneReceiving bool   `json:"phoneReceiving"`
}

func (s *server) status(snap Snapshot) statusJSON {
	st := statusJSON{
		Folder:     displayPath(snap.Folder),
		FolderNote: textsafe.Clean(snap.FolderNote, 400),
		PhoneName:  textsafe.Clean(snap.PhoneName, 40),
		Code:       textsafe.Clean(snap.Code, 12),
		Phones:     []phoneJSON{},
		FilesDone:  max(snap.FilesDone, 0),
		FilesTotal: max(snap.FilesTotal, 0),
		BytesDone:  max(snap.BytesDone, 0),
		BytesTotal: max(snap.BytesTotal, 0),
		Current:    textsafe.Clean(snap.Current, 200),
		Rows:       []rowJSON{},
		Error:      textsafe.Clean(snap.Error, 400),
		Version:    textsafe.Clean(snap.Version, 40),

		ComputerName:   textsafe.Clean(snap.ComputerName, maxNameRunes),
		PairedPhones:   []pairedJSON{},
		WaitingFor:     textsafe.Clean(snap.WaitingFor, maxNameRunes),
		StartAtLogin:   snap.StartAtLogin,
		Installed:      snap.Installed,
		EtaSeconds:     min(max(snap.EtaSeconds, 0), maxEtaSeconds),
		Direction:      "to-computer",
		PhoneReceiving: snap.PhoneReceiving,
		Features: featuresJSON{
			ChooseFolder: s.h.ChooseFolder != nil,
			SetFolder:    s.h.SetFolder != nil,
			Address:      s.h.EnterAddress != nil,
			OpenFolder:   s.h.OpenFolder != nil,
			Retry:        s.h.Retry != nil,
			Quit:         s.h.Quit != nil,
			Rename:       s.h.Rename != nil,
			Forget:       s.h.Forget != nil,
			StartAtLogin: s.h.SetStartAtLogin != nil,
			Install:      s.h.Install != nil,
			ChangeFolder: s.h.ChangeFolder != nil,
			Thumbs:       s.h.Thumb != nil,
			PickAndSend:  s.h.PickAndSend != nil,
			SendFiles:    s.h.SendFiles != nil,
		},
	}
	for i, p := range snap.PairedPhones {
		if i >= maxPairedSent {
			break
		}
		if name := textsafe.Clean(p.Name, maxNameRunes); name != "" {
			st.PairedPhones = append(st.PairedPhones, pairedJSON{Name: name, LastSeen: textsafe.Clean(p.LastSeen, 40)})
		}
	}
	if phases[snap.Phase] {
		st.Phase = snap.Phase
	}
	if snap.Direction == "to-phone" {
		st.Direction = "to-phone"
	}
	if s.svgOK(snap.QRSVG) {
		st.QRSVG = snap.QRSVG
	}
	for i, p := range snap.Phones {
		if i >= maxPhonesSent {
			break
		}
		st.Phones = append(st.Phones, phoneJSON{
			Name: textsafe.Clean(p.Name, 40),
			Code: textsafe.Clean(p.Code, 12),
			Addr: textsafe.Clean(p.Addr, maxAddressLen),
		})
	}
	first := max(len(snap.Rows)-maxRowsSent, 0)
	st.RowsHidden = first
	for i, r := range snap.Rows {
		state := r.State
		if !rowStates[state] {
			state = "waiting"
		}
		switch state {
		case "waiting":
			st.Counts.Waiting++
		case "receiving":
			st.Counts.Receiving++
		case "saved":
			st.Counts.Saved++
		case "verified":
			st.Counts.Verified++
		case "skipped":
			st.Counts.Skipped++
		case "failed":
			st.Counts.Failed++
		case "uploading":
			st.Counts.Uploading++
		case "sent":
			st.Counts.Sent++
		case "not-found":
			st.Counts.NotFound++
		}
		if i >= first {
			st.Rows = append(st.Rows, rowJSON{
				Index:  i,
				Name:   textsafe.Clean(r.Name, 200),
				Size:   max(r.Size, 0),
				State:  state,
				Detail: textsafe.Clean(r.Detail, 300),
				Thumb:  r.Thumb && s.h.Thumb != nil,
			})
		}
	}
	return st
}

// svgOK caches the verdict for the last QR SVG, which stays the same for a whole pairing.
func (s *server) svgOK(svg string) bool {
	if svg == "" {
		return false
	}
	s.svgMu.Lock()
	defer s.svgMu.Unlock()
	if svg != s.svgLast {
		s.svgLast, s.svgLastOK = svg, vetSVG(svg)
	}
	return s.svgLastOK
}

// displayPath makes a local path safe to show: valid UTF-8, no control or bidi characters.
// Spaces are kept exactly, so the shown path can be sent back to set-folder.
func displayPath(p string) string {
	p = strings.ToValidUTF8(p, "\uFFFD")
	if !strings.ContainsFunc(p, badPathRune) {
		return p
	}
	var b strings.Builder
	for _, r := range p {
		if !badPathRune(r) {
			b.WriteRune(r)
		}
	}
	return b.String()
}
