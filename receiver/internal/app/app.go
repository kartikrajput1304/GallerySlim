// Package app is the receiver's state machine for the local page: choose a folder, find the
// phone, pair, receive, done. It is independent of the page and desktop packages; main.go
// connects them.
package app

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"galleryslim/receiver/internal/client"
	"galleryslim/receiver/internal/discover"
	"galleryslim/receiver/internal/qr"
	"galleryslim/receiver/internal/receive"
	"galleryslim/receiver/internal/store"
)

// Phases shown by the page.
const (
	PhaseChooseFolder = "choose-folder"
	PhaseFinding      = "finding"
	PhasePairing      = "pairing"
	PhaseReceiving    = "receiving"
	PhaseDone         = "done"
	PhaseError        = "error"
)

// Free choice: how often the idle session is kept alive and checked.
const keepAliveEvery = 60 * time.Second

// Row states.
const (
	RowWaiting   = "waiting"
	RowReceiving = "receiving"
	RowSaved     = "saved"
	RowVerified  = "verified"
	RowSkipped   = "skipped"
	RowFailed    = "failed"
)

// MaxRows is how many of the newest file rows Status returns.
const MaxRows = 500

// Phone is a phone found on the network. Known means it is a remembered phone (§9).
type Phone struct {
	Name, Code, Addr string
	Known            bool
}

// Row is one file on the page. Preview means PreviewPath(ID) has a JPEG/PNG to show.
type Row struct {
	ID, Name string
	Size     int64
	State    string
	Detail   string
	Preview  bool
}

// Status is everything the page shows. Text from the phone is already cleaned of control and
// bidi characters but still has to be escaped by the page.
type Status struct {
	Phase              string
	Folder, FolderNote string
	PhoneName, Code    string
	QRSVG              string
	Phones             []Phone
	FilesDone          int
	FilesTotal         int
	BytesDone          int64
	BytesTotal         int64
	Current            string
	Rows               []Row
	Error              string
	Version            string

	// ComputerName is the name the phone shows for this computer.
	ComputerName string
	// Paired are the remembered phones, most recently used first.
	Paired []PairedPhone
	// Background: running quietly (started at login or with --background).
	Background bool
	// Waiting: in the background, waiting for a remembered phone to start sending; the page
	// shows "Ready" and a "Search for a phone" button (Retry) instead of a search in progress.
	Waiting bool
	// Remembered: the current pairing uses a remembered phone; the owner confirms on the phone,
	// no QR is shown.
	Remembered bool

	// Direction is DirToComputer or DirToPhone (§10 Bring back to phone).
	Direction string
	// PhoneReceiving: the approved phone session takes files now; SendToPhone works.
	PhoneReceiving bool
}

// Directions of a transfer.
const (
	DirToComputer = "to-computer"
	DirToPhone    = "to-phone"
)

// Row states of a send to the phone (besides RowWaiting and RowFailed).
const (
	RowUploading = "uploading"
	RowSent      = "sent"
	RowNotFound  = "not-found"
)

// Deps are the outside services the state machine uses.
type Deps struct {
	PickFolder    func(title, start string) (string, error)
	DefaultFolder func() (path, note string)
	CloudSynced   func(path string) (bool, string)
	OpenFolder    func(path string) error
	KeepAwake     func() (release func())
	SaveFolder    func(path string) error
	Find          func(ctx context.Context) ([]discover.Candidate, error)
	Probe         discover.Prober
	HTTP          *http.Client
	Client        client.Config
	Hostname      string
	OSName        string // windows | mac | linux
	Log           *slog.Logger
	Version       string
	RescanEvery   time.Duration // search again while no phone is found (default 8 s)
	PollEvery     time.Duration // pairing state poll (default 1 s)

	// §9 remembered pairing.
	Pairs *Pairs // remembered phones; nil = remember nothing
	RID   string // this receiver's id
	// SaveName stores a new computer name (nil: not saved).
	SaveName func(name string) error
	// Background: wait quietly for remembered phones instead of searching; never show a QR
	// unless the person asks for a search on the page.
	Background bool
	// Quick looks for phones at the given last-known addresses and with mDNS (no subnet
	// sweep). Required for Background.
	Quick func(ctx context.Context, last []string) []discover.Candidate
	// WatchEvery is the background look interval (default 5 s).
	WatchEvery time.Duration
	// SearchFor bounds a search asked for on the page in background mode (default 5 min).
	SearchFor time.Duration
	// OnReceiving is called (on its own goroutine) when files start arriving, or start being
	// sent to the phone.
	OnReceiving func()
	// PickFiles shows the system dialog for choosing files to send to the phone.
	PickFiles func(title string) ([]string, error)
}

type eventKind int

const (
	evFolder eventKind = iota + 1
	evAddress
	evRetry
)

type event struct {
	kind eventKind
	addr string
}

// App is the state machine.
type App struct {
	d      Deps
	events chan event

	mu        sync.Mutex
	st        Status
	rowIdx    map[string]int
	haveDir   bool
	receiving bool
	verified  map[string]bool // phone|id|size confirmed in this run

	backoff map[string]time.Time // background: pid|code → don't claim again before
	thumbs  map[string]string    // row id → full path of the saved file (previewable rows)

	qrHook atomic.Pointer[func(string)] // tests: receives the QR text

	sendReq chan []string // free choice: files to send to the phone
}

func (a *App) setQRHook(f func(string)) {
	if f == nil {
		a.qrHook.Store(nil)
		return
	}
	a.qrHook.Store(&f)
}

// New creates the state machine; folder is the saved folder ("" on first run).
func New(d Deps, folder string) *App {
	if d.RescanEvery == 0 {
		d.RescanEvery = 8 * time.Second
	}
	if d.Log == nil {
		d.Log = slog.New(slog.DiscardHandler)
	}
	if d.WatchEvery == 0 {
		d.WatchEvery = 5 * time.Second
	}
	if d.SearchFor == 0 {
		d.SearchFor = 5 * time.Minute
	}
	a := &App{d: d, events: make(chan event, 8), rowIdx: map[string]int{}, verified: map[string]bool{},
		backoff: map[string]time.Time{}, thumbs: map[string]string{},
		sendReq: make(chan []string, 1)}
	a.st.Version = d.Version
	a.st.ComputerName = CleanComputerName(d.Hostname)
	a.st.Background = d.Background
	a.st.Phase = PhaseChooseFolder
	if folder != "" && store.CheckWritable(folder) == nil {
		a.st.Folder, a.haveDir = folder, true
		a.st.FolderNote = a.cloudNote(folder)
	} else if d.DefaultFolder != nil {
		a.st.Folder, a.st.FolderNote = d.DefaultFolder()
	}
	return a
}

func (a *App) update(fn func(s *Status)) {
	a.mu.Lock()
	fn(&a.st)
	a.mu.Unlock()
}

// Status returns a copy of the current state with the newest MaxRows rows.
func (a *App) Status() Status {
	a.mu.Lock()
	defer a.mu.Unlock()
	s := a.st
	rows := a.st.Rows
	if len(rows) > MaxRows {
		rows = rows[len(rows)-MaxRows:]
	}
	s.Rows = append([]Row(nil), rows...)
	s.Phones = append([]Phone(nil), a.st.Phones...)
	s.Paired = a.d.Pairs.List()
	return s
}

// SetComputerName changes the name the phone shows for this computer (from the next pairing).
func (a *App) SetComputerName(name string) error {
	name = CleanComputerName(name)
	if name == "" {
		return errors.New("Please type a name for this computer")
	}
	if a.d.SaveName != nil {
		if err := a.d.SaveName(name); err != nil {
			a.d.Log.Warn("could not save settings", "err", err)
			return errors.New("The name could not be saved")
		}
	}
	a.update(func(s *Status) { s.ComputerName = name })
	return nil
}

func (a *App) computerName() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.st.ComputerName
}

// ForgetPhone deletes a remembered phone: the next time it needs the QR code again.
func (a *App) ForgetPhone(pid string) error {
	if !a.d.Pairs.Known(pid) {
		return errors.New("That phone is not paired with this computer")
	}
	if err := a.d.Pairs.Forget(pid); err != nil {
		a.d.Log.Warn("could not forget phone", "err", err)
		return errors.New("The phone could not be forgotten; please try again")
	}
	a.d.Log.Info("phone forgotten")
	return nil
}

// PreviewPath returns the saved file of a row of this run (row id) when the page can show a
// small picture of it: received and saved (or sent to the phone), and a JPEG or PNG.
func (a *App) PreviewPath(id string) (string, bool) {
	a.mu.Lock()
	defer a.mu.Unlock()
	p, ok := a.thumbs[id]
	return p, ok
}

// insideFolder joins a "<month>/<name>" path under root and refuses anything that would leave it.
func insideFolder(root, rel string) (string, error) {
	if root == "" || rel == "" || filepath.IsAbs(rel) || !filepath.IsLocal(filepath.FromSlash(rel)) {
		return "", errors.New("bad path")
	}
	return filepath.Join(root, filepath.FromSlash(rel)), nil
}

// Receiving reports whether files are being received right now.
func (a *App) Receiving() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.receiving
}

func (a *App) send(e event) {
	select {
	case a.events <- e:
	default: // a burst of clicks; the state machine is already busy with one
	}
}

func (a *App) drainEvents() {
	for {
		select {
		case <-a.events:
		default:
			return
		}
	}
}

// waitEvent waits for a page action, or returns false after timeout (0 = no timeout).
func (a *App) waitEvent(ctx context.Context, timeout time.Duration) (event, bool) {
	var tc <-chan time.Time
	if timeout > 0 {
		t := time.NewTimer(timeout)
		defer t.Stop()
		tc = t.C
	}
	select {
	case e := <-a.events:
		return e, true
	case <-tc:
		return event{}, false
	case <-ctx.Done():
		return event{}, false
	}
}

func (a *App) cloudNote(path string) string {
	if a.d.CloudSynced == nil {
		return ""
	}
	if synced, provider := a.d.CloudSynced(path); synced {
		if provider == "" {
			provider = "a cloud service"
		}
		return fmt.Sprintf("This folder syncs to %s. Photos there can be moved to the cloud and removed from this computer, so they can't be checked here. A folder that stays on this computer is safer.", provider)
	}
	return ""
}

func expandHome(p string) string {
	if p == "~" || strings.HasPrefix(p, "~/") || strings.HasPrefix(p, `~\`) {
		if home, err := os.UserHomeDir(); err == nil {
			return filepath.Join(home, p[1:])
		}
	}
	return p
}

// SetFolder checks and saves the folder the user chose.
func (a *App) SetFolder(path string) error {
	path = expandHome(strings.TrimSpace(path))
	if path == "" || !filepath.IsAbs(path) {
		return errors.New("Please give the full path of a folder")
	}
	path = filepath.Clean(path)
	if a.Receiving() {
		return errors.New("Please wait until receiving has finished")
	}
	if err := store.CheckWritable(path); err != nil {
		a.d.Log.Warn("folder not writable", "err", err)
		cause := err
		if inner := errors.Unwrap(err); inner != nil {
			cause = inner // "permission denied" rather than the whole path again
		}
		return fmt.Errorf("Photos can't be saved in that folder: %v", cause)
	}
	if a.d.SaveFolder != nil {
		if err := a.d.SaveFolder(path); err != nil {
			a.d.Log.Warn("could not save settings", "err", err)
		}
	}
	note := a.cloudNote(path)
	a.mu.Lock()
	a.st.Folder, a.st.FolderNote, a.haveDir = path, note, true
	a.mu.Unlock()
	a.d.Log.Info("folder chosen", "cloud", note != "")
	a.send(event{kind: evFolder})
	return nil
}

// ChooseFolder opens the system folder dialog and saves the choice. It returns "" when the
// user cancelled; errors from the dialog are passed through (the page shows the text box).
func (a *App) ChooseFolder() (string, error) {
	if a.d.PickFolder == nil {
		return "", errors.New("folder dialog not supported")
	}
	a.mu.Lock()
	start := a.st.Folder
	a.mu.Unlock()
	path, err := a.d.PickFolder("Choose where to save photos", start)
	if err != nil || path == "" {
		return "", err
	}
	return path, a.SetFolder(path)
}

// EnterAddress uses an address the user typed (or a phone button).
func (a *App) EnterAddress(addr string) error {
	if _, err := discover.ParseAddress(addr); err != nil {
		return err
	}
	a.send(event{kind: evAddress, addr: strings.TrimSpace(addr)})
	return nil
}

// Retry means "Try again", "Search again" or "Receive more", depending on the phase.
func (a *App) Retry() error {
	a.send(event{kind: evRetry})
	return nil
}

// OpenFolder shows the folder in the file manager.
func (a *App) OpenFolder() error {
	a.mu.Lock()
	folder, ok := a.st.Folder, a.haveDir
	a.mu.Unlock()
	if !ok || a.d.OpenFolder == nil {
		return errors.New("No folder chosen yet")
	}
	return a.d.OpenFolder(folder)
}

func (a *App) folder() (string, bool) {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.st.Folder, a.haveDir
}

// Run drives the page until ctx ends.
func (a *App) Run(ctx context.Context) {
	for ctx.Err() == nil {
		if _, ok := a.folder(); !ok {
			a.update(func(s *Status) { s.Phase = PhaseChooseFolder; s.Error = "" })
			for {
				ev, _ := a.waitEvent(ctx, 0)
				if ctx.Err() != nil {
					return
				}
				if ev.kind == evFolder {
					break
				}
			}
		}
		if a.d.Background {
			cand, ok := a.watch(ctx)
			if ok {
				a.session(ctx, cand, true)
				continue
			}
			if ctx.Err() != nil {
				return
			}
			// The person asked for a search on the page: search like the page mode, for a while.
			sctx, cancel := context.WithTimeout(ctx, a.d.SearchFor)
			cand, ok = a.findPhone(sctx)
			cancel()
			if ok {
				a.session(ctx, cand, false)
			}
			continue
		}
		cand, ok := a.findPhone(ctx)
		if !ok {
			continue
		}
		a.session(ctx, cand, false)
	}
}

// pageWait is how long a question on the page (Try again, Receive more) waits for an answer:
// forever normally, a while in the background before going back to waiting quietly.
func (a *App) pageWait() time.Duration {
	if a.d.Background {
		return a.d.SearchFor
	}
	return 0
}

func backoffKey(c discover.Candidate) string { return c.Pid + "|" + c.Code }

// holdOff stops the background watcher from claiming this phone again during the same
// sharing run (the phone's short code changes with every run) for a while.
func (a *App) holdOff(c discover.Candidate, d time.Duration) {
	a.mu.Lock()
	defer a.mu.Unlock()
	now := time.Now()
	for k, t := range a.backoff {
		if now.After(t) {
			delete(a.backoff, k)
		}
	}
	a.backoff[backoffKey(c)] = now.Add(d)
}

func (a *App) heldOff(c discover.Candidate) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	t, ok := a.backoff[backoffKey(c)]
	return ok && time.Now().Before(t)
}

// watch waits quietly for a remembered phone to appear (background mode). It looks at the
// remembered phones' last addresses and asks mDNS every WatchEvery. It returns a remembered
// phone, or false when the person asked for a search on the page (or ctx ended).
func (a *App) watch(ctx context.Context) (discover.Candidate, bool) {
	a.drainEvents()
	for ctx.Err() == nil {
		a.update(func(s *Status) {
			if s.Phase != PhaseDone && s.Phase != PhaseError {
				s.Phase, s.Error, s.Current = PhaseFinding, "", ""
			}
			s.Waiting, s.Phones, s.QRSVG, s.Remembered = true, nil, "", false
		})
		if a.d.Pairs.Len() > 0 && a.d.Quick != nil {
			for _, c := range a.d.Quick(ctx, a.d.Pairs.Addrs()) {
				if a.d.Pairs.Known(c.Pid) && !a.heldOff(c) {
					a.update(func(s *Status) { s.Waiting = false })
					a.d.Log.Info("remembered phone found")
					return c, true
				}
			}
		}
		ev, got := a.waitEvent(ctx, a.d.WatchEvery)
		if got && (ev.kind == evRetry || ev.kind == evAddress) {
			a.update(func(s *Status) { s.Waiting = false })
			if ev.kind == evAddress {
				a.send(ev) // let findPhone use it
			}
			return discover.Candidate{}, false
		}
	}
	return discover.Candidate{}, false
}

const noPhoneMsg = "No phone found yet. On your phone, open GallerySlim and tap \"Send to computer\". " +
	"Both need to be on the same Wi-Fi. You can also type the address shown on the phone."

func (a *App) findPhone(ctx context.Context) (discover.Candidate, bool) {
	typed := ""
	if !a.d.Background {
		a.drainEvents()
	}
	for ctx.Err() == nil {
		a.update(func(s *Status) {
			s.Phase, s.Error, s.Phones, s.QRSVG, s.PhoneName, s.Code = PhaseFinding, "", nil, "", "", ""
			s.Current, s.Waiting, s.Remembered = "Looking for your phone…", false, false
		})
		timeout := a.d.RescanEvery
		if typed != "" {
			c, err := discover.ProbeAddress(ctx, typed, a.d.Probe)
			typed = ""
			if err == nil {
				a.d.Log.Info("phone found at typed address", "addr", c.Addr)
				return c, true
			}
			a.update(func(s *Status) { s.Current, s.Error = "", err.Error() })
			timeout = 0
		} else {
			cands, _ := a.d.Find(ctx)
			if ctx.Err() != nil {
				return discover.Candidate{}, false
			}
			a.d.Log.Info("search finished", "phones", len(cands))
			if len(cands) == 1 {
				return cands[0], true
			}
			a.update(func(s *Status) {
				s.Current = ""
				if len(cands) > 1 {
					for _, c := range cands {
						s.Phones = append(s.Phones, Phone{Name: c.Name, Code: c.Code, Addr: c.Addr, Known: a.d.Pairs.Known(c.Pid)})
					}
				} else {
					s.Error = noPhoneMsg
				}
			})
			if len(cands) > 1 {
				timeout = 0 // the person picks one
			}
		}
		ev, got := a.waitEvent(ctx, timeout)
		if got && ev.kind == evAddress {
			typed = ev.addr
		}
	}
	return discover.Candidate{}, false
}

// showQR is called by Pair with the pairing text; only the drawn QR is kept.
func (a *App) showQR(text, code string) {
	if h := a.qrHook.Load(); h != nil {
		go (*h)(text)
	}
	c, err := qr.Encode([]byte(text))
	svg := ""
	if err == nil {
		svg = c.SVG()
	}
	a.update(func(s *Status) {
		s.QRSVG, s.Current = svg, ""
		if code != "" {
			s.Code = code
		}
	})
}

func (a *App) sessionInfo(folder string) client.SessionInfo {
	return client.SessionInfo{Receiver: a.computerName(), OS: a.d.OSName, Folder: filepath.Base(folder), RID: a.d.RID}
}

// session pairs with one phone and receives, until the person goes back to searching. auto
// is a remembered phone found by the background watcher: no QR, and back to waiting after.
func (a *App) session(ctx context.Context, cand discover.Candidate, auto bool) {
	note := ""
	for ctx.Err() == nil {
		if !auto {
			a.drainEvents()
		}
		a.update(func(s *Status) {
			s.Phase, s.PhoneName, s.Code, s.QRSVG, s.Phones = PhasePairing, cand.Name, cand.Code, "", nil
			s.Error, s.Current, s.Waiting, s.Remembered = note, "Preparing a code…", false, false
			if a.d.Pairs.Known(cand.Pid) {
				s.Current = "Contacting your phone…"
			}
		})
		if auto {
			sess, err := a.pairAuto(ctx, cand)
			if sess == nil {
				a.holdOff(cand, autoHold(err))
				return
			}
			failed, err := a.transfer(ctx, sess, cand)
			if ctx.Err() != nil {
				return
			}
			a.holdOff(cand, 10*time.Minute) // the phone's "Send to computer" screen may still be open
			a.update(func(s *Status) {
				s.Phase, s.Current = PhaseDone, ""
				switch {
				case err != nil && !errors.Is(err, client.ErrExpired):
					s.Phase, s.Error = PhaseError, receive.Friendly(err)
				case failed > 0:
					s.Error = fmt.Sprintf("%d of %d files could not be received. Send again from the phone to retry them.", failed, s.FilesTotal)
				}
			})
			return
		}
		sess, back := a.pair(ctx, cand)
		if sess == nil {
			if back {
				return
			}
			continue
		}
		note = ""
		for {
			failed, err := a.transfer(ctx, sess, cand)
			if ctx.Err() != nil {
				return
			}
			if errors.Is(err, client.ErrExpired) || errors.Is(err, client.ErrAuth) || errors.Is(err, client.ErrForbidden) {
				note = "The phone ended the session. Scan the new code to continue; files already saved are kept."
				break
			}
			if err != nil {
				a.update(func(s *Status) { s.Phase, s.Error = PhaseError, receive.Friendly(err) })
				if _, got := a.waitEvent(ctx, a.pageWait()); !got {
					return // background: nobody answered; back to waiting
				}
				break // a new code
			}
			a.update(func(s *Status) {
				s.Phase, s.Current = PhaseDone, ""
				if failed > 0 {
					s.Error = fmt.Sprintf("%d of %d files could not be received. Press Try again to retry them.", failed, s.FilesTotal)
				}
			})
			a.drainEvents()
			if a.d.Background && failed == 0 {
				return // back to waiting quietly; the page keeps showing the result
			}
			ev, got := a.waitEvent(ctx, a.pageWait())
			if ctx.Err() != nil || !got {
				return
			}
			if failed > 0 && ev.kind == evRetry {
				continue // same session, only the missing files
			}
			return // receive more: search again
		}
	}
}

// autoHold is how long the watcher leaves a phone alone after a remembered pairing ended
// without a transfer.
func autoHold(err error) time.Duration {
	switch {
	case errors.Is(err, receive.ErrNotRemembered), errors.Is(err, receive.ErrPairingExpired):
		return 10 * time.Minute // forgotten on the phone, or the owner said no / didn't answer
	}
	return 30 * time.Second // network trouble
}

// pairAuto pairs with a remembered phone found in the background: claim, then wait for the
// owner to confirm on the phone. It never shows a QR. A page action (search, address) ends it.
func (a *App) pairAuto(ctx context.Context, cand discover.Candidate) (*client.Session, error) {
	folder, _ := a.folder()
	pctx, cancel := context.WithCancel(ctx)
	defer cancel()
	type result struct {
		p   receive.Paired
		err error
	}
	ch := make(chan result, 1)
	go func() {
		p, err := receive.PairRemembered(pctx, a.pairOptions(cand, folder, true))
		ch <- result{p, err}
	}()
	var res result
	select {
	case res = <-ch:
	case ev := <-a.events:
		cancel()
		<-ch
		a.send(ev) // the watcher handles it
		return nil, context.Canceled
	}
	if res.err != nil {
		a.d.Log.Info("remembered pairing ended", "err", res.err)
		a.update(func(s *Status) { s.Phase, s.Remembered, s.Current = PhaseFinding, false, "" })
		return nil, res.err
	}
	a.paired(cand, res.p)
	return res.p.Session, nil
}

func (a *App) pairOptions(cand discover.Candidate, folder string, noQR bool) receive.PairOptions {
	o := receive.PairOptions{
		HTTP: a.d.HTTP, Addr: cand.Addr, Config: a.d.Client, Info: a.sessionInfo(folder),
		ShowQR: a.showQR, PollEvery: a.d.PollEvery, NoQR: noQR,
		OnClaimed: func() {
			a.update(func(s *Status) {
				s.Remembered, s.QRSVG = true, ""
				s.Current = "On your phone, tap \u201c" + s.ComputerName + "\u201d and confirm with your fingerprint."
			})
		},
		OnRejected: func() {
			a.d.Log.Info("remembered pairing not accepted by the phone")
			a.update(func(s *Status) { s.Remembered = false })
		},
	}
	if a.d.Pairs.Known(cand.Pid) {
		o.PairKey = a.d.Pairs.Key(cand.Pid)
	}
	return o
}

// paired stores what an approved pairing taught us about the phone.
func (a *App) paired(cand discover.Candidate, p receive.Paired) {
	switch {
	case p.Remembered:
		a.d.Log.Info("paired (remembered)")
		a.d.Pairs.Used(cand.Pid, cand.Name, cand.Addr)
	case p.PairKey != nil && a.d.Pairs != nil && cand.Pid != "":
		if err := a.d.Pairs.Remember(cand.Pid, cand.Name, cand.Addr, p.PairKey); err != nil {
			a.d.Log.Warn("could not remember the phone", "err", err)
		} else {
			a.d.Log.Info("phone remembered")
		}
	}
	clear(p.PairKey)
}

// pair shows a code until the phone approves. It returns the session, or nil and whether the
// person went back to searching (an address typed) — otherwise a new code is made.
func (a *App) pair(ctx context.Context, cand discover.Candidate) (*client.Session, bool) {
	folder, _ := a.folder()
	pctx, cancel := context.WithCancel(ctx)
	defer cancel()
	type result struct {
		p   receive.Paired
		err error
	}
	ch := make(chan result, 1)
	go func() {
		p, err := receive.PairRemembered(pctx, a.pairOptions(cand, folder, false))
		ch <- result{p, err}
	}()
	var res result
	for waiting := true; waiting; {
		select {
		case res = <-ch:
			waiting = false
		case ev := <-a.events:
			switch ev.kind {
			case evAddress:
				cancel()
				<-ch
				a.clearQR()
				a.send(ev) // let findPhone use it
				return nil, true
			case evRetry: // a fresh code
				cancel()
				<-ch
				a.clearQR()
				return nil, false
			}
		case <-ctx.Done():
			return nil, true
		}
	}
	a.clearQR()
	if res.err == nil {
		a.d.Log.Info("paired", "phone", cand.Name)
		a.paired(cand, res.p)
		return res.p.Session, false
	}
	a.d.Log.Info("pairing ended", "err", res.err)
	msg := "Couldn't reach the phone. Check that it still shows \"Send to computer\" and is on the same Wi-Fi."
	if errors.Is(res.err, receive.ErrPairingExpired) {
		msg = "The code expired before it was confirmed on the phone. Press Try again for a new code."
		if a.Status().Remembered {
			msg = "The phone didn't confirm in time. Press Try again, then confirm on the phone."
		}
	}
	a.update(func(s *Status) { s.Phase, s.Error = PhaseError, msg })
	a.drainEvents()
	ev, got := a.waitEvent(ctx, a.pageWait())
	if !got {
		return nil, true
	}
	if ev.kind == evAddress {
		a.send(ev)
		return nil, true
	}
	return nil, !errors.Is(res.err, receive.ErrPairingExpired)
}

func (a *App) clearQR() {
	a.update(func(s *Status) { s.QRSVG = "" })
}

func (a *App) verifiedKey(phone string, f client.File) string {
	return fmt.Sprintf("%s\x00%s\x00%d", phone, f.ID, f.Size)
}

func (a *App) receiveAll(ctx context.Context, sess *client.Session, cand discover.Candidate) (int, error) {
	folderPath, _ := a.folder()
	folder, err := store.Open(folderPath)
	if err != nil {
		return 0, err
	}
	folder.CleanupParts(14 * 24 * time.Hour)
	a.mu.Lock()
	a.receiving = true
	a.st.Phase, a.st.Error, a.st.Current = PhaseReceiving, "", ""
	a.st.Rows, a.rowIdx = nil, map[string]int{}
	a.st.FilesDone, a.st.FilesTotal, a.st.BytesDone, a.st.BytesTotal = 0, 0, 0, 0
	a.st.Waiting, a.st.Remembered = false, false
	a.st.Direction, a.st.PhoneReceiving = DirToComputer, false
	a.thumbs = map[string]string{}
	a.mu.Unlock()
	if a.d.OnReceiving != nil {
		go a.d.OnReceiving()
	}
	defer func() {
		a.mu.Lock()
		a.receiving = false
		a.mu.Unlock()
	}()
	sum, err := receive.Run(ctx, receive.Options{
		Session: sess, Folder: folder, Phone: cand.Name, Events: (*events)(a),
		KeepAwake: a.d.KeepAwake,
		Skip: func(f client.File) bool {
			a.mu.Lock()
			defer a.mu.Unlock()
			return a.verified[a.verifiedKey(cand.Name, f)]
		},
	})
	a.d.Log.Info("receive finished", "verified", sum.Verified, "already", sum.Already, "saved", sum.Saved,
		"skipped", sum.Skipped, "failed", sum.Failed, "err", err)
	return sum.Failed, err
}

// events adapts App to receive.Events.
type events App

func (e *events) Listed(files []client.File, total int64) {
	a := (*App)(e)
	a.update(func(s *Status) { s.FilesTotal, s.BytesTotal = len(files), total })
}

func (e *events) FileStarted(f client.File, name string) {
	a := (*App)(e)
	a.mu.Lock()
	defer a.mu.Unlock()
	a.rowIdx[f.ID] = len(a.st.Rows)
	a.st.Rows = append(a.st.Rows, Row{ID: f.ID, Name: name, Size: f.Size, State: RowReceiving})
	a.st.Current = name
}

func (e *events) Progress(d int64) {
	a := (*App)(e)
	a.update(func(s *Status) { s.BytesDone += d })
}

func (e *events) FileDone(r receive.Result) {
	a := (*App)(e)
	state := RowFailed
	switch r.Outcome {
	case receive.Verified:
		state = RowVerified
	case receive.Saved:
		state = RowSaved
	case receive.Skipped:
		state = RowSkipped
	}
	a.d.Log.Info("file", "name", r.Name, "result", string(r.Outcome), "already", r.Already, "detail", r.Detail)
	a.mu.Lock()
	defer a.mu.Unlock()
	if r.Outcome == receive.Verified {
		a.verified[a.verifiedKey(a.st.PhoneName, r.File)] = true
	}
	name := r.Name
	if r.Rel != "" {
		name = r.Rel
	}
	preview := false
	if (r.Outcome == receive.Verified || r.Outcome == receive.Saved) && r.Rel != "" && store.Previewable(r.Rel) {
		if path, err := insideFolder(a.st.Folder, r.Rel); err == nil {
			a.thumbs[r.File.ID] = path
			preview = true
		}
	}
	if i, ok := a.rowIdx[r.File.ID]; ok {
		a.st.Rows[i].State, a.st.Rows[i].Detail, a.st.Rows[i].Name = state, r.Detail, name
		a.st.Rows[i].Preview = preview
	}
	a.st.FilesDone++
	if a.st.Current == r.Name {
		a.st.Current = ""
	}
}

// ---------------------------------------------------------------------------
// Bring back to phone (gs2 §10).

// transfer runs an approved session in the direction the phone is in: when the phone is in
// "Bring back" mode it has a wanted list (sent at once) or an empty one (free choice: the
// person picks files on the page); otherwise the phone sends files to this computer.
func (a *App) transfer(ctx context.Context, sess *client.Session, cand discover.Candidate) (int, error) {
	wctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	wants, err := sess.Wants(wctx)
	cancel()
	switch {
	case err == nil && len(wants) > 0:
		return a.sendWanted(ctx, sess, wants)
	case err == nil:
		return a.freeChoice(ctx, sess)
	case errors.Is(err, client.ErrExpired), errors.Is(err, client.ErrAuth), errors.Is(err, client.ErrForbidden), ctx.Err() != nil:
		return 0, err
	}
	// Not receiving (signed 403), or a phone without §10: it sends to this computer.
	return a.receiveAll(ctx, sess, cand)
}

// startSend resets the rows for a send to the phone.
func (a *App) startSend(keys, names []string, sizes []int64) {
	a.mu.Lock()
	a.receiving = true
	a.st.Phase, a.st.Error, a.st.Current = PhaseReceiving, "", ""
	a.st.Direction, a.st.Waiting, a.st.Remembered = DirToPhone, false, false
	a.st.Rows, a.rowIdx = nil, map[string]int{}
	a.thumbs = map[string]string{}
	var total int64
	for i, k := range keys {
		a.rowIdx[k] = len(a.st.Rows)
		a.st.Rows = append(a.st.Rows, Row{ID: k, Name: names[i], Size: sizes[i], State: RowWaiting})
		total += sizes[i]
	}
	a.st.FilesDone, a.st.FilesTotal, a.st.BytesDone, a.st.BytesTotal = 0, len(keys), 0, total
	a.mu.Unlock()
	if a.d.OnReceiving != nil {
		go a.d.OnReceiving()
	}
}

func (a *App) endSend() {
	a.mu.Lock()
	a.receiving = false
	a.mu.Unlock()
}

func (a *App) sendWanted(ctx context.Context, sess *client.Session, wants []client.Want) (int, error) {
	folderPath, _ := a.folder()
	folder, err := store.Open(folderPath)
	if err != nil {
		return 0, err
	}
	defer a.endSend()
	sum, err := receive.SendWanted(ctx, sess, folder, wants, (*backEvents)(a))
	a.d.Log.Info("sent to phone", "sent", sum.Sent, "notFound", sum.NotFound, "failed", sum.Failed, "err", err)
	if err == nil {
		dctx, cancel := context.WithTimeout(ctx, 10*time.Second)
		sess.Done(dctx)
		cancel()
	}
	if errors.Is(err, client.ErrNotReceiving) {
		return sum.Failed, nil // the owner closed the screen; the rows say what arrived
	}
	return sum.Failed, err
}

// freeChoice keeps an approved session open while the phone takes files, sending what the
// person picks on the page. It ends when the phone stops receiving or the session ends.
func (a *App) freeChoice(ctx context.Context, sess *client.Session) (int, error) {
	a.drainSends()
	a.update(func(s *Status) {
		s.Phase, s.Direction, s.PhoneReceiving, s.Error, s.Current = PhaseDone, DirToPhone, true, "", ""
		s.Waiting, s.Remembered, s.Rows, s.FilesDone, s.FilesTotal, s.BytesDone, s.BytesTotal = false, false, nil, 0, 0, 0, 0
	})
	defer func() {
		// A send asked for just as the session ended: undo its "receiving" state.
		a.mu.Lock()
		select {
		case <-a.sendReq:
			a.receiving = false
			a.st.Phase = PhaseDone
		default:
		}
		a.st.PhoneReceiving = false
		a.mu.Unlock()
	}()
	a.d.Log.Info("phone takes files (free choice)")
	failed := 0
	tick := time.NewTicker(keepAliveEvery)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return failed, ctx.Err()
		case paths := <-a.sendReq:
			sum, err := receive.SendChosen(ctx, sess, paths, (*backEvents)(a))
			a.endSend()
			a.d.Log.Info("sent to phone", "sent", sum.Sent, "refused", sum.Refused, "failed", sum.Failed, "err", err)
			failed += sum.Failed
			a.update(func(s *Status) {
				s.Phase, s.Current = PhaseDone, ""
				if err != nil {
					s.Error = receive.Friendly(err)
				}
			})
			if errors.Is(err, client.ErrNotReceiving) {
				return failed, nil
			}
			if err != nil && (errors.Is(err, client.ErrExpired) || errors.Is(err, client.ErrAuth) || errors.Is(err, client.ErrForbidden)) {
				return failed, err
			}
		case <-tick.C:
			wctx, cancel := context.WithTimeout(ctx, 30*time.Second)
			_, err := sess.Wants(wctx) // keeps the session alive and checks the phone still takes files
			cancel()
			if errors.Is(err, client.ErrNotReceiving) {
				return failed, nil
			}
			if errors.Is(err, client.ErrExpired) || errors.Is(err, client.ErrAuth) || errors.Is(err, client.ErrForbidden) {
				return failed, err
			}
		}
	}
}

func (a *App) drainSends() {
	for {
		select {
		case <-a.sendReq:
		default:
			return
		}
	}
}

// SendToPhone sends files the person chose (full paths) to the phone. It works while
// Status.PhoneReceiving and nothing else is being sent.
func (a *App) SendToPhone(paths []string) error {
	if len(paths) == 0 {
		return errors.New("No files chosen")
	}
	clean := make([]string, 0, len(paths))
	for _, p := range paths {
		if p = strings.TrimSpace(p); p != "" {
			clean = append(clean, p)
		}
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if !a.st.PhoneReceiving || a.receiving {
		if a.receiving {
			return errors.New("Please wait until the files already chosen have been sent")
		}
		return errors.New("The phone is not ready to take files. On the phone, open \"Receive from computer\".")
	}
	select {
	case a.sendReq <- clean:
		// From now until the send ends the page shows a send in progress (the page keeps
		// dropped files until then).
		a.receiving = true
		a.st.Phase, a.st.Direction, a.st.Error = PhaseReceiving, DirToPhone, ""
		return nil
	default:
		return errors.New("Please wait until the files already chosen have been sent")
	}
}

// PickAndSend shows the system file dialog and sends the chosen files to the phone.
func (a *App) PickAndSend() error {
	if a.d.PickFiles == nil {
		return errors.New("file dialog not supported")
	}
	a.mu.Lock()
	ready := a.st.PhoneReceiving && !a.receiving
	a.mu.Unlock()
	if !ready {
		return errors.New("The phone is not ready to take files. On the phone, open \"Receive from computer\".")
	}
	paths, err := a.d.PickFiles("Choose photos and videos to send to your phone")
	if err != nil || len(paths) == 0 {
		return err
	}
	return a.SendToPhone(paths)
}

// backEvents adapts App to receive.BackEvents.
type backEvents App

func (e *backEvents) BackListed(keys, names []string, sizes []int64) {
	(*App)(e).startSend(keys, names, sizes)
}

func (e *backEvents) BackStarted(key string) {
	a := (*App)(e)
	a.mu.Lock()
	defer a.mu.Unlock()
	if i, ok := a.rowIdx[key]; ok {
		a.st.Rows[i].State = RowUploading
		a.st.Current = a.st.Rows[i].Name
	}
}

func (e *backEvents) BackProgress(d int64) {
	(*App)(e).update(func(s *Status) { s.BytesDone += d })
}

func (e *backEvents) BackDone(r receive.BackResult) {
	a := (*App)(e)
	state := RowFailed
	switch r.Outcome {
	case receive.Sent:
		state = RowSent
	case receive.NotFound:
		state = RowNotFound
	}
	a.d.Log.Info("to phone", "result", string(r.Outcome), "detail", r.Detail)
	a.mu.Lock()
	defer a.mu.Unlock()
	preview := false
	if r.Outcome == receive.Sent && r.Path != "" && store.Previewable(r.Path) {
		a.thumbs[r.Key] = r.Path
		preview = true
	}
	if i, ok := a.rowIdx[r.Key]; ok {
		a.st.Rows[i].State, a.st.Rows[i].Detail, a.st.Rows[i].Preview = state, r.Detail, preview
		if a.st.Current == a.st.Rows[i].Name {
			a.st.Current = ""
		}
	}
	if r.Outcome != receive.Sent {
		a.st.BytesTotal -= r.Size // the bar counts only what is being sent
	}
	a.st.FilesDone++
}
