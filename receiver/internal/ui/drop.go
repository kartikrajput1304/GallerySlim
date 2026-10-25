package ui

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"galleryslim/receiver/internal/textsafe"
)

// Drag and drop ("Bring back to phone"): the page cannot see real file paths, so it uploads
// each dropped file's bytes to POST drop (one file per request, Content-Type
// application/octet-stream, display name in the X-Drop-Name header, percent-encoded). The
// bytes are streamed into a private temporary folder, never held in memory. POST drop-send
// {"ids":[…]} then passes those files' paths to Handlers.SendFiles. The client's name is
// only used, cleaned, as the file's display name; no client path is ever followed.
//
// Temporary files are deleted when SendFiles fails, when a to-phone transfer that used them
// has ended (the Snapshot left the receiving phase after being in it), after dropMaxAge if
// they were never sent or the transfer never started, and when the UI stops.

const (
	maxDropBytes    = 4 << 30  // per file (spec §10)
	maxDropTotal    = 64 << 30 // all files waiting in the temporary folder
	maxDropFiles    = 500      // per batch, like the phone's offer limit
	maxDropNameLen  = 120      // bytes, for the file name on disk
	dropReadTimeout = 60 * time.Minute
	dropMaxAge      = 30 * time.Minute
	dropIDBytes     = 8 // 16 hex characters

	msgPhoneNotReady = "Open Bring back on your phone first, then try again."
	msgDropBusy      = "Please wait, the previous photo is still being prepared."
	msgDropTooMany   = "That's a lot of files at once. Please send at most 500 at a time."
	msgDropTooBig    = "That file is too big to send (the limit is 4 GB per file)."
	msgDropSpace     = "There isn't room to prepare more files right now. Send these first."
	msgDropFailed    = "Couldn't prepare that file. Please try again."
	msgPickerOpen    = "The file window is already open. It may be hidden behind this browser window."
)

type dropFile struct {
	dir, path string // dir is this file's own sub-folder of the temporary folder
	size      int64
	added     time.Time
}

type dropBatch struct {
	files        []dropFile
	sent         time.Time
	sawReceiving bool
}

type dropStore struct {
	upload sync.Mutex // one upload at a time

	mu      sync.Mutex
	dir     string
	pending map[string]dropFile // uploaded, not yet passed to SendFiles
	batches []*dropBatch        // passed to SendFiles, waiting for the transfer to end
	total   int64
}

func (d *dropStore) folder() (string, error) {
	if d.dir == "" {
		dir, err := os.MkdirTemp("", "galleryslim-drop-")
		if err != nil {
			return "", err
		}
		d.dir = dir
	}
	return d.dir, nil
}

func (s *server) serveDrop(w http.ResponseWriter, r *http.Request) {
	if !allowMethods(w, r, http.MethodPost) {
		return
	}
	// A cross-site page cannot send application/octet-stream without a preflight either.
	if !s.postOK(w, r, "application/octet-stream") {
		return
	}
	if s.h.SendFiles == nil {
		writeJSON(w, http.StatusOK, actionResult{Error: msgNotAvailable})
		return
	}
	if snap, ok := s.snapshot(); !ok || !snap.PhoneReceiving {
		writeJSON(w, http.StatusOK, actionResult{Error: msgPhoneNotReady})
		return
	}
	name, ok := dropFileName(r.Header.Get("X-Drop-Name"))
	if !ok {
		writeJSON(w, http.StatusBadRequest, actionResult{Error: "That file name can't be used."})
		return
	}
	if r.ContentLength > maxDropBytes {
		writeJSON(w, http.StatusRequestEntityTooLarge, actionResult{Error: msgDropTooBig})
		return
	}
	if !s.drops.upload.TryLock() {
		writeJSON(w, http.StatusOK, actionResult{Error: msgDropBusy})
		return
	}
	defer s.drops.upload.Unlock()

	d := &s.drops
	d.mu.Lock()
	s.sweepDropsLocked(time.Now())
	room := len(d.pending) < maxDropFiles && d.total+max(r.ContentLength, 0) <= maxDropTotal
	dir, err := d.folder()
	d.mu.Unlock()
	if !room {
		writeJSON(w, http.StatusOK, actionResult{Error: msgDropSpace})
		return
	}
	if err != nil {
		writeJSON(w, http.StatusOK, actionResult{Error: msgDropFailed})
		return
	}
	id, err := newDropID()
	if err != nil {
		writeJSON(w, http.StatusOK, actionResult{Error: msgDropFailed})
		return
	}
	sub := filepath.Join(dir, id)
	path := filepath.Join(sub, name)
	if filepath.Dir(path) != sub { // cannot happen with a cleaned name; checked anyway
		writeJSON(w, http.StatusBadRequest, actionResult{Error: "That file name can't be used."})
		return
	}
	// The whole request may take longer than the server's ReadTimeout for a big video.
	_ = http.NewResponseController(w).SetReadDeadline(time.Now().Add(dropReadTimeout))
	n, err := writeDropFile(sub, path, http.MaxBytesReader(w, r.Body, maxDropBytes))
	if err != nil || n == 0 {
		_ = os.RemoveAll(sub)
		var tooBig *http.MaxBytesError
		switch {
		case errors.As(err, &tooBig):
			writeJSON(w, http.StatusRequestEntityTooLarge, actionResult{Error: msgDropTooBig})
		case err == nil:
			writeJSON(w, http.StatusOK, actionResult{Error: "That file is empty."})
		default:
			writeJSON(w, http.StatusOK, actionResult{Error: msgDropFailed})
		}
		return
	}
	d.mu.Lock()
	if d.pending == nil {
		d.pending = map[string]dropFile{}
	}
	d.pending[id] = dropFile{dir: sub, path: path, size: n, added: time.Now()}
	d.total += n
	d.mu.Unlock()
	writeJSON(w, http.StatusOK, actionResult{OK: true, ID: id})
}

func writeDropFile(sub, path string, body io.Reader) (int64, error) {
	if err := os.Mkdir(sub, 0o700); err != nil {
		return 0, err
	}
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return 0, err
	}
	n, err := io.Copy(f, body)
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	return n, err
}

func newDropID() (string, error) {
	b := make([]byte, dropIDBytes)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

// dropFileName turns the page's percent-encoded file name into a safe single file name:
// only the last path element, cleaned of control and bidi characters, without characters
// Windows forbids, not a reserved device name, and at most maxDropNameLen bytes with the
// extension kept.
func dropFileName(header string) (string, bool) {
	raw, err := url.PathUnescape(header)
	if err != nil || raw == "" || !utf8.ValidString(raw) {
		return "", false
	}
	if i := strings.LastIndexAny(raw, `/\`); i >= 0 {
		raw = raw[i+1:]
	}
	name := textsafe.Clean(raw, 4096) // shortened below, keeping the extension
	name = strings.Map(func(r rune) rune {
		if strings.ContainsRune(`<>:"/\|?*`, r) || r < 0x20 {
			return '_'
		}
		return r
	}, name)
	name = strings.TrimLeft(strings.TrimRight(name, ". "), ". ")
	if name == "" {
		return "", false
	}
	ext := filepath.Ext(name)
	if len(ext) > 16 {
		ext = ""
	}
	if len(name) > maxDropNameLen {
		stem := name[:len(name)-len(ext)]
		stem = strings.ToValidUTF8(stem[:maxDropNameLen-len(ext)], "")
		name = strings.TrimRight(stem, ". ") + ext
	}
	stem := strings.ToUpper(strings.TrimSuffix(name, ext))
	switch stem {
	case "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
		"LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9":
		name = "_" + name
	}
	return name, true
}

// dropSend hands the uploaded files with these ids to Handlers.SendFiles.
func (s *server) dropSend(ids []string) actionResult {
	if s.h.SendFiles == nil {
		return actionResult{Error: msgNotAvailable}
	}
	if len(ids) == 0 {
		return actionResult{Error: "Please drop some photos first."}
	}
	if len(ids) > maxDropFiles {
		return actionResult{Error: msgDropTooMany}
	}
	if snap, ok := s.snapshot(); !ok || !snap.PhoneReceiving {
		return actionResult{Error: msgPhoneNotReady}
	}
	d := &s.drops
	d.mu.Lock()
	seen := map[string]bool{}
	batch := &dropBatch{}
	for _, id := range ids {
		f, ok := d.pending[id]
		if !ok || seen[id] {
			d.mu.Unlock()
			return actionResult{Error: "Some of those files are no longer ready. Please drop them again."}
		}
		seen[id] = true
		batch.files = append(batch.files, f)
	}
	for _, id := range ids {
		delete(d.pending, id)
	}
	d.mu.Unlock()

	paths := make([]string, len(batch.files))
	for i, f := range batch.files {
		paths[i] = f.path
	}
	err := safeCall(func() error { return s.h.SendFiles(paths) })
	d.mu.Lock()
	defer d.mu.Unlock()
	batch.sent = time.Now()
	d.batches = append(d.batches, batch)
	if err != nil {
		s.removeBatchLocked(len(d.batches) - 1)
		return actionResult{Error: friendlyError(err)}
	}
	return actionResult{OK: true}
}

// noteDrops is called with every status snapshot to delete files whose transfer has ended.
func (s *server) noteDrops(snap Snapshot) {
	d := &s.drops
	d.mu.Lock()
	defer d.mu.Unlock()
	if len(d.batches) == 0 && len(d.pending) == 0 {
		return
	}
	for _, b := range d.batches {
		if snap.Phase == "receiving" {
			b.sawReceiving = true
		} else if b.sawReceiving {
			b.sent = time.Time{} // ended: due now
		}
	}
	s.sweepDropsLocked(time.Now())
}

// sweepDropsLocked deletes ended batches and anything older than dropMaxAge. Files that
// cannot be deleted yet (still open on Windows) are kept and retried later.
func (s *server) sweepDropsLocked(now time.Time) {
	d := &s.drops
	for i := len(d.batches) - 1; i >= 0; i-- {
		b := d.batches[i]
		if b.sent.IsZero() || (!b.sawReceiving && now.Sub(b.sent) > dropMaxAge) {
			s.removeBatchLocked(i)
		}
	}
	for id, f := range d.pending {
		if now.Sub(f.added) > dropMaxAge && os.RemoveAll(f.dir) == nil {
			d.total -= f.size
			delete(d.pending, id)
		}
	}
}

func (s *server) removeBatchLocked(i int) {
	d := &s.drops
	b := d.batches[i]
	var left []dropFile
	for _, f := range b.files {
		if os.RemoveAll(f.dir) == nil {
			d.total -= f.size
		} else {
			left = append(left, f)
		}
	}
	if len(left) > 0 {
		b.files, b.sent = left, time.Time{}
		return
	}
	d.batches = append(d.batches[:i], d.batches[i+1:]...)
}

// removeAllDrops deletes the temporary folder; called when the UI stops.
func (s *server) removeAllDrops() {
	d := &s.drops
	d.upload.Lock() // wait for an upload in progress (Shutdown has already waited for its request)
	defer d.upload.Unlock()
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.dir != "" {
		_ = os.RemoveAll(d.dir)
	}
	d.dir, d.pending, d.batches, d.total = "", nil, nil, 0
}
