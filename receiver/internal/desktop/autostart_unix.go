//go:build unix && !darwin

package desktop

// Linux and BSD desktops (freedesktop.org): the program is copied to ~/.local/bin, an
// application entry goes to $XDG_DATA_HOME/applications and the login entry to
// $XDG_CONFIG_HOME/autostart.

import (
	"errors"
	"os"
	"path/filepath"
)

type linuxPaths struct{ bin, app, autostart string }

func xdgDir(env, home, fallback string) string {
	if d := os.Getenv(env); d != "" && filepath.IsAbs(d) {
		return d
	}
	return filepath.Join(home, fallback)
}

func pathsLinux() (linuxPaths, error) {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		return linuxPaths{}, errors.New("no home folder")
	}
	return linuxPaths{
		bin:       filepath.Join(home, ".local", "bin", linuxID),
		app:       filepath.Join(xdgDir("XDG_DATA_HOME", home, ".local/share"), "applications", linuxID+".desktop"),
		autostart: filepath.Join(xdgDir("XDG_CONFIG_HOME", home, ".config"), "autostart", linuxID+".desktop"),
	}, nil
}

// loginExe is the program the login entry starts: the installed copy if there is one.
func loginExe(p linuxPaths) (string, error) {
	if Installed() {
		return p.bin, nil
	}
	return currentExe()
}

// SetStartAtLogin writes or removes ~/.config/autostart/gallery-slim-receiver.desktop.
func SetStartAtLogin(enabled bool) error {
	p, err := pathsLinux()
	if err != nil {
		return err
	}
	if !enabled {
		if err := os.Remove(p.autostart); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
		return nil
	}
	exe, err := loginExe(p)
	if err != nil {
		return err
	}
	content, err := desktopEntry(exe, true)
	if err != nil {
		return err
	}
	return writeFileAtomic(p.autostart, []byte(content), 0o644)
}

// StartAtLogin reports whether the autostart entry is present and enabled.
func StartAtLogin() bool {
	p, err := pathsLinux()
	if err != nil {
		return false
	}
	b, err := os.ReadFile(p.autostart)
	return err == nil && autostartEnabled(string(b))
}

// InstallSupported is true on Linux.
func InstallSupported() bool { return true }

// Installed reports whether ~/.local/bin/gallery-slim-receiver exists.
func Installed() bool {
	p, err := pathsLinux()
	if err != nil {
		return false
	}
	fi, err := os.Stat(p.bin)
	return err == nil && fi.Mode().IsRegular()
}

// InstallSelf copies this program to ~/.local/bin/gallery-slim-receiver and adds an
// application menu entry. A login entry, if on, is pointed at the installed copy.
func InstallSelf() (string, error) {
	p, err := pathsLinux()
	if err != nil {
		return "", err
	}
	exe, err := currentExe()
	if err != nil {
		return "", err
	}
	if !samePathOS(exe, p.bin, false) {
		if err := copyFileAtomic(exe, p.bin, 0o755); err != nil {
			return "", err
		}
	}
	content, err := desktopEntry(p.bin, false)
	if err != nil {
		return "", err
	}
	if err := writeFileAtomic(p.app, []byte(content), 0o644); err != nil {
		return "", err
	}
	if StartAtLogin() {
		if err := SetStartAtLogin(true); err != nil {
			return p.bin, err
		}
	}
	return p.bin, nil
}
