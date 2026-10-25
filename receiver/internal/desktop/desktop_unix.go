//go:build unix && !darwin

// Linux and the other Unix desktops (FreeBSD, OpenBSD, ...). This file is not
// named desktop_linux.go because Go would then build it for Linux only.

package desktop

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// PickFolder shows the desktop's folder dialog through zenity or kdialog
// (kdialog first on KDE). It returns "" and nil if the user cancelled, and
// ErrUnsupported when there is no display or neither program is installed.
func PickFolder(title, start string) (string, error) {
	if os.Getenv("DISPLAY") == "" && os.Getenv("WAYLAND_DISPLAY") == "" {
		return "", ErrUnsupported
	}
	if title == "" {
		title = defaultTitle
	}
	start = usableStart(start)
	for _, tool := range dialogOrder() {
		bin, err := exec.LookPath(tool)
		if err != nil {
			continue
		}
		var args []string
		if tool == "kdialog" {
			args = kdialogArgs(title, start)
		} else {
			args = zenityArgs(title, start)
		}
		return runDialog(bin, args)
	}
	return "", ErrUnsupported
}

// dialogOrder prefers the dialog program that matches the desktop.
func dialogOrder() []string {
	if strings.Contains(strings.ToUpper(os.Getenv("XDG_CURRENT_DESKTOP")), "KDE") {
		return []string{"kdialog", "zenity"}
	}
	return []string{"zenity", "kdialog"}
}

// usableStart returns start as a clean absolute path if it is an existing
// folder, else "". Absolute paths start with '/', so they can never be read as
// an option by the dialog program.
func usableStart(start string) string {
	if start == "" || !filepath.IsAbs(start) {
		return ""
	}
	start = filepath.Clean(start)
	if fi, err := os.Stat(start); err != nil || !fi.IsDir() {
		return ""
	}
	return start
}

func zenityArgs(title, start string) []string {
	args := []string{"--file-selection", "--directory", "--title=" + title}
	if start != "" {
		// The trailing slash makes zenity open inside the folder rather than select it.
		args = append(args, "--filename="+strings.TrimSuffix(start, "/")+"/")
	}
	return args
}

func kdialogArgs(title, start string) []string {
	if start == "" {
		if home, err := os.UserHomeDir(); err == nil && filepath.IsAbs(home) {
			start = home
		} else {
			start = "/"
		}
	}
	return []string{"--getexistingdirectory", start, "--title", title}
}

// runDialog runs a zenity/kdialog folder dialog. Exit code 1 means cancelled.
// There is deliberately no timeout: the user may take as long as they like.
func runDialog(bin string, args []string) (string, error) {
	cmd := exec.Command(bin, args...)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		var ee *exec.ExitError
		if errors.As(err, &ee) && ee.ExitCode() == 1 {
			return "", nil
		}
		msg := strings.TrimSpace(stderr.String())
		if i := strings.IndexByte(msg, '\n'); i >= 0 {
			msg = msg[:i]
		}
		if msg != "" {
			return "", fmt.Errorf("%s: %w: %s", filepath.Base(bin), err, msg)
		}
		return "", fmt.Errorf("%s: %w", filepath.Base(bin), err)
	}
	p := strings.TrimRight(stdout.String(), "\r\n")
	if p == "" {
		return "", nil
	}
	if !filepath.IsAbs(p) {
		return "", fmt.Errorf("%s returned an unexpected folder %q", filepath.Base(bin), p)
	}
	return filepath.Clean(p), nil
}

// DefaultFolder suggests the Pictures folder (from xdg-user-dir, else
// ~/Pictures), or ~/GallerySlim Photos with an explanation if Pictures syncs
// to the cloud.
func DefaultFolder() (path, note string) {
	home, _ := os.UserHomeDir()
	return chooseDefault(styleUnix, xdgPictures(), home, CloudSynced)
}

// xdgPictures asks xdg-user-dir for the Pictures folder; "" if unavailable.
func xdgPictures() string {
	bin, err := exec.LookPath("xdg-user-dir")
	if err != nil {
		return ""
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, bin, "PICTURES").Output()
	if err != nil {
		return ""
	}
	p := strings.TrimRight(string(out), "\r\n")
	if !filepath.IsAbs(p) {
		return ""
	}
	return filepath.Clean(p)
}

// CloudSynced reports whether path lies in a folder synced by OneDrive,
// Google Drive, Dropbox, iCloud Drive or Insync, and names the service.
func CloudSynced(path string) (synced bool, provider string) {
	home, _ := os.UserHomeDir()
	return cloudSyncedResolved(styleUnix, path, home, os.Getenv)
}

// OpenURL opens an http or https link in the default browser with xdg-open.
func OpenURL(link string) error {
	u, err := checkURL(link)
	if err != nil {
		return err
	}
	bin, err := exec.LookPath("xdg-open")
	if err != nil {
		return ErrUnsupported
	}
	return runOpener(bin, u)
}

// OpenFolder opens an existing folder in the file manager with xdg-open.
func OpenFolder(path string) error {
	p, err := checkFolder(path)
	if err != nil {
		return err
	}
	bin, err := exec.LookPath("xdg-open")
	if err != nil {
		return ErrUnsupported
	}
	return runOpener(bin, p)
}

// KeepAwake holds a systemd-inhibit "sleep:idle" lock until release is called.
// The inhibitor runs cat on a pipe from this process, so the lock also ends
// if the receiver crashes or is killed. Without systemd-inhibit (or logind)
// it does nothing. release is never nil and safe to call more than once.
func KeepAwake() (release func()) {
	bin, err := exec.LookPath("systemd-inhibit")
	if err != nil {
		return noRelease
	}
	cat, err := exec.LookPath("cat")
	if err != nil {
		return noRelease
	}
	cmd := exec.Command(bin,
		"--what=sleep:idle",
		"--who="+appName,
		"--why=Receiving photos",
		"--mode=block",
		cat)
	rel, err := startHelper(cmd, true)
	if err != nil {
		return noRelease
	}
	return rel
}
