//go:build unix && !darwin

package desktop

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLinuxStartAtLoginAndInstall(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("XDG_CONFIG_HOME", filepath.Join(home, "cfg"))
	t.Setenv("XDG_DATA_HOME", "relative/ignored")
	if StartAtLogin() || Installed() {
		t.Fatal("fresh home already set up")
	}
	if err := SetStartAtLogin(true); err != nil {
		t.Fatal(err)
	}
	auto := filepath.Join(home, "cfg", "autostart", "gallery-slim-receiver.desktop")
	b, err := os.ReadFile(auto)
	if err != nil || !StartAtLogin() {
		t.Fatalf("autostart entry: %v", err)
	}
	exe, _ := currentExe()
	if !strings.Contains(string(b), "Exec=\""+exe+"\" --background\n") {
		t.Errorf("not pointing at this program:\n%s", b)
	}

	path, err := InstallSelf()
	bin := filepath.Join(home, ".local", "bin", "gallery-slim-receiver")
	if err != nil || path != bin || !Installed() {
		t.Fatalf("install: %q %v", path, err)
	}
	fi, _ := os.Stat(bin)
	if fi.Mode().Perm() != 0o755 {
		t.Errorf("mode %v", fi.Mode())
	}
	orig, _ := os.ReadFile(exe)
	copied, _ := os.ReadFile(bin)
	if !bytes.Equal(orig, copied) {
		t.Error("copy differs")
	}
	app, err := os.ReadFile(filepath.Join(home, ".local", "share", "applications", "gallery-slim-receiver.desktop"))
	if err != nil || !strings.Contains(string(app), `Exec="`+bin+`"`) {
		t.Errorf("menu entry %v:\n%s", err, app)
	}
	// The login entry now starts the installed copy.
	b, _ = os.ReadFile(auto)
	if !strings.Contains(string(b), `Exec="`+bin+`" --background`) {
		t.Errorf("login entry not moved to the installed copy:\n%s", b)
	}
	if err := SetStartAtLogin(false); err != nil || StartAtLogin() {
		t.Fatalf("off: %v", err)
	}
	if err := SetStartAtLogin(false); err != nil {
		t.Errorf("off twice: %v", err)
	}
	if !InstallSupported() {
		t.Error("install unsupported on Linux")
	}
}

func TestLockInstance(t *testing.T) {
	dir := t.TempDir()
	unlock, ok, err := LockInstance(dir)
	if err != nil || !ok {
		t.Fatalf("first: %v %v", ok, err)
	}
	// flock locks belong to the open file, so a second open in the same process conflicts too.
	if _, ok, err := LockInstance(dir); ok || err != nil {
		t.Fatalf("second: %v %v", ok, err)
	}
	unlock()
	unlock()
	u2, ok, err := LockInstance(dir)
	if !ok || err != nil {
		t.Fatalf("after unlock: %v %v", ok, err)
	}
	u2()
}
