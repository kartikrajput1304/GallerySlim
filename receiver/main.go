// GallerySlim Receiver: pulls photos from the GallerySlim phone app over Wi-Fi into one folder.
//
// Usage:
//
//	GallerySlim-Receiver                      page in the browser (127.0.0.1 only)
//	GallerySlim-Receiver --background         wait quietly for paired phones (login entry)
//	GallerySlim-Receiver --headless --folder <path> [--phone <ip[:port]>]
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"galleryslim/receiver/internal/app"
	"galleryslim/receiver/internal/applog"
	"galleryslim/receiver/internal/client"
	"galleryslim/receiver/internal/desktop"
	"galleryslim/receiver/internal/discover"
	"galleryslim/receiver/internal/ui"
)

// version is set at build time (-X main.version=...).
var version = "dev"

// idleExit closes the receiver when the page has not been open for this long and nothing is
// being received (the macOS app has no window to close). Not in background mode.
const idleExit = 30 * time.Minute

// pageSeenRecently: the page asked for the status this recently, so it is open.
const pageSeenRecently = 10 * time.Second

// pageURLFile holds the running receiver's page address (with its secret), so a second start
// can open the page of the first instead of running twice. Mode 0600, removed on exit.
const pageURLFile = "page-url"

// friendlyHostname turns the system name into the default computer name.
func friendlyHostname() string {
	h, _ := os.Hostname()
	h = strings.TrimSuffix(strings.TrimSuffix(h, ".local"), ".lan")
	if n := app.CleanComputerName(h); n != "" {
		return n
	}
	return "This computer"
}

func main() { os.Exit(run(os.Args[1:], os.Stdout, os.Stderr)) }

func osName() string {
	switch runtime.GOOS {
	case "windows":
		return "windows"
	case "darwin":
		return "mac"
	}
	return "linux"
}

// noDesktop reports a Linux/BSD machine without a graphical session.
func noDesktop() bool {
	if runtime.GOOS == "windows" || runtime.GOOS == "darwin" {
		return false
	}
	return os.Getenv("DISPLAY") == "" && os.Getenv("WAYLAND_DISPLAY") == ""
}

func expandHome(p string) string {
	if p == "~" || strings.HasPrefix(p, "~/") || strings.HasPrefix(p, `~\`) {
		if home, err := os.UserHomeDir(); err == nil {
			return filepath.Join(home, p[1:])
		}
	}
	return p
}

// desktopState is what the page shows about start-at-login and install; read once and after
// every change (the registry / files are not polled).
type desktopState struct {
	mu           sync.Mutex
	startAtLogin bool
	installed    bool
}

func (d *desktopState) refresh() {
	login, inst := desktop.StartAtLogin(), desktop.Installed()
	d.mu.Lock()
	d.startAtLogin, d.installed = login, inst
	d.mu.Unlock()
}

func (d *desktopState) get() (bool, bool) {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.startAtLogin, d.installed
}

func toSnapshot(s app.Status) ui.Snapshot {
	snap := ui.Snapshot{
		Phase: s.Phase, Folder: s.Folder, FolderNote: s.FolderNote, PhoneName: s.PhoneName, Code: s.Code,
		QRSVG: s.QRSVG, FilesDone: s.FilesDone, FilesTotal: s.FilesTotal, BytesDone: s.BytesDone,
		BytesTotal: s.BytesTotal, Current: s.Current, Error: s.Error, Version: s.Version,
		ComputerName: s.ComputerName, Direction: s.Direction, PhoneReceiving: s.PhoneReceiving,
	}
	for _, p := range s.Phones {
		snap.Phones = append(snap.Phones, ui.PhoneChoice{Name: p.Name, Code: p.Code, Addr: p.Addr})
	}
	for _, r := range s.Rows {
		snap.Rows = append(snap.Rows, ui.FileRow{Name: r.Name, Size: r.Size, State: r.State, Detail: r.Detail, Thumb: r.Preview})
	}
	for _, p := range s.Paired {
		last := p.LastUsed
		if last == 0 {
			last = p.Paired
		}
		snap.PairedPhones = append(snap.PairedPhones, ui.PairedPhone{Name: p.Name, LastSeen: time.UnixMilli(last).UTC().Format(time.RFC3339)})
	}
	switch {
	case s.Remembered:
		snap.WaitingFor = s.PhoneName // confirm on this phone
	case s.Waiting && len(s.Paired) > 0:
		snap.WaitingFor = s.Paired[0].Name // ready to receive from the last used phone
	}
	return snap
}

// trayTip is the tray icon's tooltip for a status.
func trayTip(s app.Status) string {
	switch {
	case s.Phase == app.PhaseReceiving && s.Direction == app.DirToPhone:
		return fmt.Sprintf("GallerySlim Receiver \u2014 sending %d of %d to the phone", s.FilesDone, s.FilesTotal)
	case s.Phase == app.PhaseReceiving:
		return fmt.Sprintf("GallerySlim Receiver \u2014 receiving %d of %d", s.FilesDone, s.FilesTotal)
	case s.Remembered:
		return "GallerySlim Receiver \u2014 confirm on " + s.PhoneName
	case len(s.Paired) > 0:
		return "GallerySlim Receiver \u2014 ready to receive from " + s.Paired[0].Name
	}
	return "GallerySlim Receiver \u2014 ready"
}

// forgetByName maps a phone name shown on the page to its id. With two phones of the same
// name, the least recently used one is forgotten first.
func forgetByName(a *app.App, name string) error {
	paired := a.Status().Paired
	for i := len(paired) - 1; i >= 0; i-- {
		if paired[i].Name == name {
			return a.ForgetPhone(paired[i].ID)
		}
	}
	return errors.New("That phone is not paired with this computer")
}

func run(args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("GallerySlim-Receiver", flag.ContinueOnError)
	fs.SetOutput(stderr)
	headless := fs.Bool("headless", false, "run in the terminal only and print the code as text")
	folderFlag := fs.String("folder", "", "folder to save photos in")
	phone := fs.String("phone", "", "the phone's address as shown on the phone, like 192.168.1.23:8765")
	noBrowser := fs.Bool("no-browser", false, "don't open the page in the browser automatically")
	plainQR := fs.Bool("plain-qr", runtime.GOOS == "windows", "terminal code without colours (for dark terminals without ANSI colours)")
	showVersion := fs.Bool("version", false, "print the version and exit")
	background := fs.Bool("background", false, "run quietly and receive from paired phones when they send (used at login)")
	if err := fs.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return app.ExitOK
		}
		return app.ExitUsage
	}
	if *showVersion {
		fmt.Fprintln(stdout, version)
		return app.ExitOK
	}
	if fs.NArg() > 0 {
		fmt.Fprintf(stderr, "unexpected argument %q\n", fs.Arg(0))
		return app.ExitUsage
	}

	cfgDir, err := desktop.ConfigDir()
	if err != nil {
		cfgDir = ""
	}
	log := applog.Open(cfgDir)
	cfg := app.LoadConfig(cfgDir)
	rid := app.EnsureRID(cfgDir)
	pairs := app.LoadPairs(cfgDir)
	folder := cfg.Folder
	if *folderFlag != "" {
		abs, err := filepath.Abs(expandHome(*folderFlag))
		if err != nil {
			fmt.Fprintf(stderr, "bad folder: %v\n", err)
			return app.ExitUsage
		}
		folder = abs
	}
	runHeadless := *headless || noDesktop()
	runBackground := *background && !runHeadless
	log.Info("start", "version", version, "os", runtime.GOOS, "arch", runtime.GOARCH, "headless", runHeadless,
		"background", runBackground, "paired", pairs.Len())

	// One receiver per user: a second start opens the running one's page (or, from the
	// login entry, just exits).
	if !runHeadless && cfgDir != "" {
		unlock, ok, err := desktop.LockInstance(cfgDir)
		if err != nil {
			log.Warn("instance lock", "err", err)
		}
		if err == nil && !ok {
			log.Info("already running")
			if runBackground {
				return app.ExitOK
			}
			if b, err := os.ReadFile(filepath.Join(cfgDir, pageURLFile)); err == nil && !*noBrowser {
				if err := desktop.OpenURL(strings.TrimSpace(string(b))); err == nil {
					fmt.Fprintln(stdout, "GallerySlim Receiver is already running; its page was opened in the browser.")
					return app.ExitOK
				}
			}
			fmt.Fprintln(stdout, "GallerySlim Receiver is already running (see its icon or its window).")
			return app.ExitOK
		}
		defer unlock()
	}

	prober := discover.NewProber()
	typed := strings.TrimSpace(*phone)
	host := cfg.Name
	if host == "" {
		host = friendlyHostname()
	}
	deps := app.Deps{
		PickFolder:    desktop.PickFolder,
		DefaultFolder: desktop.DefaultFolder,
		CloudSynced:   desktop.CloudSynced,
		OpenFolder:    desktop.OpenFolder,
		KeepAwake:     desktop.KeepAwake,
		SaveFolder: func(p string) error {
			if cfgDir == "" {
				return errors.New("no settings folder")
			}
			c := app.LoadConfig(cfgDir)
			c.Folder = p
			return app.SaveConfig(cfgDir, c)
		},
		Find: func(ctx context.Context) ([]discover.Candidate, error) {
			if typed != "" {
				if c, err := discover.ProbeAddress(ctx, typed, prober); err == nil {
					return []discover.Candidate{c}, nil
				}
			}
			return discover.Find(ctx, prober)
		},
		Probe:    prober,
		HTTP:     client.NewHTTPClient(),
		Hostname: host,
		OSName:   osName(),
		Log:      log,
		Version:  version,
		Pairs:    pairs,
		RID:      rid,
		SaveName: func(name string) error {
			return app.UpdateConfig(cfgDir, func(c *app.Config) { c.Name = name })
		},
		Background: runBackground,
		Quick: func(ctx context.Context, last []string) []discover.Candidate {
			return discover.FindQuick(ctx, prober, last)
		},
		PickFiles: desktop.PickFiles,
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	if runHeadless {
		if *folderFlag == "" && !*headless && folder == "" {
			fmt.Fprintln(stderr, "No desktop found, so running in the terminal. Choose a folder with --folder <path>.")
			return app.ExitUsage
		}
		if *folderFlag == "" && *headless {
			fmt.Fprintln(stderr, "Please choose a folder: --headless --folder <path> [--phone <ip[:port]>]")
			return app.ExitUsage
		}
		code := app.RunHeadless(ctx, app.HeadlessOptions{Deps: deps, Folder: folder, Phone: typed, PlainQR: *plainQR, Out: stdout})
		log.Info("exit", "code", code)
		return code
	}

	var lastSeen atomic.Int64
	lastSeen.Store(time.Now().UnixNano())
	var pageURL atomic.Value // string
	openPage := func() {
		if u, _ := pageURL.Load().(string); u != "" {
			if err := desktop.OpenURL(u); err != nil {
				log.Warn("open page", "err", err)
			}
		}
	}
	if runBackground {
		// Files are arriving (or going to the phone): show the page unless it is open already.
		deps.OnReceiving = func() {
			if time.Since(time.Unix(0, lastSeen.Load())) > pageSeenRecently {
				openPage()
			}
		}
	}

	a := app.New(deps, folder)
	if *folderFlag != "" {
		if err := a.SetFolder(folder); err != nil {
			fmt.Fprintf(stderr, "%v\n", err)
			return app.ExitUsage
		}
	}
	var dstate desktopState
	dstate.refresh()
	h := ui.Handlers{
		Snapshot: func() ui.Snapshot {
			lastSeen.Store(time.Now().UnixNano())
			snap := toSnapshot(a.Status())
			snap.StartAtLogin, snap.Installed = dstate.get()
			return snap
		},
		ChooseFolder: a.ChooseFolder,
		ChangeFolder: a.ChooseFolder,
		SetFolder:    a.SetFolder,
		EnterAddress: a.EnterAddress,
		OpenFolder:   a.OpenFolder,
		Retry:        a.Retry,
		Quit:         stop,
		Rename:       a.SetComputerName,
		Forget:       func(name string) error { return forgetByName(a, name) },
		SetStartAtLogin: func(on bool) error {
			err := desktop.SetStartAtLogin(on)
			dstate.refresh()
			log.Info("start at login", "on", on, "err", err)
			return err
		},
		PickAndSend: a.PickAndSend,
		SendFiles:   a.SendToPhone,
		Thumb: func(i int) ([]byte, string, bool) {
			rows := a.Status().Rows // the same rows, in the same order, as Snapshot.Rows
			if i < 0 || i >= len(rows) {
				return nil, "", false
			}
			path, ok := a.PreviewPath(rows[i].ID)
			if !ok {
				return nil, "", false
			}
			return ui.ThumbFromFile(path)
		},
	}
	if desktop.InstallSupported() {
		h.Install = func() error {
			_, err := desktop.InstallSelf()
			dstate.refresh()
			log.Info("install", "ok", err == nil, "err", err)
			return err
		}
	}
	url, stopUI, err := ui.Start(h)
	if err != nil {
		fmt.Fprintf(stderr, "Could not start the local page: %v\n", err)
		log.Error("ui start failed", "err", err)
		return app.ExitFailed
	}
	defer stopUI()
	pageURL.Store(url)
	if cfgDir != "" {
		urlFile := filepath.Join(cfgDir, pageURLFile)
		if err := os.WriteFile(urlFile, []byte(url+"\n"), 0o600); err == nil {
			defer os.Remove(urlFile)
		}
	}

	// Windows: a tray icon (Open / Start with Windows / Quit). Other systems have none.
	tray, err := desktop.StartTray(desktop.TrayOptions{
		Tooltip: trayTip(a.Status()),
		OnOpen:  openPage,
		OnQuit:  stop,
		OnStartAtLogin: func(on bool, err error) {
			dstate.refresh()
			log.Info("start at login (tray)", "on", on, "err", err)
		},
	})
	if err == nil {
		defer tray.Close()
	} else if !errors.Is(err, desktop.ErrUnsupported) {
		log.Warn("tray icon", "err", err)
	}

	if runBackground {
		desktop.DetachConsole() // Windows: no console window at login
		log.Info("background", "page", "not opened")
	} else {
		fmt.Fprintf(stdout, "GallerySlim Receiver %s is running.\n\nIf your browser didn't open, go to:\n  %s\n\n"+
			"Keep this window open while receiving. Close it or press Ctrl+C to stop.\n", version, url)
		if !*noBrowser && cfg.WantsBrowser() {
			if err := desktop.OpenURL(url); err != nil {
				fmt.Fprintf(stdout, "Couldn't open the browser (%v); open the address above yourself.\n", err)
			}
		}
	}
	go a.Run(ctx)

	tick := time.NewTicker(time.Minute)
	defer tick.Stop()
	tipTick := time.NewTicker(2 * time.Second)
	defer tipTick.Stop()
	for {
		select {
		case <-ctx.Done():
			log.Info("exit")
			return app.ExitOK
		case <-tipTick.C:
			tray.SetTooltip(trayTip(a.Status()))
		case <-tick.C:
			if !runBackground && !a.Receiving() && time.Since(time.Unix(0, lastSeen.Load())) > idleExit {
				fmt.Fprintln(stdout, "The page has been closed for a while; stopping.")
				log.Info("idle exit")
				return app.ExitOK
			}
		}
	}
}
