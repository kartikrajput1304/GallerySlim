//go:build darwin

package desktop

// macOS: a LaunchAgent (~/Library/LaunchAgents/com.galleryslim.receiver.plist, RunAtLoad)
// starts the receiver in the background at login; Install copies the .app bundle to
// ~/Applications.

import (
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
)

func homeDir() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		return "", errors.New("no home folder")
	}
	return home, nil
}

func plistPath() (string, error) {
	home, err := homeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, "Library", "LaunchAgents", launchAgentLabel+".plist"), nil
}

func installedApp() (string, error) {
	home, err := homeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, "Applications", appBundle), nil
}

// loginExe is the program the LaunchAgent starts: the installed copy if there is one.
func loginExe() (string, error) {
	if app, err := installedApp(); err == nil && Installed() {
		return filepath.Join(app, "Contents", "MacOS", exeName), nil
	}
	return currentExe()
}

// SetStartAtLogin writes or removes the LaunchAgent. Turning it on also registers it with
// launchd now (launchctl bootstrap gui/<uid>, best effort: an already-running receiver makes
// the second copy exit at once). Turning it off only removes the file, so a receiver that
// launchd started is not killed; launchd forgets the job at the next login.
func SetStartAtLogin(enabled bool) error {
	path, err := plistPath()
	if err != nil {
		return err
	}
	if !enabled {
		if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
		return nil
	}
	exe, err := loginExe()
	if err != nil {
		return err
	}
	content, err := launchAgentPlist(exe)
	if err != nil {
		return err
	}
	if err := writeFileAtomic(path, []byte(content), 0o644); err != nil {
		return err
	}
	if bin, err := toolPath("/bin/launchctl"); err == nil {
		cmd := exec.Command(bin, "bootstrap", "gui/"+strconv.Itoa(os.Getuid()), path)
		cmd.Stdout, cmd.Stderr = io.Discard, io.Discard
		cmd.Run() // fails harmlessly when the job is already loaded
	}
	return nil
}

// StartAtLogin reports whether the LaunchAgent file exists.
func StartAtLogin() bool {
	path, err := plistPath()
	if err != nil {
		return false
	}
	_, err = os.Stat(path)
	return err == nil
}

// InstallSupported is true when this copy runs from an .app bundle.
func InstallSupported() bool {
	exe, err := currentExe()
	if err != nil {
		return false
	}
	_, ok := macBundleRoot(exe)
	return ok
}

// Installed reports whether ~/Applications or /Applications holds the app.
func Installed() bool {
	if app, err := installedApp(); err == nil {
		if fi, err := os.Stat(filepath.Join(app, "Contents", "MacOS", exeName)); err == nil && fi.Mode().IsRegular() {
			return true
		}
	}
	_, err := os.Stat(filepath.Join("/Applications", appBundle, "Contents", "MacOS", exeName))
	return err == nil
}

// InstallSelf copies the running .app bundle to ~/Applications/GallerySlim Receiver.app.
func InstallSelf() (string, error) {
	exe, err := currentExe()
	if err != nil {
		return "", err
	}
	src, ok := macBundleRoot(exe)
	if !ok {
		return "", fmt.Errorf("install works from the GallerySlim Receiver app: %w", ErrUnsupported)
	}
	dst, err := installedApp()
	if err != nil {
		return "", err
	}
	if samePathOS(src, dst, true) {
		return dst, nil
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return "", err
	}
	tmp, err := os.MkdirTemp(filepath.Dir(dst), ".gs-install-*")
	if err != nil {
		return "", err
	}
	defer os.RemoveAll(tmp)
	staged := filepath.Join(tmp, appBundle)
	if err := copyTree(src, staged); err != nil {
		return "", err
	}
	old := filepath.Join(tmp, "old.app")
	if err := os.Rename(dst, old); err != nil && !errors.Is(err, os.ErrNotExist) {
		return "", err
	}
	if err := os.Rename(staged, dst); err != nil {
		os.Rename(old, dst)
		return "", err
	}
	if StartAtLogin() {
		if err := SetStartAtLogin(true); err != nil {
			return dst, err
		}
	}
	return dst, nil
}

// copyTree copies a folder with its files, modes and symbolic links (an .app bundle).
func copyTree(src, dst string) error {
	return filepath.WalkDir(src, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(src, p)
		if err != nil {
			return err
		}
		target := filepath.Join(dst, rel)
		info, err := d.Info()
		if err != nil {
			return err
		}
		switch {
		case d.Type()&fs.ModeSymlink != 0:
			link, err := os.Readlink(p)
			if err != nil {
				return err
			}
			return os.Symlink(link, target)
		case d.IsDir():
			return os.MkdirAll(target, info.Mode().Perm()|0o700)
		case info.Mode().IsRegular():
			return copyFileAtomic(p, target, info.Mode().Perm())
		}
		return nil // sockets, devices: not part of an app
	})
}
