//go:build darwin

package desktop

import (
	"bytes"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
)

// toolPath returns the system copy of a macOS tool (SIP-protected /usr/bin),
// falling back to PATH only if it is missing.
func toolPath(abs string) (string, error) {
	if fi, err := os.Stat(abs); err == nil && !fi.IsDir() {
		return abs, nil
	}
	return exec.LookPath(filepath.Base(abs))
}

// PickFolder shows the macOS "choose folder" dialog through osascript. The
// title and start folder are embedded as escaped AppleScript string literals.
// It returns "" and nil if the user cancelled.
func PickFolder(title, start string) (string, error) {
	osa, err := toolPath("/usr/bin/osascript")
	if err != nil {
		return "", ErrUnsupported
	}
	if title == "" {
		title = defaultTitle
	}
	if start != "" {
		if fi, err := os.Stat(start); err != nil || !fi.IsDir() || !filepath.IsAbs(start) {
			start = ""
		}
	}
	var args []string
	for _, line := range appleFolderScript(title, start) {
		args = append(args, "-e", line)
	}
	// No timeout: the user may take as long as they like.
	cmd := exec.Command(osa, args...)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		msg := strings.TrimSpace(stderr.String())
		if strings.Contains(msg, "(-128)") { // "User canceled." if the script's own catch missed it
			return "", nil
		}
		if msg != "" {
			return "", fmt.Errorf("osascript: %w: %s", err, msg)
		}
		return "", fmt.Errorf("osascript: %w", err)
	}
	p := strings.TrimRight(stdout.String(), "\r\n")
	if p == "" {
		return "", nil
	}
	if !filepath.IsAbs(p) {
		return "", fmt.Errorf("osascript returned an unexpected folder %q", p)
	}
	return filepath.Clean(p), nil // also drops the trailing '/' of "POSIX path of"
}

// DefaultFolder suggests ~/Pictures, or ~/GallerySlim Photos with an
// explanation if Pictures syncs to the cloud.
func DefaultFolder() (path, note string) {
	home, _ := os.UserHomeDir()
	pictures := ""
	if home != "" {
		pictures = filepath.Join(home, "Pictures")
	}
	return chooseDefault(styleDarwin, pictures, home, CloudSynced)
}

// CloudSynced reports whether path lies in iCloud Drive (~/Library/Mobile
// Documents), a File Provider folder (~/Library/CloudStorage/*: OneDrive,
// Google Drive, Dropbox, ...), or a classic Dropbox/Google Drive/OneDrive
// folder, and names the service.
func CloudSynced(path string) (synced bool, provider string) {
	home, _ := os.UserHomeDir()
	return cloudSyncedResolved(styleDarwin, path, home, os.Getenv)
}

// OpenURL opens an http or https link in the default browser with open(1).
func OpenURL(link string) error {
	u, err := checkURL(link)
	if err != nil {
		return err
	}
	bin, err := toolPath("/usr/bin/open")
	if err != nil {
		return ErrUnsupported
	}
	return runOpener(bin, u)
}

// OpenFolder opens an existing folder in Finder with open(1).
func OpenFolder(path string) error {
	p, err := checkFolder(path)
	if err != nil {
		return err
	}
	bin, err := toolPath("/usr/bin/open")
	if err != nil {
		return ErrUnsupported
	}
	return runOpener(bin, p)
}

// KeepAwake runs "caffeinate -i -w <our pid>" until release is called; the
// -w makes caffeinate exit by itself if the receiver dies. Best effort:
// release is never nil and safe to call more than once.
func KeepAwake() (release func()) {
	bin, err := toolPath("/usr/bin/caffeinate")
	if err != nil {
		return noRelease
	}
	rel, err := startHelper(exec.Command(bin, "-i", "-w", strconv.Itoa(os.Getpid())), false)
	if err != nil {
		return noRelease
	}
	return rel
}
