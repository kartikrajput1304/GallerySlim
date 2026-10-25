package blackbox

// The receiver binary on the command line: PDF 10.1 (console), 10.4 (typed address) and
// 10.8 "Headless mode, command-line options and exit codes" (pages 82–83).

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"syscall"
	"testing"
	"time"

	"galleryslim/receiver/internal/app"
	"galleryslim/receiver/internal/client"
	"galleryslim/receiver/internal/discover"
)

func writeConfig(t *testing.T, h home, cfg map[string]any) {
	t.Helper()
	if err := os.MkdirAll(h.settingsDir(), 0o700); err != nil {
		t.Fatal(err)
	}
	b, _ := json.Marshal(cfg)
	if err := os.WriteFile(filepath.Join(h.settingsDir(), "config.json"), b, 0o600); err != nil {
		t.Fatal(err)
	}
}

// RH-01: --version prints the version and exits with 0.
func TestRH01_Version(t *testing.T) {
	h := newHome(t)
	code, out := runReceiver(t, h.env(true), 10*time.Second, "--version")
	if code != 0 {
		t.Errorf("exit %d, want 0", code)
	}
	if !strings.Contains(out, receiverVersion) {
		t.Errorf("output %q does not contain the version %s", out, receiverVersion)
	}
}

// RH-02 / RH-03: bad command line (unknown flag, stray argument) → exit 2.
func TestRH02_RH03_BadCommandLine(t *testing.T) {
	h := newHome(t)
	for name, args := range map[string][]string{
		"RH-02 unknown flag":    {"--frobnicate"},
		"RH-03 stray argument":  {"--headless", "--folder", t.TempDir(), "extra"},
		"RH-03 stray, page mode": {"photos"},
	} {
		t.Run(name, func(t *testing.T) {
			code, out := runReceiver(t, h.env(true), 10*time.Second, args...)
			if code != 2 {
				t.Errorf("exit %d, want 2; output:\n%s", code, out)
			}
		})
	}
}

// RH-04: explicit --headless always needs --folder, even when a folder is saved.
func TestRH04_HeadlessNeedsFolder(t *testing.T) {
	h := newHome(t)
	writeConfig(t, h, map[string]any{"folder": t.TempDir()})
	code, out := runReceiver(t, h.env(true), 10*time.Second, "--headless")
	if code != 2 {
		t.Errorf("exit %d, want 2", code)
	}
	if want := "Please choose a folder: --headless --folder <path> [--phone <ip[:port]>]"; !strings.Contains(out, want) {
		t.Errorf("output %q lacks %q", out, want)
	}
}

// RH-05: Linux without DISPLAY and WAYLAND_DISPLAY switches to headless; with no saved folder
// it says so and exits 2.
func TestRH05_AutoHeadlessWithoutFolder(t *testing.T) {
	h := newHome(t)
	code, out := runReceiver(t, h.env(false), 10*time.Second)
	if code != 2 {
		t.Errorf("exit %d, want 2", code)
	}
	if want := "No desktop found, so running in the terminal. Choose a folder with --folder <path>."; !strings.Contains(out, want) {
		t.Errorf("output %q lacks %q", out, want)
	}
}

// RH-06: automatic headless uses the saved folder.
func TestRH06_AutoHeadlessUsesSavedFolder(t *testing.T) {
	h := newHome(t)
	saved := t.TempDir()
	writeConfig(t, h, map[string]any{"folder": saved})
	code, out := runReceiver(t, h.env(false), 20*time.Second, "--phone", fmt.Sprintf("127.0.0.1:%d", closedPort(t)))
	if want := fmt.Sprintf("GallerySlim Receiver %s, saving to %s", receiverVersion, saved); !strings.Contains(out, want) {
		t.Errorf("output lacks %q:\n%s", want, out)
	}
	if code != 1 {
		t.Errorf("exit %d, want 1 (the --phone address does not answer)", code)
	}
}

// RH-07: folder not writable → "Photos can't be saved in <folder>: …", exit 2.
func TestRH07_FolderNotWritable(t *testing.T) {
	h := newHome(t)
	file := filepath.Join(t.TempDir(), "not-a-folder")
	os.WriteFile(file, []byte("x"), 0o644)
	folder := filepath.Join(file, "Photos")
	code, out := runReceiver(t, h.env(true), 10*time.Second, "--headless", "--folder", folder, "--phone", "127.0.0.1:1")
	if code != 2 {
		t.Errorf("exit %d, want 2", code)
	}
	if want := "Photos can't be saved in " + folder + ": "; !strings.Contains(out, want) {
		t.Errorf("output %q lacks %q", out, want)
	}
}

// RH-08 / RN-04 / RN-06: --phone malformed, a host name, or not answering → exit 1; the
// no-answer message names the address.
func TestRH08_PhoneAddressProblems(t *testing.T) {
	h := newHome(t)
	port := closedPort(t)
	cases := map[string]string{
		"malformed":       "999.1.1.1:8765",
		"text":            "my phone",
		"host name":       "galleryslim.local:8765",
		"too long":        strings.Repeat("1", 70),
		"IPv6":            "[::1]:8765",
		"does not answer": fmt.Sprintf("127.0.0.1:%d", port),
	}
	for name, addr := range cases {
		t.Run(name, func(t *testing.T) {
			folder := t.TempDir()
			code, out := runReceiver(t, h.env(true), 20*time.Second, "--headless", "--folder", folder, "--phone", addr)
			if code != 1 {
				t.Errorf("exit %d, want 1; output:\n%s", code, out)
			}
			if strings.Contains(out, "Scan this") {
				t.Errorf("pairing started for %q", addr)
			}
			if name == "does not answer" {
				want := fmt.Sprintf("no GallerySlim phone answered at %s; check that the phone shows \"Send to computer\" and is on the same Wi-Fi", addr)
				if !strings.Contains(out, want) {
					t.Errorf("output lacks %q:\n%s", want, out)
				}
			}
		})
	}
}

// RN-04: a typed address without a port tries 8765–8769.
func TestRN04_AddressWithoutPortTriesPhonePorts(t *testing.T) {
	addrs, err := discover.ParseAddress("192.168.1.23")
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"192.168.1.23:8765", "192.168.1.23:8766", "192.168.1.23:8767", "192.168.1.23:8768", "192.168.1.23:8769"}
	if strings.Join(addrs, ",") != strings.Join(want, ",") {
		t.Errorf("ParseAddress = %v, want %v", addrs, want)
	}
	for _, bad := range []string{"galleryslim.local", "galleryslim.local:8765", "phone", strings.Repeat("1", 65), "::1"} {
		if _, err := discover.ParseAddress(bad); err == nil {
			t.Errorf("ParseAddress(%q) accepted", bad)
		}
	}
	if _, err := discover.ParseAddress("http://192.168.1.23:8765/"); err != nil {
		t.Errorf("ParseAddress with http:// and / rejected: %v", err)
	}
}

// RH-09: no phone found → "No phone found. Run again with --phone <address shown on the
// phone>." exit 1. Needs the loopback-only namespace (the search would probe the network).
func TestRH09_NoPhoneFound(t *testing.T) {
	needNetns(t)
	h := newHome(t)
	code, out := runReceiver(t, h.env(true), 60*time.Second, "--headless", "--folder", t.TempDir())
	if !strings.Contains(out, "Looking for your phone (open GallerySlim and tap \"Send to computer\")…") {
		t.Errorf("output lacks the searching line:\n%s", out)
	}
	if !strings.Contains(out, "No phone found. Run again with --phone <address shown on the phone>.") {
		t.Errorf("output lacks the no-phone line:\n%s", out)
	}
	if code != 1 {
		t.Errorf("exit %d, want 1", code)
	}
}

// RH-10: several phones found → exit 2 with one line per phone (driven through the headless
// runner with a search that finds two phones).
func TestRH10_SeveralPhones(t *testing.T) {
	var out bytes.Buffer
	code := app.RunHeadless(context.Background(), app.HeadlessOptions{
		Deps: app.Deps{
			Find: func(context.Context) ([]discover.Candidate, error) {
				return []discover.Candidate{
					{Addr: "192.168.1.20:8765", Name: "Pixel 8", Code: "AB-12"},
					{Addr: "192.168.1.21:8765", Name: "Galaxy S24", Code: "CD-34"},
				}, nil
			},
			HTTP: client.NewHTTPClient(), Version: receiverVersion,
		},
		Folder: t.TempDir(), Out: &out,
	})
	if code != 2 {
		t.Errorf("exit %d, want 2", code)
	}
	s := out.String()
	if !strings.Contains(s, "Found several phones; run again with --phone and one of these addresses:") {
		t.Errorf("output lacks the several-phones line:\n%s", s)
	}
	for _, addr := range []string{"192.168.1.20:8765", "192.168.1.21:8765"} {
		if !strings.Contains(s, addr) {
			t.Errorf("phone %s not listed:\n%s", addr, s)
		}
	}
}

// RH-11 (first part) / RH-19: terminal output order up to the QR; ANSI black-on-white QR by
// default on Linux, plain with --plain-qr.
func TestRH11_RH19_TerminalOutputAndQR(t *testing.T) {
	for _, plain := range []bool{false, true} {
		t.Run(fmt.Sprintf("plain=%v", plain), func(t *testing.T) {
			p := newFakePhone(t)
			h := newHome(t)
			folder := t.TempDir()
			args := []string{"--headless", "--folder", folder, "--phone", p.Addr}
			if plain {
				args = append(args, "--plain-qr")
			}
			pr := startReceiver(t, h.env(true), args...)
			pr.waitFor(t, "Waiting for you to confirm on the phone…", 20*time.Second)
			out := pr.output()
			order := []string{
				fmt.Sprintf("GallerySlim Receiver %s, saving to %s", receiverVersion, folder),
				fmt.Sprintf("Found %s (code %s) at %s", p.Name, p.Code, p.Addr),
				fmt.Sprintf("Scan this with GallerySlim on your phone. Check that the phone shows the code %s.", p.Code),
				"Waiting for you to confirm on the phone…",
			}
			at := 0
			for _, s := range order {
				i := strings.Index(out[at:], s)
				if i < 0 {
					t.Errorf("line %q missing or out of order:\n%s", s, out)
					break
				}
				at += i + len(s)
			}
			hasBlocks := strings.ContainsAny(out, "█▀▄")
			ansi := strings.Contains(out, "\x1b[30;107m")
			if !hasBlocks {
				t.Error("QR is not drawn with half-block characters")
			}
			if plain && ansi {
				t.Error("--plain-qr still uses colours")
			}
			if !plain && !ansi {
				t.Error("default QR is not black on white (no ANSI colours)")
			}
			pr.cmd.Process.Signal(syscall.SIGINT)
			pr.wait(t, 10*time.Second)
		})
	}
}

// RH-15: Ctrl+C in headless mode → exit 1.
func TestRH15_CtrlCHeadless(t *testing.T) {
	p := newFakePhone(t)
	h := newHome(t)
	pr := startReceiver(t, h.env(true), "--headless", "--folder", t.TempDir(), "--phone", p.Addr)
	pr.waitFor(t, "Waiting for you to confirm on the phone…", 20*time.Second)
	pr.cmd.Process.Signal(syscall.SIGINT)
	if code := pr.wait(t, 10*time.Second); code != 1 {
		t.Errorf("exit %d after Ctrl+C in headless mode, want 1", code)
	}
}

// RH-16: "Pairing failed: …" → exit 1 (the phone drops the waiting code).
func TestRH16_PairingFailed(t *testing.T) {
	p := newFakePhone(t)
	h := newHome(t)
	pr := startReceiver(t, h.env(true), "--headless", "--folder", t.TempDir(), "--phone", p.Addr)
	pr.waitFor(t, "Waiting for you to confirm on the phone…", 20*time.Second)
	p.endAll()
	code := pr.wait(t, 20*time.Second)
	if !strings.Contains(pr.output(), "Pairing failed: ") {
		t.Errorf("output lacks \"Pairing failed: …\":\n%s", pr.output())
	}
	if code != 1 {
		t.Errorf("exit %d, want 1", code)
	}
}

// RH-18: synced folder note in headless mode.
func TestRH18_SyncedFolderNote(t *testing.T) {
	h := newHome(t)
	folder := filepath.Join(t.TempDir(), "Dropbox", "Phone")
	os.MkdirAll(folder, 0o755)
	_, out := runReceiver(t, h.env(true), 20*time.Second, "--headless", "--folder", folder, "--phone", fmt.Sprintf("127.0.0.1:%d", closedPort(t)))
	if want := fmt.Sprintf("Note: %s syncs to Dropbox; photos there may be moved off this computer.", folder); !strings.Contains(out, want) {
		t.Errorf("output lacks %q:\n%s", want, out)
	}
}

var pageURL = regexp.MustCompile(`http://127\.0\.0\.1:(\d+)/([0-9a-f]+)/`)

// RF-02 / RH-20 / RH-21: page mode console text, --no-browser prints the address, Ctrl+C → 0.
func TestRF02_PageModeConsole(t *testing.T) {
	h := newHome(t)
	pr := startReceiver(t, h.env(true), "--no-browser")
	pr.waitFor(t, "Close it or press Ctrl+C to stop.", 10*time.Second)
	out := pr.output()
	for _, s := range []string{
		"GallerySlim Receiver " + receiverVersion + " is running.",
		"If your browser didn't open, go to:",
		"Keep this window open while receiving. Close it or press Ctrl+C to stop.",
	} {
		if !strings.Contains(out, s) {
			t.Errorf("console lacks %q:\n%s", s, out)
		}
	}
	if !pageURL.MatchString(out) {
		t.Errorf("console shows no page address:\n%s", out)
	}
	if strings.Contains(out, "Couldn't open the browser") {
		t.Error("--no-browser still tried to open the browser")
	}
	pr.cmd.Process.Signal(syscall.SIGINT)
	if code := pr.wait(t, 10*time.Second); code != 0 {
		t.Errorf("exit %d after Ctrl+C in page mode, want 0", code)
	}
}

// RF-03: the browser cannot be opened → "Couldn't open the browser (error); open the address
// above yourself."
func TestRF03_BrowserCannotOpen(t *testing.T) {
	h := newHome(t)
	env := h.env(true)
	for i, e := range env {
		if strings.HasPrefix(e, "PATH=") {
			env[i] = "PATH=" + t.TempDir() // no xdg-open
		}
	}
	pr := startReceiver(t, env)
	pr.waitFor(t, "open the address above yourself.", 10*time.Second)
	if !regexp.MustCompile(`Couldn't open the browser \(.+\); open the address above yourself\.`).MatchString(pr.output()) {
		t.Errorf("message format differs:\n%s", pr.output())
	}
	pr.cmd.Process.Signal(syscall.SIGINT)
	pr.wait(t, 10*time.Second)
}
